package com.miguanm7a.hsr.monitor

/** `/json/list` 返回的一条 DevTools target。 */
data class CdpTarget(
    val id: String,
    val type: String,
    val title: String,
    val url: String,
    val webSocketDebuggerUrl: String,
)

/** 监看状态。 */
sealed interface MonitorState {
    data object Idle : MonitorState
    data object Discovering : MonitorState
    data object Connecting : MonitorState
    data class Streaming(val port: Int, val targetTitle: String) : MonitorState
    data object Stopped : MonitorState
    data class Failed(val message: String) : MonitorState
}

/**
 * 纯逻辑部分：端口候选与 target 选择。
 *
 * 单独抽出来是为了能单测 —— 这几条规则出过错就会表现为「找不到画面」，
 * 而在真机上很难区分是端口没找到、还是 target 选错了。
 */
object CdpDiscovery {

    /** 默认调试端口（与上游 `browser_debug_port` 默认值一致）。 */
    const val DEFAULT_PORT = 9222

    /** 扫描多少个连续端口。 */
    const val PORT_SCAN_SPAN = 20

    /**
     * 端口候选列表。
     *
     * 上游 `cloud.py` 在配置端口无法绑定时会**递增**找空闲端口
     * （`_find_available_port(configured_port)`），所以配置值不等于实际值，
     * 必须按范围探测。
     */
    fun portCandidates(configured: Int, span: Int = PORT_SCAN_SPAN): List<Int> {
        val start = configured.coerceIn(1, 65535)
        val end = (start + span).coerceAtMost(65535)
        return (start..end).toList()
    }

    /**
     * 从 target 列表里挑出「云游戏画面」所在的那个页面。
     *
     * 规则：
     * 1. 只看 `type == "page"`（排除 iframe / service_worker / browser 等）
     * 2. 排除 `devtools://`（那是开发者工具自身的页面）
     * 3. 优先 URL 里带 mihoyo / cloud / sr 的（云崩铁页面）
     * 4. 否则取第一个可用的 page
     */
    fun pick(targets: List<CdpTarget>): CdpTarget? {
        val pages = targets.filter { it.type == "page" }
            .filter { !it.url.startsWith("devtools://") }
            .filter { it.webSocketDebuggerUrl.isNotBlank() }
        if (pages.isEmpty()) return null

        val preferred = pages.firstOrNull { t ->
            val u = t.url.lowercase()
            u.contains("mihoyo") || u.contains("cloud") || u.contains("sr.")
        }
        return preferred ?: pages.first()
    }
}
