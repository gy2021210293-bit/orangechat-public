/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.service.ProactiveMessageTriggerService
import me.rerere.rikkahub.data.service.AssistantWakeCoordinator
import me.rerere.rikkahub.data.service.AssistantWakeRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

class DesireScheduler(
    private val context: Context,
    private val desireRepository: DesireRepository,
    private val conversationRepository: ConversationRepository,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "DesireScheduler"

        internal fun buildDesireActiveContext(
            reason: String,
            assistant: Assistant? = null,
            nickname: String = "",
        ): String = buildString {
            appendLine("## 主动联络契机")
            val promptText = DesireActiveSendPolicy.getActiveSendPrompt(assistant, reason, nickname)
            appendLine("触发原因：$promptText")
            appendLine()
            appendLine("【表达要求】")
            appendLine("- 严禁无营养的查户口式空洞发问（如“在干嘛”、“吃了吗”、“睡了吗”、“我问你个事”等）。")
            appendLine("- 必须带上具体的事情、真实的感受或有趣的日常细节来开启话题（例如具体分享自己看到/想到的事，或者表达明确的心理感受），而不是把接话压力抛给对方。")
        }
    }

    private val assistantLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun scheduleNextCheck(assistantId: String, delaySeconds: Long? = null) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val nowMs = System.currentTimeMillis()

        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.assistants.find { it.id.toString() == assistantId }
        if (assistant == null || !LegacyDesireFeature.enabled || !assistant.desireSystemEnabled) {
            cancel(assistantId)
            return
        }

        val targetCheckAt = if (delaySeconds != null && delaySeconds > 0) {
            nowMs + delaySeconds * 1000L
        } else {
            calculateNextCheckAt(assistant, nowMs)
        }
        val delayMs = (targetCheckAt - nowMs).coerceAtLeast(1000L)

        // 1. AlarmManager (非精确延迟容忍唤醒)
        if (alarmManager != null) {
            val intent = Intent(context, DesireAlarmReceiver::class.java).apply {
                action = DesireAlarmReceiver.ACTION_DESIRE_TICK
                putExtra(DesireAlarmReceiver.EXTRA_ASSISTANT_ID, assistantId)
                putExtra(DesireAlarmReceiver.EXTRA_SLOT_EPOCH_MS, targetCheckAt)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                assistantId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetCheckAt, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, targetCheckAt, pendingIntent)
            }
        }

        // 2. WorkManager 作为可靠保障与系统恢复兜底
        val workData = workDataOf(
            "assistant_id" to assistantId,
            "slot_epoch_ms" to targetCheckAt,
        )
        val workRequest = OneTimeWorkRequestBuilder<DesireTickWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workData)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "desire_tick_$assistantId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        Log.d(TAG, "Scheduled desire check for $assistantId in ${delayMs / 1000}s (target: $targetCheckAt)")
    }

    suspend fun scheduleNextTick(assistantId: String, delaySeconds: Long? = null) {
        scheduleNextCheck(assistantId, delaySeconds)
    }

    suspend fun calculateNextCheckAt(assistant: Assistant, nowMs: Long): Long {
        val absentHours = getAbsentHours(assistant.id.toString())
        val lastUserMsgEpochMs = if (absentHours < 900.0) {
            (nowMs - (absentHours * 3600.0 * 1000.0)).toLong()
        } else {
            0L
        }

        val candidates = mutableListOf<Long>()

        // 1. presentBoundaryAt: 最后消息 + 30 分钟
        if (lastUserMsgEpochMs > 0L) {
            val presentBoundaryAt = lastUserMsgEpochMs + 30 * 60 * 1000L
            if (presentBoundaryAt > nowMs) {
                candidates.add(presentBoundaryAt)
            }

            // 2. minAbsentBoundaryAt: 最后消息 + 3 小时
            val minAbsentBoundaryAt = lastUserMsgEpochMs + 3 * 3600 * 1000L
            if (minAbsentBoundaryAt > nowMs) {
                candidates.add(minAbsentBoundaryAt)
            }

            // 3. longAbsentBoundaryAt: 最后消息 + 6 小时
            val longAbsentBoundaryAt = lastUserMsgEpochMs + 6 * 3600 * 1000L
            if (longAbsentBoundaryAt > nowMs) {
                candidates.add(longAbsentBoundaryAt)
            }
        }

        // 4. passCooldownEndAt
        val passCooldownEndAt = desireRepository.getPassCooldownEnd(assistant.id.toString())
        if (passCooldownEndAt > nowMs) {
            candidates.add(passCooldownEndAt)
        }

        // 5. 预计阈值越过或常规兜底检查（最多 30 分钟）
        val defaultIntervalMs = 30 * 60 * 1000L
        candidates.add(nowMs + defaultIntervalMs)

        val earliest = candidates.minOrNull() ?: (nowMs + defaultIntervalMs)
        return maxOf(nowMs + 60 * 1000L, earliest)
    }

    fun cancel(assistantId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val intent = Intent(context, DesireAlarmReceiver::class.java).apply {
            action = DesireAlarmReceiver.ACTION_DESIRE_TICK
            putExtra(DesireAlarmReceiver.EXTRA_ASSISTANT_ID, assistantId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            assistantId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager?.cancel(pendingIntent)
        WorkManager.getInstance(context).cancelUniqueWork("desire_tick_$assistantId")
        Log.d(TAG, "Cancelled desire tick for $assistantId")
    }

    suspend fun rescheduleAll() {
        val settings = settingsStore.settingsFlow.first()
        settings.assistants.forEach { assistant ->
            if (LegacyDesireFeature.enabled && assistant.desireSystemEnabled) {
                scheduleNextCheck(assistant.id.toString())
            } else {
                cancel(assistant.id.toString())
            }
        }
    }

    suspend fun runTick(
        assistantId: String,
        triggerSource: String = "unknown",
        slotEpochMs: Long = System.currentTimeMillis(),
    ): TickResult? = assistantLocks.computeIfAbsent(assistantId) { Mutex() }.withLock {
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.assistants.find { it.id.toString() == assistantId }
        if (assistant == null || !LegacyDesireFeature.enabled || !assistant.desireSystemEnabled) {
            cancel(assistantId)
            return@withLock null
        }

        val nowMs = System.currentTimeMillis()
        val absentHours = getAbsentHours(assistantId)
        val isMasterPresent = absentHours < DesireActiveSendPolicy.PRESENT_THRESHOLD_HOURS

        // 本轮当前检查（使用 slotEpochMs 保证 Alarm 与 Worker 去重）
        val slotEventId = "${assistantId}_check_$slotEpochMs"
        val tickResult = desireRepository.runTickOnce(
            assistantId = assistantId,
            tickEventId = slotEventId,
            isMasterPresent = isMasterPresent,
            nowMs = nowMs,
            customThoughtTemplates = assistant.desireCustomThoughtTemplates,
        )

        // 主动消息检查与触发
        if (assistant.desireActiveSendEnabled && tickResult != null) {
            val decision = desireRepository.checkShouldSendActiveMessage(
                assistantId = assistantId,
                absentHours = absentHours,
                nowMs = nowMs,
                assistant = assistant,
            )
            if (decision.shouldSend && decision.reason != null) {
                triggerActiveSend(
                    assistant = assistant,
                    reason = decision.reason,
                    nowMs = nowMs,
                    allowAllToolsAndPlugins = settings.proactiveMessageSetting.allowAllToolsAndPlugins,
                )
            }
        }

        // 预约下一次有意义的检查时间点
        scheduleNextCheck(assistantId)

        tickResult
    }

    suspend fun getAbsentHours(assistantId: String): Double {
        val nowMs = System.currentTimeMillis()
        val assistantUuid = runCatching { Uuid.parse(assistantId) }.getOrNull() ?: return 999.0
        val recentConversations = conversationRepository.getRecentConversations(assistantUuid, limit = 5)
        var latestUserMsgTimeMs = 0L
        for (conv in recentConversations) {
            for (msg in conv.currentMessages) {
                if (msg.role == MessageRole.USER) {
                    val isSyntheticContext = msg.metadata?.let { meta ->
                        meta.containsKey("dynamic_environment") ||
                            meta.containsKey("memory_context") ||
                            meta.containsKey("plugin_context") ||
                            meta.containsKey("stable_self_context") ||
                            meta.containsKey("desire_state_context")
                    } ?: false
                    if (!isSyntheticContext) {
                        val epochMs = runCatching {
                            msg.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                        }.getOrDefault(0L)
                        if (epochMs > latestUserMsgTimeMs) {
                            latestUserMsgTimeMs = epochMs
                        }
                    }
                }
            }
        }
        if (latestUserMsgTimeMs <= 0L) return 999.0
        return ((nowMs - latestUserMsgTimeMs).toDouble() / (3600.0 * 1000.0)).coerceAtLeast(0.0)
    }

    private fun triggerActiveSend(
        assistant: Assistant,
        reason: String,
        nowMs: Long,
        allowAllToolsAndPlugins: Boolean,
    ) {
        val desireContextText = buildDesireActiveContext(
            reason, assistant, settingsStore.settingsFlow.value.displaySetting.userNickname,
        )
        AssistantWakeCoordinator.submit(
            context,
            AssistantWakeRequest(
                assistantId = assistant.id.toString(),
                source = "desire",
                sourceName = "情感主动联络",
                prompt = desireContextText,
                allowAllToolsAndPlugins = allowAllToolsAndPlugins,
                desireReason = reason,
                desireEventId = "${assistant.id}_active_send_$nowMs",
            ),
        )
    }
}
