package com.miguanm7a.hsr.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.miguanm7a.hsr.deploy.TaskToggles
import com.miguanm7a.hsr.deploy.ToggleGroup
import com.miguanm7a.hsr.ui.MainViewModel
import com.miguanm7a.hsr.ui.components.MaaCard
import com.miguanm7a.hsr.ui.components.MaaDivider
import com.miguanm7a.hsr.ui.theme.MaaDesignTokens
import com.miguanm7a.hsr.ui.theme.WarnColor

/**
 * 任务开关页。
 *
 * 这些开关直接对应上游 `config.yaml` 的顶层键，用来决定**完整运行时会跑哪几项**。
 * 改动只写到本地，需要点「生成脚本」才会增量同步进容器（同步要先拉出容器内
 * 现有配置做增量修改，不能直接覆盖）。
 */
@Composable
fun TaskTogglesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val values by vm.toggles.collectAsState()
    val expanded = remember { mutableStateMapOf<ToggleGroup, Boolean>() }

    Column(Modifier.fillMaxSize()) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaaDesignTokens.Spacing.sm,
                    vertical = MaaDesignTokens.Spacing.sm,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("任务开关", style = MaterialTheme.typography.headlineSmall)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = MaaDesignTokens.Spacing.listHorizontal,
                vertical = MaaDesignTokens.Spacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
        ) {
            item {
                MaaCard {
                    Text(
                        "这些开关对应 config.yaml 里的 *_enable，决定「完整运行」会执行哪几项。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "改完必须点「生成脚本」才会写入容器。写入是增量修改，"
                            + "不会覆盖程序记录的时间戳等状态。",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarnColor,
                    )
                }
            }

            for (group in ToggleGroup.entries) {
                val toggles = TaskToggles.ALL.filter { it.group == group }
                if (toggles.isEmpty()) continue
                val isOpen = expanded[group] == true
                val onCount = toggles.count { values[it.key] == true }

                item(key = "hdr-${group.name}") {
                    MaaCard(onClick = { expanded[group] = !isOpen }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(group.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "已开启 $onCount / ${toggles.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                if (isOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (isOpen) "收起" else "展开",
                            )
                        }
                    }
                }

                if (isOpen) {
                    items(toggles, key = { it.key }) { toggle ->
                        MaaCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        toggle.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(
                                        toggle.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        toggle.key,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = values[toggle.key] == true,
                                    onCheckedChange = { vm.setToggle(toggle.key, it) },
                                )
                            }
                        }
                    }
                    item(key = "div-${group.name}") { MaaDivider() }
                }
            }

            item {
                Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
                Button(
                    onClick = { vm.regenerateScripts() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text("生成脚本并写入容器", style = MaterialTheme.typography.titleMedium)
                }
            }
            item {
                OutlinedButton(
                    onClick = { vm.resetTogglesToDefault() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("恢复上游默认值")
                }
            }
            item { Spacer(Modifier.height(MaaDesignTokens.Spacing.xxl)) }
        }
    }
}
