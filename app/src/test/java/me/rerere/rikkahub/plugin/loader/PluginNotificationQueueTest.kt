/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.loader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PluginNotificationQueueTest {

    @Test
    fun testFirstNotificationFiresImmediately() = runBlocking {
        val scheduler = PluginNotificationScheduler(minIntervalMs = 2000L, maxQueueSize = 10)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val shown = AtomicBoolean(false)

        val result = scheduler.schedule("plugin.a", scope) {
            shown.set(true)
            true
        }

        assertTrue("First notification should be accepted", result.accepted)
        assertEquals("First notification should have 0 delay", 0L, result.delayMs)
        
        // 稍作等待确保首条在 scope.launch 中完成执行
        delay(50L)
        assertTrue("Notification show callback should have been invoked", shown.get())
        assertEquals(0, scheduler.getPendingCount("plugin.a"))
        scope.cancel()
    }

    @Test
    fun testSubsequentNotificationsAreSpaced() = runBlocking {
        val scheduler = PluginNotificationScheduler(minIntervalMs = 500L, maxQueueSize = 10)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val r1 = scheduler.schedule("plugin.b", scope) { true }
        assertTrue(r1.accepted)
        assertEquals(0L, r1.delayMs)

        // 紧接着调度第二条，应该被平滑延时排队
        val r2 = scheduler.schedule("plugin.b", scope) { true }
        assertTrue("Second notification should be accepted into queue", r2.accepted)
        assertTrue("Second notification should be delayed", r2.delayMs > 0L)

        scope.cancel()
    }

    @Test
    fun testQueueCapacityLimitPreventsFlooding() = runBlocking {
        val scheduler = PluginNotificationScheduler(minIntervalMs = 10_000L, maxQueueSize = 3)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // 使用挂起模拟任务未结束，避免首条在后续入队前瞬间执行完成并递减计数
        val r1 = scheduler.schedule("plugin.flooder", scope) { delay(1000L); true }
        val r2 = scheduler.schedule("plugin.flooder", scope) { delay(1000L); true }
        val r3 = scheduler.schedule("plugin.flooder", scope) { delay(1000L); true }

        assertTrue(r1.accepted)
        assertTrue(r2.accepted)
        assertTrue(r3.accepted)
        assertEquals(3, scheduler.getPendingCount("plugin.flooder"))

        // 第 4 条超过容量上限，应被限流拒绝
        val r4 = scheduler.schedule("plugin.flooder", scope) { true }
        assertFalse("Notification exceeding maxQueueSize should be rejected", r4.accepted)
        assertEquals(-1L, r4.delayMs)

        scope.cancel()
    }

    @Test
    fun testPreReserveEliminatesRaceCondition() = runBlocking {
        val scheduler = PluginNotificationScheduler(minIntervalMs = 2000L, maxQueueSize = 5)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // 模拟在异步协程启动前，主调用线程同步预占名额
        assertFalse(scheduler.hasPending("plugin.race"))
        val reserved = scheduler.preReserve("plugin.race")
        assertTrue("Pre-reserve should succeed", reserved)

        // 此时协程即使完全没有启动，awaitPending 检查也不会误判为 0！
        assertTrue("hasPending should immediately be true after preReserve", scheduler.hasPending("plugin.race"))
        assertEquals(1, scheduler.getPendingCount("plugin.race"))

        val shown = AtomicBoolean(false)
        val result = scheduler.schedule("plugin.race", scope, alreadyReserved = true) {
            shown.set(true)
            true
        }

        assertTrue(result.accepted)
        // 验证不会因 alreadyReserved = true 导致计数变为 2
        assertEquals(1, result.pendingCount)

        delay(50L)
        assertTrue(shown.get())
        assertEquals(0, scheduler.getPendingCount("plugin.race"))

        scope.cancel()
    }

    @Test
    fun testPluginScopeCancellationPreventsOrphanExecution() = runBlocking {
        val scheduler = PluginNotificationScheduler(minIntervalMs = 1000L, maxQueueSize = 10)
        val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val secondNotificationShown = AtomicBoolean(false)

        // 首条立即触发
        scheduler.schedule("plugin.unload", pluginScope) { true }

        // 第二条需要延时 1000ms
        val r2 = scheduler.schedule("plugin.unload", pluginScope) {
            secondNotificationShown.set(true)
            true
        }
        assertTrue(r2.accepted)
        assertTrue(r2.delayMs >= 900L)

        // 模拟插件卸载：直接取消该插件的专属 pluginScope 并清理调度状态
        delay(100L)
        pluginScope.cancel()
        scheduler.clearPlugin("plugin.unload")

        assertEquals(0, scheduler.getPendingCount("plugin.unload"))

        // 等待超过 1000ms 的计划时间
        delay(1100L)
        assertFalse("Orphan coroutine must NOT fire after plugin unload/cancel", secondNotificationShown.get())
    }

    @Test
    fun testClearPluginResetsState() = runBlocking {
        val scheduler = PluginNotificationScheduler(minIntervalMs = 5000L, maxQueueSize = 5)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        scheduler.schedule("plugin.reset", scope) { true }
        scheduler.schedule("plugin.reset", scope) { true }

        assertTrue(scheduler.hasPending("plugin.reset"))

        scheduler.clearPlugin("plugin.reset")
        assertFalse(scheduler.hasPending("plugin.reset"))
        assertEquals(0, scheduler.getPendingCount("plugin.reset"))

        // 重新调度应该像新插件一样，首条 delay 为 0
        val freshResult = scheduler.schedule("plugin.reset", scope) { true }
        assertTrue(freshResult.accepted)
        assertEquals(0L, freshResult.delayMs)

        scope.cancel()
    }

    @Test
    fun testBatchNotificationsAccountingAndNoUnderflow() = runBlocking {
        val scheduler = PluginNotificationScheduler(minIntervalMs = 200L, maxQueueSize = 5)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // 模拟外部解析到 JsonArray，同步预占 1 个名额
        assertTrue(scheduler.preReserve("plugin.batch"))
        assertEquals(1, scheduler.getPendingCount("plugin.batch"))

        // 批量中的 3 个通知：仅首个消费 alreadyReserved = true，后续 2 个传入 false
        val executedCount = AtomicInteger(0)
        val r1 = scheduler.schedule("plugin.batch", scope, alreadyReserved = true) {
            delay(400L)
            executedCount.incrementAndGet()
            true
        }
        val r2 = scheduler.schedule("plugin.batch", scope, alreadyReserved = false) {
            delay(400L)
            executedCount.incrementAndGet()
            true
        }
        val r3 = scheduler.schedule("plugin.batch", scope, alreadyReserved = false) {
            delay(400L)
            executedCount.incrementAndGet()
            true
        }

        assertTrue(r1.accepted)
        assertTrue(r2.accepted)
        assertTrue(r3.accepted)
        // 验证计数器正确计入 3 个待发任务，没有发生漏记或下溢
        assertEquals(3, scheduler.getPendingCount("plugin.batch"))

        // 等待所有排队通知按间隔平滑送达并执行完成
        delay(1500L)
        assertEquals(3, executedCount.get())
        assertEquals(0, scheduler.getPendingCount("plugin.batch"))
        assertFalse(scheduler.hasPending("plugin.batch"))

        // 额外验证未消费预占时的归还逻辑
        assertTrue(scheduler.preReserve("plugin.batch"))
        assertEquals(1, scheduler.getPendingCount("plugin.batch"))
        scheduler.cancelReservation("plugin.batch")
        assertEquals(0, scheduler.getPendingCount("plugin.batch"))

        scope.cancel()
    }

    @Test
    fun testAwaitPendingWaitsForFullQueueDelivery() = runBlocking {
        val scheduler = PluginNotificationScheduler(minIntervalMs = 300L, maxQueueSize = 5)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // 调度 3 条排队通知：首条 0ms，第2条 300ms，第3条 600ms
        scheduler.schedule("plugin.await", scope) { true }
        scheduler.schedule("plugin.await", scope) { true }
        scheduler.schedule("plugin.await", scope) { true }

        assertTrue(scheduler.hasPending("plugin.await"))

        val start = System.currentTimeMillis()
        // 最大等待设置为充裕的 5 秒，但应在全部发送完（约 600ms）后立即退出循环返回
        scheduler.awaitPending(5000L)
        val elapsed = System.currentTimeMillis() - start

        assertFalse("All notifications should be delivered after awaitPending", scheduler.hasPending("plugin.await"))
        assertEquals(0, scheduler.getPendingCount("plugin.await"))
        assertTrue("awaitPending should wait at least until the last notification fires (~600ms)", elapsed >= 500L)
        assertTrue("awaitPending should exit early once queue is empty (< 2000ms), not wait for full timeout", elapsed < 2000L)

        scope.cancel()
    }
}
