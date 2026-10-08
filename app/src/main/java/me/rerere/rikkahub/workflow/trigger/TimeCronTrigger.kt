/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.trigger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import me.rerere.rikkahub.data.service.SchedulerDiagnostics
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.koin.java.KoinJavaComponent
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 工作流时间与 Cron 调度管理器。
 *
 * 采用混合双通道调度策略：
 * 1. 主通道：精确 AlarmManager (setExactAndAllowWhileIdle)，在 scheduledAt 准点唤醒并在 [WorkflowExecutionService] 执行。
 * 2. 补偿通道：一次性 WorkManager 延迟 15 分钟触发 (OneTimeWorkRequest)，携带完全相同的 (workflowId, scheduledAt)。
 *    若 Alarm 已执行完成，Worker 原子检测到 SUCCEEDED 直接跳过；若 Alarm 漏执行，Worker 自动补偿接管。
 */
object TimeCronAlarmScheduler {
    private const val TAG = "TimeCronAlarm"
    private const val BASE_REQUEST_CODE = 30000
    const val KEY_WORKFLOW_ID = "workflow_id"
    const val KEY_SCHEDULED_AT = "scheduled_at"

    fun requestCodeFor(workflowId: String): Int {
        return (abs(workflowId.hashCode()) % 50000) + BASE_REQUEST_CODE
    }

    fun workTag(workflowId: String) = "wf_fallback_$workflowId"
    fun workName(workflowId: String, scheduledAt: Long) = "workflow-fallback-$workflowId-$scheduledAt"

    /**
     * 统一调度函数：同时创建精确闹钟主任务与延迟 15 分钟的 WorkManager 补偿任务。
     */
    fun scheduleOccurrence(
        context: Context,
        workflow: WorkflowDefinition,
        scheduledAt: Long,
    ) {
        // 1. 安排精确闹钟主任务
        val intent = Intent(context, WorkflowAlarmReceiver::class.java).apply {
            action = WorkflowAlarmReceiver.ACTION_WORKFLOW_ALARM
            putExtra(WorkflowAlarmReceiver.EXTRA_WORKFLOW_ID, workflow.id)
            putExtra(WorkflowAlarmReceiver.EXTRA_SCHEDULED_AT, scheduledAt)
        }
        val requestCode = requestCodeFor(workflow.id)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val isExact: Boolean
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms() -> {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    scheduledAt,
                    pendingIntent,
                )
                isExact = true
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    scheduledAt,
                    pendingIntent,
                )
                isExact = false
                Log.w(TAG, "Exact alarm permission not granted for ${workflow.id}, using inexact alarm")
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    scheduledAt,
                    pendingIntent,
                )
                isExact = true
            }
            else -> {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, scheduledAt, pendingIntent)
                isExact = true
            }
        }

        // 2. 安排 WorkManager 补偿任务 (scheduledAt + 15 分钟)
        val now = System.currentTimeMillis()
        val compensationFireTime = scheduledAt + 15 * 60 * 1000L
        val delayMs = (compensationFireTime - now).coerceAtLeast(0L)

        val workReq = OneTimeWorkRequestBuilder<WorkflowTimeCronWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    KEY_WORKFLOW_ID to workflow.id,
                    KEY_SCHEDULED_AT to scheduledAt,
                )
            )
            .addTag(workTag(workflow.id))
            .build()

        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(workflow.id, scheduledAt),
                ExistingWorkPolicy.REPLACE,
                workReq,
            )
        }.onFailure { Log.w(TAG, "Failed to enqueue fallback worker for ${workflow.id}", it) }

        SchedulerDiagnostics.logSchedule(
            target = "Workflow:'${workflow.name}'(${workflow.id})",
            triggerAtMillis = scheduledAt,
            exact = isExact,
        )
    }

    /**
     * 取消指定工作流的所有闹钟和补偿 WorkManager
     */
    fun cancelSchedule(context: Context, workflowId: String) {
        val intent = Intent(context, WorkflowAlarmReceiver::class.java).apply {
            action = WorkflowAlarmReceiver.ACTION_WORKFLOW_ALARM
        }
        val requestCode = requestCodeFor(workflowId)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }

        runCatching {
            WorkManager.getInstance(context).cancelAllWorkByTag(workTag(workflowId))
        }

        SchedulerDiagnostics.log("WorkflowSchedule", "Cancelled alarm and fallback workers for workflow $workflowId")
    }

    /**
     * 计算并安排下一次 occurrence
     */
    suspend fun reconcileNextOccurrence(context: Context, workflowId: String) {
        val repo = runCatching { KoinJavaComponent.getKoin().get<WorkflowRepository>() }.getOrNull() ?: return
        val loaded = repo.getById(workflowId) ?: return
        if (!loaded.entity.enabled) return

        val spec = loaded.definition.trigger as? TriggerSpec.TimeCron ?: return
        val zone = spec.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        val nextFireMs = TimeCronTriggerFamily.computeNextFireMs(spec, zone, System.currentTimeMillis())

        scheduleOccurrence(context, loaded.definition, nextFireMs)
    }
}

