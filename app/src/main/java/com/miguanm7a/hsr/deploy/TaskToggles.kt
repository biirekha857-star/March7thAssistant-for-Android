package com.miguanm7a.hsr.deploy

/** 开关分组（设置页用它分节显示）。 */
enum class ToggleGroup(val label: String) {
    REWARD("领取奖励"),
    DAILY("日常任务"),
    ACTIVITY("活动"),
    POWER("清体力 / 体力"),
    AUTOPLOT("自动剧情与战斗"),
    RUNTIME("运行行为"),
}

/**
 * 一个可配置的任务开关。
 *
 * `key` **必须与** `assets/config/config.example.yaml` 里的顶层键完全一致：
 * March7thAssistant 的 `Config._update_config()` 只覆盖已存在的键，
 * 未知键会被静默忽略（早期版本写错 `run_daily_time` 就一直没生效）。
 *
 * `defaultValue` 取自 `config.example.yaml` 的实测值，
 * 这样「没动过」的开关写回去等于原值，不会改变上游默认行为。
 */
data class TaskToggle(
    val key: String,
    val label: String,
    val description: String,
    val group: ToggleGroup,
    val defaultValue: Boolean,
)

object TaskToggles {

    val ALL: List<TaskToggle> = listOf(
        // ---------------- 领取奖励 ----------------
        TaskToggle(
            "reward_enable", "领取奖励（总开关）",
            "关闭后下面所有领取奖励都会跳过；对单独执行的任务无效，只影响完整运行/日常",
            ToggleGroup.REWARD, true,
        ),
        TaskToggle(
            "reward_dispatch_enable", "委托奖励", "领取派遣委托奖励",
            ToggleGroup.REWARD, true,
        ),
        TaskToggle(
            "reward_mail_enable", "邮件奖励", "领取邮件附件",
            ToggleGroup.REWARD, true,
        ),
        TaskToggle(
            "reward_assist_enable", "支援奖励", "领取支援奖励",
            ToggleGroup.REWARD, true,
        ),
        TaskToggle(
            "reward_quest_enable", "每日实训奖励", "领取每日实训达成奖励",
            ToggleGroup.REWARD, true,
        ),
        TaskToggle(
            "reward_srpass_enable", "无名勋礼", "领取无名勋礼奖励",
            ToggleGroup.REWARD, true,
        ),
        TaskToggle(
            "reward_redemption_code_enable", "兑换码", "自动领取兑换码奖励",
            ToggleGroup.REWARD, true,
        ),
        TaskToggle(
            "reward_achievement_enable", "成就奖励", "领取成就奖励（默认关闭）",
            ToggleGroup.REWARD, false,
        ),
        TaskToggle(
            "reward_message_enable", "短信奖励", "领取短信奖励（默认关闭）",
            ToggleGroup.REWARD, false,
        ),

        // ---------------- 日常任务 ----------------
        TaskToggle(
            "daily_enable", "日常任务", "每日实训（500 活跃度那套）",
            ToggleGroup.DAILY, true,
        ),
        TaskToggle(
            "daily_material_enable", "用「合成材料」完成",
            "通过合成材料完成每日实训",
            ToggleGroup.DAILY, true,
        ),
        TaskToggle(
            "daily_himeko_try_enable", "用「姬子试用」完成",
            "通过姬子试用完成每日实训（默认关闭）",
            ToggleGroup.DAILY, false,
        ),
        TaskToggle(
            "daily_memory_one_enable", "用「回忆一」完成",
            "通过回忆一完成每日实训（默认关闭，需配置队伍）",
            ToggleGroup.DAILY, false,
        ),

        // ---------------- 活动 ----------------
        TaskToggle(
            "activity_enable", "活动任务", "执行限时活动相关任务",
            ToggleGroup.ACTIVITY, true,
        ),
        TaskToggle(
            "activity_dailycheckin_enable", "每日签到", "活动每日签到",
            ToggleGroup.ACTIVITY, true,
        ),

        // ---------------- 清体力 ----------------
        TaskToggle(
            "power_enable", "清体力（总开关）",
            "关闭后完整运行会跳过清体力；单独执行「清体力」任务不受影响",
            ToggleGroup.POWER, true,
        ),
        TaskToggle(
            "echo_of_war_enable", "历战余响",
            "体力优先完成 3 次「历战余响」（默认关闭）",
            ToggleGroup.POWER, false,
        ),
        TaskToggle(
            "use_reserved_trailblaze_power", "使用后备开拓力",
            "允许消耗后备开拓力（默认关闭）",
            ToggleGroup.POWER, false,
        ),
        TaskToggle(
            "use_fuel", "使用燃料",
            "允许消耗燃料补充体力（默认关闭）",
            ToggleGroup.POWER, false,
        ),
        TaskToggle(
            "merge_immersifier", "优先合成沉浸器",
            "先合成沉浸器再打副本（默认关闭）",
            ToggleGroup.POWER, false,
        ),
        TaskToggle(
            "borrow_enable", "使用支援角色",
            "打副本时允许借用好友支援角色",
            ToggleGroup.POWER, true,
        ),
        TaskToggle(
            "instance_team_enable", "自动切换队伍",
            "打副本前自动切换到指定队伍（默认关闭，需在 config 里配队伍编号）",
            ToggleGroup.POWER, false,
        ),

        // ---------------- 自动剧情与战斗 ----------------
        TaskToggle(
            "autoplot_skip_enable", "自动跳过对话", "出现跳过按钮时自动点击",
            ToggleGroup.AUTOPLOT, true,
        ),
        TaskToggle(
            "autoplot_click_enable", "自动选择对话选项", "按素材选择剧情选项",
            ToggleGroup.AUTOPLOT, true,
        ),
        TaskToggle(
            "autoplot_battle_detect_enable", "自动战斗检测",
            "检测到未自动战斗时按 V 开启",
            ToggleGroup.AUTOPLOT, true,
        ),
        TaskToggle(
            "autoplot_phone_detect_enable", "自动处理短信页面", "自动处理游戏内短信界面",
            ToggleGroup.AUTOPLOT, true,
        ),

        // ---------------- 运行行为 ----------------
        TaskToggle(
            "check_update", "启动时检查更新", "检查 March7thAssistant 自身更新",
            ToggleGroup.RUNTIME, true,
        ),
        TaskToggle(
            "pause_after_success", "成功后暂停", "成功且非循环执行时暂停程序",
            ToggleGroup.RUNTIME, true,
        ),
        TaskToggle(
            "exit_after_failure", "失败后直接退出", "失败时直接退出而不是暂停",
            ToggleGroup.RUNTIME, false,
        ),
    )

    private val BY_KEY = ALL.associateBy { it.key }

    fun byKey(key: String): TaskToggle? = BY_KEY[key]

    /** 全部键的默认值。 */
    fun defaults(): Map<String, Boolean> = ALL.associate { it.key to it.defaultValue }
}
