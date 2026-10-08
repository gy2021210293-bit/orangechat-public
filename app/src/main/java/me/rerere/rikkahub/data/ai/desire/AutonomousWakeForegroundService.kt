package me.rerere.rikkahub.data.ai.desire

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import org.koin.android.ext.android.inject

/**
 * 由 AlarmManager 触发的后台主动唤醒前台服务。
 * 与 ProactiveMessageTriggerService 相同的模式：Receiver 只负责启动此服务，
 * 真正耗时的 AI 推理在这里运行，不受广播 30 秒时间限制。
 */
class AutonomousWakeForegroundService : Service() {

    companion object {
        private const val TAG = "AutonomousWakeFgSvc"
        private const val NOTIFICATION_ID = 20006
        const val EXTRA_ASSISTANT_ID = "assistant_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
        const val EXTRA_PLAN_VERSION = "plan_version"

        fun start(context: Context, assistantId: String, scheduledAt: Long, planVersion: Long) {
            val intent = Intent(context, AutonomousWakeForegroundService::class.java).apply {
                putExtra(EXTRA_ASSISTANT_ID, assistantId)
                putExtra(EXTRA_SCHEDULED_AT, scheduledAt)
                putExtra(EXTRA_PLAN_VERSION, planVersion)
            }
            context.startForegroundService(intent)
        }
    }

    private val scheduler: AutonomousWakeScheduler by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeAssistantIds = mutableSetOf<String>()
    private val activeLock = Any()
    private var latestStartId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(activeLock) {
            latestStartId = startId
        }
        val assistantId = intent?.getStringExtra(EXTRA_ASSISTANT_ID)
        val scheduledAt = intent?.getLongExtra(EXTRA_SCHEDULED_AT, System.currentTimeMillis())
            ?: System.currentTimeMillis()
        val planVersion = intent?.getLongExtra(EXTRA_PLAN_VERSION, -1L) ?: -1L

        assistantId?.let { scheduler.recordDiagnostic(it, "前台服务已收到启动请求") }

        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("后台主动唤醒中…")
                .setSmallIcon(R.drawable.small_icon)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        )
        assistantId?.let { scheduler.recordDiagnostic(it, "前台服务通知已登记") }

        if (assistantId == null) {
            Log.w(TAG, "No assistant_id, stopping immediately")
            stopIfIdle(startId)
            return START_NOT_STICKY
        }

        synchronized(activeLock) {
            if (!activeAssistantIds.add(assistantId)) {
                Log.d(TAG, "Wake already running for $assistantId, reusing the active service")
                return START_NOT_STICKY
            }
        }

        scope.launch {
            try {
                scheduler.executeWake(assistantId, scheduledAt, planVersion, triggerSource = "alarm")
            } catch (e: Exception) {
                Log.e(TAG, "Autonomous wake failed for $assistantId", e)
                scheduler.schedule(assistantId, delayMs = 15 * 60 * 1000L)
            } finally {
                synchronized(activeLock) {
                    activeAssistantIds.remove(assistantId)
                    if (activeAssistantIds.isEmpty()) {
                        stopSelfResult(latestStartId)
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun stopIfIdle(startId: Int) {
        synchronized(activeLock) {
            if (activeAssistantIds.isEmpty()) {
                stopSelfResult(maxOf(startId, latestStartId))
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
