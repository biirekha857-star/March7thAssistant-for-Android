package com.miguanm7a.hsr.monitor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 通过 Chrome DevTools Protocol 把云游戏画面**实时**取回本地显示。
 *
 * ## 原理
 *
 * March7thAssistant 启动 Chromium 时带了 `--remote-debugging-port=<port>`
 * （见 `module/game/cloud.py` 的 `_connect_or_create_browser`），端口由
 * `browser_debug_port` 配置，默认 9222；若被占用则递增找空闲端口。
 *
 * proot **不隔离网络**，所以该端口在 App 侧同样可以通过 `127.0.0.1` 访问。
 * 连上页面 target 的 WebSocket 后调用 `Page.startScreencast`，
 * DevTools 就会持续推送 JPEG 帧（base64），我们解码成 Bitmap 显示。
 *
 * ## 已知不确定点（本机无法验证）
 *
 * Selenium 已经连在同一个页面 target 上。DevTools 允许多个客户端连同一 target，
 * 但「两个客户端同时 startScreencast」的行为没有在本机验证过 —— 理论上
 * Chromium 会向每个请求过 screencast 的客户端都发送帧，各自需要 ack。
 */
class CdpMonitor(
    private val minFrameIntervalMs: Long = 200L,
    private val maxWidth: Int = 960,
    private val maxHeight: Int = 540,
    private val onState: (MonitorState) -> Unit,
    private val onLog: (String) -> Unit,
    private val onFrame: (Bitmap) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())

    /** 探测端口用：短超时，失败要快。 */
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(600, TimeUnit.MILLISECONDS)
        .readTimeout(800, TimeUnit.MILLISECONDS)
        .build()

    /** WebSocket 用：不能设短 readTimeout，否则帧间隔大时会断开。 */
    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var running = false

    private var ws: WebSocket? = null

    private var worker: Thread? = null

    private var lastFrameAt = 0L

    private var frameCount = 0

    private var nextId = 1

    private fun state(s: MonitorState) = main.post { onState(s) }

    private fun log(m: String) {
        Log.i(TAG, m)
        main.post { onLog(m) }
    }

    fun start(configuredPort: Int) {
        stop()
        running = true
        frameCount = 0
        val ports = CdpDiscovery.portCandidates(configuredPort)
        worker = Thread({ discoverAndConnect(ports) }, "cdp-monitor").apply { start() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        runCatching { ws?.send("""{"id":${nextId++},"method":"Page.stopScreencast"}""") }
        runCatching { ws?.close(1000, "stop") }
        ws = null
        state(MonitorState.Stopped)
    }

    // ------------------------------------------------------------------
    // 发现端口 → 选目标 → 连 WebSocket
    // ------------------------------------------------------------------

    private fun discoverAndConnect(ports: List<Int>) {
        state(MonitorState.Discovering)
        log("正在探测 Chromium 调试端口（${ports.first()} ~ ${ports.last()}）…")

        var hit: Pair<Int, String>? = null
        for (p in ports) {
            if (!running) return
            val version = httpGet("http://127.0.0.1:$p/json/version")
            if (version != null && version.contains("webSocketDebuggerUrl")) {
                hit = p to version
                break
            }
        }

        if (hit == null) {
            state(
                MonitorState.Failed(
                    "在 127.0.0.1:${ports.first()}~${ports.last()} 上没找到调试端口。\n" +
                        "可能原因：任务还没启动浏览器；或浏览器未带 --remote-debugging-port 启动。"
                )
            )
            log("未找到调试端口")
            return
        }
        val port = hit.first
        log("发现调试端口：$port")

        val listJson = httpGet("http://127.0.0.1:$port/json/list")
        val targets = parseTargets(listJson)
        log("共有 ${targets.size} 个 target，其中 page ${targets.count { it.type == "page" }} 个")

        val target = CdpDiscovery.pick(targets)
        if (target == null) {
            // 打印出来便于排查
            targets.take(10).forEach { log("  - [${it.type}] ${it.url.take(80)}") }
            state(MonitorState.Failed("调试端口已找到（$port），但没有可用的 page target。"))
            return
        }
        log("已选定页面：${target.title.ifBlank { target.url.take(60) }}")

        state(MonitorState.Connecting)
        connect(port, target)
    }

    private fun connect(port: Int, target: CdpTarget) {
        val req = Request.Builder().url(target.webSocketDebuggerUrl).build()
        ws = wsClient.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                log("WebSocket 已连接，启动 screencast")
                send(webSocket, "Page.enable")
                send(
                    webSocket,
                    "Page.startScreencast",
                    JSONObject().apply {
                        put("format", "jpeg")
                        put("quality", 60)
                        put("maxWidth", maxWidth)
                        put("maxHeight", maxHeight)
                        put("everyNthFrame", 1)
                    },
                )
                state(MonitorState.Streaming(port, target.title.ifBlank { target.url.take(60) }))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!running) return
                runCatching { handleMessage(webSocket, text) }
                    .onFailure { Log.w(TAG, "处理 CDP 消息失败", it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!running) return
                log("WebSocket 断开：${t.javaClass.simpleName} ${t.message}")
                state(MonitorState.Failed("连接中断：${t.message ?: t.javaClass.simpleName}"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!running) return
                state(MonitorState.Stopped)
            }
        })
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val obj = JSONObject(text)

        // 命令响应：检查错误
        if (obj.has("id") && obj.has("error")) {
            log("CDP 命令报错：${obj.getJSONObject("error").optString("message")}")
            return
        }
        if (!obj.has("method")) return

        when (obj.optString("method")) {
            "Page.screencastFrame" -> {
                val params = obj.optJSONObject("params") ?: return
                val sessionId = params.optInt("sessionId", -1)
                val data = params.optString("data", "")

                // 无论是否显示这一帧，都必须 ack，否则 Chromium 会停止推流
                if (sessionId >= 0) {
                    send(webSocket, "Page.screencastFrameAck", JSONObject().put("sessionId", sessionId))
                }

                val now = System.currentTimeMillis()
                if (now - lastFrameAt < minFrameIntervalMs) return   // 限帧，省 CPU
                lastFrameAt = now

                if (data.isEmpty()) return
                val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() ?: return
                val bmp = runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull() ?: return

                frameCount++
                main.post { onFrame(bmp) }
            }

            "Page.frameNavigated" -> {
                val url = obj.optJSONObject("params")
                    ?.optJSONObject("frame")?.optString("url").orEmpty()
                if (url.isNotBlank()) log("页面跳转：${url.take(80)}")
            }
        }
    }

    private fun send(ws: WebSocket, method: String, params: JSONObject? = null) {
        val msg = JSONObject().apply {
            put("id", nextId++)
            put("method", method)
            if (params != null) put("params", params)
        }
        ws.send(msg.toString())
    }

    // ------------------------------------------------------------------
    // HTTP 辅助
    // ------------------------------------------------------------------

    private fun httpGet(url: String): String? = try {
        probeClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (t: Throwable) {
        // 端口没开时是立刻 ECONNREFUSED，属正常情况
        null
    }

    private fun parseTargets(json: String?): List<CdpTarget> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr: JSONArray = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                CdpTarget(
                    id = o.optString("id"),
                    type = o.optString("type"),
                    title = o.optString("title"),
                    url = o.optString("url"),
                    webSocketDebuggerUrl = o.optString("webSocketDebuggerUrl"),
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "CdpMonitor"
    }
}
