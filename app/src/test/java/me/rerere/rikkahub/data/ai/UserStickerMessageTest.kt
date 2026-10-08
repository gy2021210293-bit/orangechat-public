package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Sticker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UserStickerMessageTest {
    @Test
    fun `sticker stays Markdown locally but is replaced by name for the model`() {
        val sticker = Sticker(name = "小狗点头", url = "https://example.com/sticker.jpg")
        val localPart = sticker.toUserStickerMessage()

        assertEquals("![小狗点头](https://example.com/sticker.jpg)", localPart.text)

        val providerPart = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(localPart))
        ).replaceUserStickersWithNames().single().parts.single() as UIMessagePart.Text

        assertEquals("用户发送了表情包：小狗点头", providerPart.text)
        assertFalse(providerPart.text.contains("https://"))
    }

    @Test
    fun `ordinary Markdown text is not rewritten`() {
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("![普通图片](https://example.com/image.jpg)")),
        )

        assertEquals(message, listOf(message).replaceUserStickersWithNames().single())
    }
}
