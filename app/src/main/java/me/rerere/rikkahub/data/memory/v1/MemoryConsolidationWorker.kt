/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.MemoryMode
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "MemoryConsolidationWorker"

/**
 * 每日主模型整理 WorkManager 任务：
 * 每日 04:00 由系统触发，针对开启了 LOCAL_V1 且启用每日整理的助手，整理前一天的记忆日。
 */
class MemoryConsolidationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val consolidationService: MemoryConsolidationService by inject()
    private val settingsStore: SettingsStore by inject()

    companion object {
        const val KEY_MEMORY_DATE = "memory_date"
        const val MAX_WORKER_RETRIES = 3
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "MemoryConsolidationWorker triggered (runAttemptCount=$runAttemptCount)")
        try {
            val settings = settingsStore.settingsFlow.first()
            val targetAssistants = settings.assistants.filter {
                it.effectiveMemoryMode == MemoryMode.LOCAL_V1 && it.dailyConsolidationEnabled
            }

            if (targetAssistants.isEmpty()) {
                Log.d(TAG, "No assistants configured for local v1 daily consolidation")
                MemoryConsolidationScheduler.scheduleDaily(applicationContext, forceReplace = true)
                return Result.success()
            }

            val targetDate = inputData.getString(KEY_MEMORY_DATE)
                ?: MemoryDateUtils.getYesterdayMemoryDate()
            Log.i(TAG, "Consolidating date ($targetDate) for ${targetAssistants.size} assistant(s)")

            var hasFailed = false
            for (assistant in targetAssistants) {
                try {
                    val result = consolidationService.consolidateDate(
                        ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                        ownerId = assistant.id.toString(),
                        memoryDate = targetDate,
                        force = false,
                    )
                    if (result is ConsolidationServiceResult.Failed) {
                        Log.w(TAG, "Consolidation failed for assistant ${assistant.name}: ${result.message} (code=${result.errorCode})")
                        hasFailed = true
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Consolidation threw exception for assistant ${assistant.name} (${assistant.id})", e)
                    hasFailed = true
                }
            }

            if (hasFailed && runAttemptCount < MAX_WORKER_RETRIES) {
                Log.w(TAG, "One or more assistants failed consolidation; requesting retry (attempt=$runAttemptCount)")
                return Result.retry()
            }

            // 成功或达到最大重试次数后，调度下一次 04:00 任务
            MemoryConsolidationScheduler.scheduleDaily(applicationContext, forceReplace = true)
            return if (hasFailed) Result.failure() else Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "MemoryConsolidationWorker execution failed", e)
            return if (runAttemptCount < MAX_WORKER_RETRIES) Result.retry() else Result.failure()
        }
    }
}

