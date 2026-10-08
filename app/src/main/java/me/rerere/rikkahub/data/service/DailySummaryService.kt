/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.PLUGIN_CRON_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.loader.PluginScheduledHook
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.service.CronExpressionParser
import org.koin.core.context.GlobalContext
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * 插件定时任务调度服务。
 *
 * 继续沿用原 DailySummaryService 名称和 Android 组件，避免破坏旧配置；
 * 实际调度已升级为按每个插件 hook.schedule 计算下一次触发时间。
 * 宿主只维护一个 AlarmManager 闹钟，每次指向所有插件计划中的最近一次任务。
 */
class DailySummaryService {

    companion object {
        const val TAG = "DailySummaryService"
        const val ACTION_DAILY_CRON = "me.rerere.orangechat.DAILY_CRON"
        const val REQUEST_CODE = 10003
        private const val INIT_TIMEOUT_MS = 25_000L

        private const val PREFS_NAME = "daily_cron_prefs"
        private const val KEY_NEXT_TRIGGER_TIME = "next_trigger_time"
        private const val KEY_ENABLED = "enabled"

        const val EXTRA_PLUGIN_IDS = "plugin_ids"
        const val EXTRA_HANDLERS = "handlers"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
        const val EXTRA_RETRY_COUNT = "retry_count"

        private data class Candidate(
            val hook: PluginScheduledHook,
            val triggerAtMillis: Long,
        )

        /**
         * 从当前已加载插件重新计算整个调度表。
         */
        fun rescheduleIfEnabled(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                rescheduleNow(context)
            }
        }

        /**
         * 立即重新计算下一次插件任务。供 TriggerService 在执行完成后或插件配置变动时调用。
         * 必须等待 PluginManager 完成冷启动初始化，防止过早查询导致空 hook 从而误取消闹钟。
         */
        suspend fun rescheduleNow(context: Context) {
            try {
                val pluginManager = GlobalContext.get().getOrNull<PluginManager>()
                val pluginLoader = GlobalContext.get().getOrNull<PluginLoader>()

                if (pluginManager == null || pluginLoader == null) {
                    SchedulerDiagnostics.log(
                        "PluginCron",
                        "PluginManager or PluginLoader not available, keeping existing schedule without cancel",
                    )
                    return
                }

                // 显式等待插件管理器初始化完成（冷启动/被杀后恢复时必经流程）
                val initStart = System.currentTimeMillis()
                val initResult = withTimeoutOrNull(INIT_TIMEOUT_MS) {
                    pluginManager.awaitInitialization()
                }
                val initDuration = System.currentTimeMillis() - initStart

                if (initResult == null) {
                    SchedulerDiagnostics.log(
                        "PluginCron",
                        "PluginManager initialization timed out after ${initDuration}ms during rescheduleNow. Keeping existing schedule to avoid accidental cancellation.",
                    )
                    return
                }
                if (initResult.isFailure) {
                    SchedulerDiagnostics.log(
                        "PluginCron",
                        "PluginManager initialization failed during rescheduleNow: ${initResult.exceptionOrNull()?.message}. Keeping existing schedule to avoid accidental cancellation.",
                    )
                    return
                }

                val hooks = pluginLoader.getScheduledHooks()
                if (hooks.isEmpty()) {
                    cancel(context)
                    SchedulerDiagnostics.log(
                        "PluginCron",
                        "No scheduled plugin hooks found after initialization. Cancelled plugin cron alarm.",
                    )
                    return
                }

                val nextBatch = computeNextBatch(hooks)
                if (nextBatch == null) {
                    SchedulerDiagnostics.log(
                        "PluginCron",
                        "No valid future plugin cron execution could be calculated for ${hooks.size} hooks. Keeping existing schedule.",
                    )
                    return
                }

                scheduleBatch(
                    context = context,
                    triggerTime = nextBatch.first,
                    hooks = nextBatch.second,
                )
            } catch (e: Exception) {
                SchedulerDiagnostics.log("PluginCron", "Failed to reschedule plugin cron", e)
            }
        }

