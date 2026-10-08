package me.rerere.rikkahub.data.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantWakeQueueTest {
    @Test
    fun `batch merges only wakes for the first assistant`() {
        val requests = listOf(
            request("one", "assistant-a", "desire"),
            request("two", "assistant-b", "workflow"),
            request("three", "assistant-a", "plugin"),
        )

        assertEquals(listOf("one", "three"), AssistantWakeQueue.selectBatch(requests).map { it.id })
    }

    @Test
    fun `batch is bounded and keeps FIFO order`() {
        val requests = (1..10).map { request(it.toString(), "assistant-a", "plugin") }

        assertEquals((1..8).map { it.toString() }, AssistantWakeQueue.selectBatch(requests).map { it.id })
    }

    @Test
    fun `deferred wake does not block a later eligible assistant`() {
        val requests = listOf(
            request("deferred", "assistant-a", "workflow").copy(notBeforeMs = 100L),
            request("ready", "assistant-b", "plugin"),
        )

        assertEquals(listOf("ready"), AssistantWakeQueue.selectBatch(requests, nowMs = 0L).map { it.id })
    }

    @Test
    fun `combined prompt preserves every request and mandatory wake cannot pass`() {
        val prompt = AssistantWakeQueue.buildCombinedPrompt(
            listOf(
                request("one", "assistant-a", "desire", prompt = "我有点想念用户"),
                request("two", "assistant-a", "plugin", prompt = "检查朋友圈待回复"),
            ),
        )

        assertTrue(prompt.contains("我有点想念用户"))
        assertTrue(prompt.contains("检查朋友圈待回复"))
        assertTrue(prompt.contains("不允许用 [PASS]"))
    }

    @Test
    fun `desire only batch may still pass`() {
        val prompt = AssistantWakeQueue.buildCombinedPrompt(
            listOf(request("one", "assistant-a", "desire")),
        )

        assertFalse(prompt.contains("不允许用 [PASS]"))
    }

    private fun request(
        id: String,
        assistantId: String,
        source: String,
        prompt: String = id,
    ) = AssistantWakeRequest(
        id = id,
        assistantId = assistantId,
        source = source,
        sourceName = source,
        prompt = prompt,
    )
}
