package me.rerere.rikkahub.data.ai.desire

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AlarmManager 触发的后台主动唤醒接收器。
 * 只负责启动 AutonomousWakeForegroundService，不在此做任何耗时操作。
 * 与 ProactiveMessageReceiver → ProactiveMessageTriggerService 模式一致。
 */
class AutonomousWakeAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AutonomousWakeAlarm"
        const val ACTION_AUTONOMOUS_WAKE = "me.rerere.orangechat.AUTONOMOUS_WAKE"
        const val EXTRA_ASSISTANT_ID = "assistant_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
        const val EXTRA_PLAN_VERSION = "plan_version"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_AUTONOMOUS_WAKE) return
        val assistantId = intent.getStringExtra(EXTRA_ASSISTANT_ID) ?: return
        val scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, System.currentTimeMillis())
        val planVersion = intent.getLongExtra(EXTRA_PLAN_VERSION, -1L)
        Log.d(TAG, "AlarmManager triggered autonomous wake for $assistantId, starting foreground service")
        AutonomousWakeForegroundService.start(context, assistantId, scheduledAt, planVersion)
    }
}
