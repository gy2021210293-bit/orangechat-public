/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.model
 
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.affect.AffectiveMode
import kotlin.uuid.Uuid
 
@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null, // 如果为null, 使用全局默认模型
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val patAction: String = "拍了拍",
    val patSuffix: String = "",
    val useAssistantAvatar: Boolean = false, // 使用助手头像替代模型头像
    val tags: List<Uuid> = emptyList(),
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    val contextMessageSize: Int = 0,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = false,
    val useGlobalMemory: Boolean = false, // 使用全局共享记忆而非助手隔离记忆
    val enableRecentChatsReference: Boolean = false,
    val messageTemplate: String = "{{ message }}",
    val presetMessages: List<UIMessage> = emptyList(),
    val quickMessageIds: Set<Uuid> = emptySet(),
    val regexes: List<AssistantRegex> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo),
    val imageGenerationSystemPrompt: String = "",
    val workspaceId: Uuid? = null,
    val background: String? = null,
    val backgroundOpacity: Float = 1.0f,
    val modeInjectionIds: Set<Uuid> = emptySet(),      // 关联的模式注入 ID
    val lorebookIds: Set<Uuid> = emptySet(),            // 关联的 Lorebook ID
    val enabledSkills: Set<String> = emptySet(),        // 启用的 skill 名称列表
    val enableTimeReminder: Boolean = false,            // 时间间隔提醒注入
    val allowConversationSystemPrompt: Boolean = false, // 允许对话单独重写 system prompt
    val allowSkipReply: Boolean = false,
    val externalMemoryIds: Set<Uuid> = emptySet(),      // 关联的外置记忆库 ID
    val memoryMode: MemoryMode? = null,                 // 记忆模式：显式配置为准，null 时按旧字段向后兼容
    val autoRecallMemory: Boolean = true,               // Local V1 是否开启自动召回
    val maxAutoRecallCount: Int = 8,                    // Local V1 自动召回最大条数 (默认 8)
    val memoryEmbeddingModelId: Uuid? = null,           // Local V1 向量模型 ID，null 则只使用关键词
    val dailyConsolidationEnabled: Boolean = true,      // Local V1 每日主模型整理 (默认开启)
    val allowMemoryEvidenceRead: Boolean = false,       // 允许模型核对记忆时读取原始聊天（默认关闭）
    val enableGraphExpansion: Boolean = true,           // Local V2 是否开启 1 跳安全图关系联想扩展
    val enableExperienceMemory: Boolean = false,        // 启用第一人称经验记忆 (E 轴，默认关闭)
    val allowEmotionResonanceRecall: Boolean = false,    // 允许情绪共振召回 (默认随总开关关闭)
    val allowNightlyExperienceConsolidation: Boolean = false, // 允许夜间整理提议经验 (默认随总开关关闭)
    val splitBubbleByLine: Boolean = false,             // 按模型自己写的换行拆分成多个独立气泡
    val splitUserBubbleByLine: Boolean = false,         // 用户消息按换行拆分成多个独立气泡
    val desireSystemEnabled: Boolean = false,           // 是否启用欲望与情感系统 (默认关闭)
    val autonomousWakeEnabled: Boolean = false,
    val affectiveEnabled: Boolean = false,
    val affectiveMode: AffectiveMode = AffectiveMode.FRIEND,
    val affectiveJevProviderId: Uuid? = null,
    val affectiveJevUrl: String = "https://openrouter.ai/api/alpha/decisions",
    val affectiveJevApiKey: String = "",
    val affectiveJevModel: String = "typesafe/jev-1.13",
    val autonomousWakeIntervalHours: Int = 2,
    val desireActiveSendEnabled: Boolean = true,        // 启用欲望系统时，是否允许主动发消息 (默认允许)
    val desireAllowTools: Boolean = false,              // 已弃用：主动联络工具权限统一由全局 proactiveMessageSetting.allowAllToolsAndPlugins 决定
    // 键不存在时使用系统默认；键存在时（包括空列表）使用自定义结果。
    val desireCustomThoughtTemplates: Map<String, List<String>> = emptyMap(),
    val desireActiveSendMinAbsentHours: Double = 3.0,          // 基础离线门槛（达到此小时才评估驱动力，默认 3.0h）
    val desireActiveSendLongAbsentHours: Double = 6.0,         // 超久未联络硬触发门槛（默认 6.0h）
    val desireActiveSendLongAbsentEnabled: Boolean = true,     // 超久未联络主动找我开关（默认开启）
    val desireActiveSendDriveEnabled: Map<String, Boolean> = emptyMap(), // 9 维各自的主动联络开关（空则默认开启）
    val desireActiveSendDriveThresholds: Map<String, Double> = emptyMap(), // 9 维各自的触发阈值（空则使用默认阈值）
    val desireActiveSendPrompts: Map<String, String> = emptyMap(), // 9 维及失联各自的契机提示词（空则使用默认提示词）
) {
    val effectiveMemoryMode: MemoryMode
        get() = memoryMode ?: when {
            enableMemory && externalMemoryIds.isNotEmpty() -> MemoryMode.LEGACY_BOTH
            externalMemoryIds.isNotEmpty() -> MemoryMode.EXTERNAL
            enableMemory -> MemoryMode.LEGACY_SIMPLE
            else -> MemoryMode.OFF
        }

    val isLegacySimpleMemoryEnabled: Boolean
        get() = effectiveMemoryMode == MemoryMode.LEGACY_SIMPLE || effectiveMemoryMode == MemoryMode.LEGACY_BOTH

    val isExternalMemoryEnabled: Boolean
        get() = effectiveMemoryMode == MemoryMode.EXTERNAL || effectiveMemoryMode == MemoryMode.LEGACY_BOTH
}