        private fun computeNextBatch(
            hooks: List<PluginScheduledHook>,
            basis: ZonedDateTime = ZonedDateTime.now(),
        ): Pair<Long, List<PluginScheduledHook>>? {
            val candidates = hooks.mapNotNull { hook ->
                val cron = CronExpressionParser.parse(hook.schedule).getOrElse { error ->
                    Log.w(
                        TAG,
                        "Invalid plugin cron '${hook.schedule}' for ${hook.pluginId}.${hook.handler}: ${error.message}"
                    )
                    return@mapNotNull null
                }
                val next = CronExpressionParser.nextExecution(cron, basis)
                if (next == null) {
                    Log.w(TAG, "Plugin cron has no future execution: ${hook.pluginId}.${hook.handler}")
                    null
                } else {
                    Candidate(
                        hook = hook,
                        triggerAtMillis = next.toInstant().toEpochMilli(),
                    )
                }
            }

            if (candidates.isEmpty()) return null

            val earliest = candidates.minOf { it.triggerAtMillis }
            // 5-field cron 的精度为分钟；这里留 1 秒容差，把同一时刻的任务合并到一个闹钟。
            val dueHooks = candidates
                .filter { abs(it.triggerAtMillis - earliest) <= 1_000L }
                .map { it.hook }

            return earliest to dueHooks
        }

        private fun scheduleBatch(
            context: Context,
            triggerTime: Long,
            hooks: List<PluginScheduledHook>,
        ) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_NEXT_TRIGGER_TIME, triggerTime)
                .putBoolean(KEY_ENABLED, true)
                .apply()

            val pluginIds = ArrayList(hooks.map { it.pluginId })
            val handlers = ArrayList(hooks.map { it.handler })

            val intent = Intent(context, DailySummaryReceiver::class.java).apply {
                action = ACTION_DAILY_CRON
                putStringArrayListExtra(EXTRA_PLUGIN_IDS, pluginIds)
                putStringArrayListExtra(EXTRA_HANDLERS, handlers)
                putExtra(EXTRA_SCHEDULED_AT, triggerTime)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val isExact: Boolean
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms() -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent,
                    )
                    isExact = true
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent,
                    )
                    isExact = false
                    Log.w(TAG, "Exact alarm permission not granted, using inexact plugin cron alarm")
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerTime,
                        pendingIntent,
                    )
                    isExact = true
                }
                else -> {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
                    isExact = true
                }
            }

            val hookDescs = hooks.joinToString { "${it.pluginId}.${it.handler} [${it.schedule}]" }
            SchedulerDiagnostics.logSchedule(
                target = "PluginCron(${hooks.size} hooks: $hookDescs)",
                triggerAtMillis = triggerTime,
                exact = isExact,
            )
        }

        fun cancel(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_NEXT_TRIGGER_TIME)
                .putBoolean(KEY_ENABLED, false)
                .apply()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, DailySummaryReceiver::class.java).apply {
                action = ACTION_DAILY_CRON
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pendingIntent?.let {
                alarmManager.cancel(it)
                it.cancel()
                SchedulerDiagnostics.log("PluginCron", "Cancelled plugin cron alarm in AlarmManager")
            }
        }

        fun getNextTriggerTime(context: Context): Long? {
            val triggerTime = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_NEXT_TRIGGER_TIME, 0L)
            return triggerTime.takeIf { it > 0L }
        }

        fun isEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
        }

        /**
         * 手动触发：不指定目标 hook 时，TriggerService 会执行全部已声明定时 hook。
         */
        fun triggerNow(context: Context) {
            val serviceIntent = Intent(context, DailySummaryTriggerService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}

class DailySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduledAt = intent.getLongExtra(DailySummaryService.EXTRA_SCHEDULED_AT, 0L)
        val retryCount = intent.getIntExtra(DailySummaryService.EXTRA_RETRY_COUNT, 0)
        SchedulerDiagnostics.logAlarmReceived("PluginCronReceiver", scheduledAt)
        when (intent.action) {
            DailySummaryService.ACTION_DAILY_CRON -> {
                val serviceIntent = Intent(context, DailySummaryTriggerService::class.java).apply {
                    putStringArrayListExtra(
                        DailySummaryService.EXTRA_PLUGIN_IDS,
                        intent.getStringArrayListExtra(DailySummaryService.EXTRA_PLUGIN_IDS),
                    )
                    putStringArrayListExtra(
                        DailySummaryService.EXTRA_HANDLERS,
                        intent.getStringArrayListExtra(DailySummaryService.EXTRA_HANDLERS),
                    )
                    putExtra(
                        DailySummaryService.EXTRA_SCHEDULED_AT,
                        scheduledAt,
                    )
                    putExtra(
                        DailySummaryService.EXTRA_RETRY_COUNT,
                        retryCount,
                    )
                }
                context.startForegroundService(serviceIntent)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                SchedulerDiagnostics.log("PluginCron", "Boot completed, rebuilding plugin cron schedule")
                DailySummaryService.rescheduleIfEnabled(context)
            }
        }
    }
}

