package com.miguanm7a.hsr.deploy

import android.content.Context
import com.miguanm7a.hsr.termux.TermuxPaths
import com.miguanm7a.hsr.termux.TermuxShell

/**
 * config.yaml 的「拉取 → 增量修改 → 推回」同步。
 *
 * 部署流程与设置页「生成脚本」共用这一段，避免两条路径行为不一致。
 *
 * ## 为什么是增量修改而不是整份生成
 *
 * March7thAssistant 会把整份合并配置 dump 回 `config.yaml`，其中包含状态字段：
 *
 * ```yaml
 * echo_of_war_timestamp: 1730000000
 * weekly_relic_cleanup_timestamp: 1730000001
 * daily_tasks: []
 * power_plan: [...]
 * ```
 *
 * 整份覆盖会把它们清零，程序就会以为「历战余响没打过」「每周遗器没清过」
 * 而重复执行。所以必须先拉出容器内的现有文件当基底。
 */
object ConfigSync {

    /**
     * 同步配置。
     *
     * @param bundled 内置镜像（项目在 `/m7a`）还是联网安装（项目在 `~/March7thAssistant`）
     * @return 共享配置文件的**容器可见**绝对路径（供容器内 `cp` 使用）
     */
    fun refresh(
        context: Context,
        settings: DeploySettings,
        bundled: Boolean,
        alias: String,
        onLog: (String) -> Unit,
        extraEnv: Map<String, String> = emptyMap(),
    ): String {
        val logs = SharedPaths.ensure(context)
        onLog("    共享目录：${logs.absolutePath}")
        val sharedFile = SharedPaths.configFile(context)
        val guestShared = TermuxPaths.guestPath(sharedFile)
        val appDir = if (bundled) DeployScripts.CONTAINER_APP_DIR else "~/March7thAssistant"

        // 1) 尽量拿容器里的现有文件当基底
        if (bundled) {
            TermuxShell.run(
                context,
                DeployScripts.pullContainerConfig(alias, appDir, guestShared),
                onLine = onLog,
                onErrorLine = onLog,
                extraEnv = extraEnv,
            )
        }

        val base = if (sharedFile.isFile && sharedFile.length() > 0) {
            sharedFile.readText(Charsets.UTF_8)
        } else {
            DeployScripts.configYamlTemplate()
        }

        // 2) 只改我们管理的键
        val patched = ConfigPatcher.upsertTopLevel(
            base,
            DeployScripts.configScalars(
                afterFinish = settings.afterFinish(),
                scheduledTime = settings.runDailyTime(),
                loopMode = settings.loopMode(),
                logLevel = settings.logLevel(),
                usePaidTime = settings.usePaidTime(),
                toggles = settings.allToggles(),
                debugPort = settings.monitorPort(),
            ),
        )
        sharedFile.writeText(patched, Charsets.UTF_8)
        onLog("    配置已更新（${patched.length} 字节，增量写入 ${TaskToggles.ALL.size} 个开关）")
        return guestShared
    }

    /**
     * 把共享目录里的配置推回容器（容器内执行 cp）。
     * @return 退出码
     */
    fun push(
        context: Context,
        alias: String,
        guestSharedConfig: String,
        bundled: Boolean,
        onLog: (String) -> Unit,
        extraEnv: Map<String, String> = emptyMap(),
    ): Int {
        val appDir = if (bundled) DeployScripts.CONTAINER_APP_DIR else "~/March7thAssistant"
        val inner = java.io.File(logsDirOf(context), "apply-config.sh")
        inner.writeText(
            """
            |#!/bin/bash
            |mkdir -p $appDir/logs $appDir/3rdparty/WebBrowser/UserProfile
            |cp -f "$guestSharedConfig" $appDir/config.yaml
            |echo "CONFIG_APPLIED -> $appDir/config.yaml"
            |
            """.trimMargin(),
            Charsets.UTF_8,
        )
        return TermuxShell.run(
            context,
            "exec proot-distro login $alias -- /bin/bash ${TermuxPaths.guestPath(inner)}",
            onLine = onLog,
            onErrorLine = onLog,
            extraEnv = extraEnv,
        )
    }

    private fun logsDirOf(context: Context) = SharedPaths.logsDir(context).also { it.mkdirs() }
}