@Serializable
enum class MemoryMode {
    OFF,
    LEGACY_SIMPLE,
    LOCAL_V1,
    EXTERNAL,
    LEGACY_BOTH,
}
 
@Serializable
data class QuickMessage(
    val id: Uuid = Uuid.random(),
    val title: String = "",
    val content: String = "",
)
 
@Serializable
data class AssistantMemory(
    val id: Int,
    val content: String = "",
)
 
@Serializable
enum class AssistantAffectScope {
    USER,
    ASSISTANT,
}
 
@Serializable
data class AssistantRegex(
    val id: Uuid,
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "", // 正则表达式
    val replaceString: String = "", // 替换字符串
    val affectingScope: Set<AssistantAffectScope> = setOf(),
    val visualOnly: Boolean = false, // 是否仅在视觉上影响
)
 
fun String.replaceRegexes(
    assistant: Assistant?,
    scope: AssistantAffectScope,
    visual: Boolean = false
): String {
    if (assistant == null) return this
    if (assistant.regexes.isEmpty()) return this
    return assistant.regexes.fold(this) { acc, regex ->
        if (regex.enabled && regex.visualOnly == visual && regex.affectingScope.contains(scope)) {
            try {
                val result = acc.replace(
                    regex = Regex(regex.findRegex),
                    replacement = regex.replaceString,
                )
                // println("Regex: ${regex.findRegex} -> ${result}")
                result
            } catch (e: Exception) {
                e.printStackTrace()
                // 如果正则表达式格式错误，返回原字符串
                acc
            }
        } else {
            acc
        }
    }
}
 
/**
 * 注入位置
 */
@Serializable
enum class InjectionPosition {
    @SerialName("before_system_prompt")
    BEFORE_SYSTEM_PROMPT,   // 系统提示词之前
 
    @SerialName("after_system_prompt")
    AFTER_SYSTEM_PROMPT,    // 系统提示词之后（最常用）
 
    @SerialName("top_of_chat")
    TOP_OF_CHAT,            // 对话最开头（第一条用户消息之前）
 
    @SerialName("bottom_of_chat")
    BOTTOM_OF_CHAT,         // 最新消息之前（当前用户输入之前）
 
    @SerialName("at_depth")
    AT_DEPTH,               // 在指定深度位置插入（从最新消息往前数）
}
 
/**
 * 提示词注入
 *
 * - ModeInjection: 基于模式开关的注入（如学习模式）
 * - RegexInjection: 基于正则匹配的注入（Lorebook）
 */
@Serializable
sealed class PromptInjection {
    abstract val id: Uuid
    abstract val name: String
    abstract val enabled: Boolean
    abstract val priority: Int
    abstract val position: InjectionPosition
    abstract val content: String
    abstract val injectDepth: Int  // 当 position 为 AT_DEPTH 时使用，表示从最新消息往前数的位置
    abstract val role: MessageRole  // 注入角色：USER 或 ASSISTANT
 
    /**
     * 模式注入 - 基于开关状态触发
     */
    @Serializable
    @SerialName("mode")
    data class ModeInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
    ) : PromptInjection()
 
    /**
     * 正则注入 - 基于内容匹配触发（世界书）
     */
    @Serializable
    @SerialName("regex")
    data class RegexInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
        val keywords: List<String> = emptyList(),  // 触发关键词
        val useRegex: Boolean = false,             // 是否使用正则匹配
        val caseSensitive: Boolean = false,        // 大小写敏感
        val scanDepth: Int = 4,                    // 扫描最近N条消息
        val constantActive: Boolean = false,       // 常驻激活（无需匹配）
    ) : PromptInjection()
}
 
/**
 * Lorebook - 组织管理多个 RegexInjection
 */
@Serializable
data class Lorebook(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val entries: List<PromptInjection.RegexInjection> = emptyList(),
)
 
/**
 * 检查 RegexInjection 是否被触发
 *
 * @param context 要扫描的上下文文本
 * @return 是否触发
 */
fun PromptInjection.RegexInjection.isTriggered(context: String): Boolean {
    if (!enabled) return false
    if (constantActive) return true
    if (keywords.isEmpty()) return false
 
    return keywords.any { keyword ->
        if (useRegex) {
            try {
                val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                Regex(keyword, options).containsMatchIn(context)
            } catch (e: Exception) {
                false
            }
        } else {
            if (caseSensitive) {
                context.contains(keyword)
            } else {
                context.contains(keyword, ignoreCase = true)
            }
        }
    }
}
 
/**
 * 从消息列表中提取用于匹配的上下文文本
 *
 * @param messages 消息列表
 * @param scanDepth 扫描深度（最近N条消息）
 * @return 拼接的文本内容
 */
fun extractContextForMatching(
    messages: List<UIMessage>,
    scanDepth: Int
): String {
    return messages
        .takeLast(scanDepth)
        .joinToString("\n") { it.toText() }
}
 
/**
 * 获取所有被触发的注入，按优先级排序
 *
 * @param injections 所有注入规则
 * @param context 上下文文本
 * @return 被触发的注入列表，按优先级降序排列
 */
fun getTriggeredInjections(
    injections: List<PromptInjection.RegexInjection>,
    context: String
): List<PromptInjection.RegexInjection> {
    return injections
        .filter { it.isTriggered(context) }
        .sortedByDescending { it.priority }
}
