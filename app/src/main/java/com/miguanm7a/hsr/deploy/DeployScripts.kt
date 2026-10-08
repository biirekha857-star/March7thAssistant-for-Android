package com.miguanm7a.hsr.deploy

import com.miguanm7a.hsr.termux.TermuxPaths

/**
 * 部署脚本生成器：把《Termux 安卓部署》手册的每一步固化成幂等 shell 脚本。
 *
 * 设计要点
 * - 所有脚本随 App 内置，运行时不下载任何脚本。
 * - 输出带 `@@STEP:<id>:<msg>` / `@@DONE:<id>` / `@@FAIL:<id>:<msg>` 标记，
 *   供 App 解析成实时进度看板。
 * - 内置镜像（BUNDLED）下，March7thAssistant 已在容器内 /m7a，且 /opt/venv
 *   依赖齐全，因此不需要 git clone、不需要 uv sync。
 *
 * 注意：这些是 Kotlin raw string，脚本里的每个 `$` 都必须写成 `${'$'}`，
 * 否则 Kotlin 会当成模板插值，编译期就会报错。
 */
object DeployScripts {

    const val MARK = "@@STEP:"
    const val MARK_DONE = "@@DONE:"
    const val MARK_FAIL = "@@FAIL:"

    /** 容器内项目路径（内置镜像布局）。 */
    const val CONTAINER_APP_DIR = "/m7a"

    /** 容器内虚拟环境（内置镜像布局）。 */
    const val CONTAINER_VENV = "/opt/venv"

    /**
     * 宿主与容器共享的目录名（放在 Termux 的 $HOME 下）。
     *
     * proot-distro 默认模式下 $HOME 会以同一路径绑定进容器，所以把配置、
     * 启动脚本放这里，容器内可以直接按绝对路径读到。
     */
    const val SHARED_DIR_NAME = "m7a-shared"

    /** 配置文件在共享目录里的文件名。 */
    const val SHARED_CONFIG_NAME = "config.yaml"

    /**
     * Asset 中的离线镜像。
     *
     * 扩展名刻意用 `.bin` 而不是 `.tar.gz`：AGP 对 `.gz` 会走「解压后存储」的
     * 打包路径，实测会把 497MB 的 gzip 展开成 1.4GB 塞进 APK。用 `.bin` 就是
     * 原样存储，内容仍然是 gzip 压缩的 tar。
     */
    const val ROOTFS_ASSET = "rootfs/m7a-rootfs.bin"

    /** 版本标记：脚本结构变化时递增，便于排查旧缓存。 */
    const val SCRIPT_REV = 4

    /**
     * 读取 proot 冒烟测试留下的额外环境变量。
     *
     * 内容形如 `PROOT_NO_SECCOMP=1`。某些设备上 proot 需要它才能启动，
     * 检测一次后所有容器操作都沿用，避免每次重新试错。
     */
    fun readProotEnvExtra(homeDir: java.io.File): Map<String, String> {
        val f = java.io.File(java.io.File(homeDir, "m7a-shared"), "proot-env")
        if (!f.isFile) return emptyMap()
        return runCatching {
            f.readText(Charsets.UTF_8).lineSequence()
                .map { it.trim().trim('"', '\'') }
                .filter { it.contains('=') && !it.startsWith("#") }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
                .filterKeys { it.isNotBlank() }
        }.getOrDefault(emptyMap())
    }

    private const val D = "$"

    private fun header(title: String): String = buildString {
        appendLine("#!${TermuxPaths.OFFICIAL_BASH}")
        appendLine("# 由 MaaTermux 生成：$title  (rev $SCRIPT_REV)")
        appendLine("export PS1=''")
        appendLine("step() { echo \"$MARK${D}1:${D}2\"; }")
        appendLine("ok()   { echo \"$MARK_DONE${D}1\"; }")
        appendLine("fail() { echo \"$MARK_FAIL${D}1:${D}2\"; }")
        appendLine("export DEBIAN_FRONTEND=noninteractive")
        appendLine()
    }

