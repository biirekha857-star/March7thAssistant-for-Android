package com.miguanm7a.hsr.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miguanm7a.hsr.deploy.AfterFinish
import com.miguanm7a.hsr.deploy.DeployEngine
import com.miguanm7a.hsr.deploy.DeployScripts
import com.miguanm7a.hsr.deploy.DeploySettings
import com.miguanm7a.hsr.deploy.M7aTask
import com.miguanm7a.hsr.deploy.ConfigSync
import com.miguanm7a.hsr.deploy.RootfsInstaller
import com.miguanm7a.hsr.deploy.RootfsSource
import com.miguanm7a.hsr.deploy.SharedPaths
import com.miguanm7a.hsr.deploy.TaskToggles
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.miguanm7a.hsr.deploy.TermuxMirror
import com.miguanm7a.hsr.service.TaskKeepAliveService
import com.miguanm7a.hsr.service.TaskRunner
import com.miguanm7a.hsr.termux.BootstrapInstaller
import com.miguanm7a.hsr.termux.TermuxPaths
import com.miguanm7a.hsr.termux.TermuxShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val runtimeReady: Boolean = false,
    val rootfsReady: Boolean = false,
    val deployed: Boolean = false,
    val runningTask: Boolean = false,
    val runningTaskName: String? = null,
    val containerAlias: String = "m7a",
    val termuxMirror: TermuxMirror = TermuxMirror.TUNA,
    val rootfsSource: RootfsSource = RootfsSource.BUNDLED,
    val task: M7aTask = M7aTask.FULL,
    val afterFinish: AfterFinish = AfterFinish.EXIT,
    val logLevel: String = "DEBUG",
    val runDailyTime: String = "04:00",
    val loopMode: String = "scheduled",
    val usePaidTime: Boolean = false,
    val monitorPort: Int = 9222,
    val version: String = "",
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val settings = DeploySettings(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _qr = MutableStateFlow<QrInfo?>(null)

    /** 当前可用的登录二维码；为 null 表示暂时没有（未到登录步骤或已登录）。 */
    val qr: StateFlow<QrInfo?> = _qr.asStateFlow()

    private var qrWatchJob: Job? = null

    init {        // 观察任务输出与状态。任务进程归 TaskRunner 所有，
        // 所以 ViewModel 重建后重新接上即可，不会丢任务。
        viewModelScope.launch {
            TaskRunner.lines.collect { line ->
                appendLog(line, LogParsing.detectLevel(line))
            }
        }
        viewModelScope.launch {
            TaskRunner.state.collect { snap ->
                val wasRunning = _ui.value.runningTask
                _ui.update {
                    it.copy(runningTask = snap.running, runningTaskName = snap.label)
                }
                if (wasRunning && !snap.running) {
                    // 任务结束
                    stopQrWatch()
                    appendLog(
                        "任务已结束（退出码 ${snap.lastExitCode ?: "?"}）",
                        if (snap.lastExitCode == 0) LogLevel.OK else LogLevel.WARN,
                    )
                }
            }
        }
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val engine = DeployEngine(
        context = app,
        settings = settings,
        onLog = { line -> appendLog(line) },
    )
    val deployState = engine.state

    private var taskProcess: Process? = null

    // ------------------------------------------------------------------
    // 日志
    // ------------------------------------------------------------------

    fun appendLog(text: String, level: LogLevel = LogLevel.INFO) {
        // 存进去之前就剥掉 ANSI 颜色码，避免界面上一堆 [94m / [0m
        val entry = LogEntry(timeFmt.format(Date()), level, LogParsing.stripAnsi(text))
        _logs.update { current ->
            val next = current + entry
            if (next.size > 3000) next.takeLast(3000) else next
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    // ------------------------------------------------------------------
    // 状态刷新
    // ------------------------------------------------------------------

    fun refresh(context: Context) {
        val ready = TermuxPaths.isInstalled(context)
        _ui.update {
            it.copy(
                runtimeReady = ready,
                rootfsReady = RootfsInstaller.isReady(context),
                deployed = settings.isDeployed(),
                containerAlias = settings.containerAlias(),
                termuxMirror = settings.termuxMirror(),
                rootfsSource = settings.rootfsSource(),
                task = settings.task(),
                afterFinish = settings.afterFinish(),
                logLevel = settings.logLevel(),
                runDailyTime = settings.runDailyTime(),
                loopMode = settings.loopMode(),
                usePaidTime = settings.usePaidTime(),
                monitorPort = settings.monitorPort(),
                version = com.miguanm7a.hsr.BuildConfig.VERSION_NAME,
            )
        }
        // 开关读一次进内存，避免界面重组时反复读 DataStore
        _toggles.value = settings.allToggles()

        // 从后台回到前台时：任务可能仍在跑（归 TaskRunner 所有），
        // 同步状态并恢复二维码监听与保活服务。
        val snap = TaskRunner.state.value
        _ui.update { it.copy(runningTask = snap.running, runningTaskName = snap.label) }
        if (snap.running) {
            if (qrWatchJob?.isActive != true) startQrWatch()
            TaskKeepAliveService.start(context.applicationContext, snap.label.orEmpty())
        }
    }

    // ------------------------------------------------------------------
    // 一键部署
    // ------------------------------------------------------------------

    fun startDeploy() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            appendLog("=== 开始一键部署 ===", LogLevel.OK)
            val result = engine.runFull()
            refresh(app)
            if (result.isSuccess) {
                appendLog("=== 部署完成 ===", LogLevel.OK)
            } else {
                appendLog(
                    "=== 部署失败：${result.exceptionOrNull()?.message} ===",
                    LogLevel.ERROR,
                )
            }
        }
    }

    /** 只安装运行时（用于快速开始）。 */
    fun installRuntimeOnly() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val r = BootstrapInstaller.install(app) { p ->
                appendLog(p.message)
            }
            refresh(app)
            r.onFailure { appendLog("运行时安装失败：${it.message}", LogLevel.ERROR) }
        }
    }

    // ------------------------------------------------------------------
    // 运行任务
    // ------------------------------------------------------------------

    /** 一键运行选中的任务。 */
    fun runSelectedTask() {
        val app = getApplication<Application>()
        val current = _ui.value
        if (TaskRunner.isRunning()) {
            appendLog("已有任务在运行中，请先停止", LogLevel.WARN)
            return
        }
        val cmd = DeployScripts.runTaskCommand(
            alias = current.containerAlias,
            task = current.task,
            afterFinish = current.afterFinish,
            logLevel = current.logLevel,
            bundled = settings.isBundled(),
            // 把容器内 logs 绑到共享目录，二维码才能显示出来
            guestSharedLogs = SharedPaths.guestLogsDir(app),
        )
        runRawCommand(cmd, "任务：${current.task.label}")
    }

    fun runRawCommand(cmd: String, label: String) {
        val app = getApplication<Application>()
        // 确保 --bind 的源目录存在，否则 proot 的 bind 会失败
        SharedPaths.ensure(app)

        if (TaskRunner.isRunning()) {
            appendLog("已有任务在运行中，请先停止", LogLevel.WARN)
            return
        }

        // 冒烟测试若发现设备需要 PROOT_NO_SECCOMP=1，任务运行同样沿用
        val prootEnv = DeployScripts.readProotEnvExtra(TermuxPaths.homeDir(app))
        if (prootEnv.isNotEmpty()) {
            appendLog("（沿用 proot 额外环境：$prootEnv）")
        }

        // 任务进程归 TaskRunner（单例）所有，这样 ViewModel/Activity 被回收
        // 也不会杀掉正在跑的任务。
        if (!TaskRunner.start(app, cmd, label, prootEnv)) {
            appendLog("任务启动失败", LogLevel.ERROR)
            return
        }

        // 前台服务 + WakeLock：防止 App 退到后台被系统冻结/回收
        TaskKeepAliveService.start(app, label)
        startQrWatch()
    }

    // ------------------------------------------------------------------
    // 登录二维码监听
    // ------------------------------------------------------------------

    /**
     * 任务运行期间轮询共享目录里的登录二维码。
     *
     * 容器内的 `logs` 已被 `--bind` 到共享目录，所以 March7thAssistant 写出的
     * `logs/qrcode_login.png` 会直接出现在这里。二维码会过期刷新，
     * 所以按 mtime+size 判断变化并重新解码。
     */
    private fun startQrWatch() {
        qrWatchJob?.cancel()
        val app = getApplication<Application>()
        qrWatchJob = viewModelScope.launch(Dispatchers.IO) {
            val f = SharedPaths.qrFile(app)
            appendLog("等待登录二维码（首次运行需要米游社 App 扫码）…", LogLevel.INFO)
            while (isActive) {
                val info = if (f.isFile && f.length() > 0) {
                    QrInfo(f, f.lastModified(), f.length())
                } else {
                    null
                }
                if (info?.revision != _qr.value?.revision) {
                    _qr.value = info
                    if (info != null) {
                        appendLog(
                            "检测到登录二维码 → 请用米游社 App 扫描（也可在「任务」页查看）",
                            LogLevel.OK,
                        )
                    }
                }
                delay(2000)
            }
        }
    }

    private fun stopQrWatch() {
        qrWatchJob?.cancel()
        qrWatchJob = null
    }

    /** 手动清除已显示的二维码（例如已登录成功）。 */
    fun clearQr() {
        _qr.value = null
    }

    /** 手动重新读取二维码文件（任务不在跑、轮询停掉时用）。 */
    fun rescanQr() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val f = SharedPaths.qrFile(app)
            val info = if (f.isFile && f.length() > 0) QrInfo(f, f.lastModified(), f.length()) else null
            _qr.value = info
            withContext(Dispatchers.Main) {
                appendLog(
                    if (info == null) "未找到登录二维码（${f.absolutePath}）"
                    else "已读取登录二维码：${f.name}（${info.size} 字节）",
                    if (info == null) LogLevel.WARN else LogLevel.OK,
                )
            }
        }
    }

    fun stopTask() {
        if (!TaskRunner.isRunning()) {
            appendLog("当前没有正在运行的任务", LogLevel.WARN)
            return
        }
        TaskRunner.stop()
        stopQrWatch()
        _ui.update { it.copy(runningTask = false, runningTaskName = null) }
        appendLog("=== 任务已停止 ===", LogLevel.WARN)
    }

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------

    fun setTermuxMirror(v: TermuxMirror) {
        settings.setTermuxMirror(v); _ui.update { it.copy(termuxMirror = v) }
    }

    fun setRootfsSource(v: RootfsSource) {
        settings.setRootfsSource(v); _ui.update { it.copy(rootfsSource = v) }
    }

    fun setContainerAlias(v: String) {
        settings.setContainerAlias(v); _ui.update { it.copy(containerAlias = settings.containerAlias()) }
    }

    fun setTask(v: M7aTask) {
        settings.setTask(v); _ui.update { it.copy(task = v) }
    }

    fun setAfterFinish(v: AfterFinish) {
        settings.setAfterFinish(v); _ui.update { it.copy(afterFinish = v) }
    }

    fun setLogLevel(v: String) {
        settings.setLogLevel(v); _ui.update { it.copy(logLevel = v) }
    }

    fun setRunDailyTime(v: String) {
        settings.setRunDailyTime(v); _ui.update { it.copy(runDailyTime = v) }
    }

    fun setUsePaidTime(v: Boolean) {
        settings.setUsePaidTime(v); _ui.update { it.copy(usePaidTime = v) }
    }

    fun setLoopMode(v: String) {
        settings.setLoopMode(v)
        _ui.update { it.copy(loopMode = v) }
    }

    fun setMonitorPort(v: Int) {
        settings.setMonitorPort(v)
        _ui.update { it.copy(monitorPort = settings.monitorPort()) }
    }

    /**
     * 任务开关（config.yaml 里的 `*_enable`）的内存镜像。
     *
     * 不要每次重组都去读 DataStore（30 个开关 × runBlocking 读取会卡顿），
     * 所以启动时读一次、改动时更新这里的值。
     */
    private val _toggles = MutableStateFlow(TaskToggles.defaults())
    val toggles: StateFlow<Map<String, Boolean>> = _toggles.asStateFlow()

    fun setToggle(key: String, value: Boolean) {
        settings.setToggle(key, value)
        _toggles.update { it + (key to value) }
    }

    fun resetTogglesToDefault() {
        TaskToggles.ALL.forEach { settings.setToggle(it.key, it.defaultValue) }
        _toggles.value = TaskToggles.defaults()
        appendLog("任务开关已恢复默认值，点「生成脚本」后生效", LogLevel.OK)
    }

    /** 重新生成 start-m7a.sh 与 config.yaml；若已部署则把配置推进容器。 */
    fun regenerateScripts() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val cur = _ui.value
            val bundled = settings.isBundled()
            val home = TermuxPaths.homeDir(app).also { it.mkdirs() }

            // 1) 宿主侧便捷启动脚本
            val script = File(home, "start-m7a.sh")
            script.writeText(
                DeployScripts.startScript(
                    cur.containerAlias, cur.afterFinish, cur.logLevel, bundled,
                    guestSharedLogs = SharedPaths.guestLogsDir(app),
                ),
                Charsets.UTF_8,
            )
            script.setExecutable(true, false)

            val deployed = cur.deployed && cur.runtimeReady
            val prootEnv = DeployScripts.readProotEnvExtra(home)

            // 2) 拉取容器内现有配置 → 增量修改 → 写回共享文件
            //    未部署时容器不存在，pull 会失败并回落到模板（安全）
            val guestCfg = ConfigSync.refresh(
                context = app,
                settings = settings,
                bundled = bundled,
                alias = cur.containerAlias,
                onLog = { appendLog(it) },
                extraEnv = prootEnv,
            )

            withContext(Dispatchers.Main) {
                appendLog("已重新生成 start-m7a.sh 与 config.yaml", LogLevel.OK)
            }

            // 3) 已部署则把配置推回容器
            if (!deployed) {
                withContext(Dispatchers.Main) {
                    appendLog("尚未部署完成，配置会在部署时自动写入容器", LogLevel.INFO)
                }
                return@launch
            }

            val code = ConfigSync.push(
                context = app,
                alias = cur.containerAlias,
                guestSharedConfig = guestCfg,
                bundled = bundled,
                onLog = { appendLog(it) },
                extraEnv = prootEnv,
            )
            withContext(Dispatchers.Main) {
                appendLog(
                    if (code == 0) "配置已写入容器" else "配置写入容器失败（退出码 $code）",
                    if (code == 0) LogLevel.OK else LogLevel.ERROR,
                )
            }
        }
    }

    /** 检查容器是否存在。 */
    fun checkContainer() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val alias = _ui.value.containerAlias
            val out = TermuxShell.capture(app, "proot-distro list --quiet 2>&1 || true", 20_000)
            withContext(Dispatchers.Main) {
                appendLog("--- proot-distro list --quiet ---")
                out.lines().filter { it.isNotBlank() }.forEach { appendLog(it) }
                val exists = out.lines().any { it.trim() == alias }
                appendLog(
                    if (exists) "容器 $alias 存在" else "容器 $alias 不存在",
                    if (exists) LogLevel.OK else LogLevel.WARN,
                )
            }
        }
    }

    fun resetRuntime() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            appendLog("=== 正在重置运行时 ===", LogLevel.WARN)
            BootstrapInstaller.uninstall(app)
            RootfsInstaller.delete(app)
            settings.setDeployed(false)
            refresh(app)
            appendLog("=== 运行时已重置 ===", LogLevel.WARN)
        }
    }

    override fun onCleared() {
        // 刻意**不**销毁任务进程：任务归 TaskRunner（单例）所有，
        // Activity/ViewModel 被回收不应该中断挂机任务。
        // 真正需要停止请用 stopTask() 或通知栏的「停止任务」。
        super.onCleared()
    }
}
