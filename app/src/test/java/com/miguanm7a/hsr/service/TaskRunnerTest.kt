package com.miguanm7a.hsr.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TaskRunner 的纯逻辑测试。
 *
 * 任务进程本身需要 Android Context，没法在 JVM 单测里跑；
 * 但「任务归谁所有」这个不变式值得写清楚：
 * Snapshot 是值语义，重建观察者不会影响正在跑的任务。
 */
class TaskRunnerTest {

    @Test
    fun `formatDuration seconds`() {
        assertEquals("0 秒", TaskRunner.formatDuration(0))
        assertEquals("5 秒", TaskRunner.formatDuration(5_000))
        assertEquals("59 秒", TaskRunner.formatDuration(59_000))
    }

    @Test
    fun `formatDuration minutes`() {
        assertEquals("1 分 0 秒", TaskRunner.formatDuration(60_000))
        assertEquals("5 分 30 秒", TaskRunner.formatDuration(330_000))
        assertEquals("59 分 59 秒", TaskRunner.formatDuration(3_599_000))
    }

    @Test
    fun `formatDuration hours`() {
        assertEquals("1 小时 0 分", TaskRunner.formatDuration(3_600_000))
        assertEquals("2 小时 30 分", TaskRunner.formatDuration(9_000_000))
    }

    /** 负值（时钟回拨等异常）不应产生奇怪输出。 */
    @Test
    fun `formatDuration handles negative`() {
        assertEquals("0 秒", TaskRunner.formatDuration(-1))
        assertEquals("0 秒", TaskRunner.formatDuration(-100_000))
    }

    @Test
    fun `snapshot elapsed text empty when not running`() {
        val idle = TaskRunner.Snapshot()
        assertEquals("", idle.elapsedText())
        assertFalse(idle.running)
    }

    @Test
    fun `snapshot elapsed text reflects elapsed time`() {
        val snap = TaskRunner.Snapshot(running = true, label = "任务", startedAt = 1_000_000)
        // 传入固定的 now，保证可重复
        assertEquals("2 分 0 秒", snap.elapsedText(now = 1_000_000 + 120_000))
    }

    /** 未运行时没有「已运行多久」的概念。 */
    @Test
    fun `stopped snapshot has no elapsed text`() {
        val snap = TaskRunner.Snapshot(running = false, startedAt = 1_000_000)
        assertEquals("", snap.elapsedText(now = 2_000_000))
    }

    /**
     * 重要不变式：ViewModel 不再拥有任务进程。
     *
     * 这条通过反射检查源码级契约意义不大，这里改为断言 Snapshot 是纯数据
     * （可自由复制/传递），真正的所有权由 TaskRunner 单例持有。
     */
    @Test
    fun `snapshot is a plain value type`() {
        val a = TaskRunner.Snapshot(running = true, label = "x", startedAt = 5)
        val b = a.copy(label = "y")
        assertEquals("x", a.label)
        assertEquals("y", b.label)
        assertTrue(a.running)
    }
}