    // ---------------------------------------------------------------------
    // Termux 侧准备：仅配置软件源，并校验 proot-distro 是否可用
    // ---------------------------------------------------------------------
    fun setupTermux(mirror: TermuxMirror): String = header("Termux 侧准备") + buildString {
        appendLine("step pre \"配置 Termux 软件源：${mirror.label}\"")
        appendLine("mkdir -p \"${D}PREFIX/etc/apt/sources.list.d\"")
        appendLine("cat > \"${D}PREFIX/etc/apt/sources.list\" <<'MIRROR_EOF'")
        appendLine("deb ${mirror.url} stable main")
        appendLine("MIRROR_EOF")
        appendLine("rm -f \"${D}PREFIX/etc/apt/sources.list.d/\"*.list 2>/dev/null || true")
        appendLine("ok pre")
        appendLine()
        appendLine("step proot \"检查内置 proot-distro\"")
        appendLine("if command -v proot-distro >/dev/null 2>&1; then")
        appendLine("  echo \"    proot-distro 已内置\"")
        appendLine("  proot-distro --version 2>/dev/null | head -1 || true")
        appendLine("  ok proot")
        appendLine("else")
        appendLine("  echo \"    未找到内置 proot-distro，尝试从软件源安装\"")
        appendLine("  apt-get update -o Acquire::Retries=3 || { fail proot \"apt-get update 失败\"; exit 1; }")
        appendLine("  apt-get install -y -o Dpkg::Options::=\"--force-confold\" proot-distro || { fail proot \"proot-distro 安装失败\"; exit 1; }")
        appendLine("  ok proot")
        appendLine("fi")
        appendLine()
        appendLine("step dirs \"创建共享目录\"")
        appendLine("mkdir -p \"${D}HOME/m7a-shared/logs\" \"${D}HOME/march7thassistant/logs\" \\")
        appendLine("         \"${D}HOME/march7thassistant/3rdparty/WebBrowser/UserProfile\"")
        appendLine("ok dirs")
        appendLine()
    }

    // ---------------------------------------------------------------------
    // 使用 APK 内置的离线 rootfs 安装容器（完全离线）
    // ---------------------------------------------------------------------
    fun installBundledRootfs(alias: String): String = header("离线安装容器") + buildString {
        appendLine("ROOTFS=\"${D}HOME/m7a-shared/m7a-rootfs.tar.gz\"")
        appendLine("step rootfs \"校验内置离线镜像\"")
        appendLine("if [ ! -f \"${D}ROOTFS\" ]; then")
        appendLine("  fail rootfs \"内置镜像未就绪：${D}ROOTFS 不存在\"; exit 1")
        appendLine("fi")
        appendLine("echo \"    镜像大小：${D}(du -m \"${D}ROOTFS\" | cut -f1) MB\"")
        appendLine()
        // `list --quiet` 输出的是裸容器名，逐行比对最稳（默认输出是带符号的项目列表）
        //
        // 已存在且可用时复用容器，不重装：重装一次要 30 秒，而且会把浏览器
        // 登录态（$CONTAINER_APP_DIR/3rdparty/WebBrowser/UserProfile）一起
        // 抹掉 —— 那意味着每次部署都要重新扫码登录。只有容器确实坏掉才重装。
        appendLine("if proot-distro list --quiet 2>/dev/null | grep -qx '$alias'; then")
        appendLine("  step rootfs \"容器 $alias 已存在，校验可用性\"")
        appendLine("  if proot-distro login $alias -- /bin/bash -c 'test -f $CONTAINER_APP_DIR/main.py' >/dev/null 2>&1; then")
        appendLine("    echo \"    容器可复用（保留登录态），跳过重装\"")
        appendLine("    ok rootfs")
        appendLine("    exit 0")
        appendLine("  fi")
        appendLine("  echo \"    容器不可用，准备重装\"")
        appendLine("  proot-distro remove $alias >/dev/null 2>&1 || true")
        appendLine("fi")
        appendLine()
        appendLine("step rootfs \"从本地镜像安装容器 $alias（无需联网）\"")
        appendLine("proot-distro install -n $alias \"${D}ROOTFS\" || { fail rootfs \"本地容器安装失败\"; exit 1; }")
        appendLine("ok rootfs")
        appendLine()
    }

