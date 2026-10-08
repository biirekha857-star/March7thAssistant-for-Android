package com.miguanm7a.hsr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志解析的回归测试。
 *
 * 起因是用户真机截图问「这个报错重要吗」—— 那行其实是 onnxruntime 的
 * **无害警告**，却因为我的朴素匹配（包含 "ERROR" 就标红）被涂成红色，
 * 加上 ANSI 颜色码没剥离，显得像严重错误。
 */
class LogParsingTest {

    // ---------------------------------------------------------------
    // 真实日志样本（来自真机）
    // ---------------------------------------------------------------

    /** 让用户以为出错的这条：其实只是 onnxruntime 找不到 GPU，回落 CPU。 */
    private val onnxruntimeGpuWarning =
        "\u001B[0;93m2026-10-07 20:51:43.159717341 " +
            "[W:onnxruntime:Default, device_discovery.cc:332 DiscoverDevicesForPlatform] " +
            "GPU device discovery failed: Error: std::error_code with category name: generic, " +
            "value: 13, message: Permission denied, filesystem path: \"/sys/class/drm\", " +
            "context: Iterating over DRM sysfs devices\u001B[m"

    @Test
    fun `onnxruntime gpu warning is a WARNING not an ERROR`() {
        // 关键：行里含 "Error: std::error_code"，但 [W: 说明它是警告
        assertEquals(LogLevel.WARN, LogParsing.detectLevel(onnxruntimeGpuWarning))
    }

    @Test
    fun `onnxruntime error marker is an ERROR`() {
        val line = "[E:onnxruntime:Default, env.cc:227 ThreadMain] something failed"
        assertEquals(LogLevel.ERROR, LogParsing.detectLevel(line))
    }

    // ---------------------------------------------------------------
    // ANSI 颜色码
    // ---------------------------------------------------------------

    @Test
    fun `ansi colour codes are stripped`() {
        val raw = "\u001B[94mDEBUG\u001B[0m | hello"
        assertEquals("DEBUG | hello", LogParsing.stripAnsi(raw))

        val line = "2026-10-07 20:51:39,921 | \u001B[94mDEBUG\u001B[0m | 执行了一个操作"
        assertFalse("不应残留 [94m", LogParsing.stripAnsi(line).contains("[94m"))
        assertFalse("不应残留 [0m", LogParsing.stripAnsi(line).contains("[0m"))
        assertTrue(LogParsing.stripAnsi(line).contains("执行了一个操作"))
    }

    /** ESC 已被上游丢掉时，裸的 [0m / [0;93m 也要能清掉。 */
    @Test
    fun `bare ansi codes without ESC are stripped`() {
        assertEquals("DEBUG | x", LogParsing.stripAnsi("[94mDEBUG[0m | x"))
        assertEquals("hello", LogParsing.stripAnsi("[0;93mhello[m"))
    }

    // ---------------------------------------------------------------
    // March7thAssistant 自己的级别标记
    // ---------------------------------------------------------------

    @Test
    fun `upstream level markers are honoured`() {
        fun line(level: String) = "2026-10-07 20:51:46,587 | $level | 消息"
        assertEquals(LogLevel.DEBUG, LogParsing.detectLevel(line("DEBUG")))
        assertEquals(LogLevel.INFO, LogParsing.detectLevel(line("INFO")))
        assertEquals(LogLevel.WARN, LogParsing.detectLevel(line("WARNING")))
        assertEquals(LogLevel.ERROR, LogParsing.detectLevel(line("ERROR")))
    }

    @Test
    fun `coloured upstream level marker still recognised`() {
        val line = "2026-10-07 20:51:41,724 | \u001B[92mINFO\u001B[0m | 切换到: 每日实训"
        assertEquals(LogLevel.INFO, LogParsing.detectLevel(line))
    }

    // ---------------------------------------------------------------
    // 正常内容不能被误判
    // ---------------------------------------------------------------

    @Test
    fun `normal output stays INFO`() {
        val samples = listOf(
            "鼠标按下 (1106, 737)",
            "目标图片: screen/guide/guide2.png 相似度: 0.98 匹配阈值: 0.88",
            "OCR初始化完成",
            "OCR初始化耗时: 2.25 秒",
            "已通过进程内补丁禁用 OpenVINO telemetry",
            "登录游戏:已完成",
            // 含 "error" 的普通业务文本不应标红
            "检查 error_code 字段",
        )
        for (s in samples) {
            assertEquals("误判: $s", LogLevel.INFO, LogParsing.detectLevel(s))
        }
    }

    @Test
    fun `python traceback is an ERROR`() {
        assertEquals(
            LogLevel.ERROR,
            LogParsing.detectLevel("Traceback (most recent call last):"),
        )
    }

    @Test
    fun `explicit error prefixes are errors`() {
        assertEquals(LogLevel.ERROR, LogParsing.detectLevel("ERROR: 连接失败"))
        assertEquals(LogLevel.ERROR, LogParsing.detectLevel("[ERROR] 连接失败"))
        assertEquals(LogLevel.WARN, LogParsing.detectLevel("WARNING: 内存不足"))
    }

    /** 级别顺序用于界面过滤，必须单调。 */
    @Test
    fun `severity ordering is monotonic`() {
        val ordered = listOf(
            LogLevel.DEBUG, LogLevel.INFO, LogLevel.OK, LogLevel.WARN, LogLevel.ERROR
        )
        for (i in 1 until ordered.size) {
            assertTrue(
                "${ordered[i]} 的严重度应大于 ${ordered[i - 1]}",
                ordered[i].severity > ordered[i - 1].severity,
            )
        }
    }
}
