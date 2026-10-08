/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.groupMessageParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatInteractionTest {

    @Test
    fun targetControlsTheDisplayedSuffix() {
        assertEquals(
            "你捏一捏「小橘」的脸",
            formatUserPatAssistantDisplayText("小橘", "的脸", "捏一捏"),
        )
        assertEquals(
            "小橘戳一戳你的小脑袋",
            formatAiPatUserDisplayText("小橘", "的小脑袋", "戳一戳"),
        )
    }

    @Test
    fun userPatPartKeepsModelPromptAndDisplayTextSeparate() {
        val part = createUserPatAssistantPart(
            assistantName = "小橘",
            assistantPatAction = "捏一捏",
            assistantPatSuffix = "的脸",
        )

        assertEquals("你捏一捏「小橘」的脸", part.patDisplayTextOrNull())
        assertTrue(part.text.contains("用户捏一捏你"))
        assertTrue(part.text.contains("的脸"))
    }

    @Test
    fun patToolUsesConfiguredDefaultsWithoutArgumentsOrApproval() = runBlocking {
        val tool = createPatUserTool(
            ToolInvocationContext(
                callerAssistantName = "小橘",
                userPatAction = "摸摸",
                userPatSuffix = "的小脑袋",
            )
        )

        val output = tool.execute(JsonObject(emptyMap()))
        val toolPart = UIMessagePart.Tool(
            toolCallId = "pat-1",
            toolName = tool.name,
            input = "{}",
            output = output,
        )

        assertFalse(tool.needsApproval)
        assertEquals("小橘摸摸你的小脑袋", toolPart.patDisplayTextOrNull())
    }

    @Test
    fun patToolAcceptsAiProvidedActionAndContent() = runBlocking {
        val tool = createPatUserTool(
            ToolInvocationContext(
                callerAssistantName = "小橘",
                userPatAction = "拍了拍",
                userPatSuffix = "的肩膀",
            )
        )

        val output = tool.execute(
            buildJsonObject {
                put("action", "捏一捏")
                put("content", "的脸")
            }
        )
        val toolPart = UIMessagePart.Tool(
            toolCallId = "pat-1",
            toolName = tool.name,
            input = """{"action":"捏一捏","content":"的脸"}""",
            output = output,
        )

        assertEquals("小橘捏一捏你的脸", toolPart.patDisplayTextOrNull())
    }

    @Test
    fun executedPatToolRendersAsContentInsteadOfThinkingStep() = runBlocking {
        val tool = createPatUserTool(
            ToolInvocationContext(
                callerAssistantName = "小橘",
                userPatAction = "拍了拍",
                userPatSuffix = "的小脑袋",
            )
        )
        val toolPart = UIMessagePart.Tool(
            toolCallId = "pat-1",
            toolName = tool.name,
            input = "{}",
            output = tool.execute(JsonObject(emptyMap())),
        )

        val blocks = listOf<UIMessagePart>(toolPart).groupMessageParts()

        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MessagePartBlock.ContentBlock)
    }
}
