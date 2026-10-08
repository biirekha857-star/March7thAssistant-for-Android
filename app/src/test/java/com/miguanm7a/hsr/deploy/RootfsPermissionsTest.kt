package com.miguanm7a.hsr.deploy

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * 回归测试：内置容器镜像里的可执行位不能丢。
 *
 * 背景（真机踩过的坑）：
 * 镜像是在 Windows 上重新打包的。Windows 的 `os.stat()` 没有可执行位，
 * 而 `tarfile.add()` 会把 `st_mode` 原样写进归档 —— 于是所有 0755 静默变成
 * 0666。真机表现是：
 *     proot error: '/bin/bash' is not executable
 *     fatal error: see `proot --help`.
 * 看起来像内核限制 ptrace/seccomp，实际是权限位被抹掉了。
 *
 * 所以这里直接检查归档里关键文件是否带可执行位。
 * 资产不存在时跳过（它约 500MB，不进版本库）。
 */
class RootfsPermissionsTest {

    private val asset: File? = sequenceOf(
        File("src/main/assets/rootfs/m7a-rootfs.bin"),
        File("app/src/main/assets/rootfs/m7a-rootfs.bin"),
    ).firstOrNull { it.isFile }

    /** 这些必须可执行，否则容器根本起不来或跑不了 main.py。 */
    private val mustBeExecutable = listOf(
        "usr/bin/bash",
        "usr/bin/sh",
        "usr/bin/env",
        "usr/bin/ls",
        "usr/bin/chromium",
        "usr/bin/chromedriver",
        "usr/local/bin/python3.14",
    )

    @Test
    fun `critical binaries keep their execute bit`() {
        assumeTrue("内置容器镜像不存在，跳过", asset != null)

        val found = HashMap<String, Int>()
        val symlinks = HashMap<String, String>()

        GZIPInputStream(asset!!.inputStream().buffered(1 shl 20)).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var e = tar.nextEntry
                while (e != null) {
                    val name = e.name.removePrefix("./")
                    if (e.isSymbolicLink) {
                        symlinks[name] = e.linkName ?: ""
                    } else if (!e.isDirectory) {
                        found[name] = e.mode and 0xFFF
                    }
                    e = tar.nextEntry
                }
            }
        }

        assertTrue("应该读到大量条目（实际 ${found.size}）", found.size > 10_000)

        for (path in mustBeExecutable) {
            val mode = found[path]
            if (mode == null) {
                // 可能是符号链接（例如 usr/bin/sh -> dash）
                if (symlinks.containsKey(path)) continue
                throw AssertionError("$path 在镜像里不存在")
            }
            assertTrue(
                "$path 缺少可执行位：mode=${Integer.toOctalString(mode)}。" +
                    "打包时用了 Windows 的 os.stat()，必须从原始 layer 元数据恢复权限。",
                (mode and 0b001_001_001) != 0,
            )
        }
    }

    /** /usr/bin 下不应该有任何「非软链但不可执行」的条目。 */
    @Test
    fun `everything in usr-bin is runnable`() {
        assumeTrue("内置容器镜像不存在，跳过", asset != null)

        var total = 0
        val broken = ArrayList<String>()

        GZIPInputStream(asset!!.inputStream().buffered(1 shl 20)).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var e = tar.nextEntry
                while (e != null) {
                    val name = e.name.removePrefix("./")
                    if (name.startsWith("usr/bin/") && !e.isDirectory &&
                        !e.isSymbolicLink
                    ) {
                        total++
                        if ((e.mode and 0b001_001_001) == 0) {
                            broken += "$name(${Integer.toOctalString(e.mode)})"
                        }
                    }
                    e = tar.nextEntry
                }
            }
        }

        assertTrue("usr/bin 下应该有可执行文件（实际 $total）", total > 100)
        assertTrue("usr/bin 下存在不可执行的普通文件：$broken", broken.isEmpty())
    }

    /** 内置镜像的布局必须是 /m7a + /opt/venv（不是 ~/March7thAssistant）。 */
    @Test
    fun `image layout matches what the scripts assume`() {
        assumeTrue("内置容器镜像不存在，跳过", asset != null)

        val wanted = setOf(
            "m7a/main.py",
            "m7a/pyproject.toml",
            "opt/venv/bin/python",
            "opt/venv/lib/python3.14/site-packages",
            "usr/bin/chromium",
            "usr/bin/chromedriver",
        )
        val seen = HashSet<String>()

        GZIPInputStream(asset!!.inputStream().buffered(1 shl 20)).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var e = tar.nextEntry
                while (e != null) {
                    val name = e.name.removePrefix("./").removeSuffix("/")
                    if (name in wanted) seen += name
                    e = tar.nextEntry
                }
            }
        }

        assertTrue("镜像缺少关键路径：${wanted - seen}", seen.containsAll(wanted))
    }
}