    // ---------------------------------------------------------------------
    // 联网安装容器（ghcr 镜像 / 官方 debian）
    // ---------------------------------------------------------------------
    fun installRemoteRootfs(source: RootfsSource, alias: String): String {
        val refs: List<String> = when (source) {
            RootfsSource.GHCR_OFFICIAL -> listOf("ghcr.io/moesnow/march7thassistant:latest")
            RootfsSource.GHCR_NJU -> listOf("ghcr.nju.edu.cn/moesnow/march7thassistant:latest")
            RootfsSource.DEBIAN_MANUAL -> listOf("debian:stable")
            RootfsSource.BUNDLED -> emptyList()
        }
        return header("联网安装容器") + buildString {
            appendLine("step rootfs \"检查可用空间\"")
            appendLine("FREE_MB=${D}(df -m \"${D}PREFIX\" | awk 'NR==2{print ${D}4}')")
            appendLine("echo \"    可用空间：约 ${D}FREE_MB MB\"")
            appendLine("if [ \"${D}FREE_MB\" -lt 5120 ]; then")
            appendLine("  echo \"    [警告] 空间不足 5GB，解压容器可能失败\"")
            appendLine("fi")
            appendLine()
            appendLine("REFS=(")
            refs.forEach { appendLine("  \"$it\"") }
            appendLine(")")
            appendLine()
            appendLine("for R in \"${D}{REFS[@]}\"; do")
            appendLine("  step rootfs \"拉取容器镜像 ${D}R（约 1-3GB，请保持应用在前台）\"")
            appendLine("  if proot-distro install -n $alias \"${D}R\"; then")
            appendLine("    ok rootfs; exit 0")
            appendLine("  fi")
            appendLine("  echo \"    [失败] ${D}R，尝试下一个源\"")
            appendLine("  proot-distro remove $alias >/dev/null 2>&1 || true")
            appendLine("done")
            appendLine()
            appendLine("fail rootfs \"所有镜像源均失败\"")
            appendLine("exit 1")
            appendLine()
        }
    }

    // ---------------------------------------------------------------------
    // proot 冒烟测试
    // ---------------------------------------------------------------------
    /**
     * 真正启动一次 proot。
     *
     * 为什么单独一步：`proot-distro install` 只是解压 tar，**不会调用 proot**。
     * 所以「安装容器成功」并不代表 proot 能跑。某些设备/内核下 proot 会因为
     * seccomp 或 ptrace 限制启动失败，此时需要 `PROOT_NO_SECCOMP=1`。
     * 这一步把「proot 起不来」和「容器内脚本有问题」区分开。
     */
    fun prootSmokeTest(alias: String): String = header("proot 冒烟测试") + buildString {
        appendLine("MARKER=\"${D}HOME/m7a-shared/proot-env\"")
        appendLine("mkdir -p \"${D}HOME/m7a-shared\"")
        appendLine("rm -f \"${D}MARKER\"")
        appendLine()
        appendLine("step prootrun \"启动 proot（安装容器并不会真正调用 proot）\"")
        appendLine("proot-distro login $alias -- /bin/echo PROOT_SMOKE_OK")
        appendLine("RC=${D}?")
        appendLine("if [ \"${D}RC\" -eq 0 ]; then")
        appendLine("  echo \"    默认模式可用\"")
        appendLine("  ok prootrun")
        appendLine("  exit 0")
        appendLine("fi")
        appendLine()
        appendLine("echo \"    默认模式失败（rc=${D}RC），尝试 PROOT_NO_SECCOMP=1\"")
        appendLine("PROOT_NO_SECCOMP=1 proot-distro login $alias -- /bin/echo PROOT_SMOKE_OK")
        appendLine("RC2=${D}?")
        appendLine("if [ \"${D}RC2\" -eq 0 ]; then")
        appendLine("  echo 'PROOT_NO_SECCOMP=1' > \"${D}MARKER\"")
        appendLine("  echo \"    已记录：后续容器操作会带 PROOT_NO_SECCOMP=1\"")
        appendLine("  ok prootrun")
        appendLine("  exit 0")
        appendLine("fi")
        appendLine()
        appendLine("fail prootrun \"proot 无法启动（rc=${D}RC / ${D}RC2）\"")
        appendLine("exit 1")
        appendLine()
    }

