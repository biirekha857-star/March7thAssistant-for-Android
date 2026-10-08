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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.miguanm7a.hsr.deploy.RootfsSource
import com.miguanm7a.hsr.deploy.StepState
import com.miguanm7a.hsr.deploy.TermuxMirror
import com.miguanm7a.hsr.ui.MainViewModel
import com.miguanm7a.hsr.ui.components.MaaCard
import com.miguanm7a.hsr.ui.components.SectionHeader
import com.miguanm7a.hsr.ui.components.SmallSpinner
import com.miguanm7a.hsr.ui.theme.ErrColor
import com.miguanm7a.hsr.ui.theme.MaaDesignTokens
import com.miguanm7a.hsr.ui.theme.OkColor
import com.miguanm7a.hsr.ui.theme.WarnColor

@Composable
fun DeployScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    val deploy by vm.deployState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MaaDesignTokens.Spacing.listHorizontal),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.md),
    ) {
        Spacer(Modifier.height(MaaDesignTokens.Spacing.lg))
        Text("一键部署", style = MaterialTheme.typography.headlineMedium)
        Text(
            "全部步骤在 App 内置的 Termux 运行时中执行，不需要安装 Termux、不需要电脑、不需要 adb。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---------------- 步骤列表 ----------------
        SectionHeader("部署步骤")
        MaaCard {
            val steps = deploy.steps
            if (steps.isEmpty()) {
                Text(
                    "尚未开始部署。点击下方「开始部署」按钮。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                steps.forEach { step ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (step.state) {
                            StepState.RUNNING -> SmallSpinner()
                            else -> Text(
                                text = when (step.state) {
                                    StepState.DONE -> "✔"
                                    StepState.FAILED -> "✘"
                                    StepState.SKIPPED -> "–"
                                    else -> "○"
                                },
                                color = when (step.state) {
                                    StepState.DONE -> OkColor
                                    StepState.FAILED -> ErrColor
                                    StepState.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                                    StepState.RUNNING -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Spacer(Modifier.size(MaaDesignTokens.Spacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(step.title, style = MaterialTheme.typography.bodyMedium)
                            if (step.message.isNotBlank()) {
                                Text(
                                    step.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (deploy.running) {
                Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
                LinearProgressIndicator(
                    progress = { deploy.overallProgress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---------------- 部署来源 ----------------
        SectionHeader("部署来源")
        MaaCard {
            Text("容器镜像", style = MaterialTheme.typography.titleSmall)
            RootfsSource.entries.forEach { src ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = ui.rootfsSource == src,
                        onClick = { vm.setRootfsSource(src) },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(src.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (ui.rootfsSource == RootfsSource.BUNDLED) {
                Text(
                    "使用 APK 内置的离线镜像，首次运行完全不需要网络。",
                    style = MaterialTheme.typography.bodySmall,
                    color = OkColor,
                )
            } else {
                Text(
                    "需要联网下载 1-3GB 镜像，请确保 Wi-Fi 与足够存储空间。",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarnColor,
                )
            }
        }

        SectionHeader("Termux 软件源")
        MaaCard {
            TermuxMirror.entries.forEach { m ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = ui.termuxMirror == m,
                        onClick = { vm.setTermuxMirror(m) },
                    )
                    Text(m.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // ---------------- 操作 ----------------
        Button(
            onClick = { vm.startDeploy() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = !deploy.running,
        ) {
            Text(
                if (deploy.running) "部署中…" else "开始部署",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        OutlinedButton(
            onClick = { vm.checkContainer() },
            modifier = Modifier.fillMaxWidth(),
            enabled = ui.runtimeReady && !deploy.running,
        ) {
            Text("检查容器状态")
        }

        if (deploy.error != null) {
            MaaCard {
                Text("部署失败", style = MaterialTheme.typography.titleSmall, color = ErrColor)
                Text(
                    deploy.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(MaaDesignTokens.Spacing.xxl))
    }
}
