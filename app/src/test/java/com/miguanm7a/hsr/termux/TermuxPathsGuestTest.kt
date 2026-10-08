package com.miguanm7a.hsr.termux

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归测试：交给容器的路径必须是 /data/data 形式。
 *
 * 真机踩过的坑：`Context.getFilesDir()` 返回 `/data/user/0/<pkg>/files`，
 * 而 proot-distro 只把 `/data/data/<pkg>` 绑定进容器。容器里不存在
 * `/data/user/0/...`，于是把部署脚本按真实路径交给容器时：
 *     /bin/bash: /data/user/0/com.m7ahsr/files/home/.m7a-inner.sh: No such file or directory
 *     退出码 127
 *
 * guestPath 只做纯字符串处理，不碰 Context，所以可以直接在 JVM 单测里跑。
 */
class TermuxPathsGuestTest {

    @Test
    fun `user-0 paths are rewritten to data-data`() {
        assertEquals(
            "/data/data/com.m7ahsr/files/home/.m7a-inner.sh",
            TermuxPaths.guestPath("/data/user/0/com.m7ahsr/files/home/.m7a-inner.sh"),
        )
        assertEquals(
            "/data/data/com.m7ahsr/files/usr",
            TermuxPaths.guestPath("/data/user/0/com.m7ahsr/files/usr"),
        )
    }

    @Test
    fun `other user ids are rewritten too`() {
        assertEquals(
            "/data/data/com.m7ahsr/files/home/x",
            TermuxPaths.guestPath("/data/user/10/com.m7ahsr/files/home/x"),
        )
    }

    @Test
    fun `already canonical paths are untouched`() {
        val p = "/data/data/com.m7ahsr/files/home/start-m7a.sh"
        assertEquals(p, TermuxPaths.guestPath(p))
    }

    @Test
    fun `unrelated paths are untouched`() {
        assertEquals("/system/bin/sh", TermuxPaths.guestPath("/system/bin/sh"))
        assertEquals("/sdcard/x", TermuxPaths.guestPath("/sdcard/x"))
        assertEquals("relative/path", TermuxPaths.guestPath("relative/path"))
    }

    @Test
    fun `constants match the hardcoded prefix in termux binaries`() {
        // 这两个值是 Termux 二进制里硬编码的，改动会直接让 bash/proot 找不到自己
        assertEquals("/data/data/com.m7ahsr/files/usr", TermuxPaths.OFFICIAL_PREFIX)
        assertEquals("/data/data/com.m7ahsr/files/home", TermuxPaths.OFFICIAL_HOME)
    }
}
