package com.miguanm7a.hsr.service

import android.content.Context
import com.miguanm7a.hsr.termux.TermuxShell
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 任务进程的**唯一持有者**。
 *
 * ## 为什么必须是单例，而不是 ViewModel 的成员
 *
 * 之前任务进程由 `MainViewModel` 持有，并在 `onCleared()` 里 `destroy()`。
 * 这意味着 Activity 一旦被回收（旋转、内存压力、返回后台被销毁），
 * 正在跑的 March7thAssistant 就会被杀掉 —— 对一个「挂机跑日常」的工具来说
 * 这是致命的。
 *
 * 现在进程归这个 object 管（只要 App 进程活着就在），ViewModel 只是观察者：
 * 它可以被反复创建销毁，任务不受影响；重建后通过 [state] 与 [lines] 重新接上。
 *
 * ## 保活链路
 *
 * 本 object 只保证「进程不被自己人杀掉」。真正的抗系统回收靠
 * [TaskKeepAliveService]：前台服务 + WakeLock 让 App 不被冻结/回收，
 * 子进程链（bash → proot → python → chromium）才能一起活下来。
 */
object TaskRunner {

    data class Snapshot(
        val running: Boolean = false,
        val label: String? = null,
        val startedAt: Long = 0L,
        val lastExitCode: Int? = null,
    ) {
        /** 供通知与界面显示「已运行多久」。纯函数，可单测。 */
        fun elapsedText(now: Long = System.currentTimeMillis()): String =
            if (!running || startedAt <= 0L) "" else formatDuration(now - startedAt)
    }

    /** 输出行。replay 让中途重建的观察者也能拿到最近的内容。 */
    private val _lines = MutableSharedFlow<String>(
        replay = 128,
        extraBufferCapacity = 512,
    )
    val lines: SharedFlow<String> = _lines.asSharedFlow()

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private var process: Process? = null

    private val stopping = AtomicBoolean(false)

    fun isRunning(): Boolean = process?.isAlive == true

    /**
     * 启动任务。已有任务在跑时返回 false。
     *
     * 注意：调用方（ViewModel）负责同时启动 [TaskKeepAliveService]，
     * 否则 App 退到后台就可能被系统回收。
     */
    fun start(
        context: Context,
        command: String,
        label: String,
        extraEnv: Map<String, String> = emptyMap(),
    ): Boolean {
        if (isRunning()) return false
        stopping.set(false)
        _lines.tryEmit("=== 运行 $label ===")
        _lines.tryEmit("$ $command")

        val proc = TermuxShell.startAsync(
            context = context,
            script = command,
            extraEnv = extraEnv,
            onLine = { line -> _lines.tryEmit(line) },
            onExit = { code ->
                _state.update {
                    it.copy(running = false, label = null, lastExitCode = code)
                }
                _lines.tryEmit("=== $label 结束（退出码 $code）===")
                process = null
            },
        ) ?: return false

        process = proc
        _state.value = Snapshot(
            running = true,
            label = label,
            startedAt = System.currentTimeMillis(),
        )
        return true
    }

    /** 停止任务（用户主动 / 通知栏按钮）。 */
    fun stop() {
        val p = process
        if (p == null || !p.isAlive) {
            _state.update { it.copy(running = false, label = null) }
            return
        }
        stopping.set(true)
        runCatching { p.destroy() }
        runCatching { p.destroyForcibly() }
        process = null
        _state.update { it.copy(running = false, label = null) }
        _lines.tryEmit("=== 任务已停止 ===")
    }

    /** 供外部补写一行日志（例如「沿用 PROOT_NO_SECCOMP」）。 */
    fun log(line: String) {
        _lines.tryEmit(line)
    }

    // ------------------------------------------------------------------
    // 纯函数：时长格式化（可单测）
    // ------------------------------------------------------------------

    fun formatDuration(millis: Long): String {
        if (millis <= 0) return "0 秒"
        val totalSec = millis / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "$h 小时 $m 分"
            m > 0 -> "$m 分 $s 秒"
            else -> "$s 秒"
        }
    }
}
