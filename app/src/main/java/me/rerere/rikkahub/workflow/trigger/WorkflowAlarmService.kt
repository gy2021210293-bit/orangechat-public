/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.trigger

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.service.SchedulerDiagnostics
import me.rerere.rikkahub.workflow.execution.DirectWorkflowWakeDispatcher
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.model.ClaimResult
import me.rerere.rikkahub.workflow.model.WorkflowFireSource
import me.rerere.rikkahub.workflow.model.WorkflowRunStatus
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * 工作流精确闹钟接收器。
 * 由 AlarmManager 精确闹钟唤醒，并在 Android 12+ 前台服务启动豁免下立即拉起 [WorkflowExecutionService]。
 */
class WorkflowAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_WORKFLOW_ALARM = "me.rerere.orangechat.WORKFLOW_ALARM"
        const val EXTRA_WORKFLOW_ID = "workflow_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val workflowId = intent.getStringExtra(EXTRA_WORKFLOW_ID) ?: return
        val scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, 0L)
        val now = System.currentTimeMillis()
        val driftMs = now - scheduledAt
        SchedulerDiagnostics.log(
            "WorkflowAlarm",
            "Alarm received for $workflowId (scheduledAt=$scheduledAt, now=$now, drift=${driftMs}ms)",
        )

        val serviceIntent = Intent(context, WorkflowExecutionService::class.java).apply {
            putExtra(EXTRA_WORKFLOW_ID, workflowId)
            putExtra(EXTRA_SCHEDULED_AT, scheduledAt)
        }
        try {
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            SchedulerDiagnostics.log(
                "WorkflowAlarm",
                "Failed to start WorkflowExecutionService for $workflowId (error=${e::class.simpleName}: ${e.message}). Will be compensated by WorkManager in 15min.",
                e,
            )
        }
    }
}

/**
 * 工作流后台前台执行服务。
 * 确保工作流在息屏 / 深度 Doze 模式下拥有前台保护、CPU 唤醒与网络访问权限，
 * 一条龙完成：原子领取 -> 条件判定 -> 动作执行 -> AI 唤醒生成 -> 系统通知 -> 安排下一次。
 */
