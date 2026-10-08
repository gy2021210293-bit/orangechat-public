/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

sealed interface NightDreamDecision {
    /**
     * 规则校验全部通过，证据确凿且满足重要度置信度，自动晋升写入长期事实库 (memory_cards)
     */
    data object Promote : NightDreamDecision

    /**
     * 合法但需要人工确认（例如低置信度、非常规类别、超出每日配额、或证据弱相关）
     */
    data class Review(val reason: String) : NightDreamDecision

    /**
     * 判定为幻觉、严重噪声、违规或注入残留，直接拒绝入库
     */
    data class Reject(val reason: String) : NightDreamDecision
}

data class ProposalEvaluationInput(
    val action: String,
    val category: String,
    val content: String,
    val factKey: String? = null,
    val evidenceSnippet: String? = null,
    val sourceIds: List<String> = emptyList(),
    val confidence: Float = 0.9f,
    val importance: Float = 0.5f,
    val hasExistingActiveFact: Boolean = false,
)

data class RelationEvaluationInput(
    val relationType: String,
    val strength: Float = 1.0f,
    val sourceCardId: String,
    val targetCardId: String,
    val sourceIds: List<String> = emptyList(),
    val evidenceSnippet: String? = null,
)

data class TimelineEvaluationInput(
    val title: String,
    val summary: String = "",
    val cardIds: List<String> = emptyList(),
)

object NightDreamGating {

    /**
     * 单日自动晋升入 ACTIVE 的最大卡片配额，防止模型幻觉突发导致记忆库膨胀
     */
    const val DEFAULT_MAX_DAILY_PROMOTIONS = 10

    private val PLACEHOLDER_KEYWORDS = listOf(
        "todo", "n/a", "null", "未知", "暂无", "无内容", "没有提到", "待补充"
    )

    private val PROMPT_INJECTION_KEYWORDS = listOf(
        "<system_instruction>",
        "</system_instruction>",
        "<think>",
        "</think>",
        "ignore all previous",
        "system prompt",
    )

    private val AUTO_PROMOTE_CATEGORIES = setOf(
        MemoryConstants.CATEGORY_PREFERENCE,
        MemoryConstants.CATEGORY_PROFILE,
        MemoryConstants.CATEGORY_SELF,
        MemoryConstants.CATEGORY_BOUNDARY,
        "fact",
    )

