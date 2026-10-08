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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miguanm7a.hsr.ui.MainViewModel
import com.miguanm7a.hsr.ui.components.InfoRow
import com.miguanm7a.hsr.ui.components.LoginQrCard
import com.miguanm7a.hsr.ui.components.MaaCard
import com.miguanm7a.hsr.ui.components.SectionHeader
import com.miguanm7a.hsr.ui.theme.MaaDesignTokens
import com.miguanm7a.hsr.ui.theme.OkColor
import com.miguanm7a.hsr.ui.theme.WarnColor
import com.miguanm7a.hsr.ui.theme.color

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onGoDeploy: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenMonitor: () -> Unit = {},
) {
    val ui by vm.ui.collectAsState()
    val deploy by vm.deployState.collectAsState()
    val qr by vm.qr.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MaaDesignTokens.Spacing.listHorizontal),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
    ) {
        Spacer(Modifier.height(MaaDesignTokens.Spacing.lg))

        Text(
            text = "March7thAssistant",
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = "内嵌 Termux 运行时 · 一键部署 · 一键运行",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "版本 ${ui.version}（测试版）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))

        // ---------------- 状态卡片 ----------------
        MaaCard {
            Text("运行状态", style = MaterialTheme.typography.titleMedium)
            InfoRow(
                "Termux 运行时",
                if (ui.runtimeReady) "已就绪" else "未安装",
                valueColor = if (ui.runtimeReady) OkColor else WarnColor,
            )
            InfoRow(
                "容器",
                if (ui.deployed) ui.containerAlias else "未部署",
                valueColor = if (ui.deployed) OkColor else WarnColor,
            )
            InfoRow("当前任务", ui.task.label)
            if (ui.runningTask) {
                InfoRow(
                    "执行中",
                    ui.runningTaskName ?: "任务",
                    valueColor = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 部署进行中时展示总进度
        if (deploy.running) {
            MaaCard {
                Text("部署进行中", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { deploy.overallProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${deploy.overallProgress}%  ${deploy.currentStep?.message.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 检测到需要登录时，直接把二维码摆在首页（首次运行最容易卡在这里）
        LoginQrCard(
            qr = qr,
            runningTask = ui.runningTask,
            onRefresh = { vm.rescanQr() },
        )

        // ---------------- 主操作 ----------------
        if (!ui.runtimeReady || !ui.deployed) {
            Button(
                onClick = onGoDeploy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !deploy.running,
            ) {
                Text("一键部署", style = MaterialTheme.typography.titleMedium)
            }
        }

        Button(
            onClick = { vm.runSelectedTask() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = ui.runtimeReady && !ui.runningTask,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(8.dp))
            Text("一键运行任务", style = MaterialTheme.typography.titleMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md)) {
            OutlinedButton(
                onClick = { vm.stopTask() },
                modifier = Modifier.weight(1f),
                enabled = ui.runningTask,
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("停止")
            }
            FilledTonalButton(
                onClick = onOpenTerminal,
                modifier = Modifier.weight(1f),
                enabled = ui.runtimeReady,
            ) {
                Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("终端")
            }
        }

        // 实时监看：通过 CDP 显示云游戏画面
        FilledTonalButton(
            onClick = onOpenMonitor,
            modifier = Modifier.fillMaxWidth(),
            enabled = ui.runtimeReady,
        ) {
            Icon(Icons.Filled.Monitor, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("实时监看画面")
        }

        // ---------------- 最近日志 ----------------
        SectionHeader("最近输出")
        val logs by vm.logs.collectAsState()
        MaaCard {
            if (logs.isEmpty()) {
                Text(
                    "暂无输出。部署或运行任务后这里会显示实时日志。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                logs.takeLast(6).forEach { entry ->
                    Text(
                        text = entry.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Normal,
                        color = entry.level.color(),
                        maxLines = 2,
                    )
                }
            }
        }

        Spacer(Modifier.height(MaaDesignTokens.Spacing.xxl))
    }
}
