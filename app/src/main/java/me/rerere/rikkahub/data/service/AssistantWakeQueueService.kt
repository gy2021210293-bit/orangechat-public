package me.rerere.rikkahub.data.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.desire.DesireRepository
import me.rerere.rikkahub.data.ai.desire.DesireScheduler
import me.rerere.rikkahub.data.ai.desire.db.DesireEventReceiptEntity
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.android.ext.android.inject
import java.util.concurrent.atomic.AtomicBoolean

class AssistantWakeQueueService : Service() {
    private val desireRepository: DesireRepository by inject()
    private val desireScheduler: DesireScheduler by inject()
    private val settingsStore: SettingsStore by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val draining = AtomicBoolean(false)
    @Volatile private var latestStartId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        startForeground(
            20005,
            NotificationCompat.Builder(this, CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("正在处理 AI 唤醒…")
                .setSmallIcon(R.drawable.small_icon)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build(),
        )
        if (draining.compareAndSet(false, true)) drain()
        return START_REDELIVER_INTENT
    }

    private fun drain() = scope.launch {
        try {
            while (true) {
                val claimed = AssistantWakeQueue.claimBatch(this@AssistantWakeQueueService)
                if (claimed.isEmpty()) break
                val active = claimed.filter { request -> isStillActive(request) }
                val staleIds = claimed.map { it.id }.toSet() - active.map { it.id }.toSet()
                if (staleIds.isNotEmpty()) {
                    AssistantWakeQueue.complete(this@AssistantWakeQueueService, staleIds)
                }
                if (active.isEmpty()) {
                    AssistantWakeQueue.complete(this@AssistantWakeQueueService, claimed.map { it.id })
                    continue
                }
                // Generation ownership must belong to this batch, not the long-lived drain coroutine.
                val result = scope.async {
                    WorkflowWakeExecutor.executeWakeDetailed(
                        context = this@AssistantWakeQueueService,
                        assistantId = active.first().assistantId,
                        workflowName = "合并后台唤醒",
                        prompt = AssistantWakeQueue.buildCombinedPrompt(active),
                        actionOutput = null,
                        allowAllToolsAndPlugins = active.any { it.allowAllToolsAndPlugins },
                        waitForExistingGeneration = true,
                        requireSpecifiedAssistant = true,
                    )
                }.await()
                if (result.isFailure) {
                    AssistantWakeQueue.defer(this@AssistantWakeQueueService, claimed.map { it.id })
                    AssistantWakeQueueRecoveryWorker.schedule(this@AssistantWakeQueueService)
                    break
                }
                val report = result.getOrThrow()
                active.filter { it.source == "desire" }.forEach { request ->
                    if (report.delivered && report.messageId != null) {
                        desireRepository.recordSuccessfulActiveSend(
                            assistantId = request.assistantId,
                            reason = request.desireReason ?: "unknown",
                            content = report.replyText,
                            conversationId = report.conversationId,
                            messageId = report.messageId,
                        )
                        request.desireEventId?.let { eventId ->
                            desireRepository.insertReceipt(
                                DesireEventReceiptEntity(
                                    eventId = eventId,
                                    assistantId = request.assistantId,
                                    eventName = "active_send",
                                    sourceType = "active_send",
                                    sourceId = report.conversationId,
                                    createdAt = System.currentTimeMillis(),
                                    processedAt = System.currentTimeMillis(),
                                )
                            )
                        }
                    } else {
                        desireRepository.recordPassCooldown(
                            assistantId = request.assistantId,
                            reason = request.desireReason ?: "pass",
                            cooldownDurationMs = 30 * 60 * 1000L,
                        )
                    }
                    desireScheduler.scheduleNextCheck(request.assistantId)
                }
                AssistantWakeQueue.complete(this@AssistantWakeQueueService, claimed.map { it.id })
            }
        } finally {
            draining.set(false)
            if (AssistantWakeQueue.hasPending(this@AssistantWakeQueueService)) {
                AssistantWakeQueueRecoveryWorker.schedule(this@AssistantWakeQueueService)
            }
            stopSelfResult(latestStartId)
        }
    }

    private suspend fun isStillActive(request: AssistantWakeRequest): Boolean {
        if (request.source != "desire") {
            return request.assistantId.isBlank() || settingsStore.settingsFlow.value.assistants
                .any { it.id.toString() == request.assistantId }
        }
        if (request.desireEventId?.let { desireRepository.isReceiptExists(it) } == true) return false
        val assistant = settingsStore.settingsFlow.value.assistants
            .find { it.id.toString() == request.assistantId } ?: return false
        if (!me.rerere.rikkahub.data.ai.desire.LegacyDesireFeature.enabled || !assistant.desireSystemEnabled || !assistant.desireActiveSendEnabled) return false
        val absentHours = desireScheduler.getAbsentHours(request.assistantId)
        return desireRepository.checkShouldSendActiveMessage(
            assistantId = request.assistantId,
            absentHours = absentHours,
            nowMs = System.currentTimeMillis(),
            assistant = assistant,
        ).shouldSend
    }

    override fun onDestroy() {
        if (AssistantWakeQueue.hasPending(this)) AssistantWakeQueueRecoveryWorker.schedule(this)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, AssistantWakeQueueService::class.java))
        }
    }
}
