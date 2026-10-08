package com.miguanm7a.hsr.deploy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.deployDataStore: DataStore<Preferences> by preferencesDataStore("m7a_deploy")

/** 部署与任务相关的全部设置项持久化。 */
class DeploySettings(private val context: Context) {

    private object Keys {
        val TERMUX_MIRROR = stringPreferencesKey("termux_mirror")
        val ROOTFS_SOURCE = stringPreferencesKey("rootfs_source")
        val CONTAINER_ALIAS = stringPreferencesKey("container_alias")
        val TASK = stringPreferencesKey("task")
        val AFTER_FINISH = stringPreferencesKey("after_finish")
        val LOG_LEVEL = stringPreferencesKey("log_level")
        val RUN_DAILY_TIME = stringPreferencesKey("run_daily_time")
        val LOOP_MODE = stringPreferencesKey("loop_mode")
        val MONITOR_PORT = stringPreferencesKey("monitor_port")
        val USE_PAID_TIME = booleanPreferencesKey("use_paid_time")
        val DEPLOYED = booleanPreferencesKey("deployed")
        val ACCENT_THEME = stringPreferencesKey("accent_theme")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
    }

    private fun read(key: Preferences.Key<String>, def: String): String = runBlocking {
        context.deployDataStore.data.first()[key] ?: def
    }

    private fun write(key: Preferences.Key<String>, value: String) = runBlocking {
        context.deployDataStore.edit { it[key] = value }
    }

    private fun readBool(key: Preferences.Key<Boolean>, def: Boolean): Boolean = runBlocking {
        context.deployDataStore.data.first()[key] ?: def
    }

    private fun writeBool(key: Preferences.Key<Boolean>, value: Boolean) = runBlocking {
        context.deployDataStore.edit { it[key] = value }
    }

    // --- Termux 软件源 ---
    fun termuxMirror(): TermuxMirror =
        TermuxMirror.entries.firstOrNull { it.name == read(Keys.TERMUX_MIRROR, TermuxMirror.TUNA.name) }
            ?: TermuxMirror.TUNA

    fun setTermuxMirror(v: TermuxMirror) = write(Keys.TERMUX_MIRROR, v.name)

    // --- 容器来源 ---
    fun rootfsSource(): RootfsSource =
        RootfsSource.entries.firstOrNull { it.name == read(Keys.ROOTFS_SOURCE, RootfsSource.BUNDLED.name) }
            ?: RootfsSource.BUNDLED

    fun setRootfsSource(v: RootfsSource) = write(Keys.ROOTFS_SOURCE, v.name)

    // --- 容器别名 ---
    fun containerAlias(): String = read(Keys.CONTAINER_ALIAS, "m7a")

    fun setContainerAlias(v: String) = write(Keys.CONTAINER_ALIAS, v.ifBlank { "m7a" })

    // --- 任务 ---
    fun task(): M7aTask =
        M7aTask.entries.firstOrNull { it.name == read(Keys.TASK, M7aTask.FULL.name) } ?: M7aTask.FULL

    fun setTask(v: M7aTask) = write(Keys.TASK, v.name)

    // --- 完成后行为 ---
    fun afterFinish(): AfterFinish =
        AfterFinish.entries.firstOrNull { it.name == read(Keys.AFTER_FINISH, AfterFinish.EXIT.name) }
            ?: AfterFinish.EXIT

    fun setAfterFinish(v: AfterFinish) = write(Keys.AFTER_FINISH, v.name)

    // --- 日志级别 ---
    fun logLevel(): String = read(Keys.LOG_LEVEL, "DEBUG")

    fun setLogLevel(v: String) = write(Keys.LOG_LEVEL, v)

    // --- 每日运行时间 ---
    //
    // 注意：上游**没有** `run_daily_time` 这个键（我早期版本写错了，等于没生效）。
    // 真正的键是 `loop_mode` + `scheduled_time`，见 ConfigPatcher 的说明。
    fun runDailyTime(): String = read(Keys.RUN_DAILY_TIME, "04:00")

    fun setRunDailyTime(v: String) = write(Keys.RUN_DAILY_TIME, v)

    /** `loop_mode`：`scheduled`（定时）或 `power`（按体力计划循环）。 */
    fun loopMode(): String = read(Keys.LOOP_MODE, "scheduled")

    fun setLoopMode(v: String) = write(Keys.LOOP_MODE, v)

    /**
     * 浏览器调试端口（上游 `browser_debug_port`）。
     *
     * 实时监看要靠它连 CDP。注意上游在该端口被占用时会**递增**找空闲端口，
     * 所以监看侧是按范围探测的，不是只试这一个值。
     */
    fun monitorPort(): Int =
        read(Keys.MONITOR_PORT, "9222").toIntOrNull()?.coerceIn(1, 65535) ?: 9222

    fun setMonitorPort(v: Int) = write(Keys.MONITOR_PORT, v.coerceIn(1, 65535).toString())

    // --- 任务开关（config.yaml 里的 *_enable 等）---

    private fun toggleKey(key: String) = booleanPreferencesKey("toggle_$key")

    /** 读取某个开关；未设置过时回落到上游默认值。 */
    fun toggle(key: String): Boolean {
        val def = TaskToggles.byKey(key)?.defaultValue ?: false
        return readBool(toggleKey(key), def)
    }

    fun setToggle(key: String, value: Boolean) = writeBool(toggleKey(key), value)

    /** 全部开关的当前值。 */
    fun allToggles(): Map<String, Boolean> =
        TaskToggles.ALL.associate { it.key to toggle(it.key) }

    // --- 是否使用付费时长 ---
    fun usePaidTime(): Boolean = readBool(Keys.USE_PAID_TIME, false)

    fun setUsePaidTime(v: Boolean) = writeBool(Keys.USE_PAID_TIME, v)

    // --- 是否已部署 ---
    fun isDeployed(): Boolean = readBool(Keys.DEPLOYED, false)

    fun setDeployed(v: Boolean) = writeBool(Keys.DEPLOYED, v)

    /** 当前选择的是否为内置离线镜像。 */
    fun isBundled(): Boolean = rootfsSource() == RootfsSource.BUNDLED

    // --- 外观 ---
    fun accentTheme(): String = read(Keys.ACCENT_THEME, "MARCH7")
    fun setAccentTheme(v: String) = write(Keys.ACCENT_THEME, v)

    fun themeMode(): String = read(Keys.THEME_MODE, "SYSTEM")
    fun setThemeMode(v: String) = write(Keys.THEME_MODE, v)

    fun autoStartOnBoot(): Boolean = readBool(Keys.AUTO_START_ON_BOOT, false)
    fun setAutoStartOnBoot(v: Boolean) = writeBool(Keys.AUTO_START_ON_BOOT, v)
}
