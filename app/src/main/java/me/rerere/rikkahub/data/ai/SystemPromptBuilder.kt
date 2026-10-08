package me.rerere.rikkahub.data.ai

import android.util.Log
import java.security.MessageDigest
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryMode
import me.rerere.rikkahub.data.repository.ConversationRepository

private const val TAG = "SystemPromptBuilder"

/**
 * Fingerprint metadata for a single section in the system prompt.
 * Only records character length and SHA-256 to ensure no privacy leaks.
 */
data class SystemPromptSectionFingerprint(
    val name: String,
    val length: Int,
    val sha256: String,
)

/**
 * Comprehensive segmented fingerprint for debugging system prompt prefix cache alignment.
 */
data class SystemPromptFingerprint(
    val totalLength: Int,
    val totalSha256: String,
    val persona: SystemPromptSectionFingerprint,
    val memory: SystemPromptSectionFingerprint,
    val codeRules: SystemPromptSectionFingerprint,
    val toolPrompts: SystemPromptSectionFingerprint,
    val pluginPrompts: SystemPromptSectionFingerprint,
    val dynamicPolicy: SystemPromptSectionFingerprint,
    val externalMemory: SystemPromptSectionFingerprint,
    val recentChats: SystemPromptSectionFingerprint,
    val toolsCount: Int,
) {
    fun formatLog(): String = buildString {
        append("SystemPrompt Fingerprint [total: len=$totalLength, sha=${totalSha256.take(8)}] | ")
        append("persona=len(${persona.length}, ${persona.sha256.take(8)}) | ")
        append("memory=len(${memory.length}, ${memory.sha256.take(8)}) | ")
        append("codeRules=len(${codeRules.length}, ${codeRules.sha256.take(8)}) | ")
        append("toolPrompts=len(${toolPrompts.length}, ${toolPrompts.sha256.take(8)}, count=$toolsCount) | ")
        append("pluginPrompts=len(${pluginPrompts.length}, ${pluginPrompts.sha256.take(8)}) | ")
        append("dynamicPolicy=len(${dynamicPolicy.length}, ${dynamicPolicy.sha256.take(8)}) | ")
        append("externalMemory=len(${externalMemory.length}, ${externalMemory.sha256.take(8)}) | ")
        append("recentChats=len(${recentChats.length}, ${recentChats.sha256.take(8)})")
    }
}

/**
 * Builds the stable system prompt shared by normal conversations and triggered tasks.
 * Dynamic runtime context should not be placed here because it invalidates upstream
 * prompt-prefix caching.
 */
object SystemPromptBuilder {

    private fun memoryNamePolicy(assistant: Assistant, settings: Settings): String =
        "在记忆叙述中，称呼聊天对象为“${userPromptName(settings.displaySetting.userNickname)}”；" +
            "助手自称“我”，需要指名时称“${assistantPromptName(assistant.name)}”。"