    // ---------------------------------------------------------------------
    // 容器内自检 / 依赖补装
    // ---------------------------------------------------------------------
    /**
     * @param bundled true 表示使用内置镜像（已含 Python/Chromium/项目），只做自检；
     *                false 表示需要真正安装依赖。
     * @param guestSharedConfig 宿主上 config.yaml 的**容器可见**绝对路径
     *        （形如 /data/data/com.m7ahsr/files/home/m7a-shared/config.yaml）。
     *        传 null 表示跳过配置安装。
     */
    fun setupInsideContainer(bundled: Boolean, guestSharedConfig: String? = null): String = buildString {        appendLine("#!/bin/bash")
        appendLine("step() { echo \"$MARK${D}1:${D}2\"; }")
        appendLine("ok()   { echo \"$MARK_DONE${D}1\"; }")
        appendLine("fail() { echo \"$MARK_FAIL${D}1:${D}2\"; }")
        appendLine("export DEBIAN_FRONTEND=noninteractive")
        appendLine("export PATH=$CONTAINER_VENV/bin:${D}PATH")
        appendLine("export LANG=C.UTF-8")
        appendLine("ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime 2>/dev/null || true")
        appendLine()
        appendLine("step deps \"自检容器环境\"")
        appendLine("echo \"    Debian  : ${D}(cat /etc/debian_version 2>/dev/null || echo unknown)\"")
        appendLine("echo \"    Arch    : ${D}(uname -m)\"")
        appendLine("if [ -x $CONTAINER_VENV/bin/python ]; then")
        appendLine("  echo \"    venv    : ${D}($CONTAINER_VENV/bin/python --version 2>&1)\"")
        appendLine("fi")
        appendLine("if [ -d $CONTAINER_APP_DIR ]; then")
        appendLine("  echo \"    项目    : $CONTAINER_APP_DIR\"")
        appendLine("fi")
        appendLine("if command -v chromium >/dev/null 2>&1; then")
        appendLine("  echo \"    Chromium: ${D}(chromium --version 2>&1 | head -1)\"")
        appendLine("fi")
        appendLine()

        if (!bundled) {
            appendLine("MISSING=0")
            appendLine("for C in python3 chromium chromedriver git; do")
            appendLine("  command -v \"${D}C\" >/dev/null 2>&1 || { echo \"    缺少 ${D}C\"; MISSING=1; }")
            appendLine("done")
            appendLine("if [ \"${D}MISSING\" = \"1\" ]; then")
            appendLine("  step deps \"安装缺失依赖（耗时较长）\"")
            appendLine("  if [ -f /etc/apt/sources.list ]; then")
            appendLine("    sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list")
            appendLine("  fi")
            appendLine("  if [ -f /etc/apt/sources.list.d/debian.sources ]; then")
            appendLine("    sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/debian.sources")
            appendLine("  fi")
            appendLine("  apt-get update -o Acquire::Retries=3 || { fail deps \"apt update 失败\"; exit 1; }")
            appendLine("  apt-get install -y --no-install-recommends \\")
            appendLine("    python3 python3-pip python3-venv pipx git curl ca-certificates tzdata \\")
            appendLine("    chromium chromium-driver || { fail deps \"依赖安装失败\"; exit 1; }")
            appendLine("fi")
            appendLine()
        }

        appendLine("ok deps")
        appendLine()

        // ---- 安装 config.yaml 到项目目录 ----
        // 内置镜像的项目在 /m7a（不是 ~/March7thAssistant），配置必须写到
        // /m7a/config.yaml，否则 March7thAssistant 会按默认的「本地游戏」模式
        // 启动，而 Termux/proot 环境只能走云游戏模式。
        if (!guestSharedConfig.isNullOrBlank()) {
            appendLine("step config \"安装 config.yaml 到 $CONTAINER_APP_DIR\"")
            appendLine("if [ -f \"$guestSharedConfig\" ]; then")
            appendLine("  mkdir -p $CONTAINER_APP_DIR/logs $CONTAINER_APP_DIR/3rdparty/WebBrowser/UserProfile")
            appendLine("  cp -f \"$guestSharedConfig\" $CONTAINER_APP_DIR/config.yaml")
            appendLine("  echo \"    已写入 $CONTAINER_APP_DIR/config.yaml\"")
            appendLine("else")
            appendLine("  echo \"    [警告] 未找到 $guestSharedConfig，将使用程序默认配置\"")
            appendLine("fi")
            appendLine("ok config")
            appendLine()
        }

        appendLine("step env \"写入容器环境变量\"")
        appendLine("grep -q 'MARCH7TH_CLOUD_GAME_ENABLE' \"${D}HOME/.bashrc\" 2>/dev/null || cat >> \"${D}HOME/.bashrc\" <<'ENV_EOF'")
        appendLine("export MARCH7TH_CLOUD_GAME_ENABLE=true")
        appendLine("export MARCH7TH_BROWSER_HEADLESS_ENABLE=true")
        appendLine("export MARCH7TH_BROWSER_HEADLESS_RESTART_ON_NOT_LOGGED_IN=false")
        appendLine("export MARCH7TH_DOCKER_STARTED=true")
        appendLine("export MARCH7TH_BROWSER_TYPE=chromium")
        appendLine("export MARCH7TH_BROWSER_PATH=/usr/bin/chromium")
        appendLine("export MARCH7TH_DRIVER_PATH=/usr/bin/chromedriver")
        appendLine("export PATH=$CONTAINER_VENV/bin:${D}PATH")
        appendLine("ENV_EOF")
        appendLine("ok env")
        appendLine()
    }

