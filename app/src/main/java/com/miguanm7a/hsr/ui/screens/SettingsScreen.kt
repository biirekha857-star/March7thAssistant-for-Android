package com.miguanm7a.hsr.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.miguanm7a.hsr.service.KeepAlive
import com.miguanm7a.hsr.ui.MainViewModel
import com.miguanm7a.hsr.ui.components.InfoRow
import com.miguanm7a.hsr.ui.components.MaaCard
import com.miguanm7a.hsr.ui.components.SectionHeader
import com.miguanm7a.hsr.termux.TermuxPaths
import com.miguanm7a.hsr.ui.theme.AccentTheme
import com.miguanm7a.hsr.ui.theme.ErrColor
import com.miguanm7a.hsr.ui.theme.MaaDesignTokens
import com.miguanm7a.hsr.ui.theme.OkColor
import com.miguanm7a.hsr.ui.theme.ThemeMode
import com.miguanm7a.hsr.ui.theme.WarnColor

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    themeMode: ThemeMode,
    accentTheme: AccentTheme,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAccentChange: (AccentTheme) -> Unit,
    onOpenMonitor: () -> Unit = {},
) {
    val ui by vm.ui.collectAsState()
    var showReset by remember { mutableStateOf(false) }
    var subScreen by rememberSaveable { mutableStateOf<String?>(null) }

    if (subScreen == "toggles") {
        TaskTogglesScreen(vm = vm, onBack = { subScreen = null })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MaaDesignTokens.Spacing.listHorizontal),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        Spacer(Modifier.height(MaaDesignTokens.Spacing.lg))
        Text("设置", style = MaterialTheme.typography.headlineMedium)

        SectionHeader("容器")
        MaaCard {
            OutlinedTextField(
                value = ui.containerAlias,
                onValueChange = { vm.setContainerAlias(it) },
                label = { Text("容器名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "proot-distro 使用的容器别名，默认 m7a。修改后需要重新部署或手动重命名。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionHeader("任务")
        MaaCard(onClick = { subScreen = "toggles" }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("任务开关", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "决定「完整运行」会执行哪几项（清体力 / 日常 / 领取奖励 / 活动 …）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        MaaCard {
            OutlinedTextField(
                value = ui.logLevel,
                onValueChange = { vm.setLogLevel(it) },
                label = { Text("日志级别") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "常用值：DEBUG / INFO / WARNING / ERROR",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                FilterChip(
                    selected = ui.usePaidTime,
                    onClick = { vm.setUsePaidTime(!ui.usePaidTime) },
                    label = { Text("使用付费时长") },
                )
            }
            Text(
                "调整任务开关或下面这些选项后，点「生成脚本」才会写入容器。",
                style = MaterialTheme.typography.bodySmall,
                color = WarnColor,
            )
        }

        SectionHeader("后台保活")
        MaaCard {
            val ctx = LocalContext.current
            var ignoring by remember { mutableStateOf(KeepAlive.isIgnoringBatteryOptimizations(ctx)) }
            val running = ui.runningTask

            InfoRow(
                "前台服务",
                if (running) "运行中（已加锁）" else "未运行",
                valueColor = if (running) OkColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InfoRow(
                "电池优化",
                if (ignoring) "已在白名单" else "未加入白名单",
                valueColor = if (ignoring) OkColor else WarnColor,
            )
            Text(
                "任务运行时应用会启动前台服务并持有 WakeLock，防止系统冻结进程"
                    + "导致自动化中断。国产 ROM 还需额外放行：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "① 电池优化加入白名单（点下面按钮）\n"
                    + "② 允许「自启动」/「后台弹出界面」\n"
                    + "③ 省电策略设为「无限制」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
            if (!ignoring) {
                Button(
                    onClick = {
                        KeepAlive.requestIgnoreBatteryOptimizations(ctx)
                        ignoring = KeepAlive.isIgnoringBatteryOptimizations(ctx)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("申请加入电池优化白名单") }
            }
            OutlinedButton(
                onClick = { KeepAlive.openAppDetails(ctx) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("打开应用详情（关掉后台限制）") }
        }

        SectionHeader("实时监看")
        MaaCard(onClick = { onOpenMonitor() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("打开监看画面", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "通过 Chrome DevTools Protocol 显示云游戏实时画面（需任务已在运行）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        MaaCard {
            OutlinedTextField(
                value = ui.monitorPort.toString(),
                onValueChange = { v ->
                    v.filter { it.isDigit() }.take(5).toIntOrNull()?.let { vm.setMonitorPort(it) }
                },
                label = { Text("浏览器调试端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "对应上游 browser_debug_port，默认 9222。" +
                    "被占用时程序会自动递增，监看侧会按范围探测，所以一般不用改。" +
                    "改完点「生成脚本」才写入容器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionHeader("外观")
        MaaCard {
            Text("主题模式", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "跟随系统"
                                    ThemeMode.WHITE -> "浅色"
                                    ThemeMode.DARK -> "深色"
                                    ThemeMode.PURE_DARK -> "纯黑"
                                }
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
            Text("强调色", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
                FilterChip(
                    selected = accentTheme == AccentTheme.MARCH7,
                    onClick = { onAccentChange(AccentTheme.MARCH7) },
                    label = { Text("三月七粉") },
                )
                FilterChip(
                    selected = accentTheme == AccentTheme.MAA,
                    onClick = { onAccentChange(AccentTheme.MAA) },
                    label = { Text("MAA 蓝") },
                )
            }
        }

        SectionHeader("运行时")
        MaaCard {
            InfoRow("版本", ui.version)
            InfoRow("设备架构", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "未知")
            InfoRow("运行时", if (ui.runtimeReady) "已安装" else "未安装")
            InfoRow("容器", if (ui.deployed) ui.containerAlias else "未部署")
            InfoRow("安装路径", TermuxPaths.OFFICIAL_PREFIX)

            Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
            OutlinedButton(
                onClick = { showReset = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text("重置运行时", color = ErrColor)
            }
        }

        SectionHeader("关于")
        MaaCard {
            Text(
                "MaaTermux 把 Termux 运行时直接内置进 APK：bootstrap 从 assets 解压，"
                    + "容器由 proot-distro 在 App 自己的私有目录里运行，全程不依赖外部 Termux 应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "术语：\$PREFIX = ${TermuxPaths.OFFICIAL_PREFIX}，\$HOME = ${TermuxPaths.OFFICIAL_HOME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(MaaDesignTokens.Spacing.xxl))
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("重置运行时") },
            text = { Text("将删除已安装的 Termux 运行时与容器数据，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showReset = false
                    vm.resetRuntime()
                }) { Text("确认重置", color = ErrColor) }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) { Text("取消") }
            },
        )
    }
}
