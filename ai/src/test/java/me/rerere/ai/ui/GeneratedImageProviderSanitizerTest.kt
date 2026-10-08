package me.rerere.ai.ui

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedImageProviderSanitizerTest {
    @Test
    fun `generated image is replaced by description while ordinary image is preserved`() {
        val generated = UIMessagePart.Image(
            url = "file:///secret/generated.png",
            metadata = buildJsonObject {
                put("generated_image", true)
                put("description", "一只橘猫坐在窗边")
                put("status", "succeeded")
                put("prompt", "secret detailed prompt")
                put("system_prompt", "secret style prompt")
            }
        )
        val ordinary = UIMessagePart.Image("file:///ordinary.png")

        val parts = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(generated, ordinary)))
            .sanitizeGeneratedImagesForProvider().single().parts

        assertTrue(parts[0] is UIMessagePart.Text)
        assertEquals("AI 曾发送一张图片：一只橘猫坐在窗边", (parts[0] as UIMessagePart.Text).text)
        assertEquals(ordinary, parts[1])
    }

    @Test
    fun `pending and failed statuses remain textual`() {
        fun image(status: String) = UIMessagePart.Image(
            url = "",
            metadata = buildJsonObject {
                put("generated_image", true)
                put("description", "山间小屋")
                put("status", status)
            }
        )
        val sanitized = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(image("pending"), image("failed")))
        ).sanitizeGeneratedImagesForProvider().single().parts.filterIsInstance<UIMessagePart.Text>()

        assertEquals("AI 正在生成一张图片：山间小屋", sanitized[0].text)
        assertEquals("AI 曾尝试生成一张图片：山间小屋，但生成失败", sanitized[1].text)
    }
}
