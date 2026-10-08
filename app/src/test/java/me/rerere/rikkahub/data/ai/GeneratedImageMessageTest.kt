package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.ai.ui.UIMessagePart

class GeneratedImageMessageTest {
    @Test
    fun `system prompt is trimmed and prepended with blank line`() {
        assertEquals(
            "warm watercolor style\nkeep composition simple\n\na cat by the window",
            composeImageGenerationPrompt(
                "  warm watercolor style\nkeep composition simple  ",
                "  a cat by the window  ",
            )
        )
    }

    @Test
    fun `empty system prompt leaves model prompt unchanged`() {
        assertEquals("a cat by the window", composeImageGenerationPrompt("  ", "  a cat by the window  "))
    }

    @Test
    fun `generated image card becomes description before input transforms`() {
        val request = GeneratedImageRequest(
            jobId = "job-1",
            description = "a cat by the window",
            prompt = "watercolor cat",
            systemPrompt = "",
            aspectRatio = "square",
            modelId = "model-1",
        )

        val part = listOf(request.toCardMessage())
            .replaceGeneratedImagesWithDescriptions()
            .single()
            .parts
            .single()

        assertTrue(part is UIMessagePart.Text)
        assertEquals("AI 正在生成一张图片：a cat by the window", (part as UIMessagePart.Text).text)
    }
}