/**
 * Phase 12 — time / cron family with Hybrid AlarmManager + OneTimeWorkRequest dispatch.
 */
internal class TimeCronTriggerFamily(
    private val context: Context,
    private val scope: CoroutineScope,
) : WorkflowTriggerFamily {

    override val name = "time_cron"

    @Volatile private var lastSnapshot: List<WorkflowDefinition> = emptyList()

    override fun handles(spec: TriggerSpec): Boolean = spec is TriggerSpec.TimeCron

    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) {
        val previous = lastSnapshot.associateBy { it.id }
        val current = matching.associateBy { it.id }

        // Cancel removed or disabled workflows
        for (id in previous.keys - current.keys) {
            TimeCronAlarmScheduler.cancelSchedule(context, id)
        }

        // Schedule added or changed workflows
        for ((id, wf) in current) {
            val prev = previous[id]
            if (prev == null
                || prev.trigger != wf.trigger
                || prev.updatedAtMs != wf.updatedAtMs
                || !prev.enabled
            ) {
                val spec = wf.trigger as? TriggerSpec.TimeCron ?: continue
                val zone = spec.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
                val nextFireMs = computeNextFireMs(spec, zone, System.currentTimeMillis())
                TimeCronAlarmScheduler.scheduleOccurrence(context, wf, nextFireMs)
            }
        }
        lastSnapshot = matching
    }

    override suspend fun shutdown() {
        for (wf in lastSnapshot) {
            TimeCronAlarmScheduler.cancelSchedule(context, wf.id)
        }
        lastSnapshot = emptyList()
    }

    companion object {
        private const val TAG = "WorkflowTrigger"

        fun derivePeriodMs(spec: TriggerSpec.TimeCron): Long? {
            if (!spec.timeOfDay.isNullOrBlank()) return 24L * 60 * 60 * 1000
            val cron = spec.cron?.trim() ?: return null
            // @every Ns
            val every = Regex("^@every\\s+(\\d+)([smhd])$").find(cron)
            if (every != null) {
                val n = every.groupValues[1].toLong()
                val unit = every.groupValues[2]
                return when (unit) {
                    "s" -> n * 1000
                    "m" -> n * 60 * 1000
                    "h" -> n * 60 * 60 * 1000
                    "d" -> n * 24 * 60 * 60 * 1000
                    else -> null
                }
            }
            return when (cron) {
                "@hourly" -> 60L * 60 * 1000
                "@daily", "@midnight" -> 24L * 60 * 60 * 1000
                "@weekly" -> 7L * 24 * 60 * 60 * 1000
                else -> null
            }
        }

        fun computeNextFireMs(spec: TriggerSpec.TimeCron, zone: ZoneId, nowMs: Long): Long {
            val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
            if (!spec.timeOfDay.isNullOrBlank()) {
                val (h, m) = spec.timeOfDay.split(":").let { it[0].toInt() to it[1].toInt() }
                var candidate = now.toLocalDate().atTime(LocalTime.of(h, m)).atZone(zone)
                if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
                if (spec.daysOfWeek.isNotEmpty()) {
                    val allowed = spec.daysOfWeek.map { isoDow(it) }.toSet()
                    var hops = 0
                    while (candidate.dayOfWeek !in allowed && hops < 8) {
                        candidate = candidate.plusDays(1); hops++
                    }
                }
                return candidate.toInstant().toEpochMilli()
            }
            derivePeriodMs(spec)?.let { return nowMs + it }
            spec.cron?.trim()?.takeIf { it.isNotBlank() }?.let { cron ->
                me.rerere.rikkahub.service.CronExpressionParser.parse(cron).getOrNull()?.let { parsed ->
                    me.rerere.rikkahub.service.CronExpressionParser.nextExecution(parsed, now)
                        ?.let { return it.toInstant().toEpochMilli() }
                }
            }
            return nowMs + 60L * 60 * 1000
        }

        private fun isoDow(iso: Int): DayOfWeek = when (iso) {
            1 -> DayOfWeek.MONDAY; 2 -> DayOfWeek.TUESDAY; 3 -> DayOfWeek.WEDNESDAY
            4 -> DayOfWeek.THURSDAY; 5 -> DayOfWeek.FRIDAY; 6 -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }
    }
}
