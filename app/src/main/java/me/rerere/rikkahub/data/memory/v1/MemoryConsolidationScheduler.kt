/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.MemoryMode
import org.koin.core.context.GlobalContext
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

private const val TAG = "MemoryConsolidationScheduler"

object MemoryConsolidationScheduler {
    const val UNIQUE_WORK_NAME = "memory_consolidation_work"

    /**
     * 计算至下一次本地时间 04:00 的延迟毫秒数，并向 WorkManager 注册唯一后台任务。
     * 固定将目标整理日期写入 InputData，确保在网络故障等指数退避重试跨天时不会发生日期漂移或遗漏原失败日期。
     * 默认使用 KEEP 策略，防止 App 冷启动时意外覆盖并取消正在进行指数退避重试的任务。
     */
    fun scheduleDaily(context: Context, forceReplace: Boolean = false) {
        val now = ZonedDateTime.now()
        var next04AM = now.toLocalDate().atTime(4, 0).atZone(ZoneId.systemDefault())
        if (now.isAfter(next04AM)) {
            next04AM = next04AM.plusDays(1)
        }
        val delayMs = Duration.between(now, next04AM).toMillis().coerceAtLeast(1000L)

        // 目标记忆日：在 next04AM 执行时，整理的记忆日应当是当时的昨天
        val targetMemoryDate = MemoryDateUtils.getYesterdayMemoryDate(next04AM.toInstant().toEpochMilli())

        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<MemoryConsolidationWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    MemoryConsolidationWorker.KEY_MEMORY_DATE to targetMemoryDate
                )
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        val policy = if (forceReplace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            policy,
            workRequest
        )
        Log.i(TAG, "Scheduled next daily consolidation for $targetMemoryDate in ${delayMs / 1000 / 60} minutes (at $next04AM, policy=$policy)")
    }

    /**
     * 冷启动或进入前台时的补偿机制：
     * 扫描各助手是否存在未完成的记忆日（包含昨天、历史 PENDING 以及历史未超限的 FAILED），在后台自动补跑。
     */
    fun checkAndRunCompensation(
        context: Context,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    ) {
        scope.launch {
            try {
                val koin = GlobalContext.getOrNull() ?: return@launch
                val settingsStore = koin.getOrNull<SettingsStore>() ?: return@launch
                val repository = koin.getOrNull<MemoryRepositoryV1>() ?: return@launch
                val consolidationService = koin.getOrNull<MemoryConsolidationService>() ?: return@launch

                val settings = settingsStore.settingsFlow.first()
                val targetAssistants = settings.assistants.filter {
                    it.effectiveMemoryMode == MemoryMode.LOCAL_V1 && it.dailyConsolidationEnabled
                }
                if (targetAssistants.isEmpty()) return@launch

                val yesterday = MemoryDateUtils.getYesterdayMemoryDate()

                for (assistant in targetAssistants) {
                    val asstId = assistant.id.toString()
                    // 1. 检查昨日是否尚未有成功的整理记录（包含从未运行、待处理、或昨夜临时失败且未超限）
                    val yesterdayRun = repository.getConsolidationRun(asstId, yesterday)
                    val needsYesterdayCompensation = yesterdayRun == null ||
                        yesterdayRun.status == MemoryConstants.RUN_STATUS_PENDING ||
                        (yesterdayRun.status == MemoryConstants.RUN_STATUS_FAILED && yesterdayRun.attemptCount < 3)
                    if (needsYesterdayCompensation) {
                        Log.i(TAG, "Compensation: consolidating yesterday ($yesterday) for assistant ${assistant.name}")
                        consolidationService.consolidateDate(
                            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                            ownerId = asstId,
                            memoryDate = yesterday,
                            force = false,
                        )
                    }

                    // 2. 检查是否有历史待处理任务 (PENDING)
                    val pendingRuns = repository.getPendingRuns(MemoryConstants.OWNER_TYPE_ASSISTANT, asstId)
                    for (pending in pendingRuns) {
                        if (pending.memoryDate != yesterday) {
                            Log.i(TAG, "Compensation: executing pending consolidation for date ${pending.memoryDate}")
                            consolidationService.consolidateDate(
                                ownerType = pending.ownerType,
                                ownerId = pending.ownerId,
                                memoryDate = pending.memoryDate,
                                force = false,
                            )
                        }
                    }

                    // 3. 检查是否有历史未超限的失败任务 (FAILED 且 attemptCount < 3)，即使错过了昨日也能被拉起重试
                    val failedRuns = repository.getFailedRuns(MemoryConstants.OWNER_TYPE_ASSISTANT, asstId, maxAttempts = 3)
                    for (failed in failedRuns) {
                        if (failed.memoryDate != yesterday) {
                            Log.i(TAG, "Compensation: retrying failed consolidation for date ${failed.memoryDate} (attempt ${failed.attemptCount})")
                            consolidationService.consolidateDate(
                                ownerType = failed.ownerType,
                                ownerId = failed.ownerId,
                                memoryDate = failed.memoryDate,
                                force = false,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error during consolidation compensation check", e)
            }
        }
    }
}

