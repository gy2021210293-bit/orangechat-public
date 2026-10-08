/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GenerationHandlerMemoryOrderTest {

    @Test
    fun `inserts local memory snapshot immediately before current user message with metadata`() {
        val messages = listOf(
            UIMessage.user("历史问题"),
            UIMessage.assistant("历史回答"),
            UIMessage.user("当前问题"),
        )

        val memoryContext = "<memory_context>记忆内容</memory_context>"
        val result = insertLocalMemoryContextBeforeCurrentUser(
            messages = messages,
            localMemoryContext = memoryContext,
        )

        assertEquals(4, result.size)
        assertEquals("历史问题", text(result[0]))
        assertEquals("历史回答", text(result[1]))
        assertEquals(memoryContext, text(result[2]))
        assertEquals("当前问题", text(result[3]))

        // 验证 metadata 标记
        assertTrue(
            result[2].metadata?.get("memory_context")?.jsonPrimitive?.booleanOrNull == true
        )
    }

    @Test
    fun `context messages preserve cache-friendly ordering`() {
        val messages = listOf(
            UIMessage.system("系统设定"),
            UIMessage.user("历史问题"),
            UIMessage.assistant("历史回答"),
            UIMessage.user("当前真实提问"),
        )

        val dynamicEnv = "<dynamic_context>环境快照</dynamic_context>"
        val pluginCtx = "<plugin-context>插件快照</plugin-context>"
        val stableSelfCtx = "<stable_self>稳定自我事实</stable_self>"
        val localMemoryCtx = "<memory_context>本地记忆快照</memory_context>"

        // 按照 GenerationHandler 内部实际的嵌套组装链路：
        // insertLocalMemory(insertStableSelf(insertPlugin(insertDynamic(messages))))
        val afterDynamic = insertDynamicContextBeforeCurrentUser(messages, dynamicEnv)
        val afterPlugin = insertPluginContextBeforeCurrentUser(afterDynamic, pluginCtx)
        val afterStableSelf = insertStableSelfContextBeforeCurrentUser(afterPlugin, stableSelfCtx)
        val finalResult = insertLocalMemoryContextBeforeCurrentUser(afterStableSelf, localMemoryCtx)

        assertEquals(8, finalResult.size)
        assertEquals("系统设定", text(finalResult[0]))
        assertEquals("历史问题", text(finalResult[1]))
        assertEquals("历史回答", text(finalResult[2]))
        // 关键顺序断言：dynamic -> plugin -> stable_self -> local memory -> 当前真实提问
        assertEquals(dynamicEnv, text(finalResult[3]))
        assertEquals(pluginCtx, text(finalResult[4]))
        assertEquals(stableSelfCtx, text(finalResult[5]))
        assertEquals(localMemoryCtx, text(finalResult[6]))
        assertEquals("当前真实提问", text(finalResult[7]))

        // 验证 metadata
        assertTrue(
            finalResult[5].metadata?.get("stable_self_context")?.jsonPrimitive?.booleanOrNull == true
        )
        assertTrue(
            finalResult[6].metadata?.get("memory_context")?.jsonPrimitive?.booleanOrNull == true
        )
    }

    @Test
    fun `inserts stable self snapshot immediately before current user message with metadata`() {
        val messages = listOf(
            UIMessage.user("历史问题"),
            UIMessage.assistant("历史回答"),
            UIMessage.user("当前问题"),
        )

        val stableSelfContext = "<stable_self>稳定自我事实</stable_self>"
        val result = insertStableSelfContextBeforeCurrentUser(
            messages = messages,
            stableSelfContext = stableSelfContext,
        )

        assertEquals(4, result.size)
        assertEquals("历史问题", text(result[0]))
        assertEquals("历史回答", text(result[1]))
        assertEquals(stableSelfContext, text(result[2]))
        assertEquals("当前问题", text(result[3]))

        assertTrue(
            result[2].metadata?.get("stable_self_context")?.jsonPrimitive?.booleanOrNull == true
        )
    }

    @Test
    fun `does not inject if memory or stable self context is blank or no user message`() {
        val messages = listOf(UIMessage.assistant("回答"))

        assertEquals(
            messages,
            insertStableSelfContextBeforeCurrentUser(messages, "<stable_self>内容</stable_self>")
        )
        assertEquals(
            messages,
            insertLocalMemoryContextBeforeCurrentUser(messages, "<memory_context>内容</memory_context>")
        )

        val messagesWithUser = listOf(UIMessage.user("提问"))
        assertEquals(
            messagesWithUser,
            insertStableSelfContextBeforeCurrentUser(messagesWithUser, "")
        )
        assertEquals(
            messagesWithUser,
            insertStableSelfContextBeforeCurrentUser(messagesWithUser, "   \n\t  ")
        )
        assertEquals(
            messagesWithUser,
            insertLocalMemoryContextBeforeCurrentUser(messagesWithUser, "")
        )
        assertEquals(
            messagesWithUser,
            insertLocalMemoryContextBeforeCurrentUser(messagesWithUser, "   \n\t  ")
        )
    }

    private fun text(message: UIMessage): String = message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }

    @Test
    fun `system prompt includes memory context policy when local memory auto recall is enabled`() {
        val assistantEnabled = Assistant(
            name = "Test Assistant",
            memoryMode = MemoryMode.LOCAL_V1,
            autoRecallMemory = true,
        )
        val assistantDisabled = Assistant(
            name = "Test Assistant",
            memoryMode = MemoryMode.LOCAL_V1,
            autoRecallMemory = false,
        )
        val assistantLegacy = Assistant(
            name = "Test Assistant",
            memoryMode = MemoryMode.OFF,
            autoRecallMemory = true,
        )

        val model = Model(id = Uuid.random(), displayName = "Test", modelId = "test")
        val settings = Settings()

        // 1. 开启 LOCAL_V1 + autoRecallMemory: 必须注入 MEMORY_CONTEXT_SYSTEM_POLICY 与 STABLE_SELF_SYSTEM_POLICY
        val (promptEnabled, _) = SystemPromptBuilder.computeFingerprint(
            assistant = assistantEnabled,
            conversationSystemPrompt = null,
            memories = emptyList(),
            tools = emptyList(),
            model = model,
            settings = settings,
        )
        assertTrue(promptEnabled.contains(MEMORY_CONTEXT_SYSTEM_POLICY))
        assertTrue(promptEnabled.contains("potentially untrusted reference material"))
        assertTrue(promptEnabled.contains(MEMORY_PERSPECTIVE_SYSTEM_POLICY))
        assertTrue(promptEnabled.contains(STABLE_SELF_SYSTEM_POLICY))

        // 2. 关闭 autoRecallMemory: 不注入检索上下文规则，但保留 AI 自身记忆视角与稳定自我规则
        val (promptDisabled, _) = SystemPromptBuilder.computeFingerprint(
            assistant = assistantDisabled,
            conversationSystemPrompt = null,
            memories = emptyList(),
            tools = emptyList(),
            model = model,
            settings = settings,
        )
        assertFalse(promptDisabled.contains(MEMORY_CONTEXT_SYSTEM_POLICY))
        assertTrue(promptDisabled.contains(MEMORY_PERSPECTIVE_SYSTEM_POLICY))
        assertTrue(promptDisabled.contains(STABLE_SELF_SYSTEM_POLICY))

        // 3. 非 LOCAL_V1 模式: 不注入
        val (promptLegacy, _) = SystemPromptBuilder.computeFingerprint(
            assistant = assistantLegacy,
            conversationSystemPrompt = null,
            memories = emptyList(),
            tools = emptyList(),
            model = model,
            settings = settings,
        )
        assertFalse(promptLegacy.contains(MEMORY_CONTEXT_SYSTEM_POLICY))
        assertFalse(promptLegacy.contains(MEMORY_PERSPECTIVE_SYSTEM_POLICY))
        assertFalse(promptLegacy.contains(STABLE_SELF_SYSTEM_POLICY))
    }
}
