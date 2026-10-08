package me.rerere.rikkahub.data.memory.v1

import me.rerere.rikkahub.data.ai.desire.db.AutonomousWakeRunEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class AutonomousWakeMemoryTest {
    private fun run() = AutonomousWakeRunEntity(
        id = "reading-run", assistantId = "reader", scheduledAt = 100L,
        finishedAt = 200L, status = "completed", summary = "读了狐狸一章",
        outcome = "我读到：" + "阅读正文".repeat(1000) + "末尾句子",
        toolCallsJson = """[{"name":"read_book","status":"completed","result":"章节原文"}]""",
    )

    @Test
    fun `archive preserves full reading outcome and actions with a separate source`() {
        val run = run()
        val chunk = autonomousWakeMemoryChunk(run)!!
        assertTrue(chunk.summary.contains(run.outcome))
        assertTrue(chunk.summary.contains(run.toolCallsJson))
        assertTrue(chunk.summary.contains("末尾句子"))
        val source = chunk.asAutonomousMemoryMessage()!!
        assertEquals("AUTONOMOUS", source.role)
        assertEquals("autonomous:reading-run", source.messageId)
        assertEquals(chunk.summary, source.content)
        assertEquals(200L, source.timestamp)
    }

    @Test
    fun `failed action remains failed evidence and live runs are excluded`() {
        val failed = run().copy(status = "failed", errorText = "读取失败")
        val content = autonomousWakeMemoryChunk(failed)!!.summary
        assertTrue(content.contains("运行状态：failed"))
        assertTrue(content.contains("读取失败"))
        assertNull(autonomousWakeMemoryChunk(run().copy(status = "running")))
        assertNull(autonomousWakeMemoryChunk(run().copy(status = "waiting")))
        assertNull(autonomousWakeMemoryChunk(run().copy(finishedAt = null)))
        assertNull(autonomousWakeMemoryChunk(run().copy(summary = "", outcome = "", toolCallsJson = "[]")))
    }
    @Test
    fun `full transcript retains uncertain tool state without duplicating its result`() {
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Text("我想到一个没有写进摘要的念头"),
            UIMessagePart.Reasoning("内部思考不进入记忆"),
            UIMessagePart.Tool("c1", "read_book", "{}", listOf(UIMessagePart.Text("章节原文"))),
        )))
        val source = autonomousWakeMemoryChunk(run().copy(
            transcriptJson = Json.encodeToString(ListSerializer(UIMessage.serializer()), messages),
            toolCallsJson = """[{"tool_call_id":"c1","name":"read_book","status":"result_uncertain","result":"章节原文"}]""",
        ))!!.summary
        assertTrue(source.contains("我想到一个没有写进摘要的念头"))
        assertTrue(source.contains("result_uncertain"))
        assertEquals(1, Regex("章节原文").findAll(source).count())
        assertFalse(source.contains("内部思考不进入记忆"))
    }

}
