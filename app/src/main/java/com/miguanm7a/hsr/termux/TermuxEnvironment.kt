package com.miguanm7a.hsr.termux

import android.content.Context
import android.os.Build
import com.miguanm7a.hsr.BuildConfig
import java.io.File

/**
 * 组装启动 Termux 子进程所需的环境变量。
 *
 * 参照 Termux 官方 app 的 TermuxShellEnvironment。终端会话与一次性脚本执行
 * 共用这份定义，避免两条路径行为不一致。
 */
object TermuxEnvironment {

    /** termux-exec 是让 shebang（#!/usr/bin/env python3 之类）能在 Android 上工作的关键。 */
    private val LD_PRELOAD_CANDIDATES = listOf(
        "libtermux-exec_direct_ld-preload.so",
        "libtermux-exec-direct-ld-preload.so",
        "libtermux-exec-ld-preload.so",
    )

    fun build(context: Context): Map<String, String> {
        // 这些字符串会同时被「宿主侧的 Termux 命令」和「proot 容器内的命令」
        // 使用，所以必须用容器可见的 /data/data/... 形式：
        // Context.getFilesDir() 返回的 /data/user/0/... 在宿主上有效，但
        // proot-distro 只绑定 /data/data/<pkg>，容器里没有 /data/user/0。
        val prefixPath = TermuxPaths.guestPrefix(context)
        val homePath = TermuxPaths.guestHome(context)
        val libPath = "$prefixPath/lib"

        val env = LinkedHashMap<String, String>()

        env["HOME"] = homePath
        env["PREFIX"] = prefixPath
        env["TERMUX_PREFIX"] = prefixPath
        env["TERMUX_HOME"] = homePath
        // Termux 官方用的是 "TERMUX_<命名空间>__<键>" 双下划线形式，
        // 而 proot-distro 读的正是这两个：
        //   TERMUX_APP_PACKAGE = os.environ.get("TERMUX_APP__PACKAGE_NAME", "com.termux")
        //   TERMUX_HOME        = os.environ.get("TERMUX__HOME", f"/data/data/{TERMUX_APP_PACKAGE}/files/home")
        // 之前只设了单下划线的 TERMUX_HOME，proot-distro 走的是拼接兜底。
        // 显式设上，避免依赖"包名恰好能从 TERMUX_APP__PACKAGE_NAME 推出来"。
        env["TERMUX__PREFIX"] = prefixPath
        env["TERMUX__HOME"] = homePath
        env["TMPDIR"] = "$prefixPath/tmp"
        env["PATH"] = "$prefixPath/bin:$prefixPath/bin/applets:" +
            "/system/bin:/system/xbin:/vendor/bin"
        env["LD_LIBRARY_PATH"] = "$libPath:/system/lib64:/system/lib"
        env["SHELL"] = "$prefixPath/bin/bash"
        env["TERM"] = "xterm-256color"
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"

        // --- Android 系统路径：proot 需要它来解析 /system 下的动态链接器 ---
        env["ANDROID_ROOT"] = System.getenv("ANDROID_ROOT") ?: "/system"
        env["ANDROID_DATA"] = System.getenv("ANDROID_DATA") ?: "/data"
        env["ANDROID_ASSETS"] = "/system/app"
        env["ANDROID_STORAGE"] = "/storage"
        env["EXTERNAL_STORAGE"] = "/sdcard"
        env["DOWNLOAD_CACHE"] = "/data/cache"
        env["BOOTCLASSPATH"] = System.getenv("BOOTCLASSPATH") ?: ""

        // --- Termux app 身份，供 termux-am / termux-wake-lock 等工具使用 ---
        env["TERMUX_APP__PACKAGE_NAME"] = BuildConfig.APPLICATION_ID
        env["TERMUX_APP__PACKAGE_VARIANT"] = "apt-android-7"
        env["TERMUX_APP__PID"] = android.os.Process.myPid().toString()
        env["TERMUX_VERSION"] = TermuxPaths.TERMUX_VERSION
        env["TERMUX_MAIN_PACKAGE_FORMAT"] = "debian"
        env["TERMUX_IS_DEBUG_BUILD"] = if (BuildConfig.DEBUG) "1" else "0"

        // --- proot 相关 ---
        // 刻意不设置 PROOT_LOADER / PROOT_LOADER_32 / PROOT_TMP_DIR：
        // 官方 Termux 与 proot-distro 都不设它们，proot 会用它内置的 loader
        // 和与 $PREFIX 相关的默认临时目录。显式覆盖这些值属于「自作聪明」，
        // 一旦 loader 文件与 proot 内嵌版本不匹配就会导致 proot 直接启动失败，
        // 而 $PREFIX 已经是真实的包路径，默认值本来就能用。
        //
        // 如果设备上 proot 起不来（seccomp/ptrace 限制），由部署流程的
        // proot 冒烟测试探测并写入 PROOT_NO_SECCOMP=1 到
        // $HOME/m7a-shared/proot-env，后续容器操作会自动带上。

        val preload = LD_PRELOAD_CANDIDATES
            .map { File(libPath, it) }
            .firstOrNull { it.isFile }
        if (preload != null) {
            env["LD_PRELOAD"] = preload.absolutePath
        }

        // Android 8+ 的 linker namespace 需要显式放行私有目录，否则 dlopen 失败。
        env["LD_LIBRARY_PATH"] = env["LD_LIBRARY_PATH"] + ":" + libPath

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            env["TERMUX_APP__SDK_INT"] = Build.VERSION.SDK_INT.toString()
        }

        return env
    }

    /** 转成 ProcessBuilder / execve 需要的 "K=V" 数组形式。 */
    fun toArray(context: Context): Array<String> =
        build(context).map { (k, v) -> "$k=$v" }.toTypedArray()
}
