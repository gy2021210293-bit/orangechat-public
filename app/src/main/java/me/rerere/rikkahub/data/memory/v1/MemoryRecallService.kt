/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.ai.assistantPromptName
import me.rerere.rikkahub.data.ai.userPromptName
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryMode
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.uuid.Uuid

private const val TAG = "MemoryRecallService"

private inline fun safeLogD(tag: String, message: String) {
    runCatching { Log.d(tag, message) }
}

private inline fun safeLogW(tag: String, message: String, throwable: Throwable? = null) {
    runCatching { Log.w(tag, message, throwable) }
}

/**
 * 聊天原话证据片段。
 */
data class MemoryEvidenceGroup(
    val conversationTitle: String,
    val matchedRole: String,
    val matchedContent: String,
    val beforeRole: String? = null,
    val beforeContent: String? = null,
    val afterRole: String? = null,
    val afterContent: String? = null,
)

/**
 * 记忆原文证据检索器接口（解耦召回服务与数据库/仓库实现，便于测试与定制）。
 */
interface MemoryEvidenceSearcher {
    suspend fun searchEvidence(
        query: String,
        assistantId: String,
        sinceEpochMilli: Long,
        excludeMessageId: String? = null,
        limit: Int = 10,
    ): List<MessageSearchResult>

    suspend fun getConversation(uuid: Uuid): Conversation?
}

/**
 * 自动召回结果包装。
 */
data class MemoryRecallResult(
    val cards: List<MemoryCardEntity>,
    val formattedPrompt: String,
    val evidences: List<MemoryEvidenceGroup> = emptyList(),
    val timelineExpandedCards: List<MemoryCardEntity> = emptyList(),
    val graphExpandedCards: List<MemoryCardEntity> = emptyList(),
    val emotionResonantCards: List<MemoryCardEntity> = emptyList(),
    val recalledExperiences: List<MemoryExperienceCoordinateEntity> = emptyList(),
    val maxVectorSimilarity: Float? = null,
)

/**
 * Local Memory V1 & V2 自动召回服务.
 *
 * 职责：
 * 1. 低信息门禁 (MemoryRecallGate) 拦截；
 * 2. 关键词通道 (FTS5 jieba_query) 与可选向量通道并发召回 Top 40 候选；
 * 3. 倒数排名融合 (RRF, k=60) 与重要度微调；
 * 4. 状态过滤 (仅 ACTIVE)、同一 fact_key 只留最新 ACTIVE、当轮同源去重、内容去重；
 * 5. 数量截断 (默认 0~8 条，不凑数) 与 Token 预算控制 (<= 1800 tokens)；
 * 6. 原文证据桥 (近 90 天历史原话独立字面检索与邻近上下文提取，仅作证据与导航，受隐私开关保护)；
 * 7. E 轴情绪共振独立有界通道 (最多 1~2 条) 与第一人称回应姿态注入；
 * 8. 构造面向当前昵称的标准 <memory_context> 格式。
 */
