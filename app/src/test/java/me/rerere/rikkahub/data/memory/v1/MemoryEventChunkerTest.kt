/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEventChunkerTest {

    @Test
    fun `empty messages return empty list`() {
        val chunks = MemoryEventChunker.chunkMessages("assistant", "ast-1", emptyList())
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `same conversation within 2 hours grouped into one chunk`() {
        val baseTime = 1725800000000L
        val messages = listOf(
            CleanedDialogueMessage(
                conversationId = "c1",
                messageId = "m1",
                role = "USER",
                content = "你好小雨",
                timestamp = baseTime,
            ),
            CleanedDialogueMessage(
                conversationId = "c1",
                messageId = "m2",
                role = "ASSISTANT",
                content = "你好呀！",
                timestamp = baseTime + 5 * 60 * 1000L,
            ),
        )

        val chunks = MemoryEventChunker.chunkMessages("assistant", "ast-1", messages)
        assertEquals(1, chunks.size)
        val chunk = chunks.first()
        assertEquals("c1", chunk.conversationId)
        assertEquals(baseTime, chunk.startTime)
        assertEquals(baseTime + 5 * 60 * 1000L, chunk.endTime)
        assertTrue(chunk.summary.contains("对话事件块"))
        assertTrue(!chunk.summary.contains("你好小雨"))
        assertTrue(chunk.fingerprint.isNotBlank())
        assertTrue(chunk.id.startsWith("chunk_"))

        // 验证重新整理切块时生成的 ID 是确定且稳定的
        val chunksAgain = MemoryEventChunker.chunkMessages("assistant", "ast-1", messages)
        assertEquals(chunk.id, chunksAgain.first().id)
    }

    @Test
    fun `different conversations split into distinct chunks`() {
        val baseTime = 1725800000000L
        val messages = listOf(
            CleanedDialogueMessage(
                conversationId = "c1",
                messageId = "m1",
                role = "USER",
                content = "今天天气如何",
                timestamp = baseTime,
            ),
            CleanedDialogueMessage(
                conversationId = "c2",
                messageId = "m2",
                role = "USER",
                content = "帮我看看代码",
                timestamp = baseTime + 10 * 1000L,
            ),
        )

        val chunks = MemoryEventChunker.chunkMessages("assistant", "ast-1", messages)
        assertEquals(2, chunks.size)
        assertEquals("c1", chunks[0].conversationId)
        assertEquals("c2", chunks[1].conversationId)
    }

    @Test
    fun `same conversation split when inactivity exceeds 2 hours`() {
        val baseTime = 1725800000000L
        val messages = listOf(
            CleanedDialogueMessage(
                conversationId = "c1",
                messageId = "m1",
                role = "USER",
                content = "上午的讨论",
                timestamp = baseTime,
            ),
            CleanedDialogueMessage(
                conversationId = "c1",
                messageId = "m2",
                role = "USER",
                content = "下午回来的讨论",
                timestamp = baseTime + 3 * 3600 * 1000L, // 3 小时后
            ),
        )

        val chunks = MemoryEventChunker.chunkMessages("assistant", "ast-1", messages)
        assertEquals(2, chunks.size)
        assertEquals(baseTime, chunks[0].startTime)
        assertEquals(baseTime + 3 * 3600 * 1000L, chunks[1].startTime)
    }

    @Test
    fun `splits when chunk exceeds max characters`() {
        val baseTime = 1725800000000L
        val longContent = "A".repeat(5000)
        val messages = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", longContent, baseTime),
            CleanedDialogueMessage("c1", "m2", "USER", longContent, baseTime + 1000L),
        )

        val chunks = MemoryEventChunker.chunkMessages("assistant", "ast-1", messages)
        assertEquals(2, chunks.size)
    }
}
