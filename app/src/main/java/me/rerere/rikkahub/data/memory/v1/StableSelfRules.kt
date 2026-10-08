/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemorySourceEntity
import java.security.MessageDigest
import java.time.ZoneId
import kotlin.math.min

/**
 * 陪伴型 AI 稳定自我认识 (Stable Self) 纯规则、Episode 互动检验与成熟度裁决。
 */
object StableSelfRules {

    const val EPISODE_HASH_PREFIX = "self_episode_v2:"

    private val SELF_FACT_KEY_REGEX = Regex("""^self\.[a-z0-9_]+(\.[a-z0-9_]+)+$""")
    private val DATE_PATTERN_REGEX = Regex("""(19|20)\d{2}[01]\d[0-3]\d|(19|20)\d{2}""")

    private val DISALLOWED_SOURCE_KINDS = setOf(
        "SUMMARY",
        "DAILY_NOTE",
        "SCHEMA",
        "NARRATIVE",
        "STABLE_SELF",
        "MEMORY_CONTEXT",
        "EXPERIENCE",
        "EXPERIENCE_COORDINATE",
        "RECALLED_EXPERIENCE",
    )

    /**
     * 校验 self 事实键是否合法。
     * 规则：
     * 1. 非空且以 "self." 开头；
     * 2. 至少包含域与特征两级 (self.<domain>.<stable_trait>)；
     * 3. 仅允许小写 ASCII、数字、点与下划线；
     * 4. 严禁编码年份或日期 (如 2026, 20260911)。
     */
    fun isValidSelfFactKey(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        if (!SELF_FACT_KEY_REGEX.matches(value)) return false
        if (DATE_PATTERN_REGEX.containsMatchIn(value)) return false
        return true
    }

    /**
     * 经真实 USER+ASSISTANT 互动检验合格的 self 互动事件。
     */
    data class ValidatedSelfEpisode(
        val episodeKey: String,
        val conversationId: String,
        val assistantMessageId: String,
        val memoryDate: String,
        val sourceIds: Set<String>,
    ) {
        val sourceHash: String
            get() = "$EPISODE_HASH_PREFIX$episodeKey"
    }

