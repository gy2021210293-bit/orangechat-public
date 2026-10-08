/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DesireAlarmReceiver : BroadcastReceiver(), KoinComponent {

    companion object {
        private const val TAG = "DesireAlarmReceiver"
        const val ACTION_DESIRE_TICK = "me.rerere.orangechat.DESIRE_TICK"
        const val EXTRA_ASSISTANT_ID = "assistant_id"
        const val EXTRA_SLOT_EPOCH_MS = "slot_epoch_ms"
    }

    private val scheduler: DesireScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "DesireAlarmReceiver onReceive action: $action")
        when (action) {
            ACTION_DESIRE_TICK -> {
                val assistantId = intent.getStringExtra(EXTRA_ASSISTANT_ID) ?: return
                val slotEpochMs = intent.getLongExtra(EXTRA_SLOT_EPOCH_MS, System.currentTimeMillis())
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        scheduler.runTick(assistantId, triggerSource = "alarm_manager", slotEpochMs = slotEpochMs)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error running tick for $assistantId", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
