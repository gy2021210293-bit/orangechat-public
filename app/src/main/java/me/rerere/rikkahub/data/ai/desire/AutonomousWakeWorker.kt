package me.rerere.rikkahub.data.ai.desire

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AutonomousWakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    companion object {
        private const val TAG = "AutonomousWakeWorker"
    }

    private val scheduler: AutonomousWakeScheduler by inject()

    override suspend fun doWork(): Result {
        val assistantId = inputData.getString("assistant_id") ?: return Result.failure()
        val scheduledAt = inputData.getLong("scheduled_at", System.currentTimeMillis())
        val planVersion = inputData.getLong("plan_version", -1L)
        Log.d(TAG, "WorkManager triggered autonomous wake for $assistantId")
        return try {
            // executeWake 内部已负责调度下一次周期或15分钟后重试，此处统一返回 success 避免与 WorkManager 自身 backoff 重试产生双重并发
            scheduler.executeWake(assistantId, scheduledAt, planVersion, triggerSource = "worker")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed autonomous wake work for $assistantId", e)
            Result.failure()
        }
    }
}