class WorkflowExecutionService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 20005
        private const val WAKELOCK_TIMEOUT_MS = 9 * 60 * 1000L // 9 minutes to cover bounded execution timeout
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningTaskCount = AtomicInteger(0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val workflowId = intent?.getStringExtra(WorkflowAlarmReceiver.EXTRA_WORKFLOW_ID)
        val scheduledAt = intent?.getLongExtra(WorkflowAlarmReceiver.EXTRA_SCHEDULED_AT, 0L) ?: 0L

        val notification = NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("正在执行工作流...")
            .setSmallIcon(R.drawable.small_icon)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        if (workflowId.isNullOrBlank()) {
            if (runningTaskCount.get() == 0) {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        runningTaskCount.incrementAndGet()

        // 获取 WakeLock 保护，确保 Action 与 AI 网络请求期间 CPU 保持唤醒
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "OrangeChat:WorkflowExecutionServiceWakeLock:$workflowId",
        )?.apply {
            setReferenceCounted(false)
            runCatching { acquire(WAKELOCK_TIMEOUT_MS) }
        }

        serviceScope.launch {
            val startTime = System.currentTimeMillis()
            var workflowName = workflowId
            try {
                val koin = GlobalContext.get()
                val workflowRepo = koin.getOrNull<WorkflowRepository>()
                val workflowEngine = koin.getOrNull<WorkflowEngine>()

                if (workflowRepo == null || workflowEngine == null) {
                    SchedulerDiagnostics.log(
                        "WorkflowExec",
                        "WorkflowRepository or WorkflowEngine not available for $workflowId",
                    )
                    return@launch
                }

                val loaded = workflowRepo.getById(workflowId)
                if (loaded == null || !loaded.entity.enabled) {
                    SchedulerDiagnostics.log(
                        "WorkflowExec",
                        "Workflow $workflowId not found or disabled, cancelling schedule",
                    )
                    TimeCronAlarmScheduler.cancelSchedule(this@WorkflowExecutionService, workflowId)
                    return@launch
                }

                workflowName = loaded.definition.name

                // 核心：原子领取本次 occurrence
                val claimResult = workflowRepo.tryClaimOccurrence(
                    workflowId = workflowId,
                    scheduledAt = scheduledAt,
                    source = WorkflowFireSource.ALARM,
                    nowMs = startTime,
                )

                val claimToken = when (claimResult) {
                    is ClaimResult.AlreadyCompleted -> {
                        SchedulerDiagnostics.log(
                            "WorkflowExec",
                            "Occurrence $scheduledAt of '$workflowName' ($workflowId) already completed (${claimResult.status}), skipping",
                        )
                        TimeCronAlarmScheduler.reconcileNextOccurrence(this@WorkflowExecutionService, workflowId)
                        return@launch
                    }
                    is ClaimResult.AlreadyRunning -> {
                        SchedulerDiagnostics.log(
                            "WorkflowExec",
                            "Occurrence $scheduledAt of '$workflowName' ($workflowId) already running (lease until ${claimResult.leaseUntil}), skipping",
                        )
                        return@launch
                    }
                    is ClaimResult.RetryLimitReached -> {
                        SchedulerDiagnostics.log(
                            "WorkflowExec",
                            "Occurrence $scheduledAt of '$workflowName' ($workflowId) retry limit reached, skipping",
                        )
                        TimeCronAlarmScheduler.reconcileNextOccurrence(this@WorkflowExecutionService, workflowId)
                        return@launch
                    }
                    is ClaimResult.Acquired -> {
                        SchedulerDiagnostics.log(
                            "WorkflowExec",
                            "Acquired claim for occurrence $scheduledAt of '$workflowName' ($workflowId), executing in foreground service",
                        )
                        claimResult.claimToken
                    }
                }

                // 核心执行：驱动动作与 AI 唤醒生成（在受保护前台服务中完成，上限 8 分钟）
                val outcome = kotlinx.coroutines.withTimeoutOrNull(8 * 60 * 1000L) {
                    workflowEngine.fire(
                        workflowId = workflowId,
                        wakeDispatcher = DirectWorkflowWakeDispatcher,
                    )
                }
                val durationMs = System.currentTimeMillis() - startTime

                if (outcome == null) {
                    // 执行超时（属于瞬时超时错误，允许 Worker 接管/重试）
                    workflowRepo.markClaimFailed(
                        workflowId = workflowId,
                        scheduledAt = scheduledAt,
                        claimToken = claimToken,
                        error = "Execution timed out after 8 minutes",
                        retryable = true,
                    )
                    SchedulerDiagnostics.logWorkflowExecution(
                        workflowId = workflowId,
                        workflowName = workflowName,
                        status = WorkflowRunStatus.FAILED.name,
                        durationMs = durationMs,
                        error = "Execution timed out after 8 minutes",
                    )
                    TimeCronAlarmScheduler.reconcileNextOccurrence(
                        context = this@WorkflowExecutionService,
                        workflowId = workflowId,
                    )
                    return@launch
                }

                val isRetryable = outcome.status == WorkflowRunStatus.FAILED &&
                    me.rerere.rikkahub.workflow.model.isRetryableWorkflowError(outcome.error)

                val updated = when (outcome.status) {
                    WorkflowRunStatus.SUCCESS -> workflowRepo.markClaimSucceeded(workflowId, scheduledAt, claimToken)
                    WorkflowRunStatus.SKIPPED_DISABLED,
                    WorkflowRunStatus.SKIPPED_COOLDOWN,
                    WorkflowRunStatus.SKIPPED_DAILY_CAP,
                    WorkflowRunStatus.SKIPPED_CONDITIONS -> workflowRepo.markClaimSkipped(workflowId, scheduledAt, claimToken, outcome.error)
                    WorkflowRunStatus.FAILED -> workflowRepo.markClaimFailed(workflowId, scheduledAt, claimToken, outcome.error, retryable = isRetryable)
                }

                if (!updated) {
                    SchedulerDiagnostics.log(
                        "WorkflowExec",
                        "Claim ownership for occurrence $scheduledAt of '$workflowName' ($workflowId) was lost (preempted by another execution), terminal update skipped",
                    )
                }

                SchedulerDiagnostics.logWorkflowExecution(
                    workflowId = workflowId,
                    workflowName = workflowName,
                    status = outcome.status.name,
                    durationMs = durationMs,
                    error = outcome.error,
                )

                // 执行完成后重新计算并调度下一次触发时间
                TimeCronAlarmScheduler.reconcileNextOccurrence(
                    context = this@WorkflowExecutionService,
                    workflowId = workflowId,
                )
            } catch (e: Exception) {
                SchedulerDiagnostics.log(
                    "WorkflowExec",
                    "Unexpected error executing workflow '$workflowName' ($workflowId)",
                    e,
                )
            } finally {
                wakeLock?.let {
                    if (it.isHeld) {
                        runCatching { it.release() }
                    }
                }
                val remaining = runningTaskCount.decrementAndGet()
                if (remaining <= 0) {
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