/**
 * 插件定时任务执行服务。
 * Alarm 中携带本次真正到期的 pluginId/handler，只执行这些任务；完成后重新计算下一次计划。
 */
class DailySummaryTriggerService : Service() {

    companion object {
        private const val TAG = "DailySummaryTrigger"
        private const val NOTIFICATION_ID = 20003
        private const val INIT_TIMEOUT_MS = 25_000L
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SchedulerDiagnostics.log("PluginCronTrigger", "DailySummaryTriggerService started")

        val notification = NotificationCompat.Builder(this, PLUGIN_CRON_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("正在执行插件定时任务...")
            .setSmallIcon(R.drawable.small_icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        val requestedPluginIds = intent
            ?.getStringArrayListExtra(DailySummaryService.EXTRA_PLUGIN_IDS)
            .orEmpty()
        val requestedHandlers = intent
            ?.getStringArrayListExtra(DailySummaryService.EXTRA_HANDLERS)
            .orEmpty()
        val scheduledAt = intent?.getLongExtra(DailySummaryService.EXTRA_SCHEDULED_AT, 0L) ?: 0L
        val retryCount = intent?.getIntExtra(DailySummaryService.EXTRA_RETRY_COUNT, 0) ?: 0

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pluginManager = GlobalContext.get().getOrNull<PluginManager>()
                val pluginLoader = GlobalContext.get().getOrNull<PluginLoader>()

                if (pluginManager == null || pluginLoader == null) {
                    SchedulerDiagnostics.log("PluginCronTrigger", "PluginManager or PluginLoader not available, skipping execution")
                    return@launch
                }

                // 核心修复：等待插件管理器初始化完成（冷启动时避免空 hook 竞态）
                val initStart = System.currentTimeMillis()
                val initResult = withTimeoutOrNull(INIT_TIMEOUT_MS) {
                    pluginManager.awaitInitialization()
                }
                val initDuration = System.currentTimeMillis() - initStart
                val initOk = initResult != null && initResult.isSuccess

                SchedulerDiagnostics.logPluginInit(
                    durationMs = initDuration,
                    success = initOk,
                    details = "PluginManager ready=${initOk}, requestedIds=${requestedPluginIds.size}",
                )

                if (!initOk) {
                    val reason = if (initResult == null) "timed out" else "failed with error: ${initResult.exceptionOrNull()?.message}"
                    if (retryCount < 2 && requestedPluginIds.isNotEmpty()) {
                        SchedulerDiagnostics.log(
                            "PluginCronTrigger",
                            "Plugin initialization $reason in TriggerService, scheduling retry ${retryCount + 1}/2 in 5 minutes.",
                        )
                        val retryTime = System.currentTimeMillis() + 5 * 60 * 1000L
                        val retryIntent = Intent(this@DailySummaryTriggerService, DailySummaryReceiver::class.java).apply {
                            action = DailySummaryService.ACTION_DAILY_CRON
                            putStringArrayListExtra(DailySummaryService.EXTRA_PLUGIN_IDS, ArrayList(requestedPluginIds))
                            putStringArrayListExtra(DailySummaryService.EXTRA_HANDLERS, ArrayList(requestedHandlers))
                            putExtra(DailySummaryService.EXTRA_SCHEDULED_AT, scheduledAt)
                            putExtra(DailySummaryService.EXTRA_RETRY_COUNT, retryCount + 1)
                        }
                        val retryPendingIntent = PendingIntent.getBroadcast(
                            this@DailySummaryTriggerService,
                            DailySummaryService.REQUEST_CODE,
                            retryIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                        when {
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms() -> {
                                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, retryTime, retryPendingIntent)
                            }
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, retryTime, retryPendingIntent)
                                Log.w(TAG, "Exact alarm permission not granted for plugin retry, using inexact alarm")
                            }
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, retryTime, retryPendingIntent)
                            }
                            else -> {
                                alarmManager.setExact(AlarmManager.RTC_WAKEUP, retryTime, retryPendingIntent)
                            }
                        }
                    } else {
                        SchedulerDiagnostics.log(
                            "PluginCronTrigger",
                            "Plugin initialization $reason in TriggerService, retry limit reached (retryCount=$retryCount). Skipping batch.",
                        )
                    }
                    return@launch
                }

                val allHooks = pluginLoader.getScheduledHooks()
                val targetHooks = if (
                    requestedPluginIds.isNotEmpty() &&
                    requestedPluginIds.size == requestedHandlers.size
                ) {
                    requestedPluginIds.zip(requestedHandlers).mapNotNull { (pluginId, handler) ->
                        allHooks.firstOrNull {
                            it.pluginId == pluginId && it.handler == handler
                        }
                    }
                } else {
                    // 手动 triggerNow 或旧 PendingIntent：兼容执行全部定时 hook。
                    allHooks
                }

                if (targetHooks.isEmpty()) {
                    SchedulerDiagnostics.log("PluginCronTrigger", "No matching plugin cron hooks to execute (allHooks count=${allHooks.size})")
                    return@launch
                }

                val now = java.time.LocalDateTime.now()
                val eventData = JsonObject(
                    mapOf(
                        "timestamp" to JsonPrimitive(now.toString()),
                        "date" to JsonPrimitive(now.toLocalDate().toString()),
                        "hour" to JsonPrimitive(now.hour),
                        "minute" to JsonPrimitive(now.minute),
                        "scheduledAt" to JsonPrimitive(scheduledAt),
                    )
                )

                SchedulerDiagnostics.log("PluginCronTrigger", "Dispatching ${targetHooks.size} scheduled plugin hook(s)")
                targetHooks.forEach { hook ->
                    try {
                        pluginLoader.callScheduledHook(
                            pluginId = hook.pluginId,
                            handler = hook.handler,
                            params = eventData,
                        )
                        SchedulerDiagnostics.logHookExecution(hook.pluginId, hook.handler, "Dispatched successfully")
                    } catch (hookEx: Exception) {
                        SchedulerDiagnostics.logHookExecution(hook.pluginId, hook.handler, "Failed: ${hookEx.message}")
                    }
                }
                pluginLoader.awaitPendingNotifications(timeoutMs = 25_000L)
            } catch (e: Exception) {
                SchedulerDiagnostics.log("PluginCronTrigger", "Failed to dispatch plugin cron", e)
            } finally {
                try {
                    DailySummaryService.rescheduleNow(this@DailySummaryTriggerService)
                } catch (e: Exception) {
                    SchedulerDiagnostics.log("PluginCronTrigger", "Failed to rebuild plugin cron schedule", e)
                }
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