    /**
     * 非内置镜像时同步项目与 Python 依赖；内置镜像下返回空串（引擎会跳过）。
     */
    fun syncProjectDeps(bundled: Boolean): String {
        if (bundled) return ""
        return buildString {
            appendLine("#!/bin/bash")
            appendLine("step() { echo \"$MARK${D}1:${D}2\"; }")
            appendLine("ok()   { echo \"$MARK_DONE${D}1\"; }")
            appendLine("fail() { echo \"$MARK_FAIL${D}1:${D}2\"; }")
            appendLine("export DEBIAN_FRONTEND=noninteractive")
            appendLine("export PATH=$CONTAINER_VENV/bin:${D}HOME/.local/bin:${D}PATH")
            appendLine()
            appendLine("step project \"获取 March7thAssistant\"")
            appendLine("if [ ! -d \"${D}HOME/March7thAssistant\" ]; then")
            appendLine("  git clone https://github.com/moesnow/March7thAssistant --depth 1 \"${D}HOME/March7thAssistant\" \\")
            appendLine("    || git clone https://ghfast.top/https://github.com/moesnow/March7thAssistant --depth 1 \"${D}HOME/March7thAssistant\" \\")
            appendLine("    || { fail project \"克隆失败\"; exit 1; }")
            appendLine("fi")
            appendLine("cd \"${D}HOME/March7thAssistant\" || { fail project \"项目目录不存在\"; exit 1; }")
            appendLine("ok project")
            appendLine()
            appendLine("step deps \"安装 uv 并同步 Python 依赖\"")
            appendLine("command -v uv >/dev/null 2>&1 || pipx install uv || pip install uv || { fail deps \"uv 安装失败\"; exit 1; }")
            appendLine("uv sync --only-group docker || uv sync || { fail deps \"uv sync 失败\"; exit 1; }")
            appendLine("ok deps")
            appendLine()
        }
    }

