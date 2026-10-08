package com.miguanm7a.hsr.termux

import android.content.Context
import java.io.File

/**
 * Termux 运行时路径与环境定义。
 *
 * ## 关键约束一：$PREFIX 必须与二进制里的硬编码一致
 *
 * 内嵌的 Termux bootstrap 里的二进制把 `/data/data/<包名>/files/usr`
 * 作为 $PREFIX 硬编码进去了（位于 ELF 的 `.dynstr`，`DT_RUNPATH` 指向它）。
 *
 * 本项目用的包名是 **`com.m7ahsr`**（10 字符）。之所以是 10 字符，是因为：
 *
 * ```
 * len("/data/data/") + len(包名) + len("/files/usr") = 21 + len(包名)
 * ```
 *
 * 原前缀（`com.termux`）是 31 字节。**字符串只能改短、不能改长**：
 * `.dynstr` 是连续的字符串表，所有动态符号按偏移量引用，改长会导致
 * 后面全部错位、整张符号表作废。所以 10 字符是硬上限。
 *
 * bootstrap 资产已由 `scripts/reprefix_bootstrap.py` 重打前缀，
 * 该脚本要求新旧包名等长，因此是纯字节替换，不做任何重定位。
 *
 * **若改了 [BuildConfig.APPLICATION_ID]，必须同步重跑那个脚本并更新此处**，
 * 否则 bash/proot 找不到自己的库，表现为「点了没反应」。
 *
 * ## 关键约束二：交给容器的路径必须是 /data/data 形式
 *
 * Android 上 `Context.getFilesDir()` 返回的是 `/data/user/0/<pkg>/files`，
 * 而 `/data/data` 只是指向 `/data/user/0` 的符号链接。二者在**宿主**上等价，
 * 但 proot-distro 只把 `/data/data/<pkg>` 这一个路径绑定进容器——
 * 容器里**不存在** `/data/user/0/...`。
 *
 * 所以凡是「宿主写文件、容器读文件」的场景（例如把部署脚本写给容器执行），
 * 必须把路径规范化成 `/data/data/...`，否则容器里的 bash 会报
 * `No such file or directory`（退出码 127）。
 * 官方 Termux 也是这么做的：`replaceAll("^/data/user/0/", "/data/data/")`。
 */
object TermuxPaths {

    const val TERMUX_VERSION = "0.119.0"

    /** App 的 applicationId，必须与 [OFFICIAL_PREFIX] 中的包名一致。 */
    const val APP_PACKAGE = "com.m7ahsr"

    /** 内嵌 bootstrap 里硬编码的 $PREFIX 绝对路径。 */
    const val OFFICIAL_PREFIX = "/data/data/$APP_PACKAGE/files/usr"

    /** 内嵌 bootstrap 里硬编码的 $HOME 绝对路径。 */
    const val OFFICIAL_HOME = "/data/data/$APP_PACKAGE/files/home"

    /** 生成脚本用的 shebang。跟着 [OFFICIAL_PREFIX] 走，避免各处写死字符串。 */
    const val OFFICIAL_BASH = "$OFFICIAL_PREFIX/bin/bash"

    /**
     * 数据根目录，等价于 TermuxConstants.TERMUX_FILES_DIR_PATH。
     *
     * 注意：返回的是**真实**路径（用于 File I/O），在多数设备上是
     * `/data/user/0/<pkg>/files`。需要交给容器时请用 [guestPath]。
     */
    fun filesDir(context: Context): File = context.filesDir

    /** $PREFIX = $FILES/usr（真实路径，用于 I/O） */
    fun prefixDir(context: Context): File = File(filesDir(context), "usr")

    /** $HOME = $FILES/home（真实路径，用于 I/O） */
    fun homeDir(context: Context): File = File(filesDir(context), "home")

    /** $PREFIX/tmp */
    fun tmpDir(context: Context): File = File(prefixDir(context), "tmp")

    /** $PREFIX/bin */
    fun binDir(context: Context): File = File(prefixDir(context), "bin")

    fun bash(context: Context): File = File(binDir(context), "bash")

    /**
     * 把宿主真实路径规范化成容器内可见的路径。
     *
     * `/data/user/0/com.m7ahsr/files/home/x` -> `/data/data/com.m7ahsr/files/home/x`
     *
     * 只替换 `/data/user/<数字>/` 这一段，其余原样保留。
     */
    fun guestPath(path: String): String {
        if (path.startsWith("/data/data/")) return path
        val m = USER_DIR_RE.find(path) ?: return path
        return "/data/data/" + path.substring(m.value.length)
    }

    fun guestPath(file: File): String = guestPath(file.absolutePath)

    private val USER_DIR_RE = Regex("^/data/user/\\d+/")

    /** 容器内可见的 $PREFIX 字符串。 */
    fun guestPrefix(context: Context): String = guestPath(prefixDir(context))

    /** 容器内可见的 $HOME 字符串。 */
    fun guestHome(context: Context): String = guestPath(homeDir(context))

    /** 判断 bootstrap 是否已经落地（bash 存在即认为已安装）。 */
    fun isInstalled(context: Context): Boolean = bash(context).isFile

    /**
     * bootstrap 里的二进制是否与当前 applicationId 匹配。
     *
     * 当 applicationId 与 [APP_PACKAGE] 不一致时返回 false，调用方应给出明确提示，
     * 而不是让用户面对一个「点了没反应」的终端。
     */
    fun prefixMatchesBinaries(context: Context): Boolean =
        guestPrefix(context) == OFFICIAL_PREFIX
}