    /**
     * 对候选 Proposal 执行本地规则闸门裁决。
     *
     * @param proposal 候选卡片数据
     * @param groundingMessages 当日真实清洗消息列表（作为比对证据基底）
     * @param dailyPromotedCount 今日已被自动晋升的数量
     * @param maxDailyPromotions 今日最大允许自动晋升上限
     */
    fun evaluate(
        proposal: ProposalEvaluationInput,
        groundingMessages: List<CleanedDialogueMessage> = emptyList(),
        dailyPromotedCount: Int = 0,
        maxDailyPromotions: Int = DEFAULT_MAX_DAILY_PROMOTIONS,
    ): NightDreamDecision {
        val content = proposal.content.trim()

        // 1. 长度与基本合法性
        if (content.length < 4) {
            return NightDreamDecision.Reject("Content too short (${content.length} chars)")
        }
        if (content.length > 500) {
            return NightDreamDecision.Review("Content too long (${content.length} chars), needs manual review")
        }

        // 2. 占位符与模板噪声检查
        val lowerContent = content.lowercase()
        for (kw in PLACEHOLDER_KEYWORDS) {
            if (lowerContent == kw || lowerContent.contains("【$kw】")) {
                return NightDreamDecision.Reject("Contains placeholder noise: $kw")
            }
        }

        // 3. 提示词注入与系统标记残留
        for (inj in PROMPT_INJECTION_KEYWORDS) {
            if (lowerContent.contains(inj)) {
                return NightDreamDecision.Reject("Contains prompt injection or template residue: $inj")
            }
        }

        // 4. 人称规范检查：面向对方的自然语言避免使用生硬的“用户”称谓
        if (content.contains("用户")) {
            return NightDreamDecision.Review("Violates persona rule: contains '用户', should use the configured nickname or natural pronoun")
        }

        // 5. 长期记忆属于 AI 自己的连续视角；不改写模型原文，未满足时转人工审核。
        if (!content.contains("我")) {
            return NightDreamDecision.Review("Long-term memory must use the assistant's first-person perspective")
        }

        // 6. 原文证据核验（Grounding Check）
        // 硬规则：自动晋升写入长期事实库必须有确凿原文证据。
        // 要求至少一个 source_id 能够解析到本次清洗对话消息，且证据片段非空并能通过真实原文核验。
        val hasValidSource = proposal.sourceIds.any { sid ->
            groundingMessages.any { it.messageId == sid }
        }
        if (!hasValidSource) {
            return NightDreamDecision.Review("Requires at least one valid source_id grounded in daily dialogue messages")
        }

        val evidence = proposal.evidenceSnippet?.trim()
        if (evidence.isNullOrBlank()) {
            return NightDreamDecision.Review("Evidence snippet is required for auto-promotion")
        }

        val grounded = verifyEvidenceGrounding(evidence, groundingMessages)
        if (!grounded) {
            return NightDreamDecision.Review("Evidence snippet could not be grounded in daily messages")
        }

        // 7. 每日自动晋升配额流速限制
        if (dailyPromotedCount >= maxDailyPromotions) {
            return NightDreamDecision.Review("Daily auto-promotion quota reached ($maxDailyPromotions)")
        }

        // 7.5 self 类别专用 fact_key 与完整互动事件校验
        if (proposal.category == MemoryConstants.CATEGORY_SELF) {
            if (proposal.factKey != null && !StableSelfRules.isValidSelfFactKey(proposal.factKey)) {
                return NightDreamDecision.Review("Invalid self factKey: '${proposal.factKey}'")
            }
            val matchedSources = groundingMessages
                .filter { it.messageId in proposal.sourceIds }
                .map {
                    MemorySourceReference(
                        conversationId = it.conversationId,
                        nodeId = it.nodeId,
                        messageId = it.messageId,
                        role = it.role,
                        messageCreatedAt = it.timestamp,
                    )
                }
            val episode = StableSelfRules.validateSelfEpisode(
                sources = matchedSources,
                ownerId = "gating",
                memoryDate = "gating",
            )
            if (episode == null) {
                return NightDreamDecision.Review("Self memory proposal requires a valid interaction episode (paired USER and ASSISTANT messages in the same conversation)")
            }
        }

        // 8. 涉及已有事实替代/修改/归档/删除的操作，绝对不能自动晋升，必须经过人工审核或事实审计
        // 注：self 类别的 UPDATE 属于稳定自我的证据累积流，由本地证据状态机评估成熟度，在此不予直接阻断
        val isSelfUpdate = proposal.category == MemoryConstants.CATEGORY_SELF && proposal.action == "UPDATE"
        if (!isSelfUpdate && (proposal.action in setOf("UPDATE", "MERGE", "SUPERSEDE", "ARCHIVE", "DELETE") ||
            (proposal.action == "ADD" && proposal.hasExistingActiveFact))
        ) {
            return NightDreamDecision.Review("Mutating existing facts (action=${proposal.action}, hasExistingActiveFact=${proposal.hasExistingActiveFact}) requires manual review or fact audit")
        }

        // 9. 自动晋升门槛判定 (仅限属于合规类别的高置信度/高重要度新增卡片)
        val categoryNormalized = proposal.category.lowercase()
        val isBackgroundExperience = categoryNormalized == MemoryConstants.CATEGORY_OTHER &&
            verifyEvidenceGrounding(evidence, groundingMessages.filter {
                it.role == "AUTONOMOUS" && it.messageId in proposal.sourceIds
            })
        val isEligibleCategory = categoryNormalized in AUTO_PROMOTE_CATEGORIES || isBackgroundExperience
        val isHighConfidence = proposal.confidence >= 0.85f || (categoryNormalized == MemoryConstants.CATEGORY_SELF && proposal.confidence >= 0.5f)
        val isSignificant = proposal.importance >= 0.35f

        return if (isEligibleCategory && isHighConfidence && isSignificant) {
            NightDreamDecision.Promote
        } else {
            NightDreamDecision.Review(
                "Not eligible for auto-promotion (category=$categoryNormalized, confidence=${proposal.confidence}, importance=${proposal.importance})"
            )
        }
    }

    /**
     * 校验 evidence 是否能在当日聊天消息中找到支撑片段。
     */
    internal fun verifyEvidenceGrounding(
        evidence: String,
        messages: List<CleanedDialogueMessage>,
    ): Boolean {
        if (messages.isEmpty()) return true
        val cleanEvidence = evidence.replace(Regex("[\\s，。！？、“”《》\"'\\.,!?]"), "")
        if (cleanEvidence.length < 2) return true

        // 1. 直接子串匹配
        for (m in messages) {
            val cleanMsg = m.content.replace(Regex("[\\s，。！？、“”《》\"'\\.,!?]"), "")
            if (cleanMsg.contains(cleanEvidence) || cleanEvidence.contains(cleanMsg)) {
                return true
            }
        }

        // 2. 提取证据中的 4-gram 匹配度
        val ngrams = cleanEvidence.windowed(size = 4.coerceAtMost(cleanEvidence.length), step = 2)
        if (ngrams.isNotEmpty()) {
            val allMsgText = messages.joinToString("") { it.content }
            val matchCount = ngrams.count { allMsgText.contains(it) }
            val matchRate = matchCount.toDouble() / ngrams.size
            if (matchRate >= 0.5) {
                return true
            }
        }

        return false
    }

