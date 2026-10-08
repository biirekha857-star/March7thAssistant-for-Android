package com.miguanm7a.hsr.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 把生成的 shell 脚本原样打印出来，用来肉眼核对语法。
 *
 * DeployScripts 是纯 Kotlin（不依赖 Android），所以可以直接在 JVM 单测里跑。
 * 这类脚本一旦有转义错误，真机上的表现是「退出码 1」且看不出原因，
 * 所以值得把生成结果固定下来核对。
 */
class DeployScriptsRenderTest {

    @Test
    fun `print all generated scripts`() {
        val bundled = true
        val alias = "m7a"

        val sb = StringBuilder()
        fun sec(title: String, body: String) {
            sb.appendLine("========== $title ==========")
            sb.appendLine(body)
            sb.appendLine()
        }
        sec("setupTermux", DeployScripts.setupTermux(TermuxMirror.TUNA))
        sec("installBundledRootfs", DeployScripts.installBundledRootfs(alias))
        sec(
            "setupInsideContainer(bundled=true)",
            DeployScripts.setupInsideContainer(
                bundled,
                "/data/data/com.m7ahsr/files/home/m7a-shared/config.yaml"
            )
        )
        sec(
            "runTaskCommand",
            DeployScripts.runTaskCommand(alias, M7aTask.DAILY, AfterFinish.EXIT, "DEBUG", bundled)
        )
        sec("startScript", DeployScripts.startScript(alias, AfterFinish.EXIT, "DEBUG", bundled))
        sec("configYamlTemplate", DeployScripts.configYamlTemplate())
        sec(
            "configScalars",
            DeployScripts.configScalars(
                AfterFinish.EXIT, "04:00", "scheduled", "DEBUG", false,
                TaskToggles.defaults(),
            ).entries.joinToString("\n") { "${it.key}: ${it.value}" }
        )

        // 写到固定路径，方便人工核对（测试输出在报告里会被截断）
        val out = File(System.getProperty("java.io.tmpdir"), "maatermux-rendered-scripts.txt")
        out.writeText(sb.toString(), Charsets.UTF_8)
        println("rendered scripts written to: ${out.absolutePath}")
        println(sb.toString())
    }

    /**
     * 脚本里不能出现未展开的 Kotlin 模板残留。
     * `$` 后面跟字母在 Kotlin 里会被当插值，编译期就报错；
     * 但 `${'$'}` 忘写会变成字面量 `${D}` 之类，需要显式挡住。
     */
    @Test
    fun `no kotlin template leftovers in generated scripts`() {
        val scripts = listOf(
            "setupTermux" to DeployScripts.setupTermux(TermuxMirror.TUNA),
            "installBundledRootfs" to DeployScripts.installBundledRootfs("m7a"),
            "setupInsideContainer" to DeployScripts.setupInsideContainer(true, null),
            "runTaskCommand" to DeployScripts.runTaskCommand(
                "m7a", M7aTask.DAILY, AfterFinish.EXIT, "DEBUG", true
            ),
            "startScript" to DeployScripts.startScript("m7a", AfterFinish.EXIT, "DEBUG", true),
        )
        for ((name, text) in scripts) {
            assertFalse("$name 残留 \${D} 之类模板", text.contains("\${D}"))
            assertFalse("$name 残留 \${'$'}", text.contains("\${'$'}"))
            assertFalse("$name 残留 CONTAINER_VENV 占位", text.contains("CONTAINER_VENV"))
            assertTrue("$name 不应为空", text.isNotBlank())
        }
    }

    /** 内置镜像下，自检脚本必须用 /opt/venv 与 /m7a。 */
    @Test
    fun `bundled selfcheck references the image layout`() {
        val s = DeployScripts.setupInsideContainer(true, null)
        assertTrue(s.contains("/opt/venv"))
        assertTrue(s.contains("/m7a"))
        // 内置模式下不应该去 apt 装东西
        assertFalse("内置模式不应触发 apt-get install", s.contains("apt-get install"))
        // 应该有环境变量块
        assertTrue(s.contains("MARCH7TH_CLOUD_GAME_ENABLE=true"))
    }

    /**
     * config.yaml 必须复制到 /m7a/config.yaml。
     *
     * 内置镜像的项目在 /m7a（不是 ~/March7thAssistant），配置放错地方的话
     * March7thAssistant 会按默认的「本地游戏」模式启动，而 Termux/proot
     * 环境只能走云游戏模式 —— 结果就是任务跑不起来。
     */
    @Test
    fun `config is installed into the container app dir`() {
        val guestCfg = "/data/data/com.m7ahsr/files/home/m7a-shared/config.yaml"
        val s = DeployScripts.setupInsideContainer(true, guestCfg)

        assertTrue("应包含 config 子步骤", s.contains("step config"))
        assertTrue("应把配置复制到 /m7a/config.yaml",
            s.contains("cp -f \"$guestCfg\" ${DeployScripts.CONTAINER_APP_DIR}/config.yaml"))
        assertTrue("应创建 logs 目录", s.contains("/m7a/logs"))
        assertTrue("应创建浏览器 profile 目录",
            s.contains("/m7a/3rdparty/WebBrowser/UserProfile"))
        assertTrue("应上报 config 完成", s.contains("ok config"))
        // 不能把配置写到宿主遗留路径
        assertFalse("不应再写到 ~/march7thassistant",
            s.contains("march7thassistant/config.yaml"))
    }

