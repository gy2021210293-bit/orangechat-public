/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DesireTickWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    companion object {
        private const val TAG = "DesireTickWorker"
    }

    private val scheduler: DesireScheduler by inject()

    override suspend fun doWork(): Result {
        val assistantId = inputData.getString("assistant_id") ?: return Result.failure()
        val slotEpochMs = inputData.getLong("slot_epoch_ms", System.currentTimeMillis())
        Log.d(TAG, "DesireTickWorker doWork for $assistantId, slot: $slotEpochMs")
        return try {
            scheduler.runTick(assistantId, triggerSource = "work_manager", slotEpochMs = slotEpochMs)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to run tick for $assistantId", e)
            Result.retry()
        }
    }
}
