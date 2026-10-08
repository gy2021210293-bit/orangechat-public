package me.rerere.rikkahub.data.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Compatibility entry point for recovery work persisted by versions using the plugin-only queue. */
class PluginAiWakeQueueRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        AssistantWakeQueue.migrateLegacyPluginQueue(applicationContext)
        if (!AssistantWakeQueue.hasPending(applicationContext)) return Result.success()
        AssistantWakeQueueRecoveryWorker.schedule(applicationContext)
        return runCatching { AssistantWakeQueueService.start(applicationContext) }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}
