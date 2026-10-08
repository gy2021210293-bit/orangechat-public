package me.rerere.rikkahub.data.ai.desire

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousWakeTranscriptTest {
    @Test
    fun streamUpdatesDoNotDuplicateHistoryAndRetainEarlierGeneration() {
        val history = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("历史")))
        val tracker = AutonomousWakeTranscript(listOf(history))
        assertTrue(tracker.update(listOf(history)).isEmpty())
        val partial = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("部分")))
        tracker.update(listOf(history, partial))
        val completed = partial.copy(parts = listOf(UIMessagePart.Text("部分与后续内容")))
        assertEquals(listOf(completed), tracker.update(listOf(history, completed)))
        val final = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("收尾")))
        assertEquals(listOf(completed, final), tracker.update(listOf(history, completed, final)))
    }

    @Test
    fun preservesLongSpeechAndToolResultsInOrder() {
        val speech = "完整发言".repeat(1500)
        val result = "完整返回".repeat(1500)
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Text(speech),
                UIMessagePart.Tool(toolCallId = "call-1", toolName = "search", input = "{\"query\":\"测试\"}",
                    output = listOf(UIMessagePart.Text(result))),
            )),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("最终发言"))),
        )
        val text = formatAutonomousWakeTranscript(Json.encodeToString(ListSerializer(UIMessage.serializer()), messages))
        assertTrue(text.contains(speech))
        assertTrue(text.contains(result))
        assertTrue(text.contains("{\"query\":\"测试\"}"))
        assertTrue(text.indexOf(speech) < text.indexOf("工具：search"))
        assertTrue(text.indexOf(result) < text.indexOf("最终发言"))
    }

    @Test
    fun partialToolAndLegacyRecordsAreExplicit() {
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Text("生成到一半"),
            UIMessagePart.Tool(toolCallId = "pending", toolName = "search", input = "{}"),
        )))
        val text = formatAutonomousWakeTranscript(Json.encodeToString(ListSerializer(UIMessage.serializer()), messages))
        assertTrue(text.contains("生成到一半"))
        assertTrue(text.contains("尚无返回内容"))
        assertTrue(formatAutonomousWakeTranscript("[]").contains("旧记录无法补回"))
        assertTrue(formatAutonomousWakeTranscript("broken-json").contains("broken-json"))
    }
}