    /** 不传共享配置路径时，不应该生成 config 子步骤。 */
    @Test
    fun `no config step when path is absent`() {
        val s = DeployScripts.setupInsideContainer(true, null)
        assertFalse(s.contains("step config"))
    }

    /**
     * 扫码登录：必须把容器内的 logs 绑定到宿主共享目录。
     *
     * 否则 qrcode_login.png 留在容器里，用户在手机上看不到二维码，就没法登录。
     */
    @Test
    fun `task command binds container logs so QR can be read`() {
        val sharedLogs = "/data/data/com.m7ahsr/files/home/m7a-shared/logs"
        val c = DeployScripts.runTaskCommand(
            "m7a", M7aTask.DAILY, AfterFinish.EXIT, "DEBUG",
            bundled = true, guestSharedLogs = sharedLogs,
        )
        assertTrue(
            "应把容器 logs 绑定到共享目录",
            c.contains("--bind=$sharedLogs:${DeployScripts.CONTAINER_APP_DIR}/logs"),
        )
        // argparse 要求选项在位置参数（容器名）之前
        val bindIdx = c.indexOf("--bind=")
        val aliasIdx = c.indexOf("m7a", c.indexOf("proot-distro login"))
        assertTrue("--bind 必须出现在容器名之前（bind=$bindIdx, alias=$aliasIdx）",
            bindIdx in 1 until aliasIdx)
    }

    @Test
    fun `task command without shared logs has no bind`() {
        val c = DeployScripts.runTaskCommand(
            "m7a", M7aTask.DAILY, AfterFinish.EXIT, "DEBUG",
            bundled = true, guestSharedLogs = null,
        )
        assertFalse("未提供共享目录时不应出现 --bind", c.contains("--bind"))
    }

    @Test
    fun `start script also binds logs`() {
        val sharedLogs = "/data/data/com.m7ahsr/files/home/m7a-shared/logs"
        val s = DeployScripts.startScript(
            "m7a", AfterFinish.EXIT, "DEBUG", bundled = true, guestSharedLogs = sharedLogs,
        )
        assertTrue(s.contains("--bind=$sharedLogs:${DeployScripts.CONTAINER_APP_DIR}/logs"))
        val bindIdx = s.indexOf("--bind=")
        val loginIdx = s.indexOf("proot-distro login")
        assertTrue("--bind 应在登录命令之后、容器名之前", bindIdx > loginIdx)
    }

    /** 共享目录常量与 QR 文件名要和上游 March7thAssistant 一致。 */
    @Test
    fun `qr filename matches upstream`() {
        // module/game/cloud.py: os.path.join("logs", "qrcode_login.png")
        assertEquals("qrcode_login.png", com.miguanm7a.hsr.deploy.SharedPaths.QR_FILENAME)
    }

    /**
     * 容器已存在且可用时必须复用，不能无条件 remove。
     * 无条件重装会抹掉浏览器登录态，用户每次部署都要重新扫码。
     */
    @Test
    fun `existing healthy container is reused not wiped`() {
        val s = DeployScripts.installBundledRootfs("m7a")
        assertTrue("应检查容器是否已存在", s.contains("proot-distro list --quiet"))
        assertTrue("应校验容器可用性",
            s.contains("test -f ${DeployScripts.CONTAINER_APP_DIR}/main.py"))
        assertTrue("可用时应跳过重装", s.contains("跳过重装"))
        // remove 只能出现在「容器不可用」分支之后
        val removeIdx = s.indexOf("proot-distro remove")
        val checkIdx = s.indexOf("test -f ${DeployScripts.CONTAINER_APP_DIR}/main.py")
        assertTrue("remove 必须在可用性校验之后", removeIdx > checkIdx && checkIdx >= 0)
    }

    /** 运行命令要点：/opt/venv/bin/python + /m7a，且不依赖 uv。 */
    @Test
    fun `bundled run command uses venv python in m7a`() {
        val c = DeployScripts.runTaskCommand("m7a", M7aTask.DAILY, AfterFinish.EXIT, "DEBUG", true)
        assertTrue(c.contains("/opt/venv/bin/python main.py"))
        assertTrue(c.contains("cd /m7a"))
        assertTrue(c.contains("daily"))
        assertFalse("内置镜像不需要 uv", c.contains("uv run"))
        assertFalse("不能残留 --termux-home（proot-distro 5.x 没有该选项）",
            c.contains("--termux-home"))
    }
}
