package com.miguanm7a.hsr.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.miguanm7a.hsr.ui.QrInfo
import com.miguanm7a.hsr.ui.theme.MaaDesignTokens
import com.miguanm7a.hsr.ui.theme.OkColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 扫码登录卡片。
 *
 * March7thAssistant 首次运行必须扫码登录。二维码由程序写到项目目录下的
 * `logs/qrcode_login.png`；容器内的 `logs` 被 `--bind` 到共享目录，
 * 所以这个文件在手机上可以直接读出来显示、用米游社 App 扫。
 */
@Composable
fun LoginQrCard(
    qr: QrInfo?,
    runningTask: Boolean,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit = {},
) {
    var zoomed by remember { mutableStateOf(false) }

    MaaCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.QrCode2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(MaaDesignTokens.Spacing.sm))
            Text("扫码登录", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("刷新")
            }
        }

        if (qr == null) {
            Text(
                if (runningTask) {
                    "正在等待二维码…首次运行需要登录，程序生成二维码后会显示在这里。"
                } else {
                    "运行任务后，如果检测到未登录，登录二维码会显示在这里，" +
                        "用手机「米游社」App 扫描即可。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@MaaCard
        }

        val bitmap = remember(qr.revision) {
            runCatching { BitmapFactory.decodeFile(qr.file.absolutePath) }.getOrNull()
        }

        if (bitmap == null) {
            Text(
                "二维码文件存在但解码失败：${qr.file.name}（${qr.size} 字节）",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD9483B),
            )
            return@MaaCard
        }

        Text(
            "请用手机「米游社」App 扫描下面的二维码完成登录",
            style = MaterialTheme.typography.bodyMedium,
            color = OkColor,
        )

        // 二维码需要留白才容易被扫到，所以加白底 + 内边距
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MaaDesignTokens.Spacing.sm)
                .clip(RoundedCornerShape(MaaDesignTokens.CornerRadius.inner))
                .background(Color.White)
                .padding(MaaDesignTokens.Spacing.lg)
                .clickable { zoomed = true },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "登录二维码",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
        }

        Text(
            "二维码会过期自动刷新，显示时间：" + formatTime(qr.lastModified) + "（点图片放大）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (zoomed && qr != null) {
        val bitmap = remember(qr.revision) {
            runCatching { BitmapFactory.decodeFile(qr.file.absolutePath) }.getOrNull()
        }
        if (bitmap != null) {
            Dialog(onDismissRequest = { zoomed = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MaaDesignTokens.CornerRadius.card))
                        .background(Color.White)
                        .padding(MaaDesignTokens.Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
                ) {
                    Text(
                        "用「米游社」App 扫描",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Black,
                        textAlign = TextAlign.Center,
                    )
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "登录二维码（放大）",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                    )
                    Text(
                        "二维码位置：${qr.file.absolutePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8A8580),
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = { zoomed = false }) { Text("关闭") }
                }
            }
        }
    }
}

private val qrTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun formatTime(millis: Long): String = qrTimeFmt.format(Date(millis))

/** 首页用的紧凑提示条（有二维码时提示去任务页或直接点开）。 */
@Composable
fun LoginQrBanner(qr: QrInfo?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (qr == null) return
    MaaCard(modifier = modifier, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.QrCode2,
                contentDescription = null,
                tint = OkColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(MaaDesignTokens.Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text("需要扫码登录", style = MaterialTheme.typography.titleSmall, color = OkColor)
                Text(
                    "点这里查看登录二维码，用「米游社」App 扫描",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun LoginQrSpacer() {
    Spacer(Modifier.height(MaaDesignTokens.Spacing.sm))
}