    // ---------------------------------------------------------------------
    // 运行命令
    // ---------------------------------------------------------------------

    /**
     * 把容器内的 logs 目录绑定到宿主共享目录的 `--bind` 参数。
     *
     * 为什么必须这么做：March7thAssistant 首次运行需要**扫码登录**，二维码会写到
     * 项目目录下的 `logs/qrcode_login.png`（见 `module/game/cloud.py` 的
     * `_save_qr_img`）。如果 logs 留在容器里，用户在手机上根本看不到二维码，
     * 也就没法用米游社 App 扫码。
     *
     * 绑定之后二维码直接落在 `$HOME/m7a-shared/logs/`，App 能读出来显示。
     * 上游代码注释也说明这是预期用法（「保存到 logs 目录，方便 Docker 挂载访问」）。
     */
    private fun bindLogsArg(appDir: String, guestSharedLogs: String?): String =
        if (guestSharedLogs.isNullOrBlank()) "" else "--bind=$guestSharedLogs:$appDir/logs "

    /** 容器内执行一次 March7thAssistant 的完整命令（供任务面板直接投递）。 */
    fun runTaskCommand(
        alias: String,
        task: M7aTask,
        afterFinish: AfterFinish,
        logLevel: String,
        bundled: Boolean,
        guestSharedLogs: String? = null,
    ): String {
        val appDir = if (bundled) CONTAINER_APP_DIR else "~/March7thAssistant"
        val runner = if (bundled) {
            "$CONTAINER_VENV/bin/python main.py"
        } else {
            "uv run --only-group docker main.py"
        }
        val taskArg = task.arg?.let { " $it" } ?: ""
        val bindArg = bindLogsArg(appDir, guestSharedLogs)
        return buildString {
            append("exec proot-distro login ").append(bindArg).append(alias)
                .append(" -- /usr/bin/env")
            append(" MARCH7TH_AFTER_FINISH=").append(afterFinish.value)
            append(" MARCH7TH_LOG_LEVEL=").append(logLevel)
            append(" MARCH7TH_CLOUD_GAME_ENABLE=true")
            append(" MARCH7TH_BROWSER_HEADLESS_ENABLE=true")
            append(" MARCH7TH_BROWSER_HEADLESS_RESTART_ON_NOT_LOGGED_IN=false")
            append(" MARCH7TH_DOCKER_STARTED=true")
            append(" MARCH7TH_BROWSER_TYPE=chromium")
            append(" MARCH7TH_BROWSER_PATH=/usr/bin/chromium")
            append(" MARCH7TH_DRIVER_PATH=/usr/bin/chromedriver")
            append(" TZ=Asia/Shanghai")
            append(" PATH=$CONTAINER_VENV/bin:/usr/local/bin:/usr/bin:/bin")
            append(" /bin/bash -lc 'cd ").append(appDir)
            append(" && exec ").append(runner).append(taskArg).append("'")
        }
    }

