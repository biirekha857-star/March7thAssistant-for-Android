package com.miguanm7a.hsr.ui

import java.io.File

/**
 * 日志级别。
 *
 * 顺序即严重程度：DEBUG < INFO < OK < WARN < ERROR。
 * [DEBUG] 是从 March7thAssistant 自己的 DEBUG 标记映射来的，
 * 默认在界面上折叠掉（否则每帧操作都刷一行，根本没法看）。
 */
enum class LogLevel(val severity: Int) {
    DEBUG(0), INFO(1), OK(2), WARN(3), ERROR(4)
}

data class LogEntry(
    val time: String,
    val level: LogLevel,
    val text: String,
)

/**
 * 登录二维码状态。
 *
 * March7thAssistant 首次运行会要求扫码登录，二维码写到项目目录下的
 * `logs/qrcode_login.png`。我们把容器内的 logs 绑定到共享目录，
 * 于是这个文件可以在手机上直接显示、用米游社 App 扫。
 */
data class QrInfo(
    val file: File,
    val lastModified: Long,
    val size: Long,
) {
    /** 用于 Compose 判断是否需要重新解码图片。 */
    val revision: Long get() = lastModified * 1000 + size
}
