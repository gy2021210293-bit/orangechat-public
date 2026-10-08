/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isInvalidUnresumableToolMessage
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.SystemPromptBuilder
import me.rerere.rikkahub.data.ai.insertDynamicContextBeforeCurrentUser
import me.rerere.rikkahub.data.ai.insertExternalMemoryBeforeCurrentUser
import me.rerere.rikkahub.data.ai.insertPluginContextBeforeCurrentUser
import me.rerere.rikkahub.data.ai.insertRecentChatsBeforeCurrentUser
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class WorkflowWakePromptConsistencyTest {

    private fun testAssistant(
        name: String = "TestAssistant",
        systemPrompt: String = "You are a versatile assistant.",
        allowSkipReply: Boolean = true,
        splitBubbleByLine: Boolean = false,
        contextMessageSize: Int = 4,
    ) = Assistant(
        id = Uuid.random(),
        name = name,
        systemPrompt = systemPrompt,
        allowSkipReply = allowSkipReply,
        splitBubbleByLine = splitBubbleByLine,
        contextMessageSize = contextMessageSize,
    )

    private fun testModel() = Model(
        id = Uuid.random(),
        displayName = "Test Model",
        modelId = "test-model",
    )

    private fun testSettings() = Settings()

    /**
     * 生产路径一致性测试：模拟 GenerationHandler 和 ProactiveMessageTriggerService 两条实际生产路径
     * 的请求准备流程。即使某个工具的 systemPrompt 动态依赖传入的 promptMessages，两条路径也必须得到
     * 逐字完全一致的 System Prompt。
     */
    @Test
    fun productionPath_systemPromptVerbatimIdentical_evenWithDynamicToolPrompts() {
        val assistant = testAssistant(
            systemPrompt = "Stable persona prompt.",
            contextMessageSize = 4, // 限制上下文长度为 4 条消息
        )
        val model = testModel()
        val settings = testSettings()

        // 模拟一个包含 10 条历史消息的长对话（大于 contextMessageSize 4）
        val rawMessages = (1..10).map { i ->
            if (i % 2 == 1) UIMessage.user("User message $i")
            else UIMessage.assistant("Assistant reply $i")
        }

        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = assistant.id,
            title = "Test conversation",
            messageNodes = emptyList(),
            customSystemPrompt = "Custom conversation system prompt",
            workspaceCwd = "/sdcard/workspace",
        )

        // 模拟一个动态根据 promptMessages 生成 system prompt 的工具
        val dynamicTool = Tool(
            name = "recent_context_aware_tool",
            description = "A tool that generates its prompt based on recent message count",
            systemPrompt = { _, promptMsgs ->
                "Recent context message count = ${promptMsgs.size}, last = ${promptMsgs.lastOrNull()?.toText()}"
            },
            execute = { emptyList() },
        )
        val tools = listOf(
            Tool(name = "write_files", description = "write files", execute = { emptyList() }),
            dynamicTool,
        )

        val pluginInjections = listOf(
            "<plugin-prompt>Static plugin capability</plugin-prompt>",
            "<plugin-context name=\"battery\">Battery: 85%</plugin-context>",
        )
        val dynamicPluginPromptInjections = pluginInjections.filter { it.trimStart().startsWith("<plugin-context ") }
        val staticPluginPromptInjections = pluginInjections.filterNot { it.trimStart().startsWith("<plugin-context ") }

        // --- 生产路径 1：ChatService -> GenerationHandler 正常聊天请求准备 ---
        // 1. ChatService 先过滤悬空/无效工具消息
        val normalCleanMessages = filterInvalidToolMessages(rawMessages)
        // 2. GenerationHandler 截断上下文
        val normalContextLimitedMessages = normalCleanMessages.limitContext(assistant.contextMessageSize)
        // 3. 构建稳定系统提示词（传入截断后的 promptMessages）
        val normalSystemPrompt = SystemPromptBuilder.build(
            assistant = assistant,
            conversationSystemPrompt = conversation.customSystemPrompt,
            memories = emptyList(),
            tools = tools,
            model = model,
            settings = settings,
            staticPluginPromptInjections = staticPluginPromptInjections,
            dynamicPluginEnabled = dynamicPluginPromptInjections.isNotEmpty(),
            promptMessages = normalContextLimitedMessages,
        )

        // --- 生产路径 2：ProactiveMessageTriggerService 工作流唤醒请求准备 ---
        // 1. 先过滤无效工具调用，再截断上下文（与 ChatService 顺序完全一致）
        val workflowHistoryMessages = filterInvalidToolMessages(rawMessages).limitContext(assistant.contextMessageSize)
        // 2. 构建稳定系统提示词（传入相同的会话 customSystemPrompt 及截断后的 historyMessages）
        val workflowSystemPrompt = SystemPromptBuilder.build(
            assistant = assistant,
            conversationSystemPrompt = conversation.customSystemPrompt,
            memories = emptyList(),
            tools = tools,
            model = model,
            settings = settings,
            staticPluginPromptInjections = staticPluginPromptInjections,
            dynamicPluginEnabled = dynamicPluginPromptInjections.isNotEmpty(),
            promptMessages = workflowHistoryMessages,
        )

        // 断言：两条生产路径准备的消息与生成的 System Prompt 必须 100% 逐字完全一致！
        assertEquals("Prompt messages must be identical", normalContextLimitedMessages, workflowHistoryMessages)
        assertEquals("System prompt must be 100% byte-for-byte identical", normalSystemPrompt, workflowSystemPrompt)
        assertTrue("Dynamic tool prompt must be included", normalSystemPrompt.contains("Recent context message count = ${normalContextLimitedMessages.size}"))
        assertTrue("Dynamic tool prompt must see the truncated last message", normalSystemPrompt.contains("last = Assistant reply 10"))
    }

    /**
     * 回归测试：历史记录中存在悬空/不可恢复工具调用时，两条生产路径均遵循
     * "先过滤无效工具消息 -> 再 limitContext 截断" 的顺序，保证 promptMessages 与 SystemPrompt 逐字一致。
     */
    /**
     * 回归测试：历史记录中存在未执行且不可恢复的悬空工具调用（如中断/待处理的 Pending 工具）时，
     * 两条生产路径均遵循 "先过滤无效工具消息 -> 再 limitContext 截断" 的顺序：
     * 1. 悬空工具消息在截断前被剔除；
     * 2. 截断窗口能够向前回填更早的有效历史消息；
     * 3. 传给动态工具和 SystemPromptBuilder 的 promptMessages 保持完全一致。
     */
    @Test
    fun productionPath_danglingToolMessagesInHistory_filteredBeforeLimitContext_identicalPromptMessagesAndSystemPrompt() {
        val assistant = testAssistant(
            systemPrompt = "Stable persona prompt.",
            contextMessageSize = 4,
        )
        val model = testModel()
        val settings = testSettings()

        // 构造历史序列：共 5 条消息，悬空工具位于第 4 条（index 3）
        val rawMessages = listOf(
            UIMessage.user("Msg 1"),
            UIMessage.assistant("Msg 2"),
            UIMessage.user("Msg 3"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call_dangling",
                        toolName = "unresumable_tool",
                        input = "{}",
                        output = emptyList(),
                        approvalState = ToolApprovalState.Pending, // 未执行且不可恢复的 Pending 状态
                    )
                )
            ),
            UIMessage.user("Msg 5"),
        )

        // 验证悬空工具消息被判定为无效
        assertTrue("Dangling message should be identified as invalid", rawMessages[3].isInvalidUnresumableToolMessage())

        // 验证过滤直接删除悬空消息，消息总数从 5 变为 4
        val cleanMessages = filterInvalidToolMessages(rawMessages)
        assertEquals(4, cleanMessages.size)
        assertFalse("Clean messages should not contain dangling tool", cleanMessages.any { it.getTools().any { t -> t.toolCallId == "call_dangling" } })

        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = assistant.id,
            title = "Dangling Tool Test",
            messageNodes = emptyList(),
            customSystemPrompt = "Conv prompt with dangling tools in history",
        )

        val dynamicTool = Tool(
            name = "context_watcher",
            description = "Watches context",
            systemPrompt = { _, promptMsgs ->
                "ContextSize=${promptMsgs.size}, Start=${promptMsgs.firstOrNull()?.toText()}"
            },
            execute = { emptyList() },
        )
        val tools = listOf(dynamicTool)

        // 路径 1: ChatService.checkInvalidMessages (使用 isInvalidUnresumableToolMessage) -> GenerationHandler.limitContext
        val normalCleanMessages = rawMessages.filterNot { it.isInvalidUnresumableToolMessage() }
        val normalPromptMessages = normalCleanMessages.limitContext(assistant.contextMessageSize)
        val normalSystemPrompt = SystemPromptBuilder.build(
            assistant = assistant,
            conversationSystemPrompt = conversation.customSystemPrompt,
            memories = emptyList(),
            tools = tools,
            model = model,
            settings = settings,
            promptMessages = normalPromptMessages,
        )

        // 路径 2: ProactiveMessageTriggerService (filterInvalidToolMessages -> limitContext)
        val workflowPromptMessages = filterInvalidToolMessages(rawMessages).limitContext(assistant.contextMessageSize)
        val workflowSystemPrompt = SystemPromptBuilder.build(
            assistant = assistant,
            conversationSystemPrompt = conversation.customSystemPrompt,
            memories = emptyList(),
            tools = tools,
            model = model,
            settings = settings,
            promptMessages = workflowPromptMessages,
        )

        // 验证由于"先过滤再截断"，cleanMessages (size=4) <= contextMessageSize(4)，Msg 1 被完整保留回填进窗口
        assertEquals(4, normalPromptMessages.size)
        assertEquals(4, workflowPromptMessages.size)
        assertEquals("Msg 1", normalPromptMessages.first().toText())
        assertEquals("Msg 1", workflowPromptMessages.first().toText())

        // 验证两条生产路径产物 100% 逐字一致
        assertEquals("Prompt messages must be identical", normalPromptMessages, workflowPromptMessages)
        assertEquals("System prompt must be 100% byte-for-byte identical", normalSystemPrompt, workflowSystemPrompt)
        assertTrue("Dynamic tool prompt should see Start=Msg 1", normalSystemPrompt.contains("Start=Msg 1"))
    }

    /**
     * 验证 tool.systemPrompt() 只被执行一次（在指纹计算与最终 fullPrompt 组装时复用缓存，杜绝重复计算与副作用）
     */
    @Test
    fun toolSystemPrompt_evaluatedOnlyOncePerTool() {
        var callCount = 0
        val tool = Tool(
            name = "side_effect_tool",
            description = "Tool with tracked system prompt evaluation",
            systemPrompt = { _, _ ->
                callCount++
                "Tool prompt iteration $callCount"
            },
            execute = { emptyList() },
        )

        val (fullPrompt, fingerprint) = SystemPromptBuilder.computeFingerprint(
            assistant = testAssistant(),
            conversationSystemPrompt = null,
            memories = emptyList(),
            tools = listOf(tool),
            model = testModel(),
            settings = testSettings(),
            promptMessages = emptyList(),
        )

        assertEquals("Tool systemPrompt should only be evaluated exactly once", 1, callCount)
        assertTrue("Full prompt should contain the evaluated tool prompt", fullPrompt.contains("Tool prompt iteration 1"))
        assertEquals(
            "Fingerprint tool prompt length should match cached string length",
            "Tool prompt iteration 1".length,
            fingerprint.toolPrompts.length,
        )
    }

    @Test
    fun systemPrompt_excludesEphemeralExternalMemoryAndRecentChats() {
        val assistant = testAssistant()
        val model = testModel()
        val settings = testSettings()

        val systemPrompt = SystemPromptBuilder.build(
            assistant = assistant,
            conversationSystemPrompt = "Conv prompt",
            memories = emptyList(),
            tools = emptyList(),
            model = model,
            settings = settings,
            promptMessages = emptyList(),
        )

        assertFalse("System prompt must NOT contain external memory section", systemPrompt.contains("## 外置记忆库"))
        assertFalse("System prompt must NOT contain recent chats section", systemPrompt.contains("## 最近对话参考"))
        assertFalse("System prompt must NOT contain workflow wake header", systemPrompt.contains("## 工作流唤醒"))
    }

    @Test
    fun ephemeralContext_insertedAsUserMessagesBeforeCurrentUser() {
        val systemMsg = UIMessage.system("System prompt")
        val oldUser = UIMessage.user("Old user question")
        val oldAssistant = UIMessage.assistant("Old assistant answer")
        val currentUser = UIMessage.user("Current turn user message")

        val messages = listOf(systemMsg, oldUser, oldAssistant, currentUser)

        val withExternalMemory = insertExternalMemoryBeforeCurrentUser(
            messages = messages,
            externalMemoryContext = "## 外置记忆库\n1. Recalled memory",
        )
        val withRecentChats = insertRecentChatsBeforeCurrentUser(
            messages = withExternalMemory,
            recentChatsContext = "## 最近对话参考\n- Previous context",
        )
        val withDynamicContext = insertDynamicContextBeforeCurrentUser(
            messages = withRecentChats,
            dynamicContext = "<dynamic_context>Battery: 95%</dynamic_context>",
        )
        val withPluginContext = insertPluginContextBeforeCurrentUser(
            messages = withDynamicContext,
            pluginContext = "<plugin-context name=\"weather\">Sunny</plugin-context>",
        )

        // System prompt is unchanged
        assertEquals(MessageRole.SYSTEM, withPluginContext.first().role)
        assertEquals("System prompt", withPluginContext.first().parts.filterIsInstance<UIMessagePart.Text>().single().text)

        // Last message is the current user request
        val lastMsg = withPluginContext.last()
        assertEquals(MessageRole.USER, lastMsg.role)
        assertEquals("Current turn user message", lastMsg.parts.filterIsInstance<UIMessagePart.Text>().single().text)

        // Context items are placed between old history and current user
        val intermediateUsers = withPluginContext.subList(3, withPluginContext.size - 1)
        assertTrue("Should contain external memory", intermediateUsers.any { it.toText().contains("外置记忆库") })
        assertTrue("Should contain recent chats", intermediateUsers.any { it.toText().contains("最近对话参考") })
        assertTrue("Should contain dynamic context", intermediateUsers.any { it.toText().contains("dynamic_context") })
        assertTrue("Should contain plugin context", intermediateUsers.any { it.toText().contains("plugin-context") })

        // Dynamic environment has metadata
        val dynamicMsg = withPluginContext.find { it.toText().contains("dynamic_context") }
        assertNotNull(dynamicMsg)
        assertEquals("true", dynamicMsg?.metadata?.get("dynamic_environment")?.toString())
    }

    @Test
    fun workflowWake_userInstructionOnlyAtTail_andCustomRequirementPreserved() {
        val workflowWakeContext = "自定义处理要求：检查电量并调用通知工具发送预警"
        val userInstruction = buildWorkflowWakeUserInstruction(workflowWakeContext)

        val messages = mergeAdjacentSameRoleMessages(
            buildInitialProactiveMessages(
                systemPrompt = "System prompt",
                historyMessages = listOf(
                    UIMessage.user("History user"),
                    UIMessage.assistant("History assistant"),
                ),
                dynamicContext = "<dynamic_context>Battery: 10%</dynamic_context>",
                dynamicPluginContext = "",
                processedUserMessages = listOf(UIMessage.user(userInstruction)),
            )
        )

        // Ensure system prompt does NOT contain workflow wake text
        assertEquals(MessageRole.SYSTEM, messages[0].role)
        assertFalse(messages[0].toText().contains("工作流唤醒"))
        assertFalse(messages[0].toText().contains("自定义处理要求"))

        // Final message is workflow instruction
        val lastMsg = messages.last()
        assertEquals(MessageRole.USER, lastMsg.role)
        val lastText = lastMsg.toText()
        assertTrue("Should contain workflow wake header", lastText.contains("## 工作流唤醒"))
        assertTrue("Should contain custom requirement", lastText.contains("自定义处理要求：检查电量并调用通知工具发送预警"))
        assertFalse("Should not contain deleted execution prompt", lastText.contains("请立即执行下面的“自定义处理要求”"))
    }
}
