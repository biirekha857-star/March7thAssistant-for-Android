package com.miguanm7a.hsr.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.miguanm7a.hsr.BuildConfig
import java.io.File
import java.util.zip.ZipInputStream

/**
 * `$PREFIX` 与 bootstrap 资产之间的一致性回归测试。
 *
 * ## 为什么需要这个测试
 *
 * 内置 bootstrap 里的二进制把 `/data/data/<包名>/files/usr` **硬编码**在
 * ELF 的 `.dynstr` 里（`DT_RUNPATH` 指向它）。字符串表按偏移量引用，
 * 所以这个字符串**只能改短、不能改长**：
 *
 * ```
 * len("/data/data/") + len(包名) + len("/files/usr") = 21 + len(包名)
 * ```
 *
 * 原前缀（`com.termux`）是 31 字节 => 包名最长 10 字符。
 *
 * 一旦有人改了 `applicationId` 却忘了重跑
 * `scripts/reprefix_bootstrap.py` 重新生成资产，应用会在真机上表现为
 * 「点了没反应」（bash 起来了但找不到自己的 .so）。
 * **这个测试就是拦这种情况的** —— 它直接读真实资产核对。
 */
class TermuxPrefixInvariantTest {

    private val asset: File? = sequenceOf(
        File("src/main/assets/bootstrap/termux-bootstrap.zip"),
        File("app/src/main/assets/bootstrap/termux-bootstrap.zip"),
    ).firstOrNull { it.isFile }

    /**
     * 关键不变式：代码里的包名必须等于真实 applicationId。
     * 两者不一致时，`prefixMatchesBinaries()` 会在真机上返回 false。
     */
    @Test
    fun `APP_PACKAGE matches the real applicationId`() {
        assertEquals(
            "TermuxPaths.APP_PACKAGE 必须与 build.gradle.kts 的 applicationId 一致",
            BuildConfig.APPLICATION_ID,
            TermuxPaths.APP_PACKAGE,
        )
    }

    /**
     * 包名长度上限 10 —— 这是 ELF 字符串表决定的硬约束，不是风格问题。
     */
    @Test
    fun `package name fits within the hard length limit`() {
        assertTrue(
            "包名 '${TermuxPaths.APP_PACKAGE}' 长度 ${TermuxPaths.APP_PACKAGE.length} > 10，" +
                "前缀会超出 bootstrap 里硬编码的 31 字节，动态链接器将找不到库",
            TermuxPaths.APP_PACKAGE.length <= 10,
        )
    }

    /** 前缀必须恰好是原来的 31 字节 —— 等长才允许原地字节替换。 */
    @Test
    fun `prefix keeps the original byte length`() {
        assertEquals(
            "前缀长度必须与原版一致（31 字节），否则等长替换的前提不成立",
            31,
            TermuxPaths.OFFICIAL_PREFIX.length,
        )
    }

    @Test
    fun `path constants are derived consistently`() {
        val pkg = TermuxPaths.APP_PACKAGE
        assertEquals("/data/data/$pkg/files/usr", TermuxPaths.OFFICIAL_PREFIX)
        assertEquals("/data/data/$pkg/files/home", TermuxPaths.OFFICIAL_HOME)
        assertEquals("${TermuxPaths.OFFICIAL_PREFIX}/bin/bash", TermuxPaths.OFFICIAL_BASH)
    }

    /** Kotlin 的 `ByteArray.contains` 只接受单个 Byte，没有子数组搜索，这里补一个。 */
    private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
        String(this, Charsets.ISO_8859_1).contains(String(needle, Charsets.ISO_8859_1))

    /**
     * 最重要的一条：**真实资产**里必须已经不存在旧前缀。
     * 忘了重跑 reprefix 脚本时，这里会立刻失败。
     */
    @Test
    fun `bootstrap asset carries the current package name`() {
        assumeTrue("bootstrap 资产不存在，跳过", asset != null)

        val newBytes = "data/data/${TermuxPaths.APP_PACKAGE}".toByteArray()
        val oldBytes = "data/data/com.termux".toByteArray()

        var filesWithNew = 0
        var filesWithOld = 0
        var sawBashRunpath = false

        ZipInputStream(asset!!.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zis.readBytes()
                    if (bytes.containsBytes(newBytes)) filesWithNew++
                    if (bytes.containsBytes(oldBytes)) filesWithOld++
                    if (entry.name == "bin/bash" && bytes.containsBytes(newBytes)) {
                        sawBashRunpath = true
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        assertEquals(
            "bootstrap 里仍有旧前缀 com.termux，说明资产没有用 " +
                "scripts/reprefix_bootstrap.py 重新生成",
            0,
            filesWithOld,
        )
        assertTrue("应该有很多文件带上新前缀（实际 $filesWithNew）", filesWithNew > 500)
        assertTrue("bin/bash 里应含新前缀", sawBashRunpath)
    }

    /**
     * shebang 也要跟着走。脚本 shebang 是纯文本，改起来没有长度限制，
     * 但漏改一样会导致 `No such file or directory`。
     */
    @Test
    fun `SYMLINKS_txt absolute targets use the current prefix`() {
        assumeTrue("bootstrap 资产不存在，跳过", asset != null)

        val absoluteTargets = ArrayList<String>()
        ZipInputStream(asset!!.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "SYMLINKS.txt") {
                    val text = zis.readBytes().toString(Charsets.UTF_8)
                    text.lineSequence().forEach { line ->
                        if (line.isBlank()) return@forEach
                        val target = line.substringBefore('\u2190')
                        if (target.startsWith("/data/")) absoluteTargets.add(target)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        assertTrue("应该存在绝对路径的软链目标", absoluteTargets.isNotEmpty())
        for (t in absoluteTargets) {
            assertFalse("软链目标仍是旧前缀: $t", t.contains("com.termux"))
            assertTrue(
                "软链目标未使用当前前缀: $t",
                t.startsWith("/data/data/${TermuxPaths.APP_PACKAGE}/"),
            )
        }
    }
}
