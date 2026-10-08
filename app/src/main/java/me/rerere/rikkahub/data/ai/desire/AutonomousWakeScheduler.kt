package me.rerere.rikkahub.data.ai.desire

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.ai.desire.db.AutonomousWakePlanEntity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.service.WorkflowWakeExecutor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class AutonomousWakeScheduler(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val desireRepository: DesireRepository,
) {
    companion object {
        private const val TAG = "AutonomousWakeScheduler"
        private const val PREFS_NAME = "autonomous_wake_prefs"
        private const val KEY_SCHEDULED_AT_PREFIX = "scheduled_at_"
        private const val KEY_VERSION_PREFIX = "plan_version_"
        private const val RETRY_DELAY_MS = 15 * 60 * 1000L
    }

    private val assistantLocks = ConcurrentHashMap<String, Mutex>()
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val diagnosticsLock = Any()
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // USER_PRESENT comes from SystemUI; receive it while the application process is alive.
        // A new process also recovers persisted plans through rescheduleAll() at startup.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_USER_PRESENT) return
                recoveryScope.launch {
                    runCatching { rescheduleAll() }.onFailure {
                        Log.e(TAG, "Unlock recovery failed", it)
                    }
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext, receiver, IntentFilter(Intent.ACTION_USER_PRESENT),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { Log.e(TAG, "Unlock recovery receiver registration failed", it) }
    }

    fun recordDiagnostic(assistantId: String, message: String, error: Throwable? = null) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val line = "$time $message" + (error?.let { "：${it.javaClass.simpleName}: ${it.message}" } ?: "")
        synchronized(diagnosticsLock) {
            val key = "diagnostics_$assistantId"
            val lines = (prefs.getString(key, "").orEmpty().lines().filter { it.isNotBlank() } + line).takeLast(30)
            prefs.edit().putString(key, lines.joinToString("\n")).apply()
        }
        me.rerere.rikkahub.data.service.SchedulerDiagnostics.log("AutonomousWake", message, error)
    }

    fun getDiagnostics(assistantId: String): String = prefs.getString("diagnostics_$assistantId", "").orEmpty()

    suspend fun schedule(
        assistantId: String,
        delayMs: Long? = null,
        enabledOverride: Boolean? = null,
        intervalHoursOverride: Int? = null,
        autonomousCandidateAt: Long? = null,
        reason: String? = null,
    ): AutonomousWakePlanEntity? {
        val settings = settingsStore.settingsFlowRaw.first()
        val assistant = settings.assistants.firstOrNull { it.id.toString() == assistantId }
        if (assistant == null || !(enabledOverride ?: assistant.autonomousWakeEnabled)) {
            cancel(assistantId)
            return null
        }
        val now = System.currentTimeMillis()
        val delay = delayMs ?: (intervalHoursOverride ?: assistant.autonomousWakeIntervalHours)
            .coerceIn(1, 24) * 60L * 60L * 1000L
        val autoAt = autonomousCandidateAt ?: now + delay
        val planReason = reason ?: when {
            delayMs == 0L -> "用户开启自主唤醒，立即执行"
            delayMs != null -> "失败或恢复后的重试等待${delay / 60_000}分钟"
            else -> "用户默认间隔${intervalHoursOverride ?: assistant.autonomousWakeIntervalHours}小时"
        }
        val source = when {
            delayMs == 0L -> "enabled"
            delayMs != null -> "retry"
            else -> "default"
        }
        val previousPlan = desireRepository.getWakePlan(assistantId)
        val activeVersion = previousPlan?.runningVersion
            ?: previousPlan?.takeIf { it.status == "running" }?.version
        if (activeVersion == null) {
            WorkManager.getInstance(context).cancelUniqueWork(baseWorkName(assistantId))
            previousPlan?.let { WorkManager.getInstance(context).cancelUniqueWork(versionedWorkName(assistantId, it.version)) }
        } else {
            previousPlan?.takeIf { it.version != activeVersion }?.let {
                WorkManager.getInstance(context).cancelUniqueWork(versionedWorkName(assistantId, it.version))
            }
        }
        val plan = desireRepository.replaceWakePlan(assistantId, autoAt, source, planReason, now)
        recordDiagnostic(assistantId, "计划v${plan.version}已保存，目标时间=${plan.scheduledAt}，来源=${plan.source}")
        val workName = if (activeVersion != null) versionedWorkName(assistantId, plan.version) else baseWorkName(assistantId)
        schedulePlan(plan, ExistingWorkPolicy.REPLACE, workName)
        return plan
    }

    private fun schedulePlan(
        plan: AutonomousWakePlanEntity,
        policy: ExistingWorkPolicy,
        workName: String = baseWorkName(plan.assistantId),
    ) {
        val assistantId = plan.assistantId
        val dueAt = plan.scheduledAt
        prefs.edit()
            .putLong(KEY_SCHEDULED_AT_PREFIX + assistantId, dueAt)
            .putLong(KEY_VERSION_PREFIX + assistantId, plan.version)
            .apply()
        scheduleAlarm(assistantId, dueAt, plan.version)
        val request = OneTimeWorkRequestBuilder<AutonomousWakeWorker>()
            .setInitialDelay((dueAt - System.currentTimeMillis()).coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(
                "assistant_id" to assistantId,
                "scheduled_at" to dueAt,
                "plan_version" to plan.version,
            ))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName, policy, request)
        Log.d(TAG, "Scheduled autonomous wake for $assistantId at $dueAt version=${plan.version}")
    }

    private fun baseWorkName(assistantId: String) = "autonomous_wake_$assistantId"

    private fun versionedWorkName(assistantId: String, version: Long) = "autonomous_wake_${assistantId}_v$version"

    private fun scheduleAlarm(assistantId: String, triggerAtMs: Long, planVersion: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AutonomousWakeAlarmReceiver::class.java).apply {
            action = AutonomousWakeAlarmReceiver.ACTION_AUTONOMOUS_WAKE
            putExtra(AutonomousWakeAlarmReceiver.EXTRA_ASSISTANT_ID, assistantId)
            putExtra(AutonomousWakeAlarmReceiver.EXTRA_SCHEDULED_AT, triggerAtMs)
            putExtra(AutonomousWakeAlarmReceiver.EXTRA_PLAN_VERSION, planVersion)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ("autonomous_wake_$assistantId").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        }
    }

    suspend fun cancel(assistantId: String) {
        cancelAlarm(assistantId)
        val plan = desireRepository.getWakePlan(assistantId)
        val activeVersion = plan?.runningVersion ?: plan?.takeIf { it.status == "running" }?.version
        if (activeVersion == null) {
            WorkManager.getInstance(context).cancelUniqueWork(baseWorkName(assistantId))
            plan?.let { WorkManager.getInstance(context).cancelUniqueWork(versionedWorkName(assistantId, it.version)) }
        } else {
            plan?.takeIf { it.version != activeVersion }?.let {
                WorkManager.getInstance(context).cancelUniqueWork(versionedWorkName(assistantId, it.version))
            }
        }
        desireRepository.cancelWakePlan(assistantId)
        prefs.edit()
            .remove(KEY_SCHEDULED_AT_PREFIX + assistantId)
            .remove(KEY_VERSION_PREFIX + assistantId)
            .apply()
        Log.d(TAG, "Cancelled autonomous wake for $assistantId")
    }

    private fun cancelAlarm(assistantId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AutonomousWakeAlarmReceiver::class.java).apply {
            action = AutonomousWakeAlarmReceiver.ACTION_AUTONOMOUS_WAKE
            putExtra(AutonomousWakeAlarmReceiver.EXTRA_ASSISTANT_ID, assistantId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ("autonomous_wake_$assistantId").hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    suspend fun executeWake(
        assistantId: String,
        scheduledAt: Long,
        planVersion: Long,
        triggerSource: String,
    ): Boolean {
        recordDiagnostic(assistantId, "执行入口已到达（$triggerSource），回调版本=$planVersion")
        val mutex = assistantLocks.computeIfAbsent(assistantId) { Mutex() }
        if (!mutex.tryLock()) {
            recordDiagnostic(assistantId, "同一助手已有唤醒运行，保留当前计划等待运行结束重排")
            return true
        }
        try {
            val plan = desireRepository.getWakePlan(assistantId)
            if (plan == null || plan.version != planVersion || plan.status != "scheduled" || plan.scheduledAt != scheduledAt) {
                recordDiagnostic(assistantId, "忽略过期或重复回调：当前计划=${plan?.version}/${plan?.status}/${plan?.scheduledAt}")
                return true
            }
            if (!desireRepository.claimWakePlan(assistantId, planVersion, System.currentTimeMillis())) {
                recordDiagnostic(assistantId, "计划未领取：已被另一通道领取或尚未到期")
                return true
            }
            if (triggerSource == "alarm") {
                WorkManager.getInstance(context).cancelUniqueWork(baseWorkName(assistantId))
                WorkManager.getInstance(context).cancelUniqueWork(versionedWorkName(assistantId, planVersion))
            } else {
                cancelAlarm(assistantId)
            }
            val assistant = settingsStore.settingsFlowRaw.first().assistants
                .firstOrNull { it.id.toString() == assistantId }
            if (assistant == null || !assistant.autonomousWakeEnabled) {
                desireRepository.finishWakePlan(
                    assistantId, planVersion, plan.autonomousCandidateAt,
                    "cancelled", "自主唤醒已关闭或助手不存在", nowMs = System.currentTimeMillis(),
                )
                cancel(assistantId)
                return false
            }
            recordDiagnostic(assistantId, "计划v${planVersion}已原子领取，原因=${plan.reason}")
            val result = WorkflowWakeExecutor.executeAutonomousWake(
                context = context,
                assistantId = assistantId,
                scheduledAt = plan.scheduledAt,
                planVersion = planVersion,
                wakeReason = "${plan.source}：${plan.reason}",
            )
            val stillEnabled = settingsStore.settingsFlowRaw.first().assistants
                .any { it.id.toString() == assistantId && it.autonomousWakeEnabled }
            if (!stillEnabled) {
                desireRepository.finishWakePlan(
                    assistantId, planVersion, plan.autonomousCandidateAt,
                    "cancelled", "自主唤醒在本轮运行期间关闭", nowMs = System.currentTimeMillis(),
                )
                cancel(assistantId)
                return false
            }
            val now = System.currentTimeMillis()
            val nextPlan = if (result.isSuccess) {
                val decision = result.getOrThrow()
                desireRepository.finishWakePlan(
                    assistantId, planVersion, decision.autonomousCandidateAt,
                    decision.source, decision.reason, nowMs = now,
                )
            } else {
                val retryAt = now + RETRY_DELAY_MS
                desireRepository.finishWakePlan(
                    assistantId, planVersion, retryAt, "retry",
                    "本轮失败，15分钟后重试：${result.exceptionOrNull()?.message.orEmpty()}",
                    retryAt = retryAt,
                    nowMs = now,
                )
            }
            if (nextPlan != null) {
                recordDiagnostic(assistantId, "本轮${if (result.isSuccess) "完成" else "失败"}，下一计划v${nextPlan.version}=${nextPlan.scheduledAt}", result.exceptionOrNull())
                val nextWorkName = if (triggerSource == "worker") versionedWorkName(assistantId, nextPlan.version) else baseWorkName(assistantId)
                schedulePlan(nextPlan, ExistingWorkPolicy.REPLACE, nextWorkName)
            }
            return result.isSuccess
        } catch (error: Exception) {
            recordDiagnostic(assistantId, "执行入口异常，安排恢复检查", error)
            val now = System.currentTimeMillis()
            val retryAt = now + RETRY_DELAY_MS
            val plan = desireRepository.finishWakePlan(
                assistantId, planVersion, retryAt, "retry", "执行入口异常，15分钟后恢复检查",
                retryAt = retryAt, nowMs = now,
            )
            if (plan != null) {
                val workName = if (triggerSource == "worker") versionedWorkName(assistantId, plan.version) else baseWorkName(assistantId)
                schedulePlan(plan, ExistingWorkPolicy.REPLACE, workName)
            }
            return false
        } finally {
            mutex.unlock()
        }
    }

    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        settingsStore.settingsFlowRaw.first().assistants.forEach { assistant ->
            val assistantId = assistant.id.toString()
            assistantLocks.computeIfAbsent(assistantId) { Mutex() }.withLock {
                if (!assistant.autonomousWakeEnabled) {
                    desireRepository.getWakePlan(assistantId)?.runningVersion?.let { runningVersion ->
                        desireRepository.markAutonomousRunsInterrupted(assistantId, now)
                        desireRepository.finishWakePlan(
                            assistantId, runningVersion, now, "cancelled", "自主唤醒已关闭", nowMs = now,
                        )
                    }
                    cancel(assistantId)
                    return@withLock
                }
                val plan = desireRepository.getWakePlan(assistantId)
                when {
                    plan == null || plan.status == "cancelled" -> {
                        val legacyAt = prefs.getLong(KEY_SCHEDULED_AT_PREFIX + assistantId, 0L)
                        if (legacyAt > now) {
                            schedule(assistantId, autonomousCandidateAt = legacyAt, reason = "恢复旧版本保存的计划")
                        } else if (legacyAt > 0L) {
                            schedule(assistantId, delayMs = 0L, reason = "恢复旧版本中已过期计划，补执行一轮")
                        } else {
                            schedule(assistantId, reason = "恢复缺失的自主计划")
                        }
                    }
                    plan.status == "running" || plan.runningVersion != null -> {
                        desireRepository.markAutonomousRunsInterrupted(assistantId, now)
                        val runningVersion = plan.runningVersion ?: plan.version
                        val retryAt = now
                        val recovered = desireRepository.finishWakePlan(
                            assistantId, runningVersion, plan.autonomousCandidateAt,
                            "recovery", "恢复被系统中断的计划，补执行一轮",
                            retryAt = retryAt, nowMs = now,
                        )
                        if (recovered != null) schedulePlan(recovered, ExistingWorkPolicy.REPLACE)
                    }
                    plan.scheduledAt <= now -> {
                        schedule(assistantId, delayMs = 0L, reason = "恢复已过期计划，补执行一轮")
                    }
                    else -> schedulePlan(plan, ExistingWorkPolicy.REPLACE)
                }
            }
        }
    }
}
