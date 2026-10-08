package com.miguanm7a.hsr.ui.theme

import androidx.compose.ui.graphics.Color
import com.miguanm7a.hsr.ui.LogLevel

val OkColor = Color(0xFF2E9E5B)
val WarnColor = Color(0xFFD98B1E)
val ErrColor = Color(0xFFD9483B)
val InfoColor = Color(0xFF8A8580)

/** DEBUG 用更淡的灰，默认还会被过滤掉，不抢注意力。 */
val DebugColor = Color(0xFFA9A49E)

fun LogLevel.color(): Color = when (this) {
    LogLevel.DEBUG -> DebugColor
    LogLevel.INFO -> InfoColor
    LogLevel.OK -> OkColor
    LogLevel.WARN -> WarnColor
    LogLevel.ERROR -> ErrColor
}

fun LogLevel.label(): String = when (this) {
    LogLevel.DEBUG -> "调试"
    LogLevel.INFO -> "信息"
    LogLevel.OK -> "成功"
    LogLevel.WARN -> "警告"
    LogLevel.ERROR -> "错误"
}
