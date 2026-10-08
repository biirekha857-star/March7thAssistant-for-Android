package com.miguanm7a.hsr.deploy

import android.content.Context
import com.miguanm7a.hsr.termux.TermuxPaths
import com.miguanm7a.hsr.termux.TermuxShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/** 部署步骤状态。 */
enum class StepState { PENDING, RUNNING, DONE, FAILED, SKIPPED }

data class DeployStep(
    val id: String,
    val title: String,
    val detail: String,
    val state: StepState = StepState.PENDING,
    val progress: Int = 0,
    val message: String = "",
)

data class DeployState(
    val running: Boolean = false,
    val steps: List<DeployStep> = emptyList(),
    val deployed: Boolean = false,
    val error: String? = null,
) {
    val overallProgress: Int
        get() = if (steps.isEmpty()) 0 else steps.sumOf { it.progress } / steps.size

    val currentStep: DeployStep?
        get() = steps.firstOrNull { it.state == StepState.RUNNING }
}

/**
 * 一键部署状态机。
 *
 * 链路：内置运行时 → 内置镜像释放 → Termux 准备 → 容器安装 → 容器自检/依赖。
 * 每一步的 shell 输出通过 `@@STEP:` 协议解析回进度看板。
 */
class DeployEngine(
    private val context: Context,
    private val settings: DeploySettings,
    private val onLog: (String) -> Unit,
) {

    private val _state = MutableStateFlow(DeployState())
    val state: StateFlow<DeployState> = _state.asStateFlow()

    /**
     * 最近若干行输出。失败时把末尾几行塞进步骤说明里，
     * 这样「退出码 1」这种没信息量的报错也能直接在界面上看到原因。
     */
    private val recentLines = ArrayDeque<String>(64)

    private fun remember(line: String) {
        val t = line.trim()
        if (t.isEmpty()) return
        if (recentLines.size >= 60) recentLines.removeFirst()
        recentLines.addLast(t)
    }

    private fun tailSummary(max: Int = 3): String =
        recentLines.takeLast(max).joinToString(" ／ ").take(400)

    fun refresh() {
        _state.update { it.copy(deployed = settings.isDeployed()) }
    }

    private fun updateStep(id: String, state: StepState, progress: Int, message: String = "") {
        _state.update { s ->
            s.copy(steps = s.steps.map {
                if (it.id == id) it.copy(state = state, progress = progress, message = message) else it
            })
        }
    }

    /** 执行完整的一键部署。 */
    suspend fun runFull(): Result<Unit> = withContext(Dispatchers.IO) {
        val bundled = settings.rootfsSource() == RootfsSource.BUNDLED
        _state.value = DeployState(running = true, steps = buildStepList(bundled), deployed = false)
        val log = onLog

        fun handleLine(line: String) {
            remember(line)
            when {
                line.startsWith(DeployScripts.MARK) -> {
                    val rest = line.removePrefix(DeployScripts.MARK)
                    val id = rest.substringBefore(':')
                    val msg = rest.substringAfter(':', "")
                    mapStepId(id)?.let { updateStep(it, StepState.RUNNING, 50, msg) }
                    if (msg.isNotBlank()) log(msg)
                }

                line.startsWith(DeployScripts.MARK_DONE) -> {
                    mapStepId(line.removePrefix(DeployScripts.MARK_DONE))
                        ?.let { updateStep(it, StepState.DONE, 100) }
                }

                line.startsWith(DeployScripts.MARK_FAIL) -> {
                    val rest = line.removePrefix(DeployScripts.MARK_FAIL)
                    val id = rest.substringBefore(':')
                    val msg = rest.substringAfter(':', "")
                    mapStepId(id)?.let { updateStep(it, StepState.FAILED, 100, msg) }
                    log("✘ ${msg.ifBlank { id }}")
                }

                else -> log(line)
            }
        }

        try {
            // ---------- 1. 内置 Termux 运行时 ----------
            if (!TermuxPaths.isInstalled(context)) {
                updateStep("runtime", StepState.RUNNING, 0, "解压内置 Termux 运行时")
                log("==> 安装内置 Termux 运行时（离线）")
                val r = com.miguanm7a.hsr.termux.BootstrapInstaller.install(context) { p ->
                    updateStep("runtime", StepState.RUNNING, p.percent, p.message)
                    log(p.message)
                }
                r.getOrElse {
                    updateStep("runtime", StepState.FAILED, 100, it.message ?: "安装失败")
                    _state.update { s -> s.copy(running = false, error = it.message) }
                    return@withContext Result.failure(it)
                }
                updateStep("runtime", StepState.DONE, 100)
            } else {
                updateStep("runtime", StepState.SKIPPED, 100, "运行时已就绪")
                log("==> Termux 运行时已就绪，跳过")
            }

            // ---------- 2. 释放内置离线镜像（仅内置源） ----------
            if (bundled) {
                updateStep("rootfsCopy", StepState.RUNNING, 0, "释放内置容器镜像")
                log("==> 释放内置离线镜像（约 500MB，首次需要一点时间）")
                val r = RootfsInstaller.install(context) { p ->
                    updateStep("rootfsCopy", StepState.RUNNING, p.percent, p.message)
                }
                r.getOrElse {
                    updateStep("rootfsCopy", StepState.FAILED, 100, it.message ?: "释放失败")
                    _state.update { s -> s.copy(running = false, error = it.message) }
                    return@withContext Result.failure(it)
                }
                updateStep("rootfsCopy", StepState.DONE, 100)
            } else {
                updateStep("rootfsCopy", StepState.SKIPPED, 100, "使用联网镜像")
            }

            // 写入启动脚本
            writeStartScript(bundled)

            // ---------- 3. Termux 侧准备 ----------
            updateStep("termux", StepState.RUNNING, 0)
            log("==> Termux 侧准备")
            var code = TermuxShell.run(
                context,
                DeployScripts.setupTermux(settings.termuxMirror()),
                onLine = ::handleLine,
                onErrorLine = ::handleLine,
            )
            if (code != 0) return@withContext failOn("termux", "Termux 侧准备失败（退出码 $code）")
            updateStep("termux", StepState.DONE, 100)

            // ---------- 4. 安装容器 ----------
            updateStep("rootfs", StepState.RUNNING, 0)
            log("==> 安装容器（来源：${settings.rootfsSource().label}）")
            val alias = settings.containerAlias()
            val installScript =
                if (bundled) DeployScripts.installBundledRootfs(alias)
                else DeployScripts.installRemoteRootfs(settings.rootfsSource(), alias)
            code = TermuxShell.run(context, installScript, onLine = ::handleLine, onErrorLine = ::handleLine)
            if (code != 0) return@withContext failOn("rootfs", "容器安装失败（退出码 $code）")
            updateStep("rootfs", StepState.DONE, 100)

            // ---------- 5. proot 冒烟测试（首次真正启动 proot） ----------
            updateStep("prootrun", StepState.RUNNING, 0)
            log("==> proot 冒烟测试")
            code = TermuxShell.run(
                context,
                DeployScripts.prootSmokeTest(alias),
                onLine = ::handleLine,
                onErrorLine = ::handleLine,
            )
            if (code != 0) {
                return@withContext failOn(
                    "prootrun",
                    "proot 无法启动。常见原因：设备内核限制 ptrace/seccomp，" +
                        "或厂商 ROM 阻止了 proot 的运行。"
                )
            }
            updateStep("prootrun", StepState.DONE, 100)

            // ---------- 6. 容器内自检 / 依赖 ----------
            // 先把容器里已有的 config.yaml 拉出来当基底（保留状态字段），
            // 再增量写入我们在设置页管理的键，最后推回容器。
            updateStep("deps", StepState.RUNNING, 0)
            val guestConfig = refreshSharedConfig(bundled, alias)
            log("==> 容器内自检")
            code = execLogin(alias, DeployScripts.setupInsideContainer(bundled, guestConfig), ::handleLine)
            if (code != 0) return@withContext failOn("deps", "容器内准备失败（退出码 $code）")
            updateStep("deps", StepState.DONE, 100)

            // ---------- 7. 联网镜像才需要同步 Python 依赖 ----------
            if (bundled) {
                updateStep("pydeps", StepState.SKIPPED, 100, "内置镜像已含依赖")
            } else {
                updateStep("pydeps", StepState.RUNNING, 0)
                log("==> 同步 Python 依赖")
                code = execLogin(alias, DeployScripts.syncProjectDeps(false), ::handleLine)
                if (code != 0) return@withContext failOn("pydeps", "Python 依赖同步失败（退出码 $code）")
                updateStep("pydeps", StepState.DONE, 100)
            }

            settings.setDeployed(true)
            _state.update { it.copy(running = false, deployed = true) }
            log("==> 部署完成 ✔")
            Result.success(Unit)
        } catch (t: Throwable) {
            log("部署异常：${t.message}")
            _state.update { it.copy(running = false, error = t.message) }
            Result.failure(t)
        }
    }

    private fun failOn(stepId: String, message: String): Result<Unit> {
        // 附上最后几行真实输出，避免界面只显示「退出码 1」这种没用的信息
        val tail = tailSummary()
        val full = if (tail.isBlank()) message else "$message\n最近输出：$tail"
        updateStep(stepId, StepState.FAILED, 100, full)
        _state.update { it.copy(running = false, error = full) }
        onLog("✘ $message")
        if (tail.isNotBlank()) onLog("最近输出：$tail")
        return Result.failure(IllegalStateException(full))
    }

    private fun buildStepList(bundled: Boolean): List<DeployStep> = listOf(
        DeployStep("runtime", "安装 Termux 运行时", "解压 APK 内置的 rootfs"),
        DeployStep(
            "rootfsCopy",
            if (bundled) "释放内置容器镜像" else "下载容器镜像",
            if (bundled) "约 500MB，已含 Python/Chromium" else "从网络拉取",
        ),
        DeployStep("termux", "准备 Termux 环境", "配置软件源、校验 proot-distro"),
        DeployStep("rootfs", "安装 proot 容器", "proot-distro install"),
        DeployStep("prootrun", "proot 冒烟测试", "首次真正启动 proot"),
        DeployStep("deps", "容器内自检", "Python / Chromium / 项目文件"),
        DeployStep("pydeps", "同步 Python 依赖", if (bundled) "内置镜像已包含" else "uv sync"),
    )

    private fun mapStepId(id: String): String? = when (id) {
        "pre", "proot", "dirs" -> "termux"
        "rootfs" -> "rootfs"
        "prootrun" -> "prootrun"
        "deps", "config", "env" -> "deps"
        "project" -> "pydeps"
        else -> null
    }

    /** 冒烟测试可能发现需要 PROOT_NO_SECCOMP=1，后续容器操作沿用。 */
    private fun prootExtraEnv(): Map<String, String> =
        DeployScripts.readProotEnvExtra(TermuxPaths.homeDir(context))

    /** 在容器内执行一段脚本（先写到 $HOME 再让容器读取）。 */
    private fun execLogin(alias: String, script: String, onLine: (String) -> Unit): Int {
        if (script.isBlank()) return 0
        val tmp = File(TermuxPaths.homeDir(context), ".m7a-inner.sh")
        tmp.writeText(script, Charsets.UTF_8)
        // 关键：路径必须规范化成 /data/data/... 形式。
        // 宿主写文件用的是 Context.getFilesDir()（/data/user/0/...），
        // 而 proot-distro 只把 /data/data/<pkg> 绑定进容器，
        // 容器里不存在 /data/user/0/...，直接用 absolutePath 会得到
        // bash 的 "No such file or directory"（退出码 127）。
        val guestScript = TermuxPaths.guestPath(tmp)
        onLog("    容器内执行：$guestScript")
        return TermuxShell.run(
            context,
            "exec proot-distro login $alias -- /bin/bash $guestScript",
            onLine = onLine,
            onErrorLine = onLine,
            extraEnv = prootExtraEnv(),
        )
    }

    private fun writeStartScript(bundled: Boolean) {
        val f = File(TermuxPaths.homeDir(context), "start-m7a.sh")
        f.parentFile?.mkdirs()
        f.writeText(
            DeployScripts.startScript(
                alias = settings.containerAlias(),
                afterFinish = settings.afterFinish(),
                logLevel = settings.logLevel(),
                bundled = bundled,
                // 绑定 logs，让二维码登录图片落到共享目录，App 才能显示出来
                guestSharedLogs = SharedPaths.guestLogsDir(context),
            ),
            Charsets.UTF_8,
        )
        f.setExecutable(true, false)
    }

    /**
     * 把 config.yaml 写到宿主与容器共享的目录，返回它的**容器可见**路径。
     *
     * 容器内由 setupInsideContainer 的 config 子步骤复制到 /m7a/config.yaml。
     * 之所以要写到共享目录而不是直接写容器内路径：宿主无法稳定地按
     * proot-distro 的内部目录布局去拼 rootfs 路径（不同版本会变），
     * 而 $HOME 在默认模式下是以同一路径绑定进容器的。
     */
    /**
     * 刷新共享目录里的 config.yaml，返回它的**容器可见**路径。
     * 具体逻辑在 [ConfigSync]，与设置页「生成脚本」共用。
     */
    private fun refreshSharedConfig(bundled: Boolean, alias: String): String =
        ConfigSync.refresh(
            context = context,
            settings = settings,
            bundled = bundled,
            alias = alias,
            onLog = onLog,
            extraEnv = prootExtraEnv(),
        )
}
