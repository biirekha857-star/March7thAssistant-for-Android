package com.miguanm7a.hsr.deploy

import android.content.Context
import com.miguanm7a.hsr.termux.TermuxPaths
import java.io.File

/**
 * 宿主与 proot 容器之间共享的目录。
 *
 * proot-distro 默认模式下 `$HOME` 会以**同一路径**绑定进容器，所以放在这里的
 * 文件容器内可以直接按绝对路径读到；反过来，把容器里的目录 `--bind` 到这里，
 * 宿主也就能读到容器写出的文件（二维码登录就是靠这个）。
 *
 * 统一在这里拼路径，避免各处手写字符串导致宿主/容器形式不一致
 * （`/data/user/0` vs `/data/data`，曾经真机上踩过）。
 */
object SharedPaths {

    /** 共享根目录（宿主 File）。 */
    fun dir(context: Context): File =
        File(TermuxPaths.homeDir(context), DeployScripts.SHARED_DIR_NAME)

    /** 容器内可见的共享根目录字符串。 */
    fun guestDir(context: Context): String = TermuxPaths.guestPath(dir(context))

    /** 共享的 config.yaml（宿主 File）。 */
    fun configFile(context: Context): File =
        File(dir(context), DeployScripts.SHARED_CONFIG_NAME)

    /**
     * 共享的 logs 目录（宿主 File）。
     *
     * March7thAssistant 把登录二维码写到项目目录下的 `logs/qrcode_login.png`，
     * 我们把容器内的 `logs` 绑定到这里，二维码就能被 App 读出来显示。
     */
    fun logsDir(context: Context): File = File(dir(context), "logs")

    /** 容器内可见的 logs 目录字符串（作为 --bind 的目标源）。 */
    fun guestLogsDir(context: Context): String = TermuxPaths.guestPath(logsDir(context))

    /** 登录二维码文件（宿主 File）。 */
    fun qrFile(context: Context): File = File(logsDir(context), QR_FILENAME)

    const val QR_FILENAME = "qrcode_login.png"

    /** 确保共享目录与 logs 目录存在（--bind 的源必须已存在）。 */
    fun ensure(context: Context): File {
        val logs = logsDir(context)
        logs.mkdirs()
        return logs
    }
}
