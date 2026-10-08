/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import me.rerere.rikkahub.data.ai.MEMORY_PERSPECTIVE_SYSTEM_POLICY
import me.rerere.rikkahub.data.ai.STABLE_SELF_SYSTEM_POLICY
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryMode

/**
 * 夜间整理专用的 AI 身份上下文。
 * 纯净封装当前助手的系统人设与稳定自我认知，彻底与聊天级临时变量（环境、工具、用户请求等）解耦。
 */
data class NightIdentityContext(
    val assistantId: String,
    val personaPrompt: String,
    val stableSelfPrompt: String,
    val memoryPerspectivePolicy: String = MEMORY_PERSPECTIVE_SYSTEM_POLICY,
    val stableSelfPolicy: String = STABLE_SELF_SYSTEM_POLICY,
) {
    /**
     * 将人设提示词与 Stable Self 组装为前置身份系统提示词文本。
     */
    fun buildIdentityPrompt(): String = buildString {
        if (personaPrompt.isNotBlank()) {
            appendLine("【当前助手人设与身份设定】")
            appendLine(personaPrompt)
            appendLine()
        }
        if (stableSelfPrompt.isNotBlank()) {
            appendLine("【当前助手稳定自我认知 (Stable Self)】")
            appendLine(stableSelfPrompt)
            appendLine()
        }
    }.trim()
}

/**
 * 构造夜间整理所需的纯净身份上下文，复用正常聊天的身份来源。
 */
object NightIdentityContextBuilder {

    suspend fun build(
        assistant: Assistant,
        repository: MemoryRepositoryV1?,
        nickname: String = "",
    ): NightIdentityContext {
        val personaPrompt = assistant.systemPrompt.orEmpty().trim()
        val stableSelfPrompt = if (assistant.effectiveMemoryMode == MemoryMode.LOCAL_V1 && repository != null) {
            val stableCards = StableSelfRules.resolveAlwaysInjectStableSelfCards(
                repository = repository,
                ownerId = assistant.id.toString(),
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            )
            StableSelfRules.buildStableSelfPrompt(stableCards, nickname)
        } else {
            ""
        }

        return NightIdentityContext(
            assistantId = assistant.id.toString(),
            personaPrompt = personaPrompt,
            stableSelfPrompt = stableSelfPrompt,
        )
    }
}
