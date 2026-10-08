/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.service.SchedulerDiagnostics
import me.rerere.rikkahub.workflow.execution.DirectWorkflowWakeDispatcher
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.model.ClaimResult
import me.rerere.rikkahub.workflow.model.WorkflowFireSource
import me.rerere.rikkahub.workflow.model.WorkflowRunStatus
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.java.KoinJavaComponent

/**
 * Phase 12 — WorkManager 补偿 Worker。
 * 当精确闹钟漏触发或设备关机恢复后，由延迟 15 分钟的一次性 WorkManager 触发。
 *
 * 核心逻辑：
 * 1. 使用相同的 (workflowId, scheduledAt) 尝试原子领取 occurrence。
 * 2. 若 Alarm 已成功执行 (SUCCEEDED) -> 直接跳过，仅调用 reconcileNextOccurrence 确保后续调度。
 * 3. 若 Alarm 漏执行 -> 由 Worker 托管在当前 Worker 协程中执行（限时 8 分钟），无需再次启动前台服务。
 */
class WorkflowTimeCronWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val workflowRepo: WorkflowRepository by inject()
    private val workflowEngine: WorkflowEngine by inject()

    override suspend fun doWork(): Result {
        val workflowId = inputData.getString(TimeCronAlarmScheduler.KEY_WORKFLOW_ID) ?: return Result.failure()
        val scheduledAt = inputData.getLong(TimeCronAlarmScheduler.KEY_SCHEDULED_AT, 0L)
        val nowMs = System.currentTimeMillis()

        val loaded = workflowRepo.getById(workflowId)
        if (loaded == null || !loaded.entity.enabled) {
            SchedulerDiagnostics.log(
                "WorkflowWorker",
                "Workflow $workflowId not found or disabled, cancelling fallback schedule",
            )
            TimeCronAlarmScheduler.cancelSchedule(applicationContext, workflowId)
            return Result.failure()
        }

        val claimResult = workflowRepo.tryClaimOccurrence(
            workflowId = workflowId,
            scheduledAt = scheduledAt,
            source = WorkflowFireSource.WORKER,
            nowMs = nowMs,
        )

        val claimToken = when (claimResult) {
            is ClaimResult.AlreadyCompleted -> {
                SchedulerDiagnostics.log(
                    "WorkflowWorker",
                    "Occurrence $scheduledAt for workflow $workflowId already completed (${claimResult.status}) by Alarm, skipping fallback execution",
                )
                TimeCronAlarmScheduler.reconcileNextOccurrence(applicationContext, workflowId)
                return Result.success()
            }
            is ClaimResult.AlreadyRunning -> {
                SchedulerDiagnostics.log(
                    "WorkflowWorker",
                    "Occurrence $scheduledAt for workflow $workflowId is currently running (lease until ${claimResult.leaseUntil}), retrying later",
                )
                return Result.retry()
            }
            is ClaimResult.RetryLimitReached -> {
                SchedulerDiagnostics.log(
                    "WorkflowWorker",
                    "Occurrence $scheduledAt for workflow $workflowId retry limit reached, skipping",
                )
                TimeCronAlarmScheduler.reconcileNextOccurrence(applicationContext, workflowId)
                return Result.failure()
            }
            is ClaimResult.Acquired -> {
                SchedulerDiagnostics.log(
                    "WorkflowWorker",
                    "Acquired claim for occurrence $scheduledAt of workflow $workflowId, executing compensation via WorkManager",
                )
                claimResult.claimToken
            }
        }

        val startTime = System.currentTimeMillis()
        val outcome = withTimeoutOrNull(8 * 60 * 1000L) {
            workflowEngine.fire(
                workflowId = workflowId,
                wakeDispatcher = DirectWorkflowWakeDispatcher,
            )
        }

        val durationMs = System.currentTimeMillis() - startTime
        if (outcome == null) {
            // 执行超时（属于瞬时超时错误，允许重试）
            workflowRepo.markClaimFailed(
                workflowId = workflowId,
                scheduledAt = scheduledAt,
                claimToken = claimToken,
                error = "Execution timed out after 8 minutes",
                retryable = true,
            )
            TimeCronAlarmScheduler.reconcileNextOccurrence(applicationContext, workflowId)
            return Result.retry()
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
                "WorkflowWorker",
                "Claim ownership for occurrence $scheduledAt of workflow $workflowId was lost (preempted by another execution), terminal update skipped",
            )
        }

        SchedulerDiagnostics.logWorkflowExecution(
            workflowId = workflowId,
            workflowName = loaded.definition.name,
            status = outcome.status.name,
            durationMs = durationMs,
            error = outcome.error,
        )

        TimeCronAlarmScheduler.reconcileNextOccurrence(applicationContext, workflowId)

        return when (outcome.status) {
            WorkflowRunStatus.SUCCESS,
            WorkflowRunStatus.SKIPPED_DISABLED,
            WorkflowRunStatus.SKIPPED_COOLDOWN,
            WorkflowRunStatus.SKIPPED_DAILY_CAP,
            WorkflowRunStatus.SKIPPED_CONDITIONS -> Result.success()
            WorkflowRunStatus.FAILED -> if (isRetryable) Result.retry() else Result.failure()
        }
    }
}

/**
 * Helper that other triggers use to fall back to repository read when snapshot is empty.
 */
internal object TimeCronWorkerHelper {
    suspend fun repositoryLookup(workflowId: String): WorkflowRepository.Loaded? = runCatching {
        val repo = KoinJavaComponent.getKoin().get<WorkflowRepository>()
        repo.getById(workflowId)
    }.getOrNull()
}