class MemoryRecallService(
    private val repository: MemoryRepositoryV1,
    private val embeddingService: MemoryEmbeddingService,
    private val conversationRepository: ConversationRepository? = null,
    private val messageFtsManager: MessageFtsManager? = null,
    private val evidenceSearcher: MemoryEvidenceSearcher? = null,
    private val emotionDetector: QueryEmotionDetector = RuleBasedQueryEmotionDetector(),
) {
    private val resolvedEvidenceSearcher: MemoryEvidenceSearcher? = evidenceSearcher
        ?: if (conversationRepository != null && messageFtsManager != null) {
            object : MemoryEvidenceSearcher {
                override suspend fun searchEvidence(
                    query: String,
                    assistantId: String,
                    sinceEpochMilli: Long,
                    excludeMessageId: String?,
                    limit: Int,
                ): List<MessageSearchResult> = messageFtsManager.searchEvidence(
                    query = query,
                    assistantId = assistantId,
                    sinceEpochMilli = sinceEpochMilli,
                    excludeMessageId = excludeMessageId,
                    limit = limit,
                )

                override suspend fun getConversation(uuid: Uuid): Conversation? =
                    conversationRepository.getConversationById(uuid)
            }
        } else null

    /**
     * 执行单轮自动召回。
     *
     * @param assistant 当前助理配置
     * @param query 当前用户本轮输入的真实文本
     * @param settings 全局应用配置 (包含 Provider 与 Model 信息)
     * @param currentConversationId 当前对话 ID (可选)
     * @param currentMessageId 当前消息 ID (用于当轮同源去重，可选)
     * @return 召回结果，若无需注入或召回为 0 条则返回 null
     */
    suspend fun recall(
        assistant: Assistant,
        query: String,
        settings: Settings,
        currentConversationId: String? = null,
        currentMessageId: String? = null,
        excludedCardIds: Set<String> = emptySet(),
        maxCountOverride: Int? = null,
    ): MemoryRecallResult? = withContext(Dispatchers.IO) {
        // 模式与开关前置检查
        if (assistant.effectiveMemoryMode != MemoryMode.LOCAL_V1 || !assistant.autoRecallMemory) {
            return@withContext null
        }

        // 1. 低信息门禁检查
        val maxCount = (maxCountOverride ?: assistant.maxAutoRecallCount).coerceIn(0, 20)
        if (maxCount == 0 || !assistant.autoRecallMemory) {
            safeLogD(TAG, "Recall skipped: maxAutoRecallCount=$maxCount, autoRecallMemory=${assistant.autoRecallMemory}")
            return@withContext null
        }

        if (MemoryRecallGate.isLowInformation(query)) {
            safeLogD(TAG, "Query (len=${query.length}) intercepted by MemoryRecallGate")
            return@withContext null
        }

        val assistantId = assistant.id.toString()
        val includeGlobal = assistant.useGlobalMemory
        val queryEmotion = if (assistant.enableExperienceMemory && assistant.allowEmotionResonanceRecall) {
            emotionDetector.detect(query)
        } else null

        // 2. 双通道并发候选拉取 (各最多 40 条)
        val (ftsCandidates, vectorCandidatesWithScores) = coroutineScope {
            val ftsDeferred = async {
                runCatching {
                    repository.search(
                        query = query,
                        ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                        ownerId = assistantId,
                        statusList = listOf(MemoryConstants.STATUS_ACTIVE),
                        limit = CANDIDATE_LIMIT_PER_CHANNEL,
                        includeGlobal = includeGlobal,
                    )
                }.onFailure {
                    safeLogW(TAG, "FTS search channel failed", it)
                }.getOrDefault(emptyList())
            }

            val vectorDeferred = async {
                val embeddingModelId = assistant.memoryEmbeddingModelId
                if (embeddingModelId == null) {
                    emptyList()
                } else {
                    runCatching {
                        val model = settings.findModelById(embeddingModelId)
                        val providerSetting = model?.findProvider(settings.providers)
                        if (model != null && providerSetting != null) {
                            val queryVector = embeddingService.generateEmbedding(query, model, providerSetting)
                            if (queryVector != null) {
                                val pairs = embeddingService.searchSimilarMemories(
                                    assistantId = assistantId,
                                    includeGlobal = includeGlobal,
                                    queryVector = queryVector,
                                    embeddingProvider = providerSetting.id.toString(),
                                    embeddingModel = model.modelId,
                                    limit = CANDIDATE_LIMIT_PER_CHANNEL,
                                )
                                val cardIds = pairs.map { it.first }
                                val cardsMap = repository.getCardsByIds(cardIds).associateBy { it.id }
                                pairs.mapNotNull { (cardId, similarity) -> cardsMap[cardId]?.let { it to similarity } }
                            } else emptyList()
                        } else emptyList()
                    }.onFailure {
                        safeLogW(TAG, "Vector search channel failed", it)
                    }.getOrDefault(emptyList())
                }
            }

            val archivedFtsDeferred = async {
                runCatching {
                    repository.search(
                        query = query,
                        ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                        ownerId = assistantId,
                        statusList = listOf(MemoryConstants.STATUS_ARCHIVED),
                        limit = 5,
                        includeGlobal = includeGlobal,
                    )
                }.getOrDefault(emptyList())
            }

            val (fts, vec) = ftsDeferred.await() to vectorDeferred.await()
            val archived = archivedFtsDeferred.await()
            if (archived.isNotEmpty()) {
                runCatching { repository.recordCardRecalls(archived.map { it.id }) }
            }
            fts to vec
        }
        val vectorCandidates = vectorCandidatesWithScores.map { it.first }
        val vectorSimilarityById = vectorCandidatesWithScores.associate { it.first.id to it.second }

        data class RecallSelection(
            val directCards: List<MemoryCardEntity>,
            val timelineExpanded: List<MemoryCardEntity>,
            val graphExpanded: List<MemoryCardEntity>,
            val backfillCards: List<MemoryCardEntity>,
            val emotionResonant: List<MemoryCardEntity> = emptyList(),
        )

        val selection = if (ftsCandidates.isEmpty() && vectorCandidates.isEmpty()) {
            val resonantCards = if (assistant.enableExperienceMemory && assistant.allowEmotionResonanceRecall && queryEmotion != null && queryEmotion.confidence >= 0.6f && maxCount >= 1) {
                runCatching {
                    val allCurrentE = repository.getCurrentExperiencesForOwner(MemoryConstants.OWNER_TYPE_ASSISTANT, assistantId)
                    val validE = allCurrentE.filter { it.memoryCardId !in excludedCardIds }
                    val matches = ExperienceRecallScoring.findResonantExperiences(
                        userCoord = queryEmotion.valence.toDouble() to queryEmotion.arousal.toDouble(),
                        candidates = validE,
                        limit = 1,
                    )
                    val rawCards = repository.getCardsByIds(matches.map { it.item.memoryCardId })
                    filterAuthoritativeCards(rawCards, assistantId, includeGlobal)
                }.getOrDefault(emptyList())
            } else emptyList()
            RecallSelection(emptyList(), emptyList(), emptyList(), emptyList(), resonantCards)
        } else {
            // 3. 先按 fact_key 向仓储查询权威有效状态，淘汰过时旧事实与未决冲突争议事实，再参与 RRF 融合排序
            val allRawCards = (ftsCandidates + vectorCandidates).associateBy { it.id }.values
            val rawFactKeys = allRawCards.mapNotNull { it.factKey?.takeIf(String::isNotBlank) }.distinct()
            val authoritativeStates = if (rawFactKeys.isNotEmpty()) {
                repository.getAuthoritativeFactStates(
                    ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                    ownerId = assistantId,
                    includeGlobal = includeGlobal,
                    factKeys = rawFactKeys,
                )
            } else {
                emptyMap()
            }

            // 过滤淘汰同 factKey 下较旧的过时事实或未决冲突争议卡片，并排除外部显式指定的卡片（如常驻 self 卡）
            val validCandidateCards = allRawCards.filter { card ->
                if (card.status != MemoryConstants.STATUS_ACTIVE) return@filter false
                if (card.id in excludedCardIds) return@filter false
                val fk = card.factKey
                if (!fk.isNullOrBlank()) {
                    val authState = authoritativeStates[fk]
                    if (authState != null && !authState.isAuthoritativeAndValid(card)) {
                        return@filter false
                    }
                    true
                } else {
                    true
                }
            }

            if (validCandidateCards.isEmpty()) {
                RecallSelection(emptyList(), emptyList(), emptyList(), emptyList())
            } else {
                val candidateEs: Map<String, MemoryExperienceCoordinateEntity> = if (assistant.enableExperienceMemory && validCandidateCards.isNotEmpty()) {
                    repository.getCurrentExperiencesForCards(validCandidateCards.map { it.id }).associateBy { it.memoryCardId }
                } else emptyMap()

                // 4. RRF 融合与重排 (Reciprocal Rank Fusion)
                val ftsRankMap = ftsCandidates.mapIndexed { index, card -> card.id to (index + 1) }.toMap()
                val vectorRankMap = vectorCandidates.mapIndexed { index, card -> card.id to (index + 1) }.toMap()

                data class ScoredCard(
                    val card: MemoryCardEntity,
                    val rrfScore: Double,
                    val finalScore: Double,
                )

                val scoredCards = validCandidateCards.map { card ->
                    var rrf = 0.0
                    ftsRankMap[card.id]?.let { rank ->
                        rrf += 1.0 / (RRF_K + rank)
                    }
                    vectorRankMap[card.id]?.let { rank ->
                        rrf += 1.0 / (RRF_K + rank)
                    }
                    val expBonus = candidateEs[card.id]?.let { it.eInitialPriority.coerceIn(1, 100) * 0.00005 } ?: 0.0
                    // 相关度是第一门槛，importance 仅在相关度基础上微调 (0.001 权重)，E 优先级提供微调 (至多 0.005)
                    val finalScore = rrf + (card.importance.coerceIn(0f, 1f) * 0.001) + expBonus
                    ScoredCard(card, rrf, finalScore)
                }.sortedByDescending { it.finalScore }

                // 5. 过滤当轮同源消息与内容去重
                val candidateIds = scoredCards.map { it.card.id }
                val currentTurnSourceExcluded = if (!currentMessageId.isNullOrBlank() && candidateIds.isNotEmpty()) {
                    val allSources = repository.getSourcesForMemories(candidateIds)
                    allSources.filter { it.messageId == currentMessageId }.map { it.memoryId }.toSet()
                } else {
                    emptySet()
                }
                val totalExcludedIds = excludedCardIds + currentTurnSourceExcluded

                val filteredCards = mutableListOf<MemoryCardEntity>()
                val seenFactKeys = mutableSetOf<String>()
                val seenContent = mutableSetOf<String>()

                for (scored in scoredCards) {
                    val card = scored.card

                    // 当轮同源去重与排除卡片去重
                    if (card.id in totalExcludedIds) continue

                    // 同一 fact_key 防御性再次去重
                    if (!card.factKey.isNullOrBlank()) {
                        if (card.factKey in seenFactKeys) continue
                        seenFactKeys.add(card.factKey)
                    }

                    // 内容去重 (归一化去除首尾空白)
                    val normalizedContent = card.content.trim()
                    if (normalizedContent.isEmpty() || normalizedContent in seenContent) continue
                    seenContent.add(normalizedContent)

                    filteredCards.add(card)
                }

                // 6. 按助手设置的召回条数分配直接命中、时间线和图扩展名额
                val emotionEligible = assistant.enableExperienceMemory && assistant.allowEmotionResonanceRecall && queryEmotion != null && queryEmotion.confidence >= 0.6f
                val emotionReserved = if (emotionEligible && maxCount >= 1) 1 else 0
                val normalQuota = (maxCount - emotionReserved).coerceAtLeast(0)
                val extensionEnabled = normalQuota >= 3
                val extensionSlots = (normalQuota - 1).coerceAtLeast(0)
                val timelineReserved = if (extensionEnabled) minOf(2, extensionSlots) else 0
                val graphReserved = if (extensionEnabled) minOf(1, extensionSlots - timelineReserved) else 0
                val seedQuota = normalQuota - timelineReserved - graphReserved
                val seeds = if (normalQuota == 0) emptyList() else filteredCards.take(seedQuota)
                val seedIds = seeds.map { it.id }.toSet()

                val timelineExpanded = if (timelineReserved > 0 && seeds.isNotEmpty()) {
                    runCatching {
                        val candidates = repository.expandTimelines(
                            seedCardIds = seeds.map { it.id },
                            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                            ownerId = assistantId,
                            includeGlobal = includeGlobal,
                            limit = timelineReserved * 3,
                        )
                        val timelineCurrentSourceIds = if (currentMessageId.isNullOrBlank()) {
                            emptySet()
                        } else {
                            repository.getSourcesForMemories(candidates.map { it.id })
                                .filter { it.messageId == currentMessageId }
                                .map { it.memoryId }
                                .toSet()
                        }
                        val timelineFactKeys = candidates.mapNotNull { it.factKey?.takeIf(String::isNotBlank) }.distinct()
                        val timelineAuthStates = if (timelineFactKeys.isNotEmpty()) {
                            repository.getAuthoritativeFactStates(
                                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                                ownerId = assistantId,
                                includeGlobal = includeGlobal,
                                factKeys = timelineFactKeys,
                            )
                        } else emptyMap()

                        val seenContent = seeds.map { it.content.trim() }.toMutableSet()
                        val seenFactKeys = seeds.mapNotNull { it.factKey?.takeIf(String::isNotBlank) }.toMutableSet()
                        candidates.filter { card ->
                            val content = card.content.trim()
                            val factKey = card.factKey?.takeIf(String::isNotBlank)
                            if (card.status != MemoryConstants.STATUS_ACTIVE || card.id in seedIds || card.id in totalExcludedIds ||
                                card.id in timelineCurrentSourceIds || content.isEmpty() ||
                                content in seenContent || (factKey != null && factKey in seenFactKeys)) {
                                false
                            } else {
                                if (factKey != null) {
                                    val authState = timelineAuthStates[factKey]
                                    if (authState != null && !authState.isAuthoritativeAndValid(card)) {
                                        return@filter false
                                    }
                                }
                                seenContent += content
                                factKey?.let { seenFactKeys += it }
                                true
                            }
                        }.take(timelineReserved)
                    }.onFailure {
                        safeLogW(TAG, "Timeline expansion failed", it)
                    }.getOrDefault(emptyList())
                } else emptyList()

                val timelineIds = timelineExpanded.map { it.id }.toSet()

                val expanded = if (graphReserved > 0 && assistant.enableGraphExpansion && seeds.isNotEmpty()) {
                    runCatching {
                        val graphCandidates = repository.graphExpand(
                            seedCardIds = seeds.map { it.id },
                            maxHops = 1,
                            limit = graphReserved * 3,
                            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                            ownerId = assistantId,
                            includeGlobal = includeGlobal,
                        ).filter { it.id !in seedIds && it.id !in timelineIds && it.id !in totalExcludedIds && it.status == MemoryConstants.STATUS_ACTIVE }

                        val graphCurrentSourceIds = if (currentMessageId.isNullOrBlank() || graphCandidates.isEmpty()) {
                            emptySet()
                        } else {
                            repository.getSourcesForMemories(graphCandidates.map { it.id })
                                .filter { it.messageId == currentMessageId }
                                .map { it.memoryId }
                                .toSet()
                        }

                        val graphFactKeys = graphCandidates.mapNotNull { it.factKey?.takeIf(String::isNotBlank) }.distinct()
                        val graphAuthStates = if (graphFactKeys.isNotEmpty()) {
                            repository.getAuthoritativeFactStates(
                                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                                ownerId = assistantId,
                                includeGlobal = includeGlobal,
                                factKeys = graphFactKeys,
                            )
                        } else emptyMap()

                        val seenContent = (seeds + timelineExpanded).map { it.content.trim() }.toMutableSet()
                        val seenFactKeys = (seeds + timelineExpanded).mapNotNull { it.factKey?.takeIf(String::isNotBlank) }.toMutableSet()

                        graphCandidates.filter { card ->
                            val content = card.content.trim()
                            val factKey = card.factKey?.takeIf(String::isNotBlank)
                            if (card.id in seedIds || card.id in timelineIds || card.id in totalExcludedIds || card.id in graphCurrentSourceIds ||
                                content.isEmpty() || content in seenContent || (factKey != null && factKey in seenFactKeys)) {
                                false
                            } else {
                                if (factKey != null) {
                                    val authState = graphAuthStates[factKey]
                                    if (authState != null && !authState.isAuthoritativeAndValid(card)) {
                                        return@filter false
                                    }
                                }
                                seenContent += content
                                factKey?.let { seenFactKeys += it }
                                true
                            }
                        }.take(graphReserved)
                    }.onFailure {
                        safeLogW(TAG, "Graph expansion failed", it)
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }

                // 情绪共振独立候选通道
                val chosenCardsWithoutResonance = (seeds + timelineExpanded + expanded)
                val chosenCardIds = chosenCardsWithoutResonance.map { it.id }.toSet()
                val resonantCards = if (emotionReserved > 0) {
                    runCatching {
                        val chosenEs = candidateEs.filterKeys { it in chosenCardIds }
                        val matchedInChosen = if (chosenEs.isNotEmpty()) {
                            ExperienceRecallScoring.findResonantExperiences(
                                userCoord = queryEmotion!!.valence.toDouble() to queryEmotion.arousal.toDouble(),
                                candidates = chosenEs.values.toList(),
                                limit = 1,
                            )
                        } else emptyList()

                        if (matchedInChosen.isNotEmpty()) {
                            // 已有普通卡中包含高共振卡，不额外拉取，保留给 backfill 或作为自然共振
                            emptyList()
                        } else {
                            val allOwnerEs = repository.getCurrentExperiencesForOwner(MemoryConstants.OWNER_TYPE_ASSISTANT, assistantId)
                            val validResonantEs = allOwnerEs.filter {
                                it.memoryCardId !in totalExcludedIds && it.memoryCardId !in chosenCardIds
                            }
                            val topResonant = ExperienceRecallScoring.findResonantExperiences(
                                userCoord = queryEmotion!!.valence.toDouble() to queryEmotion.arousal.toDouble(),
                                candidates = validResonantEs,
                                limit = 1,
                            )
                            if (topResonant.isNotEmpty()) {
                                val rawCards = repository.getCardsByIds(topResonant.map { it.item.memoryCardId })
                                filterAuthoritativeCards(rawCards, assistantId, includeGlobal)
                            } else emptyList()
                        }
                    }.getOrDefault(emptyList())
                } else emptyList()

                // 回填未占满的图与共振配额
                val currentTotal = seeds.size + timelineExpanded.size + expanded.size + resonantCards.size
                val backfillNeeded = (maxCount - currentTotal).coerceAtLeast(0)
                val backfillCards = if (backfillNeeded > 0) {
                    val usedIds = seedIds + timelineIds + expanded.map { it.id }.toSet() + resonantCards.map { it.id }.toSet() + totalExcludedIds
                    val usedContent = (seeds + timelineExpanded + expanded + resonantCards).map { it.content.trim() }.toMutableSet()
                    val usedFactKeys = (seeds + timelineExpanded + expanded + resonantCards).mapNotNull { it.factKey?.takeIf(String::isNotBlank) }.toMutableSet()
                    filteredCards.drop(seedQuota)
                        .filter { card ->
                            val content = card.content.trim()
                            val fk = card.factKey?.takeIf(String::isNotBlank)
                            if (card.id in usedIds || content.isEmpty() || content in usedContent || (fk != null && fk in usedFactKeys)) {
                                false
                            } else {
                                usedContent += content
                                fk?.let { usedFactKeys += it }
                                true
                            }
                        }
                        .take(backfillNeeded)
                } else {
                    emptyList()
                }
                RecallSelection(seeds, timelineExpanded, expanded, backfillCards, resonantCards)
            }
        }

        val timelineExpandedCardIds = selection.timelineExpanded.map { it.id }.toSet()
        val graphExpandedCardIds = selection.graphExpanded.map { it.id }.toSet()
        val emotionResonantCardIds = mutableSetOf<String>()
        selection.emotionResonant.forEach { emotionResonantCardIds.add(it.id) }

        val selectedCards = (selection.directCards + selection.timelineExpanded +
            selection.graphExpanded + selection.backfillCards + selection.emotionResonant).distinctBy { it.id }

        // 7. 原文证据桥检索（受隐私开关保护）
        val evidenceGroups = recallEvidence(
            assistant = assistant,
            userName = userPromptName(settings.displaySetting.userNickname),
            query = query,
            currentConversationId = currentConversationId,
            currentMessageId = currentMessageId,
            selectedCards = selectedCards,
        )

        if (selectedCards.isEmpty() && evidenceGroups.isEmpty()) {
            return@withContext null
        }

        // 8. 提取入选卡片的所有 CURRENT E 坐标以注入主体经验，并标记自然共振卡
        val experiencesMap: Map<String, MemoryExperienceCoordinateEntity> = if (assistant.enableExperienceMemory && selectedCards.isNotEmpty()) {
            val list = repository.getCurrentExperiencesForCards(selectedCards.map { it.id })
            if (assistant.allowEmotionResonanceRecall && queryEmotion != null && queryEmotion.confidence >= 0.6f && list.isNotEmpty()) {
                runCatching {
                    val matches = ExperienceRecallScoring.findResonantExperiences(
                        userCoord = queryEmotion.valence.toDouble() to queryEmotion.arousal.toDouble(),
                        candidates = list,
                        limit = 1,
                    )
                    matches.forEach { emotionResonantCardIds.add(it.item.memoryCardId) }
                }
            }
            list.associateBy { it.memoryCardId }
        } else emptyMap()

        // 9. 构造规范格式的 <memory_context>。召回条数由助手设置控制，不截断内容。
        val formattedPrompt = buildRecallPrompt(
            userName = userPromptName(settings.displaySetting.userNickname),
            cards = selectedCards,
            timelineExpandedCardIds = timelineExpandedCardIds,
            graphExpandedCardIds = graphExpandedCardIds,
            emotionResonantCardIds = emotionResonantCardIds,
            experiences = experiencesMap,
            evidences = evidenceGroups,
        )

        val finalTimelineExpandedCards = selectedCards.filter { it.id in timelineExpandedCardIds }
        val finalGraphExpandedCards = selectedCards.filter { it.id in graphExpandedCardIds }
        val finalEmotionResonantCards = selectedCards.filter { it.id in emotionResonantCardIds }
        val finalRecalledExperiences = selectedCards.mapNotNull { experiencesMap[it.id] }

        // 10. [V2-4] M 轴代谢事件记录：递增命中次数并更新热度
        if (selectedCards.isNotEmpty()) {
            runCatching {
                repository.recordCardRecalls(selectedCards.map { it.id })
            }.onFailure {
                safeLogW(TAG, "Failed to record card recall metabolism metrics", it)
            }
        }

        MemoryRecallResult(
            cards = selectedCards,
            formattedPrompt = formattedPrompt,
            evidences = evidenceGroups,
            timelineExpandedCards = finalTimelineExpandedCards,
            graphExpandedCards = finalGraphExpandedCards,
            emotionResonantCards = finalEmotionResonantCards,
            recalledExperiences = finalRecalledExperiences,
            maxVectorSimilarity = selectedCards.mapNotNull { vectorSimilarityById[it.id] }.maxOrNull(),
        )
    }

    private suspend fun recallEvidence(
        assistant: Assistant,
        userName: String,
        query: String,
        currentConversationId: String?,
        currentMessageId: String?,
        selectedCards: List<MemoryCardEntity>,
    ): List<MemoryEvidenceGroup> {
        if (!assistant.allowMemoryEvidenceRead) return emptyList()
        val searcher = resolvedEvidenceSearcher ?: return emptyList()

        val isLiteral = MemoryLiteralQuery.shouldRunLiteralSearch(query)
        val isCardResultWeak = selectedCards.isEmpty()
        if (!isLiteral && !isCardResultWeak) {
            return emptyList()
        }

        val assistantId = assistant.id.toString()
        val ninetyDaysAgo = System.currentTimeMillis() - 90L * 24 * 3600 * 1000L

        val literalTerms = MemoryLiteralQuery.extractLiteralQueryTerms(query)
        val searchQueries = if (literalTerms.isNotEmpty()) {
            literalTerms.take(2)
        } else {
            listOf(query)
        }

        val searchHits = mutableListOf<MessageSearchResult>()
        for (q in searchQueries) {
            val hits = searcher.searchEvidence(
                query = q,
                assistantId = assistantId,
                sinceEpochMilli = ninetyDaysAgo,
                excludeMessageId = currentMessageId,
                limit = 15,
            )
            for (hit in hits) {
                if (searchHits.none { it.messageId == hit.messageId }) {
                    searchHits.add(hit)
                }
            }
            if (searchHits.size >= 15) break
        }

        if (searchHits.isEmpty()) return emptyList()

        val evidenceGroups = mutableListOf<MemoryEvidenceGroup>()
        val seenConvIds = mutableSetOf<String>()

        for (hit in searchHits) {
            if (evidenceGroups.size >= MAX_EVIDENCE_GROUPS) break

            if (hit.conversationId in seenConvIds) continue

            val convUuid = runCatching { Uuid.parse(hit.conversationId) }.getOrNull() ?: continue
            val conversation = searcher.getConversation(convUuid) ?: continue

            val activeMessages = conversation.currentMessages
            val index = activeMessages.indexOfFirst { it.id.toString() == hit.messageId }
            val (matchedMsg, beforeMsg, afterMsg) = if (index >= 0) {
                Triple(activeMessages[index], activeMessages.getOrNull(index - 1), activeMessages.getOrNull(index + 1))
            } else {
                // 不在当前主分支，尝试从所有 node 中定位该命中消息
                val node = conversation.messageNodes.find { it.id.toString() == hit.nodeId } ?: continue
                val msg = node.messages.find { it.id.toString() == hit.messageId } ?: continue
                val nodeIndex = conversation.messageNodes.indexOf(node)
                val before = if (nodeIndex > 0) {
                    val prevNode = conversation.messageNodes[nodeIndex - 1]
                    prevNode.messages.getOrNull(prevNode.selectIndex)
                } else null
                val after = if (nodeIndex in 0 until conversation.messageNodes.size - 1) {
                    val nextNode = conversation.messageNodes[nodeIndex + 1]
                    nextNode.messages.getOrNull(nextNode.selectIndex)
                } else null
                Triple(msg, before, after)
            }

            val matchedMsgTime = runCatching {
                matchedMsg.createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds()
            }.getOrDefault(0L)
            if (matchedMsgTime < ninetyDaysAgo) continue

            val matchedText = matchedMsg.toText().trim()
            if (matchedText.isEmpty()) continue

            seenConvIds.add(hit.conversationId)

            fun roleLabel(role: MessageRole): String = when (role) {
                MessageRole.USER -> userName
                MessageRole.ASSISTANT -> assistantPromptName(assistant.name)
                else -> role.name.lowercase()
            }

            val group = MemoryEvidenceGroup(
                conversationTitle = conversation.title.ifBlank { "历史对话" },
                matchedRole = roleLabel(matchedMsg.role),
                matchedContent = matchedText.take(MAX_MATCHED_CHARS),
                beforeRole = beforeMsg?.let { roleLabel(it.role) },
                beforeContent = beforeMsg?.toText()?.trim()?.take(MAX_CONTEXT_CHARS)?.ifBlank { null },
                afterRole = afterMsg?.let { roleLabel(it.role) },
                afterContent = afterMsg?.toText()?.trim()?.take(MAX_CONTEXT_CHARS)?.ifBlank { null },
            )
            evidenceGroups.add(group)
        }

        return evidenceGroups
    }

    private fun buildRecallPrompt(
        userName: String,
        cards: List<MemoryCardEntity>,
        timelineExpandedCardIds: Set<String>,
        graphExpandedCardIds: Set<String>,
        emotionResonantCardIds: Set<String> = emptySet(),
        experiences: Map<String, MemoryExperienceCoordinateEntity> = emptyMap(),
        evidences: List<MemoryEvidenceGroup>,
    ): String {
        return buildString {
            appendLine("<memory_context>")
            if (cards.isNotEmpty()) {
                appendLine("以下是与${userName}当前话题可能相关的长期记忆，仅在确实相关时自然参考；")
                appendLine("若记忆与${userName}本轮明确表达冲突，以本轮为准，并可通过记忆工具提出修正。")
                cards.forEachIndexed { index, card ->
                    val tags = buildList {
                        if (card.id in emotionResonantCardIds) add("[情绪共振]")
                        if (card.id in timelineExpandedCardIds) add("[同一事项的进展]")
                        if (card.id in graphExpandedCardIds) add("[关联记忆: 来自关系拓展]")
                    }.joinToString("") { "$it " }
                    appendLine("${index + 1}. [memory_id=${card.id}] $tags${card.content}")
                }
            }

            val expCards = cards.mapNotNull { card ->
                experiences[card.id]?.let { card to it }
            }
            if (expCards.isNotEmpty()) {
                if (cards.isNotEmpty()) {
                    appendLine()
                }
                appendLine("以下是我在过去重要经历中形成的主体经验意义与回应倾向（<recalled_experience>）。")
                appendLine("这是我过往的经验倾向，而非必须死板执行的命令；仅在当前情境确实相似时自然体现，当前${userName}明确表达与事实优先：")
                expCards.forEach { (card, exp) ->
                    appendLine("<recalled_experience memory_id=\"${card.id}\" e_id=\"${exp.id}\">")
                    appendLine("  <memory>${card.content}</memory>")
                    appendLine("  <my_meaning>${exp.meaning}</my_meaning>")
                    appendLine("  <response_tendency>${exp.responseTendency}</response_tendency>")
                    appendLine("  <tension>${String.format(java.util.Locale.US, "%.2f", exp.tension)}</tension>")
                    appendLine("</recalled_experience>")
                }
            }

            if (evidences.isNotEmpty()) {
                if (cards.isNotEmpty() || expCards.isNotEmpty()) {
                    appendLine()
                }
                appendLine("<evidence_context>")
                appendLine("以下为近 90 天聊天原文证据片段（仅供核对与导航参考，不可覆盖长期记忆事实）：")
                evidences.forEachIndexed { index, eg ->
                    appendLine("${index + 1}. [对话: \"${eg.conversationTitle}\"]")
                    if (!eg.beforeContent.isNullOrBlank()) {
                        appendLine("   上文 [${eg.beforeRole}]: ${eg.beforeContent}")
                    }
                    appendLine("   原话 [${eg.matchedRole}]: ${eg.matchedContent}")
                    if (!eg.afterContent.isNullOrBlank()) {
                        appendLine("   下文 [${eg.afterRole}]: ${eg.afterContent}")
                    }
                }
                append("</evidence_context>")
            }
            append("</memory_context>")
        }
    }

    private suspend fun filterAuthoritativeCards(
        cards: List<MemoryCardEntity>,
        ownerId: String,
        includeGlobal: Boolean,
    ): List<MemoryCardEntity> {
        val factKeys = cards.mapNotNull { it.factKey?.takeIf(String::isNotBlank) }.distinct()
        val authStates = if (factKeys.isNotEmpty()) {
            repository.getAuthoritativeFactStates(
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = ownerId,
                includeGlobal = includeGlobal,
                factKeys = factKeys,
            )
        } else {
            emptyMap()
        }
        return cards.filter { card ->
            if (card.status != MemoryConstants.STATUS_ACTIVE) return@filter false
            val fk = card.factKey
            if (!fk.isNullOrBlank()) {
                val authState = authStates[fk]
                if (authState != null && !authState.isAuthoritativeAndValid(card)) {
                    return@filter false
                }
            }
            true
        }
    }

    companion object {
        private const val CANDIDATE_LIMIT_PER_CHANNEL = 40
        private const val RRF_K = 60.0
        private const val MAX_EVIDENCE_GROUPS = 2
        private const val MAX_MATCHED_CHARS = 500
        private const val MAX_CONTEXT_CHARS = 250
    }
}
