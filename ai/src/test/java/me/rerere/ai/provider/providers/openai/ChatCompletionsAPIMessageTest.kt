/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ChatCompletionsAPI message building logic.
 * Tests the conversion from UIMessage list to OpenAI API format,
 * specifically focusing on multi-round reasoning/tool scenarios.
 */
class ChatCompletionsAPIMessageTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    private fun invokeBuildMessages(messages: List<UIMessage>): JsonArray {
        return api.buildMessages(
            messages = messages,
            model = Model(modelId = "test-model", displayName = "test-model"),
        )
    }

    private fun invokeBuildRequest(
        providerSetting: ProviderSetting.OpenAI,
        params: TextGenerationParams,
    ): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(api, listOf(UIMessage.user("hello")), params, providerSetting, false) as JsonObject
    }

    private fun invokeParseStreamChoices(payload: String): List<*> {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("parseStreamChoices", JsonObject::class.java)
        method.isAccessible = true
        return method.invoke(api, Json.parseToJsonElement(payload).jsonObject) as List<*>
    }

    @Test
    fun `stream chunks with null tool fields do not abort parsing`() {
        val choices = invokeParseStreamChoices(
            """{"id":"chatcmpl-1","choices":[{"delta":{"content":"done","tool_calls":null},"finish_reason":null}]}"""
        )

        assertEquals(1, choices.size)
    }

    @Test
    fun `stream chunks with null choices or delta are ignored`() {
        assertTrue(invokeParseStreamChoices("""{"choices":null}""").isEmpty())
        assertTrue(invokeParseStreamChoices("""{"choices":[{"delta":null,"message":null}]}""").isEmpty())
    }

    @Test
    fun `reasoning off must not become low for generic OpenAI chat completions`() {
        val request = invokeBuildRequest(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            params = TextGenerationParams(
                model = Model(
                    modelId = "gpt-5",
                    displayName = "gpt-5",
                    abilities = listOf(ModelAbility.REASONING),
                ),
                reasoningLevel = ReasoningLevel.OFF,
            ),
        )

        assertTrue("OFF must omit reasoning_effort", "reasoning_effort" !in request)
    }

    @Test
    fun `reasoning off must explicitly disable DeepSeek through a gateway`() {
        val request = invokeBuildRequest(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://gateway.example/v1"),
            params = TextGenerationParams(
                model = Model(
                    modelId = "deepseek-v4-pro",
                    displayName = "DeepSeek V4 Pro",
                    abilities = listOf(ModelAbility.REASONING),
                ),
                reasoningLevel = ReasoningLevel.OFF,
            ),
        )

        assertEquals("disabled", request["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertTrue("OFF must not send reasoning_effort", "reasoning_effort" !in request)
    }

    @Test
    fun `reasoning auto must not control DeepSeek through a gateway`() {
        val request = invokeBuildRequest(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://gateway.example/v1"),
            params = TextGenerationParams(
                model = Model(
                    modelId = "deepseek-v4-pro",
                    displayName = "DeepSeek V4 Pro",
                    abilities = listOf(ModelAbility.REASONING),
                ),
                reasoningLevel = ReasoningLevel.AUTO,
            ),
        )

        assertEquals("enabled", request["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertTrue("AUTO must not send reasoning effort", "reasoning_effort" !in request)
    }

    @Test
    fun `dynamic environment metadata should be serialized on user message`() {
        val message = UIMessage.user("snapshot").copy(
            metadata = buildJsonObject {
                put("dynamic_environment", true)
                put("generated_at", "07-29 17:10")
            }
        )

        val serialized = invokeBuildMessages(listOf(message)).single().jsonObject

        assertTrue(serialized["metadata"]!!.jsonObject["dynamic_environment"]!!.jsonPrimitive.boolean)
        assertEquals("07-29 17:10", serialized["metadata"]!!.jsonObject["generated_at"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dynamic environment snapshot must stay separate from the current user message`() {
        val snapshotText = "<dynamic_context generated_at=\"2026-08-18T10:00:00Z\">battery=51</dynamic_context>"
        val snapshot = UIMessage.user(snapshotText)
            .copy(
                metadata = buildJsonObject {
                    put("dynamic_environment", true)
                    put("generated_at", "2026-08-18T10:00:00Z")
                }
            )

        val serialized = invokeBuildMessages(listOf(snapshot, UIMessage.user("current question")))

        assertEquals(listOf("user", "user"), serialized.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(snapshotText, serialized[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("current question", serialized[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertTrue(serialized[0].jsonObject["metadata"]!!.jsonObject["dynamic_environment"]!!.jsonPrimitive.boolean)
        assertTrue("metadata must not leak onto the real user request", "metadata" !in serialized[1].jsonObject)
    }

    @Test
    fun `filtered history cannot leave consecutive user messages before an executed tool result`() {
        val toolCall = createExecutedTool("call_1", "search", "{\"query\":\"orange\"}", "result")
        val serialized = invokeBuildMessages(
            listOf(
                UIMessage.user("history"),
                UIMessage.user("current proactive instruction"),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(toolCall)),
                UIMessage.user("continue after the tool"),
            )
        )

        val roles = serialized.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("user", "assistant", "tool", "user"), roles)
        assertEquals("call_1", serialized[2].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `multi-round reasoning and tool calls should be correctly ordered`() {
        // Scenario: Assistant message with multiple rounds of reasoning and tool calls
        // [Reasoning1, Text1, Tool1(executed), Reasoning2, Text2, Tool2(executed), Text3]
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Let me think about this..."),
                UIMessagePart.Text("I'll search for information"),
                createExecutedTool("call_1", "search", """{"query": "test"}""", "Search result 1"),
                UIMessagePart.Reasoning(reasoning = "Now I need to calculate..."),
                UIMessagePart.Text("Let me calculate that"),
                createExecutedTool("call_2", "calculate", """{"expr": "1+1"}""", "2"),
                UIMessagePart.Text("The final answer is 2")
            )
        )

        val messages = listOf(
            UIMessage.user("What is 1+1?"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Result should contain:
        // 1. User message
        // 2. Assistant message with reasoning_content, content, and tool_calls for search
        // 3. Tool result for search
        // 4. Assistant message with reasoning_content, content, and tool_calls for calculate
        // 5. Tool result for calculate
        // 6. Assistant message with final text

        assertTrue("Should have at least 6 messages", result.size >= 6)

        // Verify user message
        val userMsg = result[0].jsonObject
        assertEquals("user", userMsg["role"]?.jsonPrimitive?.content)

        // Verify first assistant message (with first tool call)
        val assistant1 = result[1].jsonObject
        assertEquals("assistant", assistant1["role"]?.jsonPrimitive?.content)
        assertTrue("First assistant message should have tool_calls", assistant1.containsKey("tool_calls"))
        val toolCalls1 = assistant1["tool_calls"]?.jsonArray
        assertEquals(1, toolCalls1?.size)
        assertEquals("search", toolCalls1?.get(0)?.jsonObject?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content)

        // Verify first tool result
        val toolResult1 = result[2].jsonObject
        assertEquals("tool", toolResult1["role"]?.jsonPrimitive?.content)
        assertEquals("call_1", toolResult1["tool_call_id"]?.jsonPrimitive?.content)

        // Verify second assistant message (with second tool call)
        val assistant2 = result[3].jsonObject
        assertEquals("assistant", assistant2["role"]?.jsonPrimitive?.content)
        assertTrue("Second assistant message should have tool_calls", assistant2.containsKey("tool_calls"))
        val toolCalls2 = assistant2["tool_calls"]?.jsonArray
        assertEquals(1, toolCalls2?.size)
        assertEquals("calculate", toolCalls2?.get(0)?.jsonObject?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content)

        // Verify second tool result
        val toolResult2 = result[4].jsonObject
        assertEquals("tool", toolResult2["role"]?.jsonPrimitive?.content)
        assertEquals("call_2", toolResult2["tool_call_id"]?.jsonPrimitive?.content)

        // Verify final assistant message
        val assistant3 = result[5].jsonObject
        assertEquals("assistant", assistant3["role"]?.jsonPrimitive?.content)
        val content = assistant3["content"]
        assertTrue("Final assistant content should contain 'final answer'",
            content?.jsonPrimitive?.content?.contains("final answer") == true ||
            (content is JsonArray && content.any { it.jsonObject["text"]?.jsonPrimitive?.content?.contains("final answer") == true })
        )
    }

    @Test
    fun `parallel tool calls should be grouped together`() {
        // Scenario: Multiple tools called in parallel
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me search multiple sources"),
                createExecutedTool("call_1", "search_web", """{"query": "test1"}""", "Result 1"),
                createExecutedTool("call_2", "search_docs", """{"query": "test2"}""", "Result 2"),
                createExecutedTool("call_3", "search_wiki", """{"query": "test3"}""", "Result 3"),
                UIMessagePart.Text("Combined results show...")
            )
        )

        val messages = listOf(
            UIMessage.user("Search everything"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Verify parallel tools are in same assistant message
        var foundAssistantWithMultipleTools = false
        for (element in result) {
            val msg = element.jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "assistant") {
                val toolCalls = msg["tool_calls"]?.jsonArray
                if (toolCalls != null && toolCalls.size == 3) {
                    foundAssistantWithMultipleTools = true
                    // Verify all three tool calls are present
                    val toolNames = toolCalls.map {
                        it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                    }
                    assertTrue(toolNames.contains("search_web"))
                    assertTrue(toolNames.contains("search_docs"))
                    assertTrue(toolNames.contains("search_wiki"))
                    break
                }
            }
        }
        assertTrue("Should have assistant message with 3 parallel tool calls", foundAssistantWithMultipleTools)

        // Verify 3 separate tool result messages
        val toolResults = result.filter {
            it.jsonObject["role"]?.jsonPrimitive?.content == "tool"
        }
        assertEquals(3, toolResults.size)
    }

    @Test
    fun `reasoning should only be included for messages after last user message`() {
        // First assistant message (before user's last message) - reasoning should NOT be included
        val assistant1 = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Initial thinking"),
                UIMessagePart.Text("Initial response")
            )
        )

        // Second assistant message (after user's last message) - reasoning SHOULD be included
        val assistant2 = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Final thinking"),
                UIMessagePart.Text("Final response")
            )
        )

        val messages = listOf(
            UIMessage.user("First question"),
            assistant1,
            UIMessage.user("Second question"),
            assistant2
        )

        val result = invokeBuildMessages(messages)

        // Find assistant messages
        val assistantMessages = result.filter {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }

        assertEquals(2, assistantMessages.size)

        // First assistant should NOT have reasoning_content
        val first = assistantMessages[0].jsonObject
        assertTrue("First assistant should not have reasoning_content",
            !first.containsKey("reasoning_content") ||
            first["reasoning_content"]?.jsonPrimitive?.content.isNullOrEmpty()
        )

        // Second assistant SHOULD have reasoning_content
        val second = assistantMessages[1].jsonObject
        assertTrue("Second assistant should have reasoning_content",
            second.containsKey("reasoning_content") &&
            second["reasoning_content"]?.jsonPrimitive?.content?.isNotEmpty() == true
        )
    }

    @Test
    fun `tool_call followed by tool result should maintain correct order`() {
        // Verify the pattern: assistant (with tool_calls) -> tool (result)
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Calling tool"),
                createExecutedTool("call_abc", "my_tool", """{"param": "value"}""", "Tool output")
            )
        )

        val messages = listOf(
            UIMessage.user("Use a tool"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find the assistant message with tool_calls
        var assistantIndex = -1
        for (i in result.indices) {
            val msg = result[i].jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "assistant" && msg.containsKey("tool_calls")) {
                assistantIndex = i
                break
            }
        }

        assertTrue("Should find assistant with tool_calls", assistantIndex >= 0)

        // The next message should be the tool result
        val nextMsg = result[assistantIndex + 1].jsonObject
        assertEquals("tool", nextMsg["role"]?.jsonPrimitive?.content)
        assertEquals("call_abc", nextMsg["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("my_tool", nextMsg["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `complex multi-round conversation with interleaved reasoning and tools`() {
        // Complex scenario simulating agent conversation
        val messages = listOf(
            UIMessage.user("Plan and execute a task"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "Step 1: Analyze the task"),
                    UIMessagePart.Text("First, I'll gather information"),
                    createExecutedTool("call_1", "gather_info", "{}", "Info gathered"),
                    UIMessagePart.Reasoning(reasoning = "Step 2: Process the information"),
                    UIMessagePart.Text("Now processing..."),
                    createExecutedTool("call_2", "process", "{}", "Processed"),
                    UIMessagePart.Reasoning(reasoning = "Step 3: Generate output"),
                    UIMessagePart.Text("Here is the result")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        // Verify structure:
        // 1. user message
        // 2. assistant (reasoning + text + tool_calls)
        // 3. tool result
        // 4. assistant (reasoning + text + tool_calls)
        // 5. tool result
        // 6. assistant (reasoning + text)

        // Count message types
        val userCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
        val assistantCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
        val toolCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }

        assertEquals("Should have 1 user message", 1, userCount)
        assertEquals("Should have 2 tool results", 2, toolCount)
        assertTrue("Should have at least 3 assistant messages", assistantCount >= 3)

        // Verify order: each tool_calls should be immediately followed by tool result
        for (i in result.indices) {
            val msg = result[i].jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "assistant" && msg.containsKey("tool_calls")) {
                assertTrue("Index should not be last", i < result.size - 1)
                val nextMsg = result[i + 1].jsonObject
                assertEquals("Tool result should follow tool_calls",
                    "tool", nextMsg["role"]?.jsonPrimitive?.content)
            }
        }
    }

    @Test
    fun `assistant with only reasoning and empty text should be filtered out`() {
        val messages = listOf(
            UIMessage.user("Question 1"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "thinking"),
                    UIMessagePart.Text("")
                )
            ),
            UIMessage.user("Question 2")
        )

        val result = invokeBuildMessages(messages)

        assertEquals(2, result.size)
        assertEquals("user", result[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("Question 1", result[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("user", result[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("Question 2", result[1].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `latest assistant with reasoning and empty text should keep reasoning content`() {
        val messages = listOf(
            UIMessage.user("Question 1"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "thinking"),
                    UIMessagePart.Text("")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        assertEquals(2, result.size)
        assertEquals("user", result[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("assistant", result[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("thinking", result[1].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
        assertEquals("", result[1].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `local memory context message should not be merged with adjacent user message`() {
        val messages = listOf(
            UIMessage.user("<memory_context>记忆内容</memory_context>").copy(
                metadata = buildJsonObject {
                    put("memory_context", true)
                }
            ),
            UIMessage.user("当前真实提问")
        )

        val result = invokeBuildMessages(messages)

        assertEquals(2, result.size)
        assertEquals("user", result[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("<memory_context>记忆内容</memory_context>", result[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("user", result[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("当前真实提问", result[1].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `six-part prompt injection chain preserves all message boundaries in openai payload`() {
        val messages = listOf(
            UIMessage.system("系统指令"),
            UIMessage.user("历史问题"),
            UIMessage.assistant("历史回答"),
            UIMessage.user("<dynamic_context>环境快照</dynamic_context>").copy(
                metadata = buildJsonObject {
                    put("dynamic_environment", true)
                }
            ),
            UIMessage.user("<memory_context>本地记忆快照</memory_context>").copy(
                metadata = buildJsonObject {
                    put("memory_context", true)
                }
            ),
            UIMessage.user("<plugin-context>插件快照</plugin-context>").copy(
                metadata = buildJsonObject {
                    put("plugin_context", true)
                }
            ),
            UIMessage.user("当前真实提问")
        )

        val result = invokeBuildMessages(messages)

        assertEquals(7, result.size)
        val roles = result.map { it.jsonObject["role"]?.jsonPrimitive?.content }
        assertEquals(listOf("system", "user", "assistant", "user", "user", "user", "user"), roles)

        val contents = result.map { it.jsonObject["content"]?.jsonPrimitive?.content }
        assertEquals("系统指令", contents[0])
        assertEquals("历史问题", contents[1])
        assertEquals("历史回答", contents[2])
        assertEquals("<dynamic_context>环境快照</dynamic_context>", contents[3])
        assertEquals("<memory_context>本地记忆快照</memory_context>", contents[4])
        assertEquals("<plugin-context>插件快照</plugin-context>", contents[5])
        assertEquals("当前真实提问", contents[6])

        // 验证 metadata 边界
        assertTrue(result[3].jsonObject["metadata"]?.jsonObject?.get("dynamic_environment")?.jsonPrimitive?.boolean == true)
        assertTrue(result[4].jsonObject["metadata"]?.jsonObject?.get("memory_context")?.jsonPrimitive?.boolean == true)
        assertTrue(result[5].jsonObject["metadata"]?.jsonObject?.get("plugin_context")?.jsonPrimitive?.boolean == true)
        assertTrue("metadata" !in result[6].jsonObject)
    }

    @Test
    fun `stable_self_context user message is not merged with adjacent current user message`() {
        val messages = listOf(
            UIMessage.system("系统指令"),
            UIMessage.user("<stable_self>稳定自我事实</stable_self>").copy(
                metadata = buildJsonObject {
                    put("stable_self_context", true)
                }
            ),
            UIMessage.user("当前真实提问")
        )

        val result = invokeBuildMessages(messages)

        assertEquals(3, result.size)
        val roles = result.map { it.jsonObject["role"]?.jsonPrimitive?.content }
        assertEquals(listOf("system", "user", "user"), roles)

        val contents = result.map { it.jsonObject["content"]?.jsonPrimitive?.content }
        assertEquals("系统指令", contents[0])
        assertEquals("<stable_self>稳定自我事实</stable_self>", contents[1])
        assertEquals("当前真实提问", contents[2])
    }

    @Test
    fun `ordinary adjacent user messages are merged while stable_self is kept independent`() {
        val messages = listOf(
            UIMessage.user("<stable_self>稳定自我事实</stable_self>").copy(
                metadata = buildJsonObject {
                    put("stable_self_context", true)
                }
            ),
            UIMessage.user("用户输入部分一"),
            UIMessage.user("用户输入部分二")
        )

        val result = invokeBuildMessages(messages)

        assertEquals(2, result.size)
        // 独立未合并的 stable_self 是单文本 Primitive
        assertEquals("<stable_self>稳定自我事实</stable_self>", result[0].jsonObject["content"]?.jsonPrimitive?.content)
        // 两个普通 user 消息合并后为 JsonArray 内容
        val mergedParts = result[1].jsonObject["content"]?.jsonArray?.map { it.jsonObject["text"]?.jsonPrimitive?.content }
        assertEquals(listOf("用户输入部分一", "用户输入部分二"), mergedParts)
    }


    // ==================== Helper Functions ====================

    private fun createExecutedTool(
        callId: String,
        name: String,
        input: String,
        output: String
    ): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = name,
            input = input,
            output = listOf(UIMessagePart.Text(output))
        )
    }
}
