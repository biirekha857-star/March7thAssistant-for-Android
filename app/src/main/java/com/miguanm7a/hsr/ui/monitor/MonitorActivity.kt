package com.miguanm7a.hsr.ui.monitor

import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.miguanm7a.hsr.deploy.DeploySettings
import com.miguanm7a.hsr.monitor.CdpDiscovery
import com.miguanm7a.hsr.monitor.CdpMonitor
import com.miguanm7a.hsr.monitor.MonitorState
import com.miguanm7a.hsr.ui.theme.MaaDesignTokens
import com.miguanm7a.hsr.ui.theme.MaaTermuxTheme
import com.miguanm7a.hsr.ui.theme.ErrColor
import com.miguanm7a.hsr.ui.theme.OkColor
import com.miguanm7a.hsr.ui.theme.WarnColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 实时监看：把云游戏画面通过 CDP screencast 显示出来。
 *
 * 前提是任务已经在跑（浏览器已带 `--remote-debugging-port` 启动）。
 */
class MonitorActivity : AppCompatActivity() {

    private var monitor: CdpMonitor? = null
    private var bitmapState: androidx.compose.runtime.MutableState<Bitmap?>? = null
    private var stateValue: androidx.compose.runtime.MutableState<MonitorState>? = null
    private var logLines: androidx.compose.runtime.MutableState<List<String>>? = null
    private var frameCount: androidx.compose.runtime.MutableState<Int>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val port = DeploySettings(applicationContext).monitorPort()

        setContent {
            val bmp = remember { mutableStateOf<Bitmap?>(null) }
            val st = remember { mutableStateOf<MonitorState>(MonitorState.Idle) }
            val lines = remember { mutableStateOf(listOf<String>()) }
            val frames = remember { mutableStateOf(0) }

            bitmapState = bmp
            stateValue = st
            logLines = lines
            frameCount = frames

            MaaTermuxTheme {
                MonitorScreen(
                    port = port,
                    bitmap = bmp.value,
                    state = st.value,
                    logs = lines.value,
                    frameCount = frames.value,
                    onStart = { startMonitor(port) },
                    onStop = { stopMonitor() },
                    onClose = { finish() },
                )
            }
        }

        // 看画面时不要锁屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startMonitor(port: Int) {
        stopMonitor()
        frameCount?.value = 0
        appendLog("开始探测调试端口（起始 $port）…")
        monitor = CdpMonitor(
            onState = { s -> stateValue?.value = s; appendLog(stateText(s)) },
            onLog = { appendLog(it) },
            onFrame = { b -> bitmapState?.value = b; frameCount?.value = (frameCount?.value ?: 0) + 1 },
        ).also { it.start(port) }
    }

    private fun stopMonitor() {
        monitor?.stop()
        monitor = null
    }

    private fun appendLog(line: String) {
        val ts = timeFmt.format(Date())
        logLines?.value = (logLines?.value ?: emptyList()) + "[$ts] $line"
    }

    override fun onDestroy() {
        stopMonitor()
        super.onDestroy()
    }

    companion object {
        private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        private fun stateText(s: MonitorState): String = when (s) {
            MonitorState.Idle -> "空闲"
            MonitorState.Discovering -> "正在探测调试端口…"
            MonitorState.Connecting -> "正在连接页面…"
            is MonitorState.Streaming -> "已开始推流（端口 ${s.port}）"
            MonitorState.Stopped -> "已停止"
            is MonitorState.Failed -> "失败：${s.message}"
        }
    }
}

@Composable
private fun MonitorScreen(
    port: Int,
    bitmap: Bitmap?,
    state: MonitorState,
    logs: List<String>,
    frameCount: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(MaaDesignTokens.Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "实时监看",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Box(Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("返回") }
        }

        // 画面区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "云游戏画面",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when (state) {
                            is MonitorState.Failed -> state.message
                            MonitorState.Discovering, MonitorState.Connecting ->
                                "正在连接…（端口从 $port 开始探测）"
                            else -> "尚无画面"
                        },
                        color = if (state is MonitorState.Failed) ErrColor else Color(0xFF9E9E9E),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state is MonitorState.Idle || state is MonitorState.Stopped) {
                        Text(
                            "请先在「任务」页启动任务，浏览器起来后再点下面的开始",
                            color = Color(0xFF757575),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = MaaDesignTokens.Spacing.sm),
                        )
                    }
                }
            }
        }

        // 状态行
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (state) {
                    is MonitorState.Streaming -> "● 推流中  ${frameCount} 帧"
                    MonitorState.Discovering -> "○ 探测端口"
                    MonitorState.Connecting -> "○ 连接中"
                    is MonitorState.Failed -> "✘ 失败"
                    else -> "○ 未开始"
                },
                color = when (state) {
                    is MonitorState.Streaming -> OkColor
                    is MonitorState.Failed -> ErrColor
                    else -> WarnColor
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(MaaDesignTokens.Spacing.sm)) {
            Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" 开始监看")
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" 停止")
            }
        }

        // 诊断日志（连不上时靠它排查）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f)
                .background(Color(0xFF141414))
                .padding(MaaDesignTokens.Spacing.sm)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "诊断输出",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF9E9E9E),
            )
            SelectionContainer {
                Text(
                    text = logs.takeLast(40).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    color = Color(0xFFCFCFCF),
                )
            }
        }

        Text(
            "说明：本功能通过 Chrome DevTools Protocol 连接容器内 Chromium 的调试端口" +
                "（默认 ${CdpDiscovery.DEFAULT_PORT}，被占用时自动递增）取回画面帧。" +
                "若一直显示「未找到调试端口」，请确认任务已启动且浏览器已在运行。",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF757575),
        )
    }
}
