package com.miguanm7a.hsr.termux

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 在内置 Termux 运行时里执行命令。
 *
 * 这是「一键部署 / 一键运行任务」的执行入口：不走 adb、不走外部 Termux、
 * 不发 Intent，直接 fork 出 $PREFIX/bin/bash 子进程。
 */
object TermuxShell {

    private const val TAG = "TermuxShell"

    /** 终端里跑初始化脚本用的 shell 调用参数。 */
    fun shellCommandArgs(extraArgs: List<String> = emptyList()): List<String> {
        val args = mutableListOf("bash", "--login")
        args += extraArgs
        return args
    }

    /**
     * 同步执行一段脚本，逐行回调输出。
     * @return 退出码，-1 表示启动失败。
     */
    fun run(
        context: Context,
        script: String,
        onLine: (String) -> Unit = {},
        onErrorLine: (String) -> Unit = {},
        workingDir: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
    ): Int {
        val bash = TermuxPaths.bash(context)
        if (!bash.isFile) {
            onErrorLine("Termux 运行时未安装：${bash.absolutePath} 不存在")
            return -1
        }
        val pb = ProcessBuilder(bash.absolutePath, "-c", script)
        pb.environment().clear()
        pb.environment().putAll(TermuxEnvironment.build(context))
        pb.environment().putAll(extraEnv)
        pb.directory(workingDir ?: TermuxPaths.homeDir(context).also { it.mkdirs() })
        pb.redirectErrorStream(false)

        val process = try {
            pb.start()
        } catch (t: Throwable) {
            Log.e(TAG, "启动 bash 失败", t)
            onErrorLine("启动 bash 失败：${t.message}")
            return -1
        }

        val outThread = Thread {
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach(onLine)
                }
            }
        }
        val errThread = Thread {
            runCatching {
                process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach(onErrorLine)
                }
            }
        }
        outThread.start()
        errThread.start()
        val code = process.waitFor()
        outThread.join(2_000)
        errThread.join(2_000)
        return code
    }

    /**
     * 异步启动一段长跑脚本（部署、任务），返回 [Process] 供停止。
     */
    fun startAsync(
        context: Context,
        script: String,
        onLine: (String) -> Unit = {},
        onExit: (Int) -> Unit = {},
        workingDir: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
    ): Process? {
        val bash = TermuxPaths.bash(context)
        if (!bash.isFile) {
            onLine("Termux 运行时未安装：${bash.absolutePath} 不存在")
            onExit(-1)
            return null
        }
        val pb = ProcessBuilder(bash.absolutePath, "-c", script)
        pb.environment().clear()
        pb.environment().putAll(TermuxEnvironment.build(context))
        pb.environment().putAll(extraEnv)
        pb.directory(workingDir ?: TermuxPaths.homeDir(context).also { it.mkdirs() })
        pb.redirectErrorStream(true)

        val process = try {
            pb.start()
        } catch (t: Throwable) {
            Log.e(TAG, "启动 bash 失败", t)
            onLine("启动 bash 失败：${t.message}")
            onExit(-1)
            return null
        }

        Thread {
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach(onLine)
                }
            }
            val code = try {
                process.waitFor()
            } catch (t: Throwable) {
                -1
            }
            onExit(code)
        }.start()

        return process
    }

    /** 只取输出，不流式回调（用于探测命令，如 `proot-distro --help`）。 */
    fun capture(context: Context, script: String, timeoutMs: Long = 15_000): String {
        val sb = StringBuilder()
        val t = Thread {
            run(context, script, onLine = { sb.appendLine(it) }, onErrorLine = { sb.appendLine(it) })
        }
        t.start()
        t.join(timeoutMs)
        return sb.toString()
    }
}
