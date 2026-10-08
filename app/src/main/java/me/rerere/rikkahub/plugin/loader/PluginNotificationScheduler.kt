/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.loader

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 插件通知平滑排队与频控调度器。
 *
 * 核心特性：
 * 1. 同步预占计数：排队判定与计数递增支持在主线程/调用线程同步预占，杜绝后台任务因协程调度延迟导致的竞态误退出；
 * 2. 插件生命周期绑定：延时任务绑定至各插件专属的 CoroutineScope，插件卸载时自动取消所有未发出协程；
 * 3. 插件隔离：各插件独立维护最早允许时间戳与排队上限；
 * 4. 解耦易测：纯 Kotlin 逻辑组件，便于编写高保真单元测试。
 */
class PluginNotificationScheduler(
    val minIntervalMs: Long = 2_000L,
    val maxQueueSize: Int = 10,
) {
    companion object {
        private const val TAG = "PluginNotificationSched"
    }

    private val lock = Any()
    private val nextNotificationAtByPlugin = mutableMapOf<String, Long>()
    private val pendingCountByPlugin = mutableMapOf<String, Int>()

    data class ScheduleResult(
        val accepted: Boolean,
        val delayMs: Long,
        val pendingCount: Int,
    )

    private fun logW(tag: String, msg: String) {
        try {
            android.util.Log.w(tag, msg)
        } catch (_: Throwable) {
            // JVM 单元测试环境下 android.util.Log 默认未被 mock，优雅回退
        }
    }

    /**
     * 同步预占一个通知名额。
     * 在异步任务启动前调用，确保 awaitPending 不会因调度延迟误判计数为 0。
     * @return true 若成功预占名额；false 若队列已满被拒绝
     */
    fun preReserve(pluginId: String): Boolean {
        synchronized(lock) {
            val pending = pendingCountByPlugin[pluginId] ?: 0
            if (pending >= maxQueueSize) {
                logW(TAG, "Notification queue full for plugin=$pluginId ($pending >= $maxQueueSize), preReserve rejected")
                return false
            }
            pendingCountByPlugin[pluginId] = pending + 1
            return true
        }
    }

    /**
     * 取消之前的预占（例如参数校验失败、权限未通过时恢复计数）。
     */
    fun cancelReservation(pluginId: String) {
        synchronized(lock) {
            decrementPending(pluginId)
        }
    }

    /**
     * 调度通知发送。
     *
     * @param pluginId 插件ID
     * @param scope 该插件绑定的 CoroutineScope（延时协程将在此 scope 运行，插件卸载时自动取消）
     * @param alreadyReserved 是否已经在调用本方法前通过 preReserve 预占了名额
     * @param onShow 实际发送通知的动作
     * @return ScheduleResult 调度结果
     */
    fun schedule(
        pluginId: String,
        scope: CoroutineScope,
        alreadyReserved: Boolean = false,
        onShow: suspend () -> Boolean,
    ): ScheduleResult {
        val now = System.currentTimeMillis()
        val delayMs: Long
        val currentPending: Int

        synchronized(lock) {
            val pending = pendingCountByPlugin[pluginId] ?: 0
            if (!alreadyReserved) {
                if (pending >= maxQueueSize) {
                    logW(TAG, "Notification queue full for plugin=$pluginId ($pending >= $maxQueueSize), dropping notification")
                    return ScheduleResult(accepted = false, delayMs = -1L, pendingCount = pending)
                }
                currentPending = pending + 1
                pendingCountByPlugin[pluginId] = currentPending
            } else {
                currentPending = pending
            }

            val earliestAllowed = nextNotificationAtByPlugin[pluginId] ?: 0L
            val scheduledTime = maxOf(now, earliestAllowed)
            nextNotificationAtByPlugin[pluginId] = scheduledTime + minIntervalMs
            delayMs = scheduledTime - now
        }

        if (delayMs <= 0L) {
            // 首条通知：直接在当前协程执行，确保立刻送达系统通知栏
            scope.launch {
                try {
                    onShow()
                } finally {
                    synchronized(lock) {
                        decrementPending(pluginId)
                    }
                }
            }
        } else {
            // 后续通知：在插件专属 scope 中延时平滑触发
            scope.launch {
                try {
                    delay(delayMs)
                    onShow()
                } finally {
                    synchronized(lock) {
                        decrementPending(pluginId)
                    }
                }
            }
        }

        return ScheduleResult(accepted = true, delayMs = delayMs, pendingCount = currentPending)
    }

    private fun decrementPending(pluginId: String) {
        val current = pendingCountByPlugin[pluginId] ?: 1
        if (current <= 1) {
            pendingCountByPlugin.remove(pluginId)
        } else {
            pendingCountByPlugin[pluginId] = current - 1
        }
    }

    /**
     * 获取指定插件当前的待处理通知数。
     */
    fun getPendingCount(pluginId: String): Int = synchronized(lock) {
        pendingCountByPlugin[pluginId] ?: 0
    }

    /**
     * 检查是否存在未完成的待处理通知。
     */
    fun hasPending(pluginId: String? = null): Boolean = synchronized(lock) {
        if (pluginId != null) {
            (pendingCountByPlugin[pluginId] ?: 0) > 0
        } else {
            pendingCountByPlugin.values.any { it > 0 }
        }
    }

    /**
     * 挂起等待所有待处理通知投递完成。
     *
     * 默认最大超时设置为 25 秒，以满足最大队列深度 10 条按 2 秒平滑排队发出所需的耗时（约 18~20 秒）。
     * 若队列提前清空，将立即退出循环返回，绝不会空耗等待。
     */
    suspend fun awaitPending(timeoutMs: Long = 25_000L) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (!hasPending()) break
            delay(100L)
        }
    }

    /**
     * 插件卸载或重载时，清理其所有的频控与排队记录。
     */
    fun clearPlugin(pluginId: String) {
        synchronized(lock) {
            nextNotificationAtByPlugin.remove(pluginId)
            pendingCountByPlugin.remove(pluginId)
        }
    }
}
