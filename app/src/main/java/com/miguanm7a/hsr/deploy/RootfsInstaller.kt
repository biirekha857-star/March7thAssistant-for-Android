package com.miguanm7a.hsr.deploy

import android.content.Context
import com.miguanm7a.hsr.termux.TermuxPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 把 APK 内置的离线容器镜像（tar.gz）释放到 Termux 侧，供 proot-distro 安装。
 *
 * 之所以要在 Termux 侧先落一份真实文件，而不是让 proot-distro 直接读 APK 内的
 * 资产：proot-distro 需要 seek/解压，且我们要用哈希标记做幂等，避免每次部署
 * 都重复复制 500MB。
 */
object RootfsInstaller {

    private const val TAG = "RootfsInstaller"

    data class Progress(val percent: Int, val copiedMb: Long, val totalMb: Long, val message: String)

    fun targetFile(context: Context): File =
        File(File(TermuxPaths.homeDir(context), "m7a-shared"), "m7a-rootfs.tar.gz")

    /** 资产是否已经就位（大小一致即认为完整）。 */
    fun isReady(context: Context): Boolean {
        val f = targetFile(context)
        if (!f.isFile) return false
        val expected = assetSize(context) ?: return f.length() > 0
        return f.length() == expected
    }

    private fun assetSize(context: Context): Long? = try {
        context.assets.openFd(DeployScripts.ROOTFS_ASSET).use { it.length }
    } catch (t: Throwable) {
        null
    }

    /**
     * 把内置镜像复制到 $HOME/m7a-shared/m7a-rootfs.tar.gz。
     * 幂等：已存在且大小一致则跳过。
     */
    suspend fun install(
        context: Context,
        onProgress: (Progress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val target = targetFile(context)
            target.parentFile?.mkdirs()

            val total = assetSize(context)
            if (total != null && target.isFile && target.length() == total) {
                onProgress(Progress(100, total / 1_048_576, total / 1_048_576, "内置镜像已就位"))
                return@runCatching
            }

            val tmp = File(target.parentFile, "m7a-rootfs.tar.gz.part")
            tmp.delete()

            onProgress(Progress(0, 0, (total ?: 0) / 1_048_576, "正在释放内置容器镜像…"))

            var copied = 0L
            val buffer = ByteArray(1 shl 20) // 1MB
            context.assets.open(DeployScripts.ROOTFS_ASSET).use { input ->
                FileOutputStream(tmp).use { out ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        copied += n
                        if (total != null && total > 0) {
                            val pct = (copied * 100 / total).toInt()
                            if (pct % 5 == 0) {
                                onProgress(
                                    Progress(pct, copied / 1_048_576, total / 1_048_576, "正在释放内置容器镜像…")
                                )
                            }
                        }
                    }
                    out.flush()
                    out.fd.sync()
                }
            }

            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }

            val finalSize = target.length()
            if (total != null && finalSize != total) {
                error("镜像复制不完整：$finalSize / $total 字节")
            }

            onProgress(
                Progress(100, finalSize / 1_048_576, (total ?: finalSize) / 1_048_576, "内置镜像已就绪")
            )
        }
    }

    /** 释放占用的空间（重置时调用）。 */
    fun delete(context: Context) {
        val f = targetFile(context)
        f.delete()
        File(f.parentFile, "m7a-rootfs.tar.gz.part").delete()
    }
}