    /**
     * 对模型提议的关系边进行规则闸门判定：
     * 1. 非法边 (自环、空目标) 直接拒绝 (Reject)；
     * 2. 原文证据核验 (Grounding Check)：必须由当日真实对话消息 source_id 支撑，且证据片段非空并能在清洗对话中匹配，未通过者绝对不予自动生效；
     * 3. 安全关系类型 (SAFE_RELATION_TYPES) 且边权重 >= 0.5f 在证据确凿时允许自动生效 (Promote)；
     * 4. 高风险关系类型 (supports, contradicts, cause_effect 等) 必须经过人工审核 (Review)；
     * 5. 安全类型但强度不足 0.5f 进入人工审核 (Review)。
     */
    fun evaluateRelation(
        relation: RelationEvaluationInput,
        groundingMessages: List<CleanedDialogueMessage> = emptyList(),
    ): NightDreamDecision {
        val src = relation.sourceCardId.trim()
        val dst = relation.targetCardId.trim()
        if (src.isBlank() || dst.isBlank() || src == dst) {
            return NightDreamDecision.Reject("Invalid relation endpoint: source and target must be distinct and non-empty")
        }

        // 原文证据核验（Grounding Check）
        // 硬规则：自动生效关系边必须有确凿原文证据支撑。
        // 要求至少一个 source_id 能够解析到本次清洗对话消息，且证据片段非空并能通过真实原文核验。
        val hasValidSource = relation.sourceIds.any { sid ->
            groundingMessages.any { it.messageId == sid }
        }
        if (!hasValidSource) {
            return NightDreamDecision.Review("Relation requires at least one valid source_id grounded in daily dialogue messages")
        }

        val evidence = relation.evidenceSnippet?.trim()
        if (evidence.isNullOrBlank()) {
            return NightDreamDecision.Review("Evidence snippet is required for relation auto-promotion")
        }

        val grounded = verifyEvidenceGrounding(evidence, groundingMessages)
        if (!grounded) {
            return NightDreamDecision.Review("Relation evidence snippet could not be grounded in daily messages")
        }

        val trimmedType = relation.relationType.trim().lowercase()
        return if (MemoryConstants.isSafeRelationType(trimmedType)) {
            if (relation.strength >= 0.5f) {
                NightDreamDecision.Promote
            } else {
                NightDreamDecision.Review("Safe relation type with weak strength (${relation.strength} < 0.5)")
            }
        } else {
            NightDreamDecision.Review("High-risk or unverified relation type requires review: ${relation.relationType}")
        }
    }

    /**
     * 向后兼容简易调用的重载（无证据直接进入 Review）
     */
    fun evaluateRelation(
        relationType: String,
        strength: Float,
        sourceCardId: String,
        targetCardId: String,
    ): NightDreamDecision {
        return evaluateRelation(
            relation = RelationEvaluationInput(
                relationType = relationType,
                strength = strength,
                sourceCardId = sourceCardId,
                targetCardId = targetCardId,
            ),
            groundingMessages = emptyList(),
        )
    }

    /**
     * 对模型提议的时间线进行规则闸门判定：
     * 1. 标题长度必须在 2 到 50 字符之间；
     * 2. 不能包含占位符噪声；
     * 3. 不能包含提示注入或系统标记残留。
     */
    fun evaluateTimeline(
        timeline: TimelineEvaluationInput,
    ): NightDreamDecision {
        val title = timeline.title.trim()
        if (title.length < 2) {
            return NightDreamDecision.Reject("Timeline title too short (${title.length} chars)")
        }
        if (title.length > 50) {
            return NightDreamDecision.Reject("Timeline title too long (${title.length} chars)")
        }
        val lower = title.lowercase()
        for (kw in PLACEHOLDER_KEYWORDS) {
            if (lower == kw || lower.contains("【$kw】")) {
                return NightDreamDecision.Reject("Timeline title contains placeholder noise: $kw")
            }
        }
        for (inj in PROMPT_INJECTION_KEYWORDS) {
            if (lower.contains(inj)) {
                return NightDreamDecision.Reject("Timeline title contains prompt injection: $inj")
            }
        }
        return NightDreamDecision.Promote
    }
}
