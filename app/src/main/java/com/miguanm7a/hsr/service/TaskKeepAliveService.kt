package com.miguanm7a.hsr.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.miguanm7a.hsr.MainActivity
import com.miguanm7a.hsr.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 任务保活前台服务。
 *
 * ## 为什么必须有它
 *
 * March7thAssistant 跑在 App 的**子进程链**里：
 * `app → bash → proot-distro(python) → proot → bash → python → chromium`。
 * App 进程一旦被系统冻结或回收，整条链跟着死。
 *
 * 前台服务的作用：
 * 1. **提升进程优先级**，让系统不把 App 当成可随意回收的后台进程；
 * 2. 配合 [PowerManager.WakeLock] 阻止 CPU 休眠 —— 否则息屏后自动化会停住；
 * 3. 通知栏常驻，用户随时能停下任务，也符合 Android 的可见性要求。
 *
 * ## 关于 Android 12+ 的「幽灵进程」限制
 *
 * Android 12 起系统会回收 App 派生的、它不认识的进程（phantom process）。
 * 处于前台（含前台服务）的 App 有更高额度。但 Chromium 自身就会派生几十个
 * 进程，仍可能触顶。若出现任务莫名中断，可在设置页里把本应用加入
 * **电池优化白名单**，并关闭厂商的「后台限制」。
 */
class TaskKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_TASK -> {
                TaskRunner.stop()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
                startForeground(NOTIFICATION_ID, buildNotification(label))
                acquireWakeLock()
                observeRunner()
                return START_STICKY
            }

            else -> {
                // 被系统以 START_STICKY 拉起：任务已经不在（进程死过），没必要保活
                if (!TaskRunner.isRunning()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification(TaskRunner.state.value.label.orEmpty()))
                acquireWakeLock()
                observeRunner()
                return START_STICKY
            }
        }
    }

    /**
     * 用户从最近任务里划掉 App。
     *
     * 默认行为会连服务一起清掉。这里**不做处理**，让前台服务继续跑，
     * 任务就不会因为划掉界面而中断；并更新通知说明一下。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "从最近任务移除，保持前台服务继续运行")
        TaskRunner.log("已从最近任务移除，任务继续在后台运行")
        runCatching {
            notificationManager().notify(
                NOTIFICATION_ID,
                buildNotification(
                    TaskRunner.state.value.label.orEmpty(),
                    note = "已从最近任务移除，仍在后台运行",
                ),
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun observeRunner() {
        if (stateJob != null) return
        stateJob = scope.launch {
            TaskRunner.state.collectLatest { snap ->
                if (!snap.running) {
                    // 任务结束 → 保活使命完成
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // WakeLock
    // ------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            try {
                // 不加超时：任务可能跑很久。服务销毁时一定会释放。
                acquire()
            } catch (t: Throwable) {
                Log.w(TAG, "获取 WakeLock 失败", t)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = notificationManager()
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_task),
                // LOW：常驻通知不该响铃打扰，用户挂机时不希望手机一直响
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_task_desc)
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(label: String, note: String? = null): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable,
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TaskKeepAliveService::class.java).setAction(ACTION_STOP_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutable,
        )

        val snap = TaskRunner.state.value
        val started = if (snap.startedAt > 0) timeFmt.format(Date(snap.startedAt)) else ""
        val text = buildString {
            append(if (label.isBlank()) "任务运行中" else label)
            if (started.isNotEmpty()) append("　开始于 $started")
            if (note != null) append("\n$note")
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_task)
            .setContentTitle(getString(R.string.notif_task_running))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private val pendingImmutable: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }

    companion object {
        private const val TAG = "TaskKeepAlive"
        private const val CHANNEL_ID = "maatermux_task"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "MaaTermux::Task"
        const val ACTION_START = "com.miguanm7a.hsr.action.START_KEEPALIVE"
        const val ACTION_STOP_TASK = "com.miguanm7a.hsr.action.STOP_TASK"
        const val EXTRA_LABEL = "label"

        private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        /** 启动保活（任务开始时调用）。 */
        fun start(context: Context, label: String) {
            val intent = Intent(context, TaskKeepAliveService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LABEL, label)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "启动保活服务失败", it) }
        }

        /** 停止保活（任务结束时调用，一般由服务自己发现状态后停止）。 */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, TaskKeepAliveService::class.java))
            }
        }
    }
}