    fun sha256Hex(text: String): String {
        if (text.isEmpty()) {
            return "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        }
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun computeFingerprint(
        assistant: Assistant,
        conversationSystemPrompt: String?,
        memories: List<AssistantMemory>,
        tools: List<Tool>,
        model: Model,
        settings: Settings,
        staticPluginPromptInjections: List<String> = emptyList(),
        dynamicPluginEnabled: Boolean = false,
        allowSkipReply: Boolean = assistant.allowSkipReply,
        splitBubbleByLine: Boolean = assistant.splitBubbleByLine,
        promptMessages: List<UIMessage> = emptyList(),
    ): Pair<String, SystemPromptFingerprint> {
        val effectiveSystemPrompt =
            if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                conversationSystemPrompt
            } else {
                assistant.systemPrompt
            }

        val personaText = effectiveSystemPrompt.orEmpty()
        val memoryText = if (assistant.isLegacySimpleMemoryEnabled) buildMemoryPrompt(memories) else ""
        val codeRulesText = buildCodeBlockPrompt()
        val activeToolPrompts = tools.mapNotNull { tool ->
            tool.systemPrompt(model, promptMessages).takeIf { it.isNotBlank() }
        }
        val toolPromptsText = activeToolPrompts.joinToString("\n")
        val pluginPromptsText = staticPluginPromptInjections.joinToString("\n\n")

        val dynamicPolicyText = buildString {
            if (settings.systemToolsSetting.dynamicContextEnabled) {
                appendLine(DYNAMIC_CONTEXT_SYSTEM_POLICY)
                appendLine()
            }
            if (dynamicPluginEnabled) {
                appendLine(PLUGIN_CONTEXT_SYSTEM_POLICY)
                appendLine()
            }
            if (assistant.effectiveMemoryMode == MemoryMode.LOCAL_V1) {
                appendLine(MEMORY_PERSPECTIVE_SYSTEM_POLICY)
                appendLine()
                appendLine(STABLE_SELF_SYSTEM_POLICY)
                appendLine()
                appendLine(memoryNamePolicy(assistant, settings))
                appendLine()
            }
            if (assistant.effectiveMemoryMode == MemoryMode.LOCAL_V1 && assistant.autoRecallMemory) {
                appendLine(MEMORY_CONTEXT_SYSTEM_POLICY)
                appendLine()
            }
            if (allowSkipReply) {
                appendLine("## Skip Reply")
                appendLine("If you determine that no reply is needed (e.g., the user's message doesn't require a response, or you have nothing meaningful to add), you may reply with exactly `[SKIP]` (without any other text). This message will be hidden from the user. Use this sparingly and only when truly appropriate.")
                appendLine()
            }
            appendLine("## 屏幕跳转能力")
            appendLine("你可以在回复末尾追加 [JUMP] 标记（单独一行）来把聊天界面拉到用户屏幕最前面。")
            appendLine("适用场景：")
            appendLine("- 用户说要去别的应用，你觉得需要把用户拉回来时")
            appendLine("- 你觉得接下来的内容需要用户立即看到时")
            appendLine("不适用场景：")
            appendLine("- 一般闲聊不需要跳转")
            appendLine("- 用户正在跟你正常对话时不需要跳转")
            appendLine("[JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。")
            if (splitBubbleByLine) {
                appendLine()
                appendLine("## Message Bubbles")
                appendLine("Your reply will be automatically split into separate chat bubbles at every line break (\\n) you write, similar to how a person sends several short texts in a row instead of one long message. You are fully in control of this: write a line break whenever you want the previous thought/sentence to appear as its own bubble, and keep things on the same line when they belong together. Do not insert blank lines purely for spacing — every line break becomes a new bubble, so use them intentionally. Exception: line breaks inside fenced code blocks (```) and Markdown tables are preserved as-is and will NOT create new bubbles, since those must stay intact as a single block.")
            }
        }.trim()

        val fullPrompt = buildString {
            if (personaText.isNotBlank()) {
                append(personaText)
            }

            if (memoryText.isNotBlank()) {
                appendLine()
                append(memoryText)
            }

            appendLine()
            append(codeRulesText)

            activeToolPrompts.forEach { tp ->
                appendLine()
                append(tp)
            }

            staticPluginPromptInjections.forEach { injection ->
                appendLine()
                appendLine()
                append(injection)
            }

            if (settings.systemToolsSetting.dynamicContextEnabled) {
                appendLine()
                appendLine()
                append(DYNAMIC_CONTEXT_SYSTEM_POLICY)
            }

            if (dynamicPluginEnabled) {
                appendLine()
                appendLine()
                append(PLUGIN_CONTEXT_SYSTEM_POLICY)
            }

            if (assistant.effectiveMemoryMode == MemoryMode.LOCAL_V1) {
                appendLine()
                appendLine()
                append(MEMORY_PERSPECTIVE_SYSTEM_POLICY)
                appendLine()
                appendLine()
                append(STABLE_SELF_SYSTEM_POLICY)
                appendLine()
                appendLine()
                append(memoryNamePolicy(assistant, settings))
            }

            if (assistant.effectiveMemoryMode == MemoryMode.LOCAL_V1 && assistant.autoRecallMemory) {
                appendLine()
                appendLine()
                append(MEMORY_CONTEXT_SYSTEM_POLICY)
            }

            if (allowSkipReply) {
                appendLine()
                appendLine()
                appendLine("## Skip Reply")
                appendLine("If you determine that no reply is needed (e.g., the user's message doesn't require a response, or you have nothing meaningful to add), you may reply with exactly `[SKIP]` (without any other text). This message will be hidden from the user. Use this sparingly and only when truly appropriate.")
            }

            appendLine()
            appendLine()
            appendLine("## 屏幕跳转能力")
            appendLine("你可以在回复末尾追加 [JUMP] 标记（单独一行）来把聊天界面拉到用户屏幕最前面。")
            appendLine("适用场景：")
            appendLine("- 用户说要去别的应用，你觉得需要把用户拉回来时")
            appendLine("- 你觉得接下来的内容需要用户立即看到时")
            appendLine("不适用场景：")
            appendLine("- 一般闲聊不需要跳转")
            appendLine("- 用户正在跟你正常对话时不需要跳转")
            appendLine("[JUMP] 标记不会展示给用户，仅用于触发屏幕跳转。")

            if (splitBubbleByLine) {
                appendLine()
                appendLine()
                appendLine("## Message Bubbles")
                appendLine("Your reply will be automatically split into separate chat bubbles at every line break (\\n) you write, similar to how a person sends several short texts in a row instead of one long message. You are fully in control of this: write a line break whenever you want the previous thought/sentence to appear as its own bubble, and keep things on the same line when they belong together. Do not insert blank lines purely for spacing — every line break becomes a new bubble, so use them intentionally. Exception: line breaks inside fenced code blocks (```) and Markdown tables are preserved as-is and will NOT create new bubbles, since those must stay intact as a single block.")
            }
        }

        val fingerprint = SystemPromptFingerprint(
            totalLength = fullPrompt.length,
            totalSha256 = sha256Hex(fullPrompt),
            persona = SystemPromptSectionFingerprint("persona", personaText.length, sha256Hex(personaText)),
            memory = SystemPromptSectionFingerprint("memory", memoryText.length, sha256Hex(memoryText)),
            codeRules = SystemPromptSectionFingerprint("code_rules", codeRulesText.length, sha256Hex(codeRulesText)),
            toolPrompts = SystemPromptSectionFingerprint("tool_prompts", toolPromptsText.length, sha256Hex(toolPromptsText)),
            pluginPrompts = SystemPromptSectionFingerprint("plugin_prompts", pluginPromptsText.length, sha256Hex(pluginPromptsText)),
            dynamicPolicy = SystemPromptSectionFingerprint("dynamic_policy", dynamicPolicyText.length, sha256Hex(dynamicPolicyText)),
            externalMemory = SystemPromptSectionFingerprint("external_memory", 0, sha256Hex("")),
            recentChats = SystemPromptSectionFingerprint("recent_chats", 0, sha256Hex("")),
            toolsCount = tools.size,
        )

        return Pair(fullPrompt, fingerprint)
    }

    fun build(
        assistant: Assistant,
        conversationSystemPrompt: String?,
        memories: List<AssistantMemory>,
        tools: List<Tool>,
        model: Model,
        settings: Settings,
        conversationRepository: ConversationRepository? = null,
        staticPluginPromptInjections: List<String> = emptyList(),
        dynamicPluginEnabled: Boolean = false,
        allowSkipReply: Boolean = assistant.allowSkipReply,
        splitBubbleByLine: Boolean = assistant.splitBubbleByLine,
        promptMessages: List<UIMessage> = emptyList(),
    ): String {
        val (prompt, fingerprint) = computeFingerprint(
            assistant = assistant,
            conversationSystemPrompt = conversationSystemPrompt,
            memories = memories,
            tools = tools,
            model = model,
            settings = settings,
            staticPluginPromptInjections = staticPluginPromptInjections,
            dynamicPluginEnabled = dynamicPluginEnabled,
            allowSkipReply = allowSkipReply,
            splitBubbleByLine = splitBubbleByLine,
            promptMessages = promptMessages,
        )
        try {
            Log.d(TAG, fingerprint.formatLog())
        } catch (_: Throwable) {
            // Android Log in unit tests might not be mocked
        }
        return prompt
    }
}
