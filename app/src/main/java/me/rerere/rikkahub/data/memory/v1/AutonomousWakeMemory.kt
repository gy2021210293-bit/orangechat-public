package me.rerere.rikkahub.data.memory.v1

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.desire.db.AutonomousWakeRunEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEventChunkEntity
import java.security.MessageDigest

/** 后台原始记录作为独立证据归档，不伪装成聊天消息。 */
internal fun autonomousWakeMemoryChunk(run: AutonomousWakeRunEntity): MemoryEventChunkEntity? {
    if (run.status !in setOf("completed", "failed", "interrupted")) return null
    if (run.summary.isBlank() && run.outcome.isBlank() && run.toolCallsJson in setOf("", "[]") && run.transcriptJson in setOf("", "[]")) return null
    val timestamp = run.finishedAt ?: return null
    val sourceId = "autonomous:${run.id}"
    val content = buildString {
        appendLine("[后台自主经历；不是聊天对话]")
        appendLine("运行状态：${run.status}")
        appendLine("summary（我的记录）：${run.summary}")
        appendLine("outcome（我的结果与感想）：${run.outcome}")
        if (run.nextStep.isNotBlank()) appendLine("下一步：${run.nextStep}")
        if (run.transcriptJson.isNotBlank() && run.transcriptJson != "[]") {
            appendLine(autonomousTranscriptText(run.transcriptJson, run.toolCallsJson))
        }
        if (run.toolCallsJson.isNotBlank() && run.toolCallsJson != "[]") {
            appendLine("工具动作原始记录（以各动作状态判断是否成功）：")
            appendLine(run.toolCallsJson)
        }
        if (run.errorText.isNotBlank()) appendLine("运行错误：${run.errorText}")
    }.trim()
    return MemoryEventChunkEntity(
        id = sourceId,
        ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
        ownerId = run.assistantId,
        conversationId = sourceId,
        summary = content,
        startTime = timestamp,
        endTime = timestamp,
        messageIds = "[\"$sourceId\"]",
        status = "AUTONOMOUS_SOURCE",
        fingerprint = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) },
        createdAt = timestamp,
    )
}

internal fun MemoryEventChunkEntity.asAutonomousMemoryMessage(): CleanedDialogueMessage? {
    if (status != "AUTONOMOUS_SOURCE" || !id.startsWith("autonomous:")) return null
    return CleanedDialogueMessage(
        conversationId = id,
        messageId = id,
        role = "AUTONOMOUS",
        content = summary,
        timestamp = startTime,
    )
}

private fun autonomousTranscriptText(transcriptJson: String, toolCallsJson: String): String = runCatching {
    val messages = Json { ignoreUnknownKeys = true }.decodeFromString(
        ListSerializer(UIMessage.serializer()), transcriptJson,
    )
    val loggedIds = runCatching {
        Json.parseToJsonElement(toolCallsJson).jsonArray.mapNotNull {
            it.jsonObject["tool_call_id"]?.jsonPrimitive?.contentOrNull
        }.toSet()
    }.getOrDefault(emptySet())
    messages.joinToString("\n\n") { message ->
        // 已登记动作以工具日志为准；尚未登记的调用仍保留，避免流式中断时遗漏。
        memoryMessageText(message.parts.filter { part ->
            when (part) {
                is UIMessagePart.Text -> true
                is UIMessagePart.Tool -> part.toolCallId !in loggedIds
                is UIMessagePart.ToolCall -> part.toolCallId !in loggedIds
                is UIMessagePart.ToolResult -> part.toolCallId !in loggedIds
                else -> false
            }
        })
    }
}.getOrElse { transcriptJson }
