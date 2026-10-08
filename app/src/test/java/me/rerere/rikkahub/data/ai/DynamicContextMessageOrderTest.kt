package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicContextMessageOrderTest {
    @Test
    fun insertsSnapshotImmediatelyBeforeCurrentUserMessage() {
        val messages = listOf(
            UIMessage.user("old question"),
            UIMessage.assistant("old answer"),
            UIMessage.user("current question"),
        )

        val result = insertDynamicContextBeforeCurrentUser(
            messages = messages,
            dynamicContext = "<dynamic_context generated_at=\"07-29 17:10\">snapshot</dynamic_context>",
        )

        assertEquals(
            listOf(
                "old question",
                "old answer",
                "<dynamic_context generated_at=\"07-29 17:10\">snapshot</dynamic_context>",
                "current question",
            ),
            result.map(::text),
        )
        assertTrue(
            result[2].metadata
                ?.get("dynamic_environment")
                ?.toString()
                ?.toBoolean() == true
        )
        assertEquals("07-29 17:10", result[2].metadata?.get("generated_at")?.toString()?.trim('"'))
    }

    @Test
    fun doesNotInjectWithoutCurrentUserMessage() {
        val messages = listOf(UIMessage.assistant("answer"))

        assertEquals(
            messages,
            insertDynamicContextBeforeCurrentUser(messages, "<dynamic_context>snapshot</dynamic_context>"),
        )
    }

    private fun text(message: UIMessage): String = message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }
}
