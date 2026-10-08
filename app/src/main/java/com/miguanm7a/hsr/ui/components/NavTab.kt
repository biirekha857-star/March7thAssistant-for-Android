package com.miguanm7a.hsr.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** 底部导航页签，顺序即 HorizontalPager 的页序。 */
enum class NavTab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Filled.Home),
    DEPLOY("部署", Icons.Filled.CloudDownload),
    TASKS("任务", Icons.Filled.PlayArrow),
    LOG("日志", Icons.AutoMirrored.Filled.Article),
    SETTINGS("设置", Icons.Filled.Settings),
}
