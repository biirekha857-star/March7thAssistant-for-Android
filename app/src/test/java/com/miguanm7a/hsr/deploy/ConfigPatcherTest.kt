package com.miguanm7a.hsr.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ConfigPatcher 回归测试。
 *
 * 核心不变式：**只改我们管的顶层键，其它内容一个字节都不能动**。
 *
 * 背景：March7thAssistant 会把整份合并配置 dump 回 config.yaml，
 * 里面有大量状态字段（`*_timestamp`、`daily_tasks`、`power_plan` 等）。
 * 整份覆盖会把这些状态清零，导致程序重复执行「每天/每周一次」的任务。
 */
class ConfigPatcherTest {

    /** 模拟程序运行过一轮之后、包含状态字段的真实 config.yaml 片段。 */
    private val realWorldConfig = """
        locales: zh_CN
        log_level: INFO # 日志等级
        after_finish: None # 任务完成后的操作
        power_enable: true # 是否启用清体力总开关
        echo_of_war_timestamp: 1730000000 # 上次完成历战余响的时间戳
        echo_of_war_enable: false
        weekly_relic_cleanup_timestamp: 1730000001
        last_run_timestamp: 1730000002
        daily_tasks: []
        power_plan:
        - stage: 拟造花萼（金）
          times: 6
        reward_enable: true
        check_update: true
        loop_mode: scheduled
        scheduled_time: 4:00
    """.trimIndent() + "\n"

    @Test
    fun `updates only the requested keys`() {
        val out = ConfigPatcher.upsertTopLevel(
            realWorldConfig,
            mapOf("power_enable" to "false", "check_update" to "false"),
        )

        assertTrue("power_enable 应被改为 false", out.contains("power_enable: false"))
        assertTrue("check_update 应被改为 false", out.contains("check_update: false"))
        assertFalse("不应残留 power_enable: true", out.contains("power_enable: true"))
    }

    /** 这是最重要的断言：状态字段必须原样保留。 */
    @Test
    fun `state fields and nested structures are preserved`() {
        val out = ConfigPatcher.upsertTopLevel(
            realWorldConfig,
            mapOf("reward_enable" to "false"),
        )

        assertTrue(out.contains("echo_of_war_timestamp: 1730000000"))
        assertTrue(out.contains("weekly_relic_cleanup_timestamp: 1730000001"))
        assertTrue(out.contains("last_run_timestamp: 1730000002"))
        assertTrue(out.contains("daily_tasks: []"))
        // 多行嵌套（power_plan）必须完整保留
        assertTrue(out.contains("power_plan:"))
        assertTrue(out.contains("- stage: 拟造花萼（金）"))
        assertTrue(out.contains("times: 6"))
        // 行尾注释尽量保留
        assertTrue("注释应保留", out.contains("# 是否启用清体力总开关"))
    }

    @Test
    fun `missing keys are appended`() {
        val out = ConfigPatcher.upsertTopLevel(
            "log_level: INFO\n",
            mapOf("exit_after_failure" to "true", "use_fuel" to "true"),
        )
        assertTrue(out.contains("exit_after_failure: true"))
        assertTrue(out.contains("use_fuel: true"))
        assertTrue("原有键不变", out.contains("log_level: INFO"))
        assertTrue("应以换行结尾", out.endsWith("\n"))
    }

    /** 嵌套里的同名键**不能**被误改（只匹配不缩进的顶层键）。 */
    @Test
    fun `indented keys with same name are untouched`() {
        val yaml = """
            power_enable: true
            some_section:
              power_enable: true
              nested:
                power_enable: true
        """.trimIndent() + "\n"

        val out = ConfigPatcher.upsertTopLevel(yaml, mapOf("power_enable" to "false"))

        val occurrences = Regex("power_enable:").findAll(out).count()
        assertEquals("应仍有 3 处 power_enable", 3, occurrences)
        assertTrue("顶层被改为 false", out.contains("\npower_enable: false") ||
            out.startsWith("power_enable: false"))
        assertTrue("缩进的 2 处保持 true",
            out.lines().count { it.trim() == "power_enable: true" } == 2)
    }

    /** 前缀相近的键名不能被误伤。 */
    @Test
    fun `similar key names are not confused`() {
        val yaml = """
            reward_enable: true
            reward_enable_extra: true
            reward_enableX: true
        """.trimIndent() + "\n"

        val out = ConfigPatcher.upsertTopLevel(yaml, mapOf("reward_enable" to "false"))

        assertTrue(out.contains("reward_enable: false"))
        assertTrue("reward_enable_extra 不应被改", out.contains("reward_enable_extra: true"))
        assertTrue("reward_enableX 不应被改", out.contains("reward_enableX: true"))
    }

    @Test
    fun `empty input produces just the keys`() {
        val out = ConfigPatcher.upsertTopLevel("", mapOf("a" to "true", "b" to "\"x\""))
        assertTrue(out.contains("a: true"))
        assertTrue(out.contains("b: \"x\""))
    }

    @Test
    fun `no values means unchanged`() {
        assertEquals(realWorldConfig, ConfigPatcher.upsertTopLevel(realWorldConfig, emptyMap()))
    }

    /** 写入的键全部来自配置模板 / 上游真实键名。 */
    @Test
    fun `every toggle key matches upstream naming`() {
        // 上游 config.example.yaml 里的键只含小写字母、数字和下划线
        val re = Regex("^[a-z0-9_]+$")
        for (t in TaskToggles.ALL) {
            assertTrue("键名格式异常: ${t.key}", re.matches(t.key))
        }
        // 不应有重复键
        val dup = TaskToggles.ALL.groupBy { it.key }.filterValues { it.size > 1 }.keys
        assertTrue("存在重复键: $dup", dup.isEmpty())
        assertTrue("开关数量应在合理范围", TaskToggles.ALL.size >= 20)
    }

    /** 应用全部开关的默认值应是幂等的（写回等于原值）。 */
    @Test
    fun `applying defaults is a no-op on values`() {
        val yaml = TaskToggles.ALL.joinToString("\n") { "${it.key}: ${it.defaultValue}" } + "\n"
        val out = ConfigPatcher.upsertTopLevel(
            yaml,
            TaskToggles.defaults().mapValues { (_, v) -> v.toString() },
        )
        assertEquals(yaml, out)
    }
}