    /** 生成 Termux 侧便捷启动脚本 ~/start-m7a.sh。 */
    fun startScript(
        alias: String,
        afterFinish: AfterFinish,
        logLevel: String,
        bundled: Boolean,
        guestSharedLogs: String? = null,
    ): String {
        val appDir = if (bundled) CONTAINER_APP_DIR else "~/March7thAssistant"
        val runner = if (bundled) {
            "$CONTAINER_VENV/bin/python main.py"
        } else {
            "uv run --only-group docker main.py"
        }
        return buildString {
            appendLine("#!${TermuxPaths.OFFICIAL_BASH}")
            appendLine("# 由 MaaTermux 生成：启动 March7thAssistant  (rev $SCRIPT_REV)")
            appendLine("#")
            appendLine("# 用法：")
            appendLine("#   bash ~/start-m7a.sh            # 完整运行")
            appendLine("#   bash ~/start-m7a.sh daily      # 指定任务")
            appendLine("#   bash ~/start-m7a.sh -l         # 列出所有可用任务")
            appendLine("set -u")
            appendLine("TASK=\"${D}{1:-}\"")
            appendLine()
            appendLine("# proot 额外环境：由部署时的冒烟测试探测写入")
            appendLine("# （部分设备 proot 需要 PROOT_NO_SECCOMP=1 才能启动）")
            appendLine("PROOT_EXTRA=\"\"")
            appendLine("if [ -f \"${D}HOME/m7a-shared/proot-env\" ]; then")
            appendLine("  PROOT_EXTRA=\"${D}(cat \"${D}HOME/m7a-shared/proot-env\")\"")
            appendLine("fi")
            appendLine()
            appendLine("exec env ${D}PROOT_EXTRA proot-distro login ${bindLogsArg(appDir, guestSharedLogs)}$alias -- /usr/bin/env \\")
            appendLine("  MARCH7TH_AFTER_FINISH=${afterFinish.value} \\")
            appendLine("  MARCH7TH_LOG_LEVEL=$logLevel \\")
            appendLine("  MARCH7TH_CLOUD_GAME_ENABLE=true \\")
            appendLine("  MARCH7TH_BROWSER_HEADLESS_ENABLE=true \\")
            appendLine("  MARCH7TH_BROWSER_HEADLESS_RESTART_ON_NOT_LOGGED_IN=false \\")
            appendLine("  MARCH7TH_DOCKER_STARTED=true \\")
            appendLine("  MARCH7TH_BROWSER_TYPE=chromium \\")
            appendLine("  MARCH7TH_BROWSER_PATH=/usr/bin/chromium \\")
            appendLine("  MARCH7TH_DRIVER_PATH=/usr/bin/chromedriver \\")
            appendLine("  TZ=Asia/Shanghai \\")
            appendLine("  PATH=$CONTAINER_VENV/bin:/usr/local/bin:/usr/bin:/bin \\")
            appendLine("  /bin/bash -lc \"cd $appDir && exec $runner ${D}TASK\"")
            appendLine()
        }
    }

    /**
     * 我们管理的 config.yaml 键 -> 已格式化的 YAML 标量。
     *
     * 键名**必须**与上游 `assets/config/config.example.yaml` 一致：
     * `Config._update_config()` 只覆盖已存在的键，未知键会被静默忽略。
     *
     * 特别注意：早期版本写的 `run_daily_time` **不是**上游的键（已被忽略），
     * 真正的定时键是 `loop_mode` + `scheduled_time`。
     */
    fun configScalars(
        afterFinish: AfterFinish,
        scheduledTime: String,
        loopMode: String,
        logLevel: String,
        usePaidTime: Boolean,
        toggles: Map<String, Boolean>,
        debugPort: Int = 9222,
    ): Map<String, String> {
        val m = LinkedHashMap<String, String>()

        // 基础
        m["locales"] = "zh_CN"
        m["log_level"] = logLevel
        m["log_retention_days"] = "30"
        m["after_finish"] = "\"${afterFinish.value}\""

        // 云游戏（Termux/proot 环境只能走云游戏）
        m["cloud_game_enable"] = "true"
        m["cloud_game_fullscreen_enable"] = "true"
        m["cloud_game_use_paid_time"] = usePaidTime.toString()
        m["cloud_game_max_queue_time"] = "60"
        m["cloud_game_login_timeout"] = "20"

        // 浏览器（无窗口 + 持久化登录态）
        m["browser_type"] = "integrated"
        m["browser_headless_enable"] = "true"
        m["browser_headless_restart_on_not_logged_in"] = "false"
        m["browser_persistent_enable"] = "true"
        m["browser_scale_factor"] = "1.0"
        m["browser_download_use_mirror"] = "true"
        // 实时监看要靠这个端口连 CDP；固定下来便于 App 侧探测
        m["browser_debug_port"] = debugPort.toString()

        // 定时（上游真实键名）
        m["loop_mode"] = loopMode
        m["scheduled_time"] = "\"$scheduledTime\""

        // 用户在设置页控制的开关
        toggles.forEach { (k, v) -> m[k] = v.toString() }

        return m
    }

