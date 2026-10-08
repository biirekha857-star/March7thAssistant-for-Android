package com.miguanm7a.hsr.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.miguanm7a.hsr.termux.TermuxEnvironment
import com.miguanm7a.hsr.termux.TermuxPaths
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * 内置终端界面。
 *
 * 直接使用 Termux 的 terminal-emulator / terminal-view 两个模块，把 $PREFIX/bin/bash
 * 跑在真实 PTY 里。不依赖外部 Termux App，也不需要任何 Intent 投递，
 * 因此不存在「调用第三方无反应」的问题。
 */
class TerminalActivity : AppCompatActivity(), TerminalViewClient {

    private lateinit var terminalView: TerminalView
    private var session: TerminalSession? = null

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            terminalView.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            title = changedSession.title ?: "终端"
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            // 会话结束：把结果留在屏幕上，不自动关闭 Activity
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            clipboard()?.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val text = clipboard()?.primaryClip?.getItemAt(0)
                ?.coerceToText(this@TerminalActivity)?.toString()
            if (!text.isNullOrEmpty() && session != null) session.write(text)
        }

        override fun onBell(session: TerminalSession) {}

        override fun onColorsChanged(session: TerminalSession) {}

        override fun onTerminalCursorStateChange(state: Boolean) {}

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

        override fun getTerminalCursorStyle(): Int? =
            TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK

        override fun logError(tag: String?, message: String?) {
            Log.e(tag ?: TAG, message ?: "")
        }

        override fun logWarn(tag: String?, message: String?) {
            Log.w(tag ?: TAG, message ?: "")
        }

        override fun logInfo(tag: String?, message: String?) {
            Log.i(tag ?: TAG, message ?: "")
        }

        override fun logDebug(tag: String?, message: String?) {}

        override fun logVerbose(tag: String?, message: String?) {}

        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message ?: "", e)
        }

        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(tag ?: TAG, "stack trace", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 测试版兜底：终端初始化一旦抛异常，界面上直接显示原因，
        // 而不是让整个 App 崩掉（用户也能把这段贴给我）。
        try {
            setupTerminal()
        } catch (t: Throwable) {
            Log.e(TAG, "终端初始化失败", t)
            showFatalError(t)
        }
    }

    private fun setupTerminal() {
        val root = FrameLayout(this)
        terminalView = TerminalView(this, null)
        terminalView.setTerminalViewClient(this)
        root.addView(
            terminalView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)

        // ⚠️ 必须先 setTextSize，再 attachSession。
        //
        // TerminalView.mRenderer 声明为 `public TerminalRenderer mRenderer;`
        // **没有初始化**，只在 setTextSize() / setTypeface() 里赋值。
        // 而 updateSize() 会做 `viewWidth / mRenderer.mFontWidth`：
        //   attachSession() 时视图还没布局（宽高为 0），updateSize 提前 return，
        //   所以当时不炸；等视图布局完成触发 onSizeChanged → updateSize()，
        //   此时宽高已非 0 而 mRenderer 仍是 null → NullPointerException 崩溃。
        // 官方 Termux 的 TermuxActivity 在 onCreate 里就会调用 setTextSize。
        terminalView.setTextSize(fontSizeSp)

        startShell(intent.getStringExtra(EXTRA_COMMAND))
        terminalView.requestFocus()
    }

    /** 初始化失败时显示可读的错误信息（含堆栈），便于用户反馈。 */
    private fun showFatalError(t: Throwable) {
        val text = android.widget.TextView(this).apply {
            setPadding(48, 96, 48, 48)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = buildString {
                appendLine("终端启动失败")
                appendLine()
                appendLine("${t.javaClass.name}: ${t.message}")
                appendLine()
                t.stackTrace.take(20).forEach { appendLine("    at $it") }
                appendLine()
                appendLine("请把以上内容截图反馈。")
            }
        }
        setContentView(
            android.widget.ScrollView(this).apply { addView(text) }
        )
    }

    /**
     * 初始字号（sp）。可双指缩放调整。
     *
     * 自己记录当前值：TerminalRenderer.mTextSize 是包级私有，
     * 跨包读不到，而字号只会由这里改动。
     */
    private var fontSizeSp = DEFAULT_FONT_SIZE_SP

    private fun changeFontSize(increase: Boolean) {
        val next = (fontSizeSp + if (increase) 2 else -2)
            .coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
        if (next != fontSizeSp) {
            fontSizeSp = next
            terminalView.setTextSize(next)
        }
    }

    private fun startShell(command: String?) {
        val bash = TermuxPaths.bash(this)
        val cwd = TermuxPaths.homeDir(this).also { it.mkdirs() }.absolutePath

        // 运行时未安装时给出明确可读的提示，而不是一片空白
        val (shellPath, args) = if (bash.isFile) {
            bash.absolutePath to if (command.isNullOrBlank()) arrayOf("-l") else arrayOf("-lc", command)
        } else {
            "/system/bin/sh" to arrayOf(
                "-c",
                "echo 'Termux 运行时尚未安装。'; echo; " +
                    "echo '请回到首页点击「一键部署」完成安装。'; echo; " +
                    "echo \"期望的 bash 路径：${bash.absolutePath}\"; echo",
            )
        }

        val s = TerminalSession(
            shellPath,
            cwd,
            args,
            TermuxEnvironment.toArray(this),
            2000,
            sessionClient,
        )
        session = s
        terminalView.attachSession(s)
    }

    /** 向当前会话写入一段命令（供外部调用）。 */
    fun runCommand(cmd: String) {
        session?.write(cmd + "\n")
    }

    private fun clipboard(): ClipboardManager? =
        getSystemService(ClipboardManager::class.java)

    // ------------------------------------------------------------------
    // TerminalViewClient
    // ------------------------------------------------------------------

    /** 双指缩放调整字号（与官方 Termux 行为一致）。 */
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            changeFontSize(scale > 1f)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        terminalView.requestFocus()
        showSoftKeyboard()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean =
        false

    override fun onEmulatorSet() {}

    override fun logError(tag: String?, message: String?) {
        Log.e(tag ?: TAG, message ?: "")
    }

    override fun logWarn(tag: String?, message: String?) {
        Log.w(tag ?: TAG, message ?: "")
    }

    override fun logInfo(tag: String?, message: String?) {}

    override fun logDebug(tag: String?, message: String?) {}

    override fun logVerbose(tag: String?, message: String?) {}

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message ?: "", e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag ?: TAG, "stack trace", e)
    }

    private fun showSoftKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        // 0 == SHOW_IMPLICIT（常量已废弃，直接用字面量）
        imm?.showSoftInput(terminalView, 0)
    }

    companion object {
        private const val TAG = "TerminalActivity"
        const val EXTRA_COMMAND = "command"

        /** 初始字号；官方 Termux 默认 14，手机上看偏小，这里取 16。 */
        private const val DEFAULT_FONT_SIZE_SP = 16
        private const val MIN_FONT_SIZE_SP = 8
        private const val MAX_FONT_SIZE_SP = 48
    }
}
