package com.miguanm7a.hsr.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CDP 发现逻辑的回归测试。
 *
 * 这部分出错的症状是「一直看不到画面」，而在真机上很难区分是
 * 端口没探到、还是 target 选错了，所以把规则固定下来。
 */
class CdpDiscoveryTest {

    private fun page(
        id: String = "1",
        type: String = "page",
        url: String = "https://example.com",
        ws: String = "ws://127.0.0.1:9222/devtools/page/1",
        title: String = "",
    ) = CdpTarget(id, type, title, url, ws)

    // ---------------- 端口候选 ----------------

    @Test
    fun `port candidates start at configured port`() {
        val ports = CdpDiscovery.portCandidates(9222)
        assertEquals(9222, ports.first())
        assertEquals(21, ports.size)          // span 20 -> 21 个
        assertEquals(9242, ports.last())
    }

    /** 上游在端口被占用时会递增找空闲端口，所以必须扫一段范围。 */
    @Test
    fun `port candidates cover the increment range`() {
        val ports = CdpDiscovery.portCandidates(9222)
        assertTrue("应覆盖 9223", 9223 in ports)
        assertTrue("应覆盖 9230", 9230 in ports)
    }

    @Test
    fun `port candidates clamp at 65535`() {
        val ports = CdpDiscovery.portCandidates(65530)
        assertEquals(65535, ports.last())
        assertTrue("不应超过 65535", ports.all { it in 1..65535 })
    }

    @Test
    fun `port candidates handle invalid configured value`() {
        assertTrue(CdpDiscovery.portCandidates(0).all { it in 1..65535 })
        assertTrue(CdpDiscovery.portCandidates(-5).all { it in 1..65535 })
        assertTrue(CdpDiscovery.portCandidates(99999).all { it in 1..65535 })
    }

    @Test
    fun `custom span is honoured`() {
        val ports = CdpDiscovery.portCandidates(9000, span = 3)
        assertEquals(listOf(9000, 9001, 9002, 9003), ports)
    }

    // ---------------- target 选择 ----------------

    @Test
    fun `prefers the cloud game page`() {
        val targets = listOf(
            page(id = "a", url = "https://www.google.com"),
            page(id = "b", url = "https://sr.mihoyo.com/cloud/"),
            page(id = "c", url = "https://other.example/x"),
        )
        assertEquals("b", CdpDiscovery.pick(targets)?.id)
    }

    @Test
    fun `prefers cloud keyword when mihoyo absent`() {
        val targets = listOf(
            page(id = "a", url = "https://example.com"),
            page(id = "b", url = "https://foo/cloud/game"),
        )
        assertEquals("b", CdpDiscovery.pick(targets)?.id)
    }

    @Test
    fun `falls back to first page when no preferred match`() {
        val targets = listOf(
            page(id = "a", url = "https://example.com"),
            page(id = "b", url = "https://other.example"),
        )
        assertEquals("a", CdpDiscovery.pick(targets)?.id)
    }

    @Test
    fun `ignores non-page targets`() {
        val targets = listOf(
            page(id = "sw", type = "service_worker", url = "https://sr.mihoyo.com/sw"),
            page(id = "if", type = "iframe", url = "https://sr.mihoyo.com/frame"),
            page(id = "p", type = "page", url = "https://example.com"),
        )
        assertEquals("p", CdpDiscovery.pick(targets)?.id)
    }

    @Test
    fun `ignores devtools pages`() {
        val targets = listOf(
            page(id = "dev", url = "devtools://devtools/bundled/inspector.html"),
            page(id = "real", url = "https://example.com"),
        )
        assertEquals("real", CdpDiscovery.pick(targets)?.id)
    }

    @Test
    fun `ignores targets without websocket url`() {
        val targets = listOf(
            page(id = "nows", url = "https://sr.mihoyo.com/cloud", ws = ""),
            page(id = "ok", url = "https://example.com"),
        )
        assertEquals("ok", CdpDiscovery.pick(targets)?.id)
    }

    @Test
    fun `returns null when nothing usable`() {
        assertNull(CdpDiscovery.pick(emptyList()))
        assertNull(CdpDiscovery.pick(listOf(page(type = "service_worker"))))
        assertNull(
            CdpDiscovery.pick(listOf(page(url = "devtools://devtools/x"))),
        )
    }

    /** 关键词匹配应忽略大小写。 */
    @Test
    fun `keyword matching is case insensitive`() {
        val targets = listOf(
            page(id = "a", url = "https://example.com"),
            page(id = "b", url = "https://SR.MiHoYo.com/Cloud"),
        )
        assertEquals("b", CdpDiscovery.pick(targets)?.id)
    }
}
