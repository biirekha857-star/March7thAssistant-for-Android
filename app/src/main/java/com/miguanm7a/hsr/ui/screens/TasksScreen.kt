package com.miguanm7a.hsr.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import com.miguanm7a.hsr.deploy.AfterFinish
import com.miguanm7a.hsr.deploy.M7aTask
import com.miguanm7a.hsr.ui.MainViewModel
import com.miguanm7a.hsr.ui.components.LoginQrCard
import com.miguanm7a.hsr.ui.components.MaaCard
import com.miguanm7a.hsr.ui.components.SectionHeader
import com.miguanm7a.hsr.ui.theme.MaaDesignTokens
import com.miguanm7a.hsr.ui.theme.WarnColor
@Composable
fun TasksScreen(
    vm: MainViewModel,
    onOpenTerminal: (String?) -> Unit,
    onOpenMonitor: () -> Unit = {},
) {
    val ui by vm.ui.collectAsState()
    val qr by vm.qr.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = MaaDesignTokens.Spacing.listHorizontal,
            vertical = MaaDesignTokens.Spacing.lg,
        ),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        item {
            Text("任务", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
            Text(
                "选择要执行的任务，然后点「一键运行」。运行输出会实时显示在「日志」页。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 扫码登录：首次运行必需，放在最显眼的位置
        item {
            Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
            LoginQrCard(
                qr = qr,
                runningTask = ui.runningTask,
                onRefresh = { vm.rescanQr() },
            )
        }

        item { SectionHeader("选择任务") }

        items(M7aTask.entries.toList()) { task ->
            MaaCard(onClick = { vm.setTask(task) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = ui.task == task, onClick = { vm.setTask(task) })
                    Column(Modifier.weight(1f)) {
                        Text(task.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            task.arg?.let { "main.py $it" } ?: "main.py",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item { SectionHeader("完成后行为") }
        item {
            MaaCard {
                AfterFinish.entries.forEach { af ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = ui.afterFinish == af, onClick = { vm.setAfterFinish(af) })
                        Column(Modifier.weight(1f)) {
                            Text(af.value, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (af == AfterFinish.EXIT) "任务完成后退出" else "任务完成后循环等待下次执行",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item { SectionHeader("定时运行") }
        item {
            MaaCard {
                Text("循环模式", style = MaterialTheme.typography.titleSmall)
                listOf(
                    "scheduled" to "按定时时间运行（配合下面的时间）",
                    "power" to "按体力计划循环",
                ).forEach { (mode, desc) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = ui.loopMode == mode,
                            onClick = { vm.setLoopMode(mode) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(mode, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
                OutlinedTextField(
                    value = ui.runDailyTime,
                    onValueChange = { vm.setRunDailyTime(it) },
                    label = { Text("定时时间（HH:mm）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "对应 config.yaml 的 loop_mode 与 scheduled_time，" +
                        "仅在 after_finish = Loop 时循环生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "改完点下方「生成脚本」才会写入容器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarnColor,
                )
            }
        }

        item { SectionHeader("操作") }

        item {
            Button(
                onClick = { vm.runSelectedTask() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = ui.runtimeReady && !ui.runningTask,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(8.dp))
                Text("一键运行 ${ui.task.label}", style = MaterialTheme.typography.titleMedium)
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)) {
                OutlinedButton(
                    onClick = { vm.stopTask() },
                    modifier = Modifier.weight(1f),
                    enabled = ui.runningTask,
                ) { Text("停止") }

                OutlinedButton(
                    onClick = { vm.regenerateScripts() },
                    modifier = Modifier.weight(1f),
                ) { Text("生成脚本") }
            }
        }

        item {
            OutlinedButton(
                onClick = { onOpenTerminal(null) },
                modifier = Modifier.fillMaxWidth(),
                enabled = ui.runtimeReady,
            ) { Text("在终端中手动执行") }
        }

        item {
            OutlinedButton(
                onClick = onOpenMonitor,
                modifier = Modifier.fillMaxWidth(),
                enabled = ui.runtimeReady,
            ) { Text("实时监看画面（CDP）") }
        }

        if (!ui.runtimeReady) {
            item {
                MaaCard {
                    Text(
                        "Termux 运行时尚未安装，请先到「部署」页完成一键部署。",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarnColor,
                    )
                }
            }
        }
    }
}
