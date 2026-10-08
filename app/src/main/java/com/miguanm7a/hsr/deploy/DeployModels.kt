package com.miguanm7a.hsr.deploy

/** Termux 软件源。 */
enum class TermuxMirror(val label: String, val url: String) {
    TUNA("清华 TUNA", "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"),
    BFSU("北外 BFSU", "https://mirrors.bfsu.edu.cn/termux/apt/termux-main"),
    OFFICIAL("官方源", "https://packages-cf.termux.dev/apt/termux-main"),
}

/** 容器根文件系统来源。 */
enum class RootfsSource(val label: String) {
    /** 使用 APK 内置的离线 rootfs，完全不需要网络（约 500MB，已含 Python/Chromium）。 */
    BUNDLED("内置离线镜像（推荐，无需联网）"),

    /** 官方 ghcr 镜像，走 proot-distro v5 的 docker 拉取能力。 */
    GHCR_OFFICIAL("官方 ghcr.io 镜像（需联网，约 1-3GB）"),

    /** 南京大学 ghcr 镜像，国内速度更好。 */
    GHCR_NJU("南大 ghcr 镜像（需联网，约 1-3GB）"),

    /** 传统方式：proot-distro install debian，再在容器内装依赖。 */
    DEBIAN_MANUAL("Debian 官方源手动安装（需联网，最慢）"),
}

/** 任务完成后的行为。 */
enum class AfterFinish(val value: String) {
    EXIT("Exit"),
    LOOP("Loop"),
}

/**
 * March7thAssistant 的任务。
 *
 * 取值来自镜像内 `utils/tasks.py` 的 AVAILABLE_TASKS，不是猜测：
 * main / routine / daily / power / currencywars / divergent / fight / universe /
 * forgottenhall / purefiction / apocalyptic / notify / game_update ...
 * 不指定任务名时执行「完整运行」。
 */
enum class M7aTask(val label: String, val arg: String?) {
    FULL("完整运行", null),
    ROUTINE("例行任务", "routine"),
    DAILY("每日实训", "daily"),
    POWER("清体力", "power"),
    CURRENCY_WARS("货币战争", "currencywars"),
    DIVERGENT("差分宇宙", "divergent"),
    FIGHT("清体力（自定义关卡）", "fight"),
    UNIVERSE("模拟宇宙", "universe"),
    FORGOTTEN_HALL("忘却之庭", "forgottenhall"),
    PURE_FICTION("虚构叙事", "purefiction"),
    APOCALYPTIC("末日幻影", "apocalyptic"),
    GAME_UPDATE("游戏更新", "game_update"),
    NOTIFY("测试推送", "notify"),
    LIST("查看任务列表", "-l"),
}
