package com.miguanm7a.hsr.termux

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * 内置 Termux bootstrap（根文件系统）安装器。
 *
 * 流程与 Termux 官方 TermuxInstaller 保持一致，但根文件系统直接从 APK 的
 * assets 读取，因此首次运行完全离线、不需要下载、不需要外部 Termux：
 *
 *  1. 把 assets/bootstrap/termux-bootstrap.zip 解压到 $FILES/usr-staging
 *  2. 按 SYMLINKS.txt 重建符号链接（非 root 环境无法在 zip 里保留软链）
 *  3. 按 zip 里的 unix mode 恢复可执行权限
 *  4. 原子重命名 usr-staging -> usr
 *
 * 第 2、3 步是整个方案的关键：漏掉任何一步，bash 与 apt 都会静默失败，
 * 外部表现就是「点了没反应」。
 */
object BootstrapInstaller {

    private const val TAG = "BootstrapInstaller"

    const val BOOTSTRAP_ASSET = "bootstrap/termux-bootstrap.zip"

    /** SYMLINKS.txt 使用的分隔符（Termux 上游约定，非普通字符）。 */
    private const val SYMLINK_SEPARATOR = "\u2190"

    data class Progress(
        val percent: Int,
        val message: String,
    )

    /**
     * 安装 bootstrap。幂等：$PREFIX/bin/bash 已存在时直接返回。
     */
    suspend fun install(
        context: Context,
        onProgress: (Progress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (TermuxPaths.isInstalled(context)) {
                onProgress(Progress(100, "Termux 运行时已就绪"))
                ensurePrefixPermissions(context)
                return@runCatching
            }

            val filesDir = TermuxPaths.filesDir(context)
            val prefix = TermuxPaths.prefixDir(context)
            val staging = File(filesDir, "usr-staging")
            val home = TermuxPaths.homeDir(context)

            filesDir.mkdirs()
            home.mkdirs()
            TermuxPaths.tmpDir(context).mkdirs()

            // 清掉上次可能失败留下的残骸
            staging.deleteRecursively()
            if (!staging.mkdirs()) error("无法创建临时目录：${staging.absolutePath}")

            onProgress(Progress(0, "正在解压内置 Termux 运行时…"))

            val symlinks = ArrayList<Pair<String, String>>(1280)
            var entries = 0
            // 进度分母：bootstrap 是固定资产（~4900 条），用条目数比用字节数稳，
            // 因为解压后的大小和资产压缩后大小不是一个量级。
            val totalEntries = 5200

            context.assets.open(BOOTSTRAP_ASSET).use { input ->
                ZipInputStream(input.buffered(64 * 1024)).use { zis ->
                    val buffer = ByteArray(128 * 1024)
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name

                        if (name == "SYMLINKS.txt") {
                            // 注意：这里绝对不能包 BufferedReader 再 useLines——
                            // use 会把 BufferedReader 关掉，而它关闭时会连带关掉
                            // 底层的 ZipInputStream，于是下一个 nextEntry 直接抛
                            // "Stream closed"。SYMLINKS.txt 又是 zip 里的第一条，
                            // 结果就是安装第一步立刻失败。
                            readEntryAsString(zis).lineSequence().forEach { line ->
                                if (line.isBlank()) return@forEach
                                val parts = line.split(SYMLINK_SEPARATOR)
                                if (parts.size != 2) {
                                    throw IllegalStateException("SYMLINKS.txt 格式异常：$line")
                                }
                                symlinks += parts[0] to parts[1]
                            }
                        } else if (name != "/" && !name.endsWith("/")) {
                            val target = File(staging, name)
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                while (true) {
                                    val read = zis.read(buffer)
                                    if (read <= 0) break
                                    out.write(buffer, 0, read)
                                }
                            }
                            // Android 的 ZipEntry 不暴露 unix mode（没有
                            // getExternalAttributes），所以按目录约定恢复权限：
                            // 可执行程序在 bin/ 与 libexec/，其余为普通数据文件。
                            if (isExecutableEntry(name)) {
                                target.setExecutable(true, false)
                            }
                        }

                        entries++
                        if (entries % 400 == 0) {
                            val pct = (entries * 88 / totalEntries).coerceIn(0, 88)
                            onProgress(Progress(pct, "已解压 $entries / ~$totalEntries 个文件…"))
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            if (symlinks.isEmpty()) error("bootstrap 缺少 SYMLINKS.txt，压缩包已损坏")

            onProgress(Progress(90, "正在重建 ${symlinks.size} 个符号链接…"))
            var linkFailures = 0
            var firstLinkError: String? = null
            symlinks.forEach { (linkPath, targetPath) ->
                val linkFile = File(staging, targetPath)
                linkFile.parentFile?.mkdirs()
                try {
                    if (linkFile.exists() || linkFile.readSymlinkOrNull() != null) {
                        linkFile.delete()
                    }
                    Os.symlink(linkPath, linkFile.absolutePath)
                } catch (t: Throwable) {
                    linkFailures++
                    if (firstLinkError == null) {
                        firstLinkError = "$targetPath -> $linkPath: ${t.message}"
                    }
                }
            }
            // 软链是 bootstrap 的命脉：bin/coreutils 一个 multicall 二进制撑起
            // mkdir/rm/cat/ls 等上百个命令，软链全失败的话后面只会报
            // "command not found"，非常难查。所以这里要显式失败。
            if (linkFailures > 0) {
                Log.w(TAG, "符号链接失败 $linkFailures / ${symlinks.size}，首个：$firstLinkError")
            }
            if (linkFailures > symlinks.size / 10) {
                error(
                    "符号链接创建大量失败（$linkFailures / ${symlinks.size}）：$firstLinkError\n" +
                        "这通常意味着设备不允许在应用私有目录创建软链接。"
                )
            }

            onProgress(Progress(94, "正在完成安装…"))

            // 原子落地：先删旧的 usr（若为空目录），再重命名
            prefix.deleteRecursively()
            if (!staging.renameTo(prefix)) {
                // 某些文件系统 rename 跨目录失败时退化为复制
                staging.copyRecursively(prefix, overwrite = true)
                staging.deleteRecursively()
            }

            ensureHomeFiles(context)
            ensurePrefixPermissions(context)

            check(TermuxPaths.isInstalled(context)) {
                "bootstrap 安装失败：${TermuxPaths.bash(context).absolutePath} 不存在"
            }

            onProgress(Progress(100, "Termux 运行时安装完成"))
            Log.i(TAG, "bootstrap 安装完成，$entries 个条目")
        }
    }

    /**
     * 读取当前 zip 条目的全部内容为字符串。
     *
     * 不能用 `bufferedReader().readText()/useLines()`：那些 API 关闭 reader 时
     * 会连带关闭底层流，导致后续 `nextEntry()` 抛 "Stream closed"。
     * `ZipInputStream.read()` 在当前条目结束时返回 -1，所以这个循环正好读完一条。
     */
    private fun readEntryAsString(zis: ZipInputStream): String {
        val out = java.io.ByteArrayOutputStream(32 * 1024)
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = zis.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    /**
     * 判断 bootstrap 里某个条目是否应当带可执行位。
     *
     * Termux 的 rootfs 布局约定：可执行文件位于 bin/、libexec/，以及 apt 的
     * helper/method 目录；share/、etc/、var/ 都是普通数据文件。
     * 另外 libtermux-exec 等 .so 需要可读但不需要可执行。
     */
    private fun isExecutableEntry(name: String): Boolean = when {
        name.startsWith("bin/") -> true
        name.startsWith("libexec/") -> true
        name.startsWith("lib/apt/apt-helper") -> true
        name.startsWith("lib/apt/methods") -> true
        else -> false
    }

    private fun File.readSymlinkOrNull(): String? = try {
        Os.readlink(absolutePath)
    } catch (t: Throwable) {
        null
    }

    /** 保证 $PREFIX 及其 bin 可执行（部分设备解压后会丢权限）。 */
    private fun ensurePrefixPermissions(context: Context) {
        val prefix = TermuxPaths.prefixDir(context)
        try {
            Os.chmod(prefix.absolutePath, 0x1ED) // 0755
        } catch (t: Throwable) {
            Log.w(TAG, "chmod prefix 失败", t)
        }
        TermuxPaths.binDir(context).listFiles()?.forEach { f ->
            if (f.isFile) f.setExecutable(true, false)
        }
    }

    /** 写入 $HOME 的基础配置文件（Termux 标准模板的精简版）。 */
    private fun ensureHomeFiles(context: Context) {
        val home = TermuxPaths.homeDir(context)
        home.mkdirs()
        File(home, ".termux").mkdirs()
        File(home, "storage").mkdirs()

        // ~/.termux/termux.properties —— 允许外部应用调用（我们自己的 App 要用）
        val props = File(File(home, ".termux"), "termux.properties")
        if (!props.exists()) {
            props.writeText(
                """
                # 由 MaaTermux 生成
                allow-external-apps=true
                use-black-ui=true
                """.trimIndent() + "\n",
                Charsets.UTF_8
            )
        }

        val prefix = TermuxPaths.prefixDir(context).absolutePath

        File(home, ".bashrc").let { f ->
            if (!f.exists()) f.writeText(
                """
                # 由 MaaTermux 生成
                [ -f "${'$'}PREFIX/etc/profile" ] && . "${'$'}PREFIX/etc/profile"
                export PATH="${'$'}PREFIX/bin:${'$'}PREFIX/bin/applets:${'$'}PATH"
                """.trimIndent() + "\n",
                Charsets.UTF_8
            )
        }
        File(home, ".profile").let { f ->
            if (!f.exists()) f.writeText(
                "[ -f \"${'$'}HOME/.bashrc\" ] && . \"${'$'}HOME/.bashrc\"\n",
                Charsets.UTF_8
            )
        }
        File(home, ".bash_profile").let { f ->
            if (!f.exists()) f.writeText(
                "[ -f \"${'$'}HOME/.bashrc\" ] && . \"${'$'}HOME/.bashrc\"\n",
                Charsets.UTF_8
            )
        }

        // 保证 $PREFIX/tmp 存在且可写
        File(prefix, "tmp").mkdirs()
    }

    /** 彻底卸载运行时（用于「重置」）。 */
    suspend fun uninstall(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            TermuxPaths.filesDir(context).deleteRecursively()
            Unit
        }
    }
}