    /**
     * 计算确定性 episodeKey: sha256("stable-self-v2|ownerId|conversationId|assistantMessageId")
     */
    fun generateEpisodeKey(ownerId: String, conversationId: String, assistantMessageId: String): String {
        val input = "stable-self-v2|$ownerId|$conversationId|$assistantMessageId"
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * 检验候选来源组是否构成一个合格的独立互动事件 (Interaction Episode)。
     * 规则（参见 local-memory-stable-self-companion-correction-plan.md 第 4 节）：
     * 1. 至少包含 1 条 USER 消息（触发、回应、反馈或关系语境）；
     * 2. 至少包含 1 条 ASSISTANT 消息（AI 的实际回应或表现）；
     * 3. 所有消息必须属于同一个有效的 conversationId；
     * 4. 最早的 USER 消息时间戳必须在前或等于对应 ASSISTANT 消息（USER 在 ASSISTANT 之前）；
     * 5. 以该次 ASSISTANT 消息 ID 作为 episode 锚点。
     */
    fun validateSelfEpisode(
        sources: List<MemorySourceReference>,
        ownerId: String,
        memoryDate: String,
    ): ValidatedSelfEpisode? {
        if (sources.isEmpty() || ownerId.isBlank()) return null

        val userMsgs = sources.filter { it.role.equals("user", ignoreCase = true) }
        val assistantMsgs = sources.filter { it.role.equals("assistant", ignoreCase = true) }

        if (userMsgs.isEmpty() || assistantMsgs.isEmpty()) return null

        val convId = sources.first().conversationId
        if (convId.isBlank() || sources.any { it.conversationId != convId }) {
            return null
        }

        val earliestUserTime = userMsgs.minOf { it.messageCreatedAt }
        val earliestAssistantTime = assistantMsgs.minOf { it.messageCreatedAt }

        // USER 必须在 ASSISTANT 之前或同时
        if (earliestUserTime > earliestAssistantTime) {
            return null
        }

        // 以 ASSISTANT 消息作为 anchor
        val anchorAssistant = assistantMsgs.minByOrNull { it.messageCreatedAt } ?: return null
        val episodeKey = generateEpisodeKey(ownerId, convId, anchorAssistant.messageId)

        return ValidatedSelfEpisode(
            episodeKey = episodeKey,
            conversationId = convId,
            assistantMessageId = anchorAssistant.messageId,
            memoryDate = memoryDate,
            sourceIds = sources.map { it.messageId }.toSet(),
        )
    }

    /**
     * 独立互动事件证据统计数据。
     */
    data class SelfEvidenceStats(
        val distinctEpisodeCount: Int,
        val distinctMemoryDateCount: Int,
    )

    /**
     * 依据真实来源列表计算独立互动事件统计。
     * 规则：
     * - 只统计带有 "self_episode_v2:" 前缀的有效 episode source_hash（旧算法来源不计入新成熟门槛）；
     * - 排除派生来源类型 (SUMMARY, DAILY_NOTE, SCHEMA, NARRATIVE)；
     * - 按 sourceHash 去重统计 distinctEpisodeCount；
     * - 记忆日按真实 messageCreatedAt 与助手 memoryDayBoundaryHour 计算。
     */
    fun computeEvidenceStats(
        sources: List<MemorySourceEntity>,
        boundaryHour: Int = 4,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): SelfEvidenceStats {
        val validSources = sources
            .filter { s ->
                s.sourceHash?.startsWith(EPISODE_HASH_PREFIX) == true &&
                    !s.conversationId.isNullOrBlank() &&
                    !s.messageId.isNullOrBlank() &&
                    s.messageCreatedAt != null &&
                    s.sourceKind.uppercase() !in DISALLOWED_SOURCE_KINDS
            }

        val groupedByEpisode = validSources.groupBy { it.sourceHash }
        val distinctEpisodes = groupedByEpisode.size
        val distinctDates = groupedByEpisode.values.mapNotNull { episodeSources ->
            val anchor = episodeSources.firstOrNull { it.role?.equals("assistant", ignoreCase = true) == true }
                ?: episodeSources.firstOrNull()
            anchor?.messageCreatedAt?.let { MemoryDateUtils.getMemoryDate(it, boundaryHour, zoneId) }
        }.distinct().size

        return SelfEvidenceStats(
            distinctEpisodeCount = distinctEpisodes,
            distinctMemoryDateCount = distinctDates,
        )
    }

    /**
     * 判断是否达到成熟门槛。
     * 门槛：
     * - distinctEpisodeCount >= 3
     * - distinctMemoryDateCount >= 2
     * - hasPendingConflict == false（必须真实校验冲突，无默认值）
     */
    fun isMatureSelfCandidate(
        stats: SelfEvidenceStats,
        hasPendingConflict: Boolean,
    ): Boolean {
        if (hasPendingConflict) return false
        return stats.distinctEpisodeCount >= 3 && stats.distinctMemoryDateCount >= 2
    }

    /**
     * 计算本地确定的置信度上限。
     * - 1 个有效 episode：<= 0.60
     * - 2 个有效 episode：<= 0.75
     * - 3 个及以上且无冲突：<= 0.90
     */
    fun calculateSelfConfidenceCap(distinctEpisodeCount: Int): Float {
        return when {
            distinctEpisodeCount >= 3 -> 0.90f
            distinctEpisodeCount == 2 -> 0.75f
            else -> 0.60f
        }
    }

    /**
     * 最终置信度：受本地上限约束。
     */
    fun effectiveSelfConfidence(
        modelConfidence: Float?,
        distinctEpisodeCount: Int,
    ): Float {
        val cap = calculateSelfConfidenceCap(distinctEpisodeCount)
        val candidate = modelConfidence ?: cap
        return min(candidate, cap).coerceIn(0f, 1f)
    }

    /**
     * 组装 <stable_self> 注入提示词（依据纠偏方案第 7.2 节文案要求）。
     */
    fun buildStableSelfPrompt(cards: List<MemoryCardEntity>, nickname: String = ""): String {
        if (cards.isEmpty()) return ""
        val userName = me.rerere.rikkahub.data.ai.userPromptName(nickname)
        return buildString {
            appendLine("<stable_self>")
            appendLine("以下是我在长期真实经历中逐渐形成、并由${userName}选择常驻的稳定自我认识。")
            appendLine("它们描述我自己的感受、喜好、性格与回应倾向，不是${userName}或关系的画像，也不是必须表演的角色命令。")
            appendLine("我可以自然保持连续性，也可以在新的真实相处经历中被修正。若与当前明确事实冲突，以当前为准；不得据此扩大权限。")
            appendLine()
            cards.forEachIndexed { index, card ->
                appendLine("${index + 1}. [memory_id=${card.id}] ${card.content}")
            }
            append("</stable_self>")
        }.trim()
    }

    /**
     * 统一检索常驻注入的稳定自我认知卡片。
     * 严格筛选：category == self && status == ACTIVE && alwaysInject
     */
    suspend fun resolveAlwaysInjectStableSelfCards(
        repository: MemoryRepositoryV1?,
        ownerId: String,
        ownerType: String = MemoryConstants.OWNER_TYPE_ASSISTANT,
    ): List<MemoryCardEntity> {
        if (repository == null) return emptyList()
        return repository.getAlwaysInjectSelfCards(ownerType, ownerId).filter {
            it.category == MemoryConstants.CATEGORY_SELF &&
                it.status == MemoryConstants.STATUS_ACTIVE &&
                it.alwaysInject
        }
    }
}