    /**
     * 把容器里**已有**的 config.yaml 拉到共享目录。
     *
     * 这一步是「不破坏状态」的关键：程序运行后会把整份配置（含
     * `*_timestamp`、`daily_tasks`、`power_plan` 等）写回容器内的
     * `/m7a/config.yaml`。我们先把它读出来当基底，再用
     * [ConfigPatcher] 增量修改我们管的键，最后推回去。
     *
     * 注意：`> "$共享文件"` 这个重定向是在**宿主** shell 里执行的，
     * 而共享目录在默认模式下与容器同路径绑定，所以两边都成立。
     */
    fun pullContainerConfig(alias: String, appDir: String, guestSharedConfig: String): String =
        header("读取容器内现有配置") + buildString {
            appendLine("step config \"读取容器内现有配置（保留状态字段）\"")
            appendLine("rm -f \"$guestSharedConfig\"")
            appendLine("proot-distro login $alias -- /bin/cat $appDir/config.yaml > \"$guestSharedConfig\" 2>/dev/null || true")
            appendLine("if [ -s \"$guestSharedConfig\" ]; then")
            appendLine("  echo \"    已读取 ${D}(wc -c < \"$guestSharedConfig\") 字节\"")
            appendLine("else")
            appendLine("  echo \"    容器内暂无 config.yaml，将使用模板\"")
            appendLine("  rm -f \"$guestSharedConfig\"")
            appendLine("fi")
            appendLine("ok config")
            appendLine()
        }

    /**
     * 首次部署用的带注释模板。
     *
     * 只在容器里**还没有** config.yaml 时使用；一旦程序运行过，
     * 文件里就包含全部键与状态（`*_timestamp` 等），此后必须用
     * [ConfigPatcher.upsertTopLevel] 增量修改，绝不能整份覆盖。
     */
    fun configYamlTemplate(): String = buildString {
        appendLine("# 由 MaaTermux 生成（Termux 云崩铁模式）")
        appendLine("# 提示：程序运行后会把完整配置写回本文件。")
        appendLine("# 本应用只会增量修改下面这些键，其它键与状态字段不会被覆盖。")
        appendLine()
        appendLine("locales: zh_CN")
        appendLine("log_level: DEBUG")
        appendLine("log_retention_days: 30")
        appendLine()
        appendLine("after_finish: \"Exit\"")
        appendLine()
        appendLine("# 定时：loop_mode 取 scheduled（定时）或 power（按体力计划循环）")
        appendLine("loop_mode: scheduled")
        appendLine("scheduled_time: \"04:00\"")
        appendLine()
        appendLine("cloud_game_enable: true")
        appendLine("cloud_game_fullscreen_enable: true")
        appendLine("cloud_game_use_paid_time: false")
        appendLine()
        appendLine("browser_type: integrated")
        appendLine("browser_headless_enable: true")
        appendLine("browser_headless_restart_on_not_logged_in: false")
        appendLine("browser_persistent_enable: true")
        appendLine()
        appendLine("# 任务开关（可在「设置 → 任务开关」调整）")
        TaskToggles.ALL.forEach { t -> appendLine("${t.key}: ${t.defaultValue}") }
        appendLine()
    }
}
