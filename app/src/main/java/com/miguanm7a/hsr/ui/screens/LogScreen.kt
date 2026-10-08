package com.miguanm7a.hsr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguanm7a.hsr.ui.LogEntry
import com.miguanm7a.hsr.ui.LogLevel
import com.miguanm7a.hsr.ui.MainViewModel
import com.miguanm7a.hsr.ui.theme.MaaDesignTokens
import com.miguanm7a.hsr.ui.theme.label
import com.miguanm7a.hsr.ui.theme.color

@Composable
fun LogScreen(vm: MainViewModel) {
    val all by vm.logs.collectAsState()

    // 默认「信息」档：DEBUG 是每帧操作的刷屏内容，默认折叠，否则日志没法看
    var minLevel by rememberSaveable { mutableStateOf(LogLevel.INFO.severity) }
    val logs = remember(all, minLevel) {
        all.filter { it.level.severity >= minLevel }
    }
    val hidden = all.size - logs.size

    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaaDesignTokens.Spacing.listHorizontal,
                    vertical = MaaDesignTokens.Spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("实时日志", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (hidden > 0) "已隐藏 $hidden 条调试日志" else "共 ${logs.size} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { vm.clearLogs() }) { Text("清空") }
        }

        // 级别过滤
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaaDesignTokens.Spacing.listHorizontal),
            horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            for (lvl in listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR)) {
                FilterChip(
                    selected = minLevel == lvl.severity,
                    onClick = { minLevel = lvl.severity },
                    label = { Text(lvl.label(), style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))

        if (logs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(MaaDesignTokens.Spacing.listHorizontal),
            ) {
                Text(
                    if (all.isEmpty()) {
                        "暂无日志。部署或运行任务后，这里会实时显示全部输出。"
                    } else {
                        "当前筛选条件下没有日志。点上面的「调试」可以看到全部输出。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                contentPadding = PaddingValues(
                    horizontal = MaaDesignTokens.Spacing.md,
                    vertical = MaaDesignTokens.Spacing.sm,
                ),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(logs) { entry -> LogRow(entry) }
                item { Spacer(Modifier.height(MaaDesignTokens.Spacing.xxl)) }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    Row {
        Text(
            text = entry.time,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(horizontal = 4.dp))
        Text(
            text = entry.text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            color = entry.level.color(),
        )
    }
}
