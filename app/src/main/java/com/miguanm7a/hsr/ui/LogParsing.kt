package com.miguanm7a.hsr.ui

/**
 * 日志文本解析：剥离 ANSI 颜色码 + 判断级别。
 *
 * 抽成纯 Kotlin（不依赖 Android）是为了能直接单测 —— 这两条逻辑都出过真机问题。
 */
object LogParsing {

    /**
     * 带 ESC 的标准 CSI 序列（颜色、光标移动等）。
     * ESC 在场时无歧义，可以宽松匹配。
     */
    private val ANSI_ESC_RE = Regex("\u001B\\[[0-9;]*[A-Za-z]")

    /**
     * ESC 丢失后残留的裸 SGR 颜色码，例如 `[94m`、`[0m`、`[0;93m`、`[m`。
     *
     * 必须收紧：早期版本写成 `\[[0-9;]*[A-Za-z]`，结果把 `[W`、`[E`、
     * `[ERROR` 也吃掉了 —— 恰好毁掉 onnxruntime 的 `[W:`/`[E:` 级别标记，
     * 于是无害警告被标成错误（真机上就是这么误导用户的）。
     *
     * SGR 颜色码一定以字母 `m` 结尾，据此限定。
     */
    private val ANSI_BARE_RE = Regex("\\[[0-9;]*m")

    /**
     * 上游自带的级别标记，形如 `2026-01-01 12:00:00 | DEBUG | 消息`
     * （颜色码已剥离）。
     */
    private val LEVEL_RE =
        Regex("\\|\\s*(TRACE|DEBUG|INFO|WARNING|WARN|ERROR|CRITICAL|FATAL)\\s*\\|")

    /** 兜底：明确的错误 / 警告前缀，允许被 `[` `(` 包裹。 */
    private val ERROR_RE =
        Regex("(^|[\\s\\[(])(ERROR|FATAL|CRITICAL)([\\s\\]:\\[]|$)")
    private val WARN_RE =
        Regex("(^|[\\s\\[(])(WARNING|WARN)([\\s\\]:\\[]|$)")

    fun stripAnsi(text: String): String =
        ANSI_BARE_RE.replace(ANSI_ESC_RE.replace(text, ""), "")

    /**
     * 判断日志级别。
     *
     * **不要**用「包含 ERROR 就标红」这种朴素匹配：onnxruntime 的无害警告里
     * 就带 `GPU device discovery failed: Error: std::error_code...`，
     * 会被误判成错误并把用户吓一跳（真机上确实发生了，用户直接来问
     * 「这个报错重要吗」）。
     *
     * 优先信任上游自己的级别标记：
     *  - March7thAssistant: `| DEBUG |`、`| INFO |`、`| ERROR |`
     *  - onnxruntime:       `[W:...]` 是警告、`[E:...]` 才是错误
     */
    fun detectLevel(rawLine: String): LogLevel {
        val line = stripAnsi(rawLine)

        LEVEL_RE.find(line)?.let { m ->
            return when (m.groupValues[1]) {
                "ERROR", "CRITICAL", "FATAL" -> LogLevel.ERROR
                "WARNING", "WARN" -> LogLevel.WARN
                "DEBUG", "TRACE" -> LogLevel.DEBUG
                else -> LogLevel.INFO
            }
        }

        // onnxruntime 等原生库的标记
        if (line.contains("[E:")) return LogLevel.ERROR
        if (line.contains("[W:")) return LogLevel.WARN

        // 兜底：只认明确的 Python 异常与错误/警告前缀
        if (line.contains("Traceback (most recent call last)")) return LogLevel.ERROR
        if (ERROR_RE.containsMatchIn(line)) return LogLevel.ERROR
        if (WARN_RE.containsMatchIn(line)) return LogLevel.WARN

        return LogLevel.INFO
    }
}
