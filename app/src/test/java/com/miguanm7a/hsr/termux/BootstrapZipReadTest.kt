package com.miguanm7a.hsr.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 针对 BootstrapInstaller 解压逻辑的回归测试。
 *
 * 覆盖一个真实踩过的坑：用 `BufferedReader.useLines()` 读 SYMLINKS.txt 会
 * 连带关闭 ZipInputStream，导致下一条 `nextEntry()` 抛 "Stream closed"。
 * 因为 SYMLINKS.txt 是 zip 的第一条，安装会立刻失败。
 *
 * 测试直接跑真实的 bootstrap 资产（存在才跑），所以它验证的是真东西，
 * 不是模拟数据。
 */
class BootstrapZipReadTest {

    private val asset: File? = sequenceOf(
        File("src/main/assets/bootstrap/termux-bootstrap.zip"),
        File("app/src/main/assets/bootstrap/termux-bootstrap.zip"),
    ).firstOrNull { it.isFile }

    /** 与 BootstrapInstaller.readEntryAsString 完全一致的实现。 */
    private fun readEntryAsString(zis: ZipInputStream): String {
        val out = ByteArrayOutputStream(32 * 1024)
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = zis.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    @Test
    fun `SYMLINKS_txt is the first entry`() {
        assumeTrue("bootstrap 资产不存在，跳过", asset != null)
        ZipInputStream(asset!!.inputStream().buffered()).use { zis ->
            val first = zis.nextEntry
            assertEquals("SYMLINKS.txt", first?.name)
        }
    }

    /**
     * 这是核心回归测试：用当前（修复后）的方式读 SYMLINKS.txt，
     * 必须还能继续读到后面的所有条目。
     */
    @Test
    fun `reading SYMLINKS_txt does not close the stream`() {
        assumeTrue("bootstrap 资产不存在，跳过", asset != null)

        var symlinkPairs = 0
        var entriesSeen = 0
        var sawBash = false

        ZipInputStream(asset!!.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entriesSeen++
                if (entry.name == "SYMLINKS.txt") {
                    val text = readEntryAsString(zis)
                    symlinkPairs = text.lineSequence().count { it.isNotBlank() }
                } else if (entry.name == "bin/bash") {
                    sawBash = true
                }
                zis.closeEntry()
                // 如果用 bufferedReader().useLines() 读上面那条，
                // 这里就会抛 IOException("Stream closed")
                entry = zis.nextEntry
            }
        }

        assertTrue("应该读到 SYMLINKS.txt 的内容", symlinkPairs > 1000)
        assertTrue("应该继续读完全部条目", entriesSeen > 4000)
        assertTrue("应该看到 bin/bash", sawBash)
    }

    /**
     * 反证：记录旧写法确实会失败。这样以后再有人改回去，测试会提醒他。
     */
    @Test
    fun `old bufferedReader approach is broken`() {
        assumeTrue("bootstrap 资产不存在，跳过", asset != null)

        var streamClosed = false
        try {
            ZipInputStream(asset!!.inputStream().buffered()).use { zis ->
                zis.nextEntry // SYMLINKS.txt
                zis.bufferedReader().useLines { it.count() } // 关掉了 zis
                zis.nextEntry?.name // 这里应该炸
            }
        } catch (t: Throwable) {
            streamClosed = t.message?.contains("Stream closed") == true ||
                t is java.io.IOException
        }
        assertTrue(
            "旧写法（bufferedReader().useLines）本应抛 Stream closed；" +
                "如果这里失败，说明 JDK 行为变了，注释需要更新",
            streamClosed,
        )
    }

    /** SYMLINKS.txt 的格式：`<linkTarget>←<pathInsidePrefix>`。 */
    @Test
    fun `symlink table parses and covers coreutils multicall`() {
        assumeTrue("bootstrap 资产不存在，跳过", asset != null)

        val pairs = HashMap<String, String>()
        ZipInputStream(asset!!.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "SYMLINKS.txt") {
                    readEntryAsString(zis).lineSequence().forEach { line ->
                        if (line.isBlank()) return@forEach
                        val parts = line.split('\u2190')
                        assertEquals("每行必须恰好一个分隔符: $line", 2, parts.size)
                        pairs[parts[1]] = parts[0]
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // 这些命令本身不是文件，全靠软链指向 bin/coreutils
        for (cmd in listOf("bin/mkdir", "bin/rm", "bin/cat", "bin/ls", "bin/ln")) {
            assertEquals("$cmd 应指向 coreutils", "coreutils", pairs[cmd])
        }
        assertEquals("bin/awk 应指向 gawk", "gawk", pairs["bin/awk"])
        assertEquals("bin/sh 应指向 dash", "dash", pairs["bin/sh"])
        assertTrue("软链条目数量不应太少（实际 ${pairs.size}）", pairs.size > 1000)
    }
}
