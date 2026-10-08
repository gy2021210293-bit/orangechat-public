package me.rerere.rikkahub.data.ai.desire

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import kotlin.uuid.Uuid
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

private val transcriptFormat = Json { ignoreUnknownKeys = true }

fun formatAutonomousWakeTranscript(transcriptJson: String): String {
    if (transcriptJson.isBlank() || transcriptJson == "[]") {
        return "（此记录没有保存完整过程；旧记录无法补回）"
    }
    return runCatching {
        val messages = transcriptFormat.decodeFromString(
            ListSerializer(UIMessage.serializer()), transcriptJson,
        )
        buildString {
            fun appendPart(part: UIMessagePart) {
                when (part) {
                    is UIMessagePart.Text -> appendLine("发言：\n${part.text}")
                    is UIMessagePart.Reasoning -> if (part.reasoning.isNotBlank()) {
                        appendLine("模型返回的思考内容：\n${part.reasoning}")
                    }
                    is UIMessagePart.Tool -> {
                        appendLine("工具：${part.toolName} · 调用 ID：${part.toolCallId}")
                        appendLine("参数：\n${part.input}")
                        appendLine("审批状态：${part.approvalState}")
                        appendLine(if (part.isExecuted) "返回内容：" else "（尚无返回内容）")
                        part.output.forEach { appendPart(it) }
                    }
                    else -> appendLine(Json.encodeToString(UIMessagePart.serializer(), part))
                }
            }
            messages.forEachIndexed { index, message ->
                appendLine("\n${index + 1}. AI · ${message.createdAt}")
                message.parts.forEach { appendPart(it) }
            }
        }
    }.getOrElse { "记录格式无法解析，以下为完整原始记录：\n$transcriptJson" }
}

internal class AutonomousWakeTranscript(initialMessages: List<UIMessage>) {
    private val initialIds = initialMessages.map { it.id }.toSet()
    private val generated = linkedMapOf<Uuid, UIMessage>()

    fun update(snapshot: List<UIMessage>): List<UIMessage> {
        snapshot.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT && it.id !in initialIds }?.let {
            generated[it.id] = it
        }
        return generated.values.toList()
    }
}
