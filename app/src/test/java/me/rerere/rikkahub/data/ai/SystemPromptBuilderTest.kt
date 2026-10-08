package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SystemToolsSetting
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SystemPromptBuilderTest {

    private fun defaultAssistant(
        allowSkipReply: Boolean = false,
        splitBubbleByLine: Boolean = false,
        systemPrompt: String = "You are a helpful assistant.",
    ) = Assistant(
        id = Uuid.random(),
        name = "Test",
        systemPrompt = systemPrompt,
        allowSkipReply = allowSkipReply,
        splitBubbleByLine = splitBubbleByLine,
    )

    private fun defaultSettings(
        dynamicContextEnabled: Boolean = false,
    ) = Settings().copy(
        systemToolsSetting = SystemToolsSetting(
            dynamicContextEnabled = dynamicContextEnabled,
        ),
    )

    private fun defaultModel() = Model(
        id = Uuid.random(),
        displayName = "Test Model",
        modelId = "test-model",
    )

    // --- Full paragraph text constants for assertion ---

    private val EXPECTED_SKIP_REPLY_FULL = """
        ## Skip Reply
        If you determine that no reply is needed (e.g., the user's message doesn't require a response, or you have nothing meaningful to add), you may reply with exactly `[SKIP]` (without any other text). This message will be hidden from the user. Use this sparingly and only when truly appropriate.
    """.trimIndent()

    private val EXPECTED_JUMP_FULL = """
        ## 屏幕跳转能力
        你可以在回复末尾追加 [JUMP] 标记（单独一行）来把聊天界面拉到用户屏幕最前面。
        适用场景：
        - 用户说要去别的应用，你觉得需要把用户拉回来时
        - 你觉得接下来的内容需要用户立即看到时
        不适用场景：
        - 一般闲聊不需要跳转
        - 用户正在跟你正常对话时不需要跳转
        [JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。
    """.trimIndent()

    private val EXPECTED_MESSAGE_BUBBLES_FULL = """
        ## Message Bubbles
        Your reply will be automatically split into separate chat bubbles at every line break (\n) you write, similar to how a person sends several short texts in a row instead of one long message. You are fully in control of this: write a line break whenever you want the previous thought/sentence to appear as its own bubble, and keep things on the same line when they belong together. Do not insert blank lines purely for spacing — every line break becomes a new bubble, so use them intentionally. Exception: line breaks inside fenced code blocks (```) and Markdown tables are preserved as-is and will NOT create new bubbles, since those must stay intact as a single block.
    """.trimIndent()

    // --- Skip Reply tests ---

    @Test
    fun skipReplySection_fullParagraphWhenEnabled() {
        val result = SystemPromptBuilder.build(
            assistant = defaultAssistant(allowSkipReply = true),
            conversationSystemPrompt = null,
            memories = emptyList(),
            tools = emptyList(),
            model = defaultModel(),
            settings = defaultSettings(),
            promptMessages = emptyList(),
        )

        assertTrue(
            "Should contain full Skip Reply paragraph",
            result.contains(EXPECTED_SKIP_REPLY_FULL)
        )
    }

    @Test
    fun skipReplySection_omittedWhenDisabled() {
        val result = SystemPromptBuilder.build(
            assistant = defaultAssistant(allowSkipReply = false),
            conversationSystemPrompt = null,
            memories = emptyList(),
            tools = emptyList(),
            model = defaultModel(),
            settings = defaultSettings(),
            promptMessages = emptyList(),
        )

        assertFalse("Should not contain Skip Reply header", result.contains("## Skip Reply"))
        assertFalse("Should not contain [SKIP] instruction", result.contains("[SKIP]"))
    }

    // --- JUMP tests ---

    @Test
    fun jumpSection_fullParagraphAlwaysPresent() {
        val result = SystemPromptBuilder.build(
            assistant = defaultAssistant(),
            conversationSystemPrompt = null,
            memories = emptyList(),
            tools = emptyList(),
            model = defaultModel(),
            settings = defaultSettings(),
            promptMessages = emptyList(),
        )

        assertTrue(
            "Should contain full JUMP paragraph",
            result.contains(EXPECTED_JUMP_FULL)
        )
    }

    // --- Message Bubbles tests ---

    @Test
    fun messageBubblesSection_fullParagraphWhenEnabled() {
        val result = SystemPromptBuilder.build(
            assistant = defaultAssistant(splitBubbleByLine = true),
            conversationSystemPrompt = null,
            memories = emptyList(),
            tools = emptyList(),
            model = defaultModel(),
            settings = defaultSettings(),
            promptMessages = emptyList(),
        )

        assertTrue(
            "Should contain full Message Bubbles paragraph",
            result.contains(EXPECTED_MESSAGE_BUBBLES_FULL)
        )
    }

    @Test
    fun messageBubblesSection_omittedWhenDisabled() {
        val result = SystemPromptBuilder.build(
            assistant = defaultAssistant(splitBubbleByLine = false),
            conversationSystemPrompt = null,
            memories = emptyList(),
            tools = emptyList(),
            model = defaultModel(),
            settings = defaultSettings(),
            promptMessages = emptyList(),
        )

        assertFalse("Should not contain Message Bubbles header", result.contains("## Message Bubbles"))
    }

    // --- Paragraph ordering ---

    @Test
    fun sectionOrder_basePromptBeforeMemoryBeforeCodeBlockBeforeJump() {
        val result = SystemPromptBuilder.build(
            assistant = defaultAssistant(
                allowSkipReply = true,
                splitBubbleByLine = true,
            ),
            conversationSystemPrompt = null,
            memories = emptyList(),
            tools = emptyList(),
            model = defaultModel(),
            settings = defaultSettings(dynamicContextEnabled = true),
            promptMessages = emptyList(),
        )

        val basePos = result.indexOf("You are a helpful assistant.")
        val codeBlockPos = result.indexOf("## Code Block Rules")
        val dynamicCtxPos = result.indexOf("A <dynamic_context> user message may be inserted")
        val skipReplyPos = result.indexOf("## Skip Reply")
        val jumpPos = result.indexOf("## 屏幕跳转能力")
        val bubblesPos = result.indexOf("## Message Bubbles")

        assertTrue("Base prompt should come first", basePos >= 0)
        assertTrue("Code Block Rules should come after base prompt", codeBlockPos > basePos)
        assertTrue("Dynamic context policy should come after code block rules", dynamicCtxPos > codeBlockPos)
        assertTrue("Skip Reply should come after dynamic context", skipReplyPos > dynamicCtxPos)
        assertTrue("JUMP should come after Skip Reply", jumpPos > skipReplyPos)
        assertTrue("Message Bubbles should come after JUMP", bubblesPos > jumpPos)
    }

    // --- Tool receives prompt messages ---

    @Test
    fun toolReceivesPromptMessages() {
        var receivedMessages: List<UIMessage>? = null
        val testTool = Tool(
            name = "test_tool",
            description = "A test tool",
            systemPrompt = { _, messages ->
                receivedMessages = messages
                "Tool prompt for ${messages.size} messages"
            },
            execute = { emptyList() },
        )
        val messages = listOf(
            UIMessage.user("hello"),
            UIMessage.assistant("hi there"),
        )

        SystemPromptBuilder.build(
            assistant = defaultAssistant(),
            conversationSystemPrompt = null,
            memories = emptyList(),
            tools = listOf(testTool),
            model = defaultModel(),
            settings = defaultSettings(),
            promptMessages = messages,
        )

        assertEquals("Tool should have received messages", true, receivedMessages != null)
        assertEquals("Tool should receive 2 messages", 2, receivedMessages!!.size)
        assertEquals(
            "First message should be user hello",
            "hello",
            receivedMessages!![0].parts.filterIsInstance<UIMessagePart.Text>().single().text
        )
        assertEquals(
            "Second message should be assistant hi there",
            "hi there",
            receivedMessages!![1].parts.filterIsInstance<UIMessagePart.Text>().single().text
        )
    }

    // --- Dynamic context policy ---

    @Test
    fun dynamicContextPolicy_fullTextWhenEnabled() {
        val result = SystemPromptBuilder.build(
            assistant = defaultAssistant(),
            conversationSystemPrompt = null,
            memories = emptyList(),
            tools = emptyList(),
            model = defaultModel(),
            settings = defaultSettings(dynamicContextEnabled = true),
            promptMessages = emptyList(),
        )

        assertTrue(
            "Should contain full dynamic context policy",
            result.contains(DYNAMIC_CONTEXT_SYSTEM_POLICY)
        )
    }

    @Test
    fun dynamicContextPolicy_omittedWhenDisabled() {
        val result = SystemPromptBuilder.build(
            assistant = defaultAssistant(),
            conversationSystemPrompt = null,
            memories = emptyList(),
            tools = emptyList(),
            model = defaultModel(),
            settings = defaultSettings(dynamicContextEnabled = false),
            promptMessages = emptyList(),
        )

        assertFalse(
            "Should not contain dynamic context policy",
            result.contains(DYNAMIC_CONTEXT_SYSTEM_POLICY)
        )
    }

    // --- Code block rules ---

    @Test
    fun codeBlockRules_fullTextAlwaysPresent() {
        val result = SystemPromptBuilder.build(
            assistant = defaultAssistant(),
            conversationSystemPrompt = null,
            memories = emptyList(),
            tools = emptyList(),
            model = defaultModel(),
            settings = defaultSettings(),
            promptMessages = emptyList(),
        )

        assertTrue(
            "Should contain Code Block Rules header",
            result.contains("## Code Block Rules")
        )
        assertTrue(
            "Should contain filename naming instruction",
            result.contains("ALWAYS name code blocks with filenames")
        )
    }

    // --- Fingerprint tests ---

    @Test
    fun computeFingerprint_tracksSectionLengthsAndHashes() {
        val assistant = defaultAssistant(systemPrompt = "Hello world")
        val (prompt, fingerprint) = SystemPromptBuilder.computeFingerprint(
            assistant = assistant,
            conversationSystemPrompt = "Conv prompt",
            memories = emptyList(),
            tools = emptyList(),
            model = defaultModel(),
            settings = defaultSettings(dynamicContextEnabled = true),
            staticPluginPromptInjections = listOf("<plugin>foo</plugin>"),
            dynamicPluginEnabled = true,
            promptMessages = emptyList(),
        )

        assertTrue("Prompt should not be blank", prompt.isNotBlank())
        assertTrue("Persona len should be positive", fingerprint.persona.length > 0)
        assertTrue("Persona sha256 should not be empty", fingerprint.persona.sha256.isNotBlank())
        assertEquals("External memory in system prompt should be 0", 0, fingerprint.externalMemory.length)
        assertEquals("Recent chats in system prompt should be 0", 0, fingerprint.recentChats.length)
        assertTrue("Total length should be greater than persona", fingerprint.totalLength > fingerprint.persona.length)
        assertTrue("Log string should contain section fingerprints", fingerprint.formatLog().contains("SystemPrompt Fingerprint"))
    }
}
