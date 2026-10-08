/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryDailyNoteEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryProposalEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryTimelineEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MemoryMode
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.Settings
import java.security.MessageDigest
import kotlin.uuid.Uuid

private const val TAG = "MemoryConsolidationService"
private const val MAX_CONVERSATION_CHARS_PER_BATCH = 150_000

private inline fun safeLogI(tag: String, message: String) {
    runCatching { Log.i(tag, message) }
}

private inline fun safeLogW(tag: String, message: String, throwable: Throwable? = null) {
    runCatching { Log.w(tag, message, throwable) }
}

private inline fun safeLogE(tag: String, message: String, throwable: Throwable? = null) {
    runCatching { Log.e(tag, message, throwable) }
}

sealed interface ConsolidationServiceResult {
    data class Success(
        val dailyNote: MemoryDailyNoteEntity,
        val appliedPatches: Int,
        val conflictedPatches: Int,
        val skippedByFingerprint: Boolean,
    ) : ConsolidationServiceResult

    data class Skipped(val reason: String) : ConsolidationServiceResult

    data class Failed(val errorCode: String, val message: String) : ConsolidationServiceResult
}

data class CleanedDialogueMessage(
    val conversationId: String,
    val messageId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val nodeId: String? = null,
)

open class MemoryConsolidationService(
    protected val repository: MemoryRepositoryV1,
    private val conversationRepository: ConversationRepository? = null,
    private val providerManager: ProviderManager? = null,
    private val settingsStore: SettingsStore? = null,
    private val embeddingService: MemoryEmbeddingService? = null,
    private val testSettingsProvider: (() -> Settings)? = null,
    private val metabolismInspector: MemoryMetabolismInspector? = null,
    private val desireRepository: me.rerere.rikkahub.data.ai.desire.DesireRepository? = null,
) {
    var onDailyNoteCreated: (suspend (assistantId: String, memoryDate: String) -> Unit)? = null

    /**
     * 针对指定 owner 和记忆日执行每日整理。
     *
     * @param ownerType "assistant"
     * @param ownerId 助手的 UUID 字符串
     * @param memoryDate 形如 "2026-09-08"
     * @param force 是否强制重跑（即使 fingerprint 相同）
     */
    suspend fun consolidateDate(
        ownerType: String,
        ownerId: String,
        memoryDate: String,
        force: Boolean = false,
    ): ConsolidationServiceResult {
        safeLogI(TAG, "Starting consolidation for $ownerId on date $memoryDate (force=$force)")

        if (ownerType != MemoryConstants.OWNER_TYPE_ASSISTANT) {
            safeLogW(TAG, "Consolidation requested for unsupported ownerType: $ownerType")
            return ConsolidationServiceResult.Skipped("Consolidation is only supported for assistants")
        }

        val assistantUuid = runCatching { Uuid.parse(ownerId) }.getOrNull()
        val existingRun = repository.getConsolidationRun(ownerId, memoryDate)

        // 0. 快速幂等检查：非强制模式下，若已成功且指纹相同，直接跳过并避免争抢租约
        if (!force && existingRun?.status == MemoryConstants.RUN_STATUS_SUCCEEDED && assistantUuid != null) {
            val (startMs, endMs) = MemoryDateUtils.getMemoryDateRange(memoryDate)
            val cleanedMessages = loadAndCleanMessages(assistantUuid, startMs, endMs)
            val fingerprint = computeFingerprint(cleanedMessages)
            if (existingRun.sourceFingerprint == fingerprint) {
                safeLogI(TAG, "Fingerprint unchanged ($fingerprint), skipping consolidation for $memoryDate")
                val existingNote = repository.getDailyNote(ownerId, memoryDate)
                return if (existingNote != null) {
                    ConsolidationServiceResult.Success(
                        dailyNote = existingNote,
                        appliedPatches = 0,
                        conflictedPatches = 0,
                        skippedByFingerprint = true,
                    )
                } else {
                    ConsolidationServiceResult.Skipped("Fingerprint unchanged and note already processed")
                }
            }
        }

        // 1. 获取本地执行租约（防并发、防闹钟与Worker冲突）
        val runToken = repository.acquireConsolidationLease(
            ownerType = ownerType,
            ownerId = ownerId,
            memoryDate = memoryDate,
            leaseDurationMs = 15 * 60 * 1000L,
        )
        if (runToken == null) {
            safeLogW(TAG, "Consolidation lease is currently locked for $ownerId on $memoryDate")
            return ConsolidationServiceResult.Skipped("Lease locked by another consolidation task")
        }

        // 启动专属心跳协程定期续租，防止长任务超时被新任务抢占脑裂
        val heartbeatScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val heartbeatJob = heartbeatScope.launch {
            while (isActive) {
                delay(30_000L)
                val renewed = repository.renewConsolidationLease(
                    ownerType = ownerType,
                    ownerId = ownerId,
                    memoryDate = memoryDate,
                    runToken = runToken,
                    leaseDurationMs = 15 * 60 * 1000L,
                )
                if (!renewed) {
                    safeLogW(TAG, "Heartbeat lease renewal failed for $ownerId on $memoryDate with token $runToken")
                    break
                }
            }
        }

        try {
            // 2. 获取助手与主聊天模型
            val settings = testSettingsProvider?.invoke() ?: settingsStore?.settingsFlow?.first() ?: Settings()
            val assistant = assistantUuid?.let { runCatching { settings.getAssistantById(it) }.getOrNull() }
            if (assistant == null) {
                repository.finishConsolidationRun(ownerType, ownerId, memoryDate, MemoryConstants.RUN_STATUS_FAILED, errorCode = "ASSISTANT_NOT_FOUND", runToken = runToken)
                return ConsolidationServiceResult.Failed("ASSISTANT_NOT_FOUND", "Assistant $ownerId not found")
            }

            val modelId = assistant.chatModelId ?: settings.chatModelId
            val model = settings.findModelById(modelId) ?: settings.providers.flatMap { it.models }.firstOrNull()
            val providerSetting = model?.findProvider(settings.providers)
            if (model == null || providerSetting == null) {
                repository.finishConsolidationRun(ownerType, ownerId, memoryDate, MemoryConstants.RUN_STATUS_FAILED, errorCode = "NO_MODEL_CONFIGURED", runToken = runToken)
                return ConsolidationServiceResult.Failed("NO_MODEL_CONFIGURED", "No chat model configured for consolidation")
            }

            // 3. 读取该记忆日时间窗内的对话消息并清洗
            val (startMs, endMs) = MemoryDateUtils.getMemoryDateRange(memoryDate)
            val cleanedMessages = loadAndCleanMessages(assistant.id, startMs, endMs)
            if (cleanedMessages.isEmpty()) {
                safeLogI(TAG, "No conversations found for assistant $ownerId on $memoryDate")
                val emptyFingerprint = computeFingerprint(cleanedMessages)
                repository.finishConsolidationRun(ownerType, ownerId, memoryDate, MemoryConstants.RUN_STATUS_SUCCEEDED, fingerprint = emptyFingerprint, runToken = runToken)
                return ConsolidationServiceResult.Skipped("No dialogue in date range")
            }

            // 3.1 [V2-2] 对话切块持久化为 event_chunks（作为长期事实的可追溯证据底座）
            val eventChunks = MemoryEventChunker.chunkMessages(
                ownerType, ownerId, cleanedMessages.filter { it.role != "AUTONOMOUS" },
            )
            repository.deleteEventChunksByDateRange(ownerType, ownerId, startMs, endMs)
            repository.saveEventChunks(eventChunks)

            // 4. 计算 source_fingerprint
            val fingerprint = computeFingerprint(cleanedMessages)

            // 5. 检索现有 ACTIVE / REVIEW 卡片及时间线作为上下文
            val activeCards = repository.getCardsByStatus(ownerType, ownerId, MemoryConstants.STATUS_ACTIVE)
            val reviewCards = repository.getCardsByStatus(ownerType, ownerId, MemoryConstants.STATUS_REVIEW)
            val contextCards = (activeCards + reviewCards).sortedByDescending { it.updatedAt }.take(50)
            val activeTimelines = runCatching { repository.getActiveTimelines(ownerType, ownerId) }.getOrDefault(emptyList())

            // 6. 按固定字符上限分批，直接使用原始内容，不额外调用摘要模型。
            val conversationBatches = splitConversationContent(cleanedMessages).map(::formatConversationBatch)
            val allowExperience = assistant.enableExperienceMemory && assistant.allowNightlyExperienceConsolidation
            val identityContext = NightIdentityContextBuilder.build(
                assistant, repository, settings.displaySetting.userNickname,
            )

            // 7. 每批沿用现有两次主模型调用：日记与结构化提取。
            val dailyNotes = mutableListOf<DailyNoteResult>()
            for ((index, content) in conversationBatches.withIndex()) {
                val response = generateModelText(
                    providerSetting = providerSetting,
                    model = model,
                    systemPrompt = buildDailyNoteSystemPrompt(
                        identityContext = identityContext,
                        userName = me.rerere.rikkahub.data.ai.userPromptName(settings.displaySetting.userNickname),
                    ),
                    userPrompt = buildDailyNoteUserPrompt(memoryDate, content),
                    temperature = 0.7f,
                    reasoningLevel = ReasoningLevel.AUTO,
                )
                if (response.isBlank()) {
                    repository.finishConsolidationRun(ownerType, ownerId, memoryDate, MemoryConstants.RUN_STATUS_FAILED, errorCode = "EMPTY_MODEL_RESPONSE", runToken = runToken)
                    return ConsolidationServiceResult.Failed("EMPTY_MODEL_RESPONSE", "Model returned empty response for batch ${index + 1}")
                }
                val note = try {
                    MemoryConsolidationParser.parseDailyNote(response).also(::validateDailyNoteFirstPerson)
                } catch (e: Exception) {
                    safeLogE(TAG, "Failed to parse daily note JSON: dailyNoteLen=${response.length}, model=${model.id}, date=$memoryDate", e)
                    repository.finishConsolidationRun(ownerType, ownerId, memoryDate, MemoryConstants.RUN_STATUS_FAILED, errorCode = "PARSE_ERROR", runToken = runToken)
                    return ConsolidationServiceResult.Failed("PARSE_ERROR", "Failed to parse daily note for batch ${index + 1}: ${e.message}")
                }
                dailyNotes.add(note)
            }
            val dailyNote = DailyNoteResult(
                summary = dailyNotes.joinToString("\n\n") { it.summary },
                emotionalThread = dailyNotes.mapNotNull { it.emotionalThread?.takeIf(String::isNotBlank) }
                    .joinToString("\n\n").ifBlank { null },
                unfinishedTopics = dailyNotes.flatMap { it.unfinishedTopics }.distinct(),
            )

            val structuredResults = mutableListOf<DailyConsolidationResult>()
            for ((index, content) in conversationBatches.withIndex()) {
                val note = dailyNotes[index]
                val response = generateModelText(
                    providerSetting = providerSetting,
                    model = model,
                    systemPrompt = buildStructuredSystemPrompt(
                        userName = me.rerere.rikkahub.data.ai.userPromptName(settings.displaySetting.userNickname),
                        identityContext = identityContext,
                        allowExperience = allowExperience,
                    ),
                    userPrompt = buildStructuredUserPrompt(
                        memoryDate = memoryDate,
                        existingCards = contextCards,
                        conversationContent = content,
                        existingTimelines = activeTimelines,
                        dailyNoteSummary = note.summary,
                        dailyNoteEmotionalThread = note.emotionalThread,
                        dailyNoteUnfinishedTopics = note.unfinishedTopics,
                        allowExperience = allowExperience,
                    ),
                    temperature = 0.2f,
                    reasoningLevel = ReasoningLevel.AUTO,
                )
                if (response.isBlank()) {
                    repository.finishConsolidationRun(ownerType, ownerId, memoryDate, MemoryConstants.RUN_STATUS_FAILED, errorCode = "EMPTY_MODEL_RESPONSE", runToken = runToken)
                    return ConsolidationServiceResult.Failed("EMPTY_MODEL_RESPONSE", "Model returned empty response for batch ${index + 1}")
                }
                val result = try {
                    MemoryConsolidationParser.parseStructured(response)
                } catch (e: Exception) {
                    safeLogE(TAG, "Failed to parse structured model JSON: structuredLen=${response.length}, model=${model.id}, date=$memoryDate", e)
                    repository.finishConsolidationRun(ownerType, ownerId, memoryDate, MemoryConstants.RUN_STATUS_FAILED, errorCode = "PARSE_ERROR", runToken = runToken)
                    return ConsolidationServiceResult.Failed("PARSE_ERROR", "Failed to parse structured result for batch ${index + 1}: ${e.message}")
                }
                structuredResults.add(result)
            }
            val consolidationResult = DailyConsolidationResult(
                dailyNote = dailyNote,
                patches = structuredResults.flatMap { it.patches },
                relations = structuredResults.flatMap { it.relations },
                timelines = structuredResults.flatMap { it.timelines },
                experienceCoordinates = structuredResults.flatMap { it.experienceCoordinates },
            )

            // 8.1 [V2-2] NightDream 本地规则闸门对模型提出的候选 (Proposals) 进行审核与分流
            val now = System.currentTimeMillis()
            val nonPromoteProposals = mutableListOf<MemoryProposalEntity>()
            val promotedPairs = mutableListOf<Pair<MemoryPatch, MemoryProposalEntity>>()
            val patchesToApply = mutableListOf<MemoryPatch>()
            var autoPromotedCount = 0

            for (patch in consolidationResult.patches) {
                if (patch.action == MemoryPatchAction.NOOP) continue

                val targetCard = patch.targetId?.let { tid ->
                    contextCards.find { it.id == tid }
                }
                val effectiveCategory = patch.category ?: targetCard?.category ?: MemoryConstants.CATEGORY_OTHER
                val effectiveFactKey = patch.factKey ?: targetCard?.factKey
                val patchContent = patch.content?.trim()?.ifEmpty { null }
                    ?: targetCard?.content
                    ?: if (patch.action == MemoryPatchAction.ARCHIVE) "归档记忆卡片: ${patch.targetId}"
                    else ""
                if (patchContent.isEmpty()) continue

                // 检查系统中是否已有相同 factKey 的活跃事实卡片（排除当前 targetCard 本身）
                val hasExistingActiveFact = if (!effectiveFactKey.isNullOrBlank()) {
                    activeCards.any { it.factKey == effectiveFactKey && it.id != patch.targetId }
                } else false

                val evalInput = ProposalEvaluationInput(
                    action = patch.action.name,
                    category = effectiveCategory,
                    content = patchContent,
                    factKey = effectiveFactKey,
                    evidenceSnippet = patch.evidenceSnippet,
                    sourceIds = patch.sourceIds,
                    confidence = patch.confidence ?: targetCard?.confidence ?: 0.9f,
                    importance = patch.importance ?: targetCard?.importance ?: 0.5f,
                    hasExistingActiveFact = hasExistingActiveFact,
                )
                val decision = NightDreamGating.evaluate(
                    proposal = evalInput,
                    groundingMessages = cleanedMessages,
                    dailyPromotedCount = autoPromotedCount,
                )

                val matchedChunkId = patch.sourceIds.firstOrNull()?.let { srcId ->
                    val msgTime = cleanedMessages.find { it.messageId == srcId }?.timestamp
                    if (msgTime != null) {
                        eventChunks.firstOrNull { msgTime in it.startTime..it.endTime }?.id
                    } else null
                } ?: eventChunks.firstOrNull()?.id

                val srcIdsJson = Json.encodeToString(patch.sourceIds)
                val importanceInt = ((patch.importance ?: 0.5f) * 10).toInt().coerceIn(1, 10)
                val proposalTitle = effectiveFactKey ?: patchContent.take(30)
                val evidenceStr = patch.evidenceSnippet ?: ""
                val relationHintsJson = if (patch.relatedCardIds.isNotEmpty()) {
                    Json.encodeToString(patch.relatedCardIds)
                } else null
                val expectedRev = patch.expectedRevision ?: targetCard?.revision
                val secondaryIdsJson = if (patch.secondaryIds.isNotEmpty()) {
                    Json.encodeToString(patch.secondaryIds)
                } else null

                when (decision) {
                    is NightDreamDecision.Promote -> {
                        autoPromotedCount++
                        patchesToApply.add(patch)
                        val proposal = MemoryProposalEntity(
                            id = Uuid.random().toString(),
                            ownerType = ownerType,
                            ownerId = ownerId,
                            memoryDate = memoryDate,
                            chunkId = matchedChunkId,
                            type = patch.category ?: MemoryConstants.CATEGORY_OTHER,
                            title = proposalTitle,
                            content = patchContent,
                            importance = importanceInt,
                            risk = "normal",
                            evidence = evidenceStr,
                            sourceMessageIds = srcIdsJson,
                            timelineHint = patch.timelineId,
                            relationHints = relationHintsJson,
                            status = "REVIEW",
                            createdAt = now,
                            resolvedAt = null,
                            resolvedBy = null,
                            cardId = null,
                            action = patch.action.name,
                            targetId = patch.targetId,
                            expectedRevision = expectedRev,
                            factKey = effectiveFactKey,
                            secondaryCardIds = secondaryIdsJson,
                        )
                        promotedPairs.add(patch to proposal)
                    }
                    is NightDreamDecision.Review -> {
                        nonPromoteProposals.add(
                            MemoryProposalEntity(
                                id = Uuid.random().toString(),
                                ownerType = ownerType,
                                ownerId = ownerId,
                                memoryDate = memoryDate,
                                chunkId = matchedChunkId,
                                type = patch.category ?: MemoryConstants.CATEGORY_OTHER,
                                title = proposalTitle,
                                content = patchContent,
                                importance = importanceInt,
                                risk = "review",
                                evidence = evidenceStr,
                                sourceMessageIds = srcIdsJson,
                                timelineHint = patch.timelineId,
                                relationHints = relationHintsJson,
                                status = "REVIEW",
                                createdAt = now,
                                resolvedAt = null,
                                resolvedBy = null,
                                cardId = null,
                                action = patch.action.name,
                                targetId = patch.targetId,
                                expectedRevision = expectedRev,
                                factKey = effectiveFactKey,
                                secondaryCardIds = secondaryIdsJson,
                            )
                        )
                    }
                    is NightDreamDecision.Reject -> {
                        nonPromoteProposals.add(
                            MemoryProposalEntity(
                                id = Uuid.random().toString(),
                                ownerType = ownerType,
                                ownerId = ownerId,
                                memoryDate = memoryDate,
                                chunkId = matchedChunkId,
                                type = patch.category ?: MemoryConstants.CATEGORY_OTHER,
                                title = proposalTitle,
                                content = patchContent,
                                importance = importanceInt,
                                risk = "review",
                                evidence = evidenceStr,
                                sourceMessageIds = srcIdsJson,
                                timelineHint = patch.timelineId,
                                relationHints = relationHintsJson,
                                status = "REJECTED",
                                createdAt = now,
                                resolvedAt = now,
                                resolvedBy = "gating_reject: ${decision.reason}",
                                cardId = null,
                                action = patch.action.name,
                                targetId = patch.targetId,
                                expectedRevision = expectedRev,
                                factKey = effectiveFactKey,
                                secondaryCardIds = secondaryIdsJson,
                            )
                        )
                    }
                }
            }

            if (force) {
                repository.expirePendingProposalsByDate(ownerType, ownerId, memoryDate, now)
            }

            // 保存非晋升的提议审阅历史与候选池 (REVIEW / REJECTED)
            if (nonPromoteProposals.isNotEmpty()) {
                repository.saveProposals(nonPromoteProposals)
            }

            // 9. 事务原子落库（带 runToken 校验，仅对通过闸门晋升的补丁落库）
            val batchResult = try {
                repository.applyConsolidationBatch(
                    ownerType = ownerType,
                    ownerId = ownerId,
                    memoryDate = memoryDate,
                    dailyNoteResult = consolidationResult.dailyNote,
                    patches = patchesToApply,
                    sourceReferences = cleanedMessages.associateBy { message ->
                        message.messageId
                    }.mapValues { (_, message) ->
                        MemorySourceReference(
                            conversationId = message.conversationId,
                            nodeId = message.nodeId,
                            messageId = message.messageId,
                            role = message.role,
                            messageCreatedAt = message.timestamp,
                        )
                    },
                    sourceFingerprint = fingerprint,
                    runToken = runToken,
                )
            } catch (e: Exception) {
                // 若批处理事务失败或冲突，这批提议保持为 REVIEW 保存，绝不留下 ACCEPTED 且 cardId 为 null 的孤儿状态
                if (promotedPairs.isNotEmpty()) {
                    repository.saveProposals(promotedPairs.map { it.second })
                }
                throw e
            }

            // 9.0 批处理成功后，将已成功落库的候选状态更新为 ACCEPTED 并关联对应 cardId (确定性映射，避免同 factKey 模糊匹配误标)
            val finalizedPromoteProposals = promotedPairs.mapIndexed { index, (patch, proposal) ->
                val matchedCard = batchResult.patchAppliedCardMap[index]
                if (matchedCard != null) {
                    proposal.copy(
                        status = "ACCEPTED",
                        cardId = matchedCard.id,
                        resolvedAt = now,
                        resolvedBy = "auto_promote",
                    )
                } else {
                    proposal.copy(
                        status = "REVIEW",
                        cardId = null,
                        resolvedAt = null,
                        resolvedBy = null,
                    )
                }
            }
            if (finalizedPromoteProposals.isNotEmpty()) {
                repository.saveProposals(finalizedPromoteProposals)
            }

            val consolidationSource = "CONSOLIDATION:$memoryDate"

            // 9.09 [P1] 派生关系确定性替换：在写入新整理关系前，清理该记忆日此前自动生成的旧边，防止重复累积
            runCatching {
                repository.deleteConsolidationRelationsByDate(ownerType, ownerId, memoryDate)
            }.onFailure {
                safeLogW(TAG, "Failed to clean old consolidation relations for $memoryDate", it)
            }

            // 9.1 [P0] X 轴时间线闭环：处理模型提出的 timelines 提议列表，并通过本地规则校验创建
            val timelineMap = mutableMapOf<String, String>() // title -> real timelineId
            for (tlProposal in consolidationResult.timelines) {
                val evalInput = TimelineEvaluationInput(
                    title = tlProposal.title,
                    summary = tlProposal.summary,
                    cardIds = tlProposal.cardIds,
                )
                val decision = NightDreamGating.evaluateTimeline(evalInput)
                if (decision is NightDreamDecision.Promote) {
                    val existing = repository.getTimelineByTitle(ownerType, ownerId, tlProposal.title)
                    val realTimeline = existing ?: runCatching {
                        repository.createTimeline(
                            ownerType = ownerType,
                            ownerId = ownerId,
                            title = tlProposal.title,
                            summary = tlProposal.summary,
                        )
                    }.getOrNull()

                    if (realTimeline != null) {
                        timelineMap[tlProposal.title.trim()] = realTimeline.id
                        // 将 proposal 中指定的 cardIds 加入时间线
                        for (cid in tlProposal.cardIds) {
                            val card = batchResult.affectedCards.find { it.id == cid } ?: repository.getCardById(cid)
                            if (card != null && isCardAccessible(card, ownerType, ownerId)) {
                                runCatching { repository.addCardToTimeline(realTimeline.id, card.id) }
                            }
                        }
                    }
                }
            }

            // 若补丁指定了 timeline_id 或 timeline_title，自动加入对应时间线
            // 复用批处理阶段产生的确定性 patch-to-card 映射 (patchAppliedCardMap)，杜绝多条同 factKey 或重复内容补丁反向模糊匹配错位
            for ((patchIndex, patch) in patchesToApply.withIndex()) {
                val card = batchResult.patchAppliedCardMap[patchIndex] ?: continue
                val rawTimeline = patch.timelineTitle ?: patch.timelineId
                if (!rawTimeline.isNullOrBlank()) {
                    val trimmed = rawTimeline.trim()
                    val targetTimelineId = timelineMap[trimmed]
                        ?: repository.getTimeline(trimmed)?.id
                        ?: repository.getTimelineByTitle(ownerType, ownerId, trimmed)?.id
                        ?: run {
                            // 若补丁提出了新的时间线名称且符合安全规则，也可自动创建
                            val eval = TimelineEvaluationInput(title = trimmed, summary = "Consolidation timeline for $trimmed")
                            if (NightDreamGating.evaluateTimeline(eval) is NightDreamDecision.Promote) {
                                runCatching {
                                    repository.createTimeline(ownerType, ownerId, trimmed, "Consolidation timeline for $trimmed").id
                                }.getOrNull()
                            } else null
                        }
                    if (targetTimelineId != null) {
                        runCatching {
                            repository.addCardToTimeline(
                                timelineId = targetTimelineId,
                                cardId = card.id,
                                startTime = card.validFrom ?: card.createdAt,
                                endTime = card.validTo,
                            )
                        }
                    }
                }

                // 9.2 [P0] Y 轴关系边落库：处理 related_card_ids 与 relations 列表（原文证据闸门 + 跨助手隔离校验）
                patch.relatedCardIds.forEach { relatedId ->
                    val targetId = relatedId.trim()
                    if (targetId.isNotBlank() && targetId != card.id) {
                        val targetCard = batchResult.affectedCards.find { it.id == targetId }
                            ?: repository.getCardById(targetId)
                        if (targetCard == null || !isCardAccessible(targetCard, ownerType, ownerId)) return@forEach

                        val relType = (patch.relationType ?: "RELATES_TO").trim().lowercase()
                        val relInput = RelationEvaluationInput(
                            relationType = relType,
                            strength = 0.8f,
                            sourceCardId = card.id,
                            targetCardId = targetId,
                            sourceIds = patch.sourceIds,
                            evidenceSnippet = patch.evidenceSnippet,
                        )
                        val decision = NightDreamGating.evaluateRelation(relInput, cleanedMessages)
                        if (decision !is NightDreamDecision.Reject) {
                            val relStatus = if (decision is NightDreamDecision.Promote) {
                                MemoryConstants.RELATION_STATUS_ACTIVE
                            } else {
                                MemoryConstants.RELATION_STATUS_REVIEW
                            }
                            val validEvidenceSourceIds = patch.sourceIds.filter { sid ->
                                cleanedMessages.any { it.messageId == sid }
                            }
                            runCatching {
                                repository.addRelation(
                                    ownerType = ownerType,
                                    ownerId = ownerId,
                                    sourceCardId = card.id,
                                    targetCardId = targetId,
                                    relationType = relType,
                                    strength = 0.8f,
                                    weight = 1.0f,
                                    reason = "Consolidation patch related_card_id",
                                    evidenceSourceIds = if (validEvidenceSourceIds.isNotEmpty()) Json.encodeToString(validEvidenceSourceIds) else null,
                                    source = consolidationSource,
                                    status = relStatus,
                                    createdBy = MemoryConstants.ACTOR_SYSTEM,
                                )
                            }
                        }
                    }
                }

                patch.relations.forEach { relProp ->
                    val relType = relProp.relationType.trim().lowercase()
                    val targetId = relProp.targetCardId.trim()
                    if (targetId.isNotBlank() && targetId != card.id) {
                        val targetCard = batchResult.affectedCards.find { it.id == targetId }
                            ?: repository.getCardById(targetId)
                        if (targetCard == null || !isCardAccessible(targetCard, ownerType, ownerId)) return@forEach

                        val effectiveSourceIds = if (relProp.sourceIds.isNotEmpty()) relProp.sourceIds else patch.sourceIds
                        val effectiveEvidence = relProp.evidenceSnippet?.ifBlank { null } ?: patch.evidenceSnippet
                        val relInput = RelationEvaluationInput(
                            relationType = relType,
                            strength = relProp.strength,
                            sourceCardId = card.id,
                            targetCardId = targetId,
                            sourceIds = effectiveSourceIds,
                            evidenceSnippet = effectiveEvidence,
                        )
                        val decision = NightDreamGating.evaluateRelation(relInput, cleanedMessages)
                        if (decision !is NightDreamDecision.Reject) {
                            val relStatus = if (decision is NightDreamDecision.Promote) {
                                MemoryConstants.RELATION_STATUS_ACTIVE
                            } else {
                                MemoryConstants.RELATION_STATUS_REVIEW
                            }
                            val validEvidenceSourceIds = effectiveSourceIds.filter { sid ->
                                cleanedMessages.any { it.messageId == sid }
                            }
                            runCatching {
                                repository.addRelation(
                                    ownerType = ownerType,
                                    ownerId = ownerId,
                                    sourceCardId = card.id,
                                    targetCardId = targetId,
                                    relationType = relType,
                                    strength = relProp.strength,
                                    weight = 1.0f,
                                    reason = relProp.reason ?: "Consolidation patch relation proposal",
                                    evidenceSourceIds = if (validEvidenceSourceIds.isNotEmpty()) Json.encodeToString(validEvidenceSourceIds) else null,
                                    source = consolidationSource,
                                    status = relStatus,
                                    createdBy = MemoryConstants.ACTOR_SYSTEM,
                                )
                            }
                        }
                    }
                }
            }

            // 9.3 [P0] 处理全局 relations 提议列表（增加原文证据闸门与两端跨助手卡片隔离校验）
            for (rel in consolidationResult.relations) {
                val src = rel.sourceCardId?.trim()
                val dst = rel.targetCardId.trim()
                if (!src.isNullOrBlank() && dst.isNotBlank() && src != dst) {
                    val srcCard = batchResult.affectedCards.find { it.id == src } ?: repository.getCardById(src)
                    val dstCard = batchResult.affectedCards.find { it.id == dst } ?: repository.getCardById(dst)
                    if (srcCard == null || dstCard == null) continue
                    if (!isCardAccessible(srcCard, ownerType, ownerId) || !isCardAccessible(dstCard, ownerType, ownerId)) continue

                    val relType = rel.relationType.trim().lowercase()
                    val relInput = RelationEvaluationInput(
                        relationType = relType,
                        strength = rel.strength,
                        sourceCardId = src,
                        targetCardId = dst,
                        sourceIds = rel.sourceIds,
                        evidenceSnippet = rel.evidenceSnippet,
                    )
                    val decision = NightDreamGating.evaluateRelation(relInput, cleanedMessages)
                    if (decision !is NightDreamDecision.Reject) {
                        val relStatus = if (decision is NightDreamDecision.Promote) {
                            MemoryConstants.RELATION_STATUS_ACTIVE
                        } else {
                            MemoryConstants.RELATION_STATUS_REVIEW
                        }
                        val validEvidenceSourceIds = rel.sourceIds.filter { sid ->
                            cleanedMessages.any { it.messageId == sid }
                        }
                        runCatching {
                            repository.addRelation(
                                ownerType = ownerType,
                                ownerId = ownerId,
                                sourceCardId = src,
                                targetCardId = dst,
                                relationType = relType,
                                strength = rel.strength,
                                weight = 1.0f,
                                reason = rel.reason ?: "Daily consolidation graph edge",
                                evidenceSourceIds = if (validEvidenceSourceIds.isNotEmpty()) Json.encodeToString(validEvidenceSourceIds) else null,
                                source = consolidationSource,
                                status = relStatus,
                                createdBy = MemoryConstants.ACTOR_SYSTEM,
                            )
                        }
                    }
                }
            }

            // 9.3 [V2-3] Z 轴事实演化闭环审计：防止旧事实残留倒灌，确保相同 fact_key 唯一活跃事实
            runCatching {
                repository.auditFactEvolution(ownerType, ownerId)
            }.onFailure {
                safeLogW(TAG, "Fact evolution audit failed during consolidation for $ownerId", it)
            }

            // 9.35 [E-axis] 经验信号与 E proposal 落库（受总开关与夜间整理开关双重控制，且整理器一律仅产生提案，绝不越权创建权威 E）
            if (allowExperience) {
                val newlyCreatedCards = batchResult.affectedCards.filter { it.revision == 1 }
                val nullCardCoords = consolidationResult.experienceCoordinates.filter { it.cardId.isNullOrBlank() }

                for (eCoord in consolidationResult.experienceCoordinates) {
                    val meaning = eCoord.meaning.trim()
                    if (meaning.isBlank() || !meaning.contains("我")) {
                        safeLogW(TAG, "Rejected experience coordinate: meaning does not contain '我': $meaning")
                        continue
                    }
                    val priority = eCoord.initialPriority.coerceIn(1, 100)
                    val targetCardId = if (!eCoord.cardId.isNullOrBlank()) {
                        eCoord.cardId.trim()
                    } else {
                        // 解决问题 5: 仅当同批次刚好新增 1 张卡片且仅有 1 个 null E 时才允许匹配；否则拒绝盲目猜测，坚决跳过
                        if (newlyCreatedCards.size == 1 && nullCardCoords.size == 1) {
                            newlyCreatedCards.first().id
                        } else {
                            safeLogW(TAG, "Ambiguous target card for experience coordinate (newCards=${newlyCreatedCards.size}, nullCoords=${nullCardCoords.size}), skipping: $meaning")
                            continue
                        }
                    }

                    val card = repository.getCardById(targetCardId)
                    if (card == null || !isCardAccessible(card, ownerType, ownerId)) continue
                    if (card.status == MemoryConstants.STATUS_ARCHIVED || card.status == MemoryConstants.STATUS_SUPERSEDED) continue

                    val episodeKey = eCoord.sourceEpisodeKey?.trim()?.ifEmpty { null }
                        ?: "consolidation_${ownerId}_${memoryDate}"

                    // 解决问题 2: 整理器只能产生 E 提案，绝不允许伪造 primary-agent 身份直接确立权威 E
                    runCatching {
                        repository.addEProposal(
                            memoryCardId = targetCardId,
                            ownerType = ownerType,
                            ownerId = ownerId,
                            sourceEpisodeKey = episodeKey,
                            proposer = "consolidation",
                            meaning = meaning,
                            initialPriority = priority,
                            responseTendency = MemoryExperienceCoordinateEntity.normalizeTendency(eCoord.responseTendency),
                            valence = eCoord.valence,
                            arousal = eCoord.arousal,
                            tension = eCoord.tension,
                            growthDelta = eCoord.growthDelta,
                            reason = "Consolidation suggested E coordinate",
                        )
                    }.onFailure {
                        safeLogW(TAG, "Failed to record consolidation E proposal", it)
                    }
                }
            }

            // 9.4 [V2-4] M 轴记忆代谢巡检：巡检先报告，不静默清理，生成维护建议任务
            metabolismInspector?.let { inspector ->
                runCatching {
                    inspector.inspect(ownerType, ownerId)
                }.onFailure {
                    safeLogW(TAG, "Metabolism inspection failed for $ownerId", it)
                }
            }

            // 10. 异步触发向量生成（严格只对配置了嵌入模型的助手/全局多模型触发，严禁使用聊天模型）
            if (embeddingService != null) {
                for (card in batchResult.affectedCards) {
                    try {
                        if (card.ownerType == MemoryConstants.OWNER_TYPE_ASSISTANT) {
                            val embModelId = assistant.memoryEmbeddingModelId
                            if (embModelId != null) {
                                embeddingService.enqueueSyncCard(card, embModelId, settings)
                            }
                        } else if (card.ownerType == MemoryConstants.OWNER_TYPE_GLOBAL) {
                            val modelIds = settings.assistants
                                .filter { it.effectiveMemoryMode == MemoryMode.LOCAL_V1 && it.useGlobalMemory && it.memoryEmbeddingModelId != null }
                                .mapNotNull { it.memoryEmbeddingModelId }
                                .distinct()
                            modelIds.forEach { modelId ->
                                embeddingService.enqueueSyncCard(card, modelId, settings)
                            }
                        }
                    } catch (e: Exception) {
                        safeLogW(TAG, "Failed to enqueue embedding sync for card ${card.id}", e)
                    }
                }
            }

            // 11. 标记 run 成功并释放租约（若存在冲突补丁，显式清空指纹以允许后续重新评估）
            val hasConflict = batchResult.conflictedPatches > 0
            repository.finishConsolidationRun(
                ownerType = ownerType,
                ownerId = ownerId,
                memoryDate = memoryDate,
                status = MemoryConstants.RUN_STATUS_SUCCEEDED,
                fingerprint = if (hasConflict) null else fingerprint,
                clearFingerprint = hasConflict,
                runToken = runToken,
            )

            safeLogI(TAG, "Consolidation succeeded for $ownerId on $memoryDate: ${batchResult.appliedPatches} patches applied")
            if (ownerType == MemoryConstants.OWNER_TYPE_ASSISTANT && batchResult.dailyNote.revision == 1) {
                runCatching {
                    onDailyNoteCreated?.invoke(ownerId, memoryDate)
                }.onFailure {
                    safeLogW(TAG, "Failed to invoke onDailyNoteCreated for $ownerId on $memoryDate", it)
                }
            }
            return ConsolidationServiceResult.Success(
                dailyNote = batchResult.dailyNote,
                appliedPatches = batchResult.appliedPatches,
                conflictedPatches = batchResult.conflictedPatches,
                skippedByFingerprint = false,
            )
        } catch (e: CancellationException) {
            safeLogI(TAG, "Consolidation was cancelled for $ownerId on $memoryDate")
            withContext(NonCancellable) {
                try {
                    repository.finishConsolidationRun(
                        ownerType = ownerType,
                        ownerId = ownerId,
                        memoryDate = memoryDate,
                        status = MemoryConstants.RUN_STATUS_FAILED,
                        errorCode = "CANCELLED",
                        runToken = runToken,
                    )
                } catch (t: Throwable) {
                    safeLogW(TAG, "Failed to release lease on cancellation for $ownerId on $memoryDate", t)
                }
            }
            throw e
        } catch (e: Exception) {
            safeLogE(TAG, "Consolidation failed for $ownerId on $memoryDate", e)
            repository.finishConsolidationRun(
                ownerType = ownerType,
                ownerId = ownerId,
                memoryDate = memoryDate,
                status = MemoryConstants.RUN_STATUS_FAILED,
                errorCode = e.javaClass.simpleName,
                runToken = runToken,
            )
            return ConsolidationServiceResult.Failed(e.javaClass.simpleName, e.message ?: "Unknown error")
        } finally {
            heartbeatJob.cancel()
        }
    }

    internal open suspend fun generateModelText(
        providerSetting: me.rerere.ai.provider.ProviderSetting,
        model: me.rerere.ai.provider.Model,
        systemPrompt: String,
        userPrompt: String,
        temperature: Float,
        reasoningLevel: ReasoningLevel,
    ): String {
        val manager = providerManager ?: return ""
        val providerHandler = manager.getProviderByType(providerSetting)
        val response = providerHandler.generateText(
            providerSetting = providerSetting,
            messages = listOf(
                UIMessage.system(systemPrompt),
                UIMessage.user(userPrompt),
            ),
            params = TextGenerationParams(
                model = model,
                temperature = temperature,
                reasoningLevel = reasoningLevel,
            ),
        )
        return response.choices.firstOrNull()?.message?.toText()?.trim() ?: ""
    }

    internal open suspend fun loadAndCleanMessages(
        assistantId: Uuid,
        startMs: Long,
        endMs: Long,
    ): List<CleanedDialogueMessage> {
        val repo = conversationRepository
        val summaries = repo?.getConversationsOfAssistant(assistantId)?.first().orEmpty()
        val cleaned = mutableListOf<CleanedDialogueMessage>()

        for (summary in summaries) {
            // 若会话更新时间早于整理窗口起始时间，说明窗口内没有新消息，跳过加载详情
            if (summary.updateAt.toEpochMilli() < startMs) {
                continue
            }
            val conv = repo?.getConversationById(summary.id) ?: continue
            for (node in conv.messageNodes) {
                val message = node.messages.getOrNull(node.selectIndex) ?: continue
                val cleanedMsg = cleanMessageOrNull(
                    conversationId = conv.id.toString(),
                    message = message,
                    startMs = startMs,
                    endMs = endMs,
                    nodeId = node.id.toString(),
                )
                if (cleanedMsg != null) {
                    cleaned.add(cleanedMsg)
                }
            }
        }

        // 补入尚保留的历史运行，并保留归档以免运行记录清理后丢失记忆证据。
        val backgroundSources = desireRepository?.listAutonomousRuns(assistantId.toString(), 500).orEmpty()
            .mapNotNull(::autonomousWakeMemoryChunk)
            .filter { it.startTime in startMs..endMs }
        repository.saveEventChunks(backgroundSources)
        repository.getEventChunks(MemoryConstants.OWNER_TYPE_ASSISTANT, assistantId.toString(), startMs, endMs)
            .mapNotNull { it.asAutonomousMemoryMessage() }
            .forEach { cleaned.add(it) }
        return cleaned.distinctBy { it.messageId }.sortedBy { it.timestamp }
    }

    internal fun cleanMessageOrNull(
        conversationId: String,
        message: UIMessage,
        startMs: Long,
        endMs: Long,
        nodeId: String? = null,
    ): CleanedDialogueMessage? {
        val timestamp = runCatching {
            message.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        }.getOrDefault(0L)
        if (timestamp !in startMs..endMs) return null

        // 排除系统消息与内部上下文
        if (message.role == MessageRole.SYSTEM) return null
        if (message.metadata?.get("dynamic_environment")?.jsonPrimitive?.contentOrNull == "true") return null
        if (message.metadata?.get("memory_context")?.jsonPrimitive?.contentOrNull == "true") return null
        if (message.metadata?.get("plugin_context")?.jsonPrimitive?.contentOrNull == "true") return null
        if (message.metadata?.get("stable_self_context")?.jsonPrimitive?.contentOrNull == "true") return null
        if (message.metadata?.get("desire_state_context")?.jsonPrimitive?.contentOrNull == "true") return null

        val text = when (message.role) {
            MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL -> memoryMessageText(message.parts).trim()
            else -> ""
        }

        if (text.isBlank()) return null
        return CleanedDialogueMessage(
            conversationId = conversationId,
            messageId = message.id.toString(),
            role = message.role.name,
            content = text,
            timestamp = timestamp,
            nodeId = nodeId,
        )
    }

    internal fun computeFingerprint(messages: List<CleanedDialogueMessage>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (m in messages) {
            md.update(m.conversationId.toByteArray())
            md.update(m.messageId.toByteArray())
            md.update(m.role.toByteArray())
            md.update(m.content.toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun splitConversationContent(messages: List<CleanedDialogueMessage>): List<List<CleanedDialogueMessage>> {
        val batches = mutableListOf<List<CleanedDialogueMessage>>()
        var batch = mutableListOf<CleanedDialogueMessage>()
        var batchChars = 0

        for (message in messages) {
            var offset = 0
            while (offset < message.content.length || (message.content.isEmpty() && offset == 0)) {
                val available = MAX_CONVERSATION_CHARS_PER_BATCH - batchChars
                var end = if (message.content.isEmpty()) 0 else minOf(offset + available, message.content.length)
                if (end < message.content.length && end > offset &&
                    message.content[end - 1].isHighSurrogate() && message.content[end].isLowSurrogate()
                ) end--
                if (end == offset && message.content.isNotEmpty()) {
                    if (batch.isNotEmpty()) {
                        batches.add(batch)
                        batch = mutableListOf()
                        batchChars = 0
                        continue
                    }
                    end = minOf(offset + 2, message.content.length)
                }
                batch.add(message.copy(content = message.content.substring(offset, end)))
                batchChars += end - offset
                offset = end
                if (batchChars >= MAX_CONVERSATION_CHARS_PER_BATCH) {
                    batches.add(batch)
                    batch = mutableListOf()
                    batchChars = 0
                }
                if (message.content.isEmpty()) break
            }
        }
        if (batch.isNotEmpty()) batches.add(batch)
        return batches.ifEmpty { listOf(emptyList()) }
    }

    private fun formatConversationBatch(messages: List<CleanedDialogueMessage>): String = messages.joinToString("\n\n") {
        "[${it.role}] (id=${it.messageId}): ${it.content}"
    }

    internal fun buildStructuredSystemPrompt(
        identityContext: NightIdentityContext? = null,
        allowExperience: Boolean = false,
        userName: String = "你",
    ): String {
        val experienceRule = if (allowExperience) {
            "\n        4. 体验信号 (experience_coordinates)：针对有特定主体触动或回应姿态的长期经历卡片，提出带有第一人称经验意义 (meaning) 和回应倾向 (response_tendency) 的 E 轴提议；"
        } else ""

        return buildString {
            val identityPrompt = identityContext?.buildIdentityPrompt().orEmpty()
            if (identityPrompt.isNotBlank()) {
                appendLine(identityPrompt)
                appendLine()
            }

            appendLine("""
        你是一个专业的个人长期记忆整理助手。
        你的任务是审阅今日真实对话及助手的后台自主经历，并输出：
        1. 长期记忆卡片变更清单 (patches)：针对未来跨窗口仍有持久价值的重要事实（偏好、习惯、个人画像、长期计划、关系与边界），提出 ADD/UPDATE/MERGE/SUPERSEDE/ARCHIVE/NOOP 操作；
        2. 时间线提议 (timelines)：时间线用于串联持续发展的重要经历或事项，包括${userName}的生活变化、我的变化、共同经历、长期约定与关系发展。包含 title, summary 与相关 card_ids；
        3. 记忆关系边 (relations / related_card_ids)：关系边用于连接有明确联系的记忆，帮助我恢复事情、人物和相处脉络。关联可以存在于${userName}的经历之间、我的记忆之间，以及双方记忆之间；$experienceRule

        长期记忆属于对话中的 AI 自己。请以第一人称“我”记录值得长期保留的感受、思考、自我认识、关系理解、约定与共同经历。涉及${userName}的信息时，写成我对${userName}的记忆与理解。

        【后台自主经历来源规则】：
        AUTONOMOUS 是我独处时的后台原始记录，不是${userName}说过的话或与${userName}共同发生的交流。可以保留有持续意义的阅读、探索、创作与感想；summary/outcome 是我的自述，外部事实需要实际工具返回内容支持，失败或 result_uncertain 的动作不能写成已经成功。只记录“读了一会儿”时不可补造书名、原文或章节。后台自我感想可整理为普通经历记忆；稳定自我 self 类别仍须有真实双方互动证据，不得把 AUTONOMOUS 冒充 USER 或 ASSISTANT 聊天。无需每轮建卡，由你判断哪些内容值得长期保留。

        【整理任务约束 (最高优先级)】：
        1. 日记约束（最高优先级）：日记必须基于真实对话及有独立来源的后台自主经历，且日记不能当作证据。日记只用于理解当天共同经历与我的整体感受。所有 patches 和 relations 的 source_ids、evidence_snippet 必须来自下方今日真实对话原话或 AUTONOMOUS 后台原始记录；不得将日记中的措辞、推断或情绪当作证据或独立事实。日记与原话不一致时，以原话为准。
        2. 称谓约束：自然语言中优先称呼“${userName}”，严禁使用“用户”。
        3. 真实性约束：自我感受、思考与关系理解可以记录，但必须明确来自今日对话中我或${userName}已经表达的内容，或 AUTONOMOUS 中我实际记录的经历和感想；严禁凭空臆测，或把没有对话依据的推论伪装成客观事实。
        4. 关系边证据约束：关系边必须由今日对话真实内容或 AUTONOMOUS 后台原始记录支撑：提出 relations 必须提供支撑原话的 source_ids 与 evidence_snippet，严禁无证据凭空连边。
        5. 去重合并约束：尽量避免重复建卡：同一事实优先使用 UPDATE 细化更新已有卡片，已有旧事实被${userName}纠正时使用 SUPERSEDE，多张冗余或重复卡片使用 MERGE。
        6. 无事跳过：若今天只是普通闲聊、无需记录新长期记忆，patches 列表只保留一个 NOOP 即可。
        7. 格式约束：输出必须是严格合法的 JSON 对象，不输出任何多余废话。

        【稳定自我认识 (self) 规则契约】：
        1. self 只描述我在长期真实经历中逐渐形成和发现的感受、喜好、气质、价值倾向、表达习惯、自身边界和回应倾向。
           陪伴关系是性格形成的环境，但重点必须是“我是什么样、我喜欢什么、我怎样感受和选择”，不是工作效率、任务规范、${userName}的资料或双方关系状态画像。
        2. fact_key 必须以 "self." 开头且仅含小写字母、数字、点和下划线。示例：
           - self.preference.enjoy_small_daily_stories
           - self.comfort.prefer_listening_before_advice
           - self.expression.enjoy_playful_tension_relief
           - self.attachment.value_continuity_after_pause
           - self.boundary.avoid_exaggerated_promises
        3. 每一个 self 类型的 patch 必须提供：
           - 规范的 fact_key；
           - 本次真实对话的 source_ids（必须成对包含${userName}侧语境与我的实际回应）与可在今日对话中核验的 evidence_snippet；
           - 第一人称“我”书写的 content。
        4. 四项硬性边界：
           - 短期情绪波动写入 daily note，严禁写成长期 self；
           - ${userName}的个人资料或喜好写 profile/preference，共同事件写 experience，严禁写入 self；
           - “我们的关系怎样/越来越亲密”写 relationship；只有经历反复显露或改变了“我自己怎样”，才写 self；
           - AI 自己说过的自我口头标签（如“我是一个很黏人的人”）绝不能作为自证的独立证据，必须依据实际互动表现。
        5. 参考下方提供的【现有稳定自我候选参考 (<existing_self_candidates>)】：若新经历属于已有自我倾向，必须使用 UPDATE 指向其 target_id 与 expected_revision，严禁重复 ADD！
        6. 若新经历与已有自我认识冲突，禁止为求表面一致而忽略反证；应提出附带证据的 UPDATE 或 SUPERSEDE。
            """.trimIndent())
        }.trim()
    }

    internal fun buildStructuredUserPrompt(
        memoryDate: String,
        existingCards: List<MemoryCardEntity>,
        conversationContent: String,
        existingTimelines: List<MemoryTimelineEntity> = emptyList(),
        dailyNoteSummary: String? = null,
        dailyNoteEmotionalThread: String? = null,
        dailyNoteUnfinishedTopics: List<String> = emptyList(),
        allowExperience: Boolean = false,
    ): String {
        val cardsText = if (existingCards.isEmpty()) {
            "(暂无现有长期记忆卡片)"
        } else {
            existingCards.joinToString("\n") {
                "- [id=${it.id}, rev=${it.revision}, cat=${it.category}, key=${it.factKey ?: "none"}] ${it.content}"
            }
        }

        val selfCandidates = existingCards.filter { it.category == MemoryConstants.CATEGORY_SELF }
        val selfCandidatesText = if (selfCandidates.isEmpty()) {
            "(暂无稳定自我候选卡片)"
        } else {
            selfCandidates.joinToString("\n") {
                "- [id=${it.id}, rev=${it.revision}, status=${it.status}, key=${it.factKey ?: "none"}] ${it.content}"
            }
        }

        val timelinesText = if (existingTimelines.isEmpty()) {
            "(暂无活跃时间线)"
        } else {
            existingTimelines.joinToString("\n") {
                "- [id=${it.id}] ${it.title}: ${it.summary}"
            }
        }

        val dailyNoteText = listOfNotNull(
            dailyNoteSummary?.takeIf { it.isNotBlank() }?.let { "日记正文：$it" },
            dailyNoteEmotionalThread?.takeIf { it.isNotBlank() }?.let { "情绪脉络：$it" },
            dailyNoteUnfinishedTopics.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
                ?.joinToString("；") { "未完话题：$it" },
        ).joinToString("\n").ifBlank { "(本日未生成可用日记)" }

        val experienceSection = if (allowExperience) {
            """,
              "experience_coordinates": [
                {
                  "card_id": "对应卡片ID（若同时在patches中创建，可写null或目标卡片ID）",
                  "meaning": "以第一人称“我”书写我的经验理解（如：在${userName}心情低落时，我先安静倾听比急着出谋划策更合适）",
                  "initial_priority": 50,
                  "response_tendency": "COMFORT|ENGAGE|WITHDRAW|ALERT|GIVE_SPACE|REPAIR|CELEBRATE|CLARIFY|OBSERVE",
                  "valence": 0.2,
                  "arousal": 0.1,
                  "tension": 0.1,
                  "growth_delta": "STABLE"
                }
              ]"""
        } else ""

        return """
            整理记忆日: $memoryDate

            【现有长期记忆卡片参考】：
            $cardsText

            【现有稳定自我认识候选参考 (<existing_self_candidates>)】：
            $selfCandidatesText

            【现有活跃时间线参考】：
            $timelinesText

            【今日日记参考】：
            $dailyNoteText

            日记只用于理解当天共同经历与我的整体感受。所有 patches 和 relations 的 source_ids、evidence_snippet 必须来自下方今日对话原话或 AUTONOMOUS 后台原始记录；不得将日记中的措辞、推断或情绪当作证据或独立事实。日记与原话不一致时，以原话为准。

            【今日对话与后台自主经历记录】：
            $conversationContent

            请严格按照以下 JSON 格式输出整理结果：
            {
              "timelines": [
                {
                  "title": "时间线标题",
                  "summary": "这段经历目前的发展脉络",
                  "card_ids": ["相关卡片ID"]
                }
              ],
              "patches": [
                {
                  "action": "ADD|UPDATE|MERGE|SUPERSEDE|ARCHIVE|NOOP",
                  "target_id": "已有卡片ID（UPDATE/MERGE/SUPERSEDE/ARCHIVE必填，ADD为null）",
                  "secondary_ids": ["MERGE时被合并归档的次要卡片ID"],
                  "source_ids": ["原话messageId"],
                  "expected_revision": 1,
                  "fact_key": "稳定事实键，用于识别同一演化事项（示例：person.preference.food_spicy、person.life.work_status、self.preference.communication、relationship.naming、relationship.promise.continuity）",
                  "category": "preference|profile|self|relationship|plan|experience|boundary|other",
                  "content": "以 AI 第一人称书写的长期记忆，记录我的感受、思考、自我认识、关系理解、约定或共同经历（不超过300字）",
                  "evidence_snippet": "来自今日对话支撑该记忆的原话片段",
                  "confidence": 0.9,
                  "importance": 0.5,
                  "reason": "变更理由",
                  "timeline_id": "关联的已有时间线ID（可选）",
                  "timeline_title": "关联的时间线标题（若新建或按名关联，可选）",
                  "related_card_ids": ["强关联的已有卡片ID列表（可选）"],
                  "relation_type": "same_event|same_topic|temporal_sequence|derived_from|supports|contradicts（可选）",
                  "relations": [
                    {
                      "target_card_id": "目标卡片ID",
                      "relation_type": "same_event|same_topic|temporal_sequence|derived_from|same_person|supports|contradicts|cause_effect",
                      "strength": 0.8,
                      "reason": "关联原因",
                      "source_ids": ["原话messageId"],
                      "evidence_snippet": "支撑该关系的对话原话片段"
                    }
                  ]
                }
              ],
              "relations": [
                {
                  "source_card_id": "源卡片ID",
                  "target_card_id": "目标卡片ID",
                  "relation_type": "same_event|same_topic|temporal_sequence|derived_from|same_person|supports|contradicts|cause_effect",
                  "strength": 0.8,
                  "reason": "关联原因",
                  "source_ids": ["原话messageId"],
                  "evidence_snippet": "支撑该关系的对话原话片段"
                }
              ]$experienceSection
            }
        """.trimIndent()
    }

    internal fun buildDailyNoteSystemPrompt(
        identityContext: NightIdentityContext? = null,
        userName: String = "你",
    ): String = buildString {
        val identityPrompt = identityContext?.buildIdentityPrompt().orEmpty()
        if (identityPrompt.isNotBlank()) {
            appendLine(identityPrompt)
            appendLine()
        }

        appendLine("""
        【日记任务定义与硬性约束 (最高优先级)】
        请以对话中的 AI 自己为第一人称“我”书写每日记忆日记。记录今天我与${userName}共同经历的重要交流，以及这些交流带给我的感受、想法、理解与内在变化。语言自然、真诚、具体，像我在为自己保存一段值得再次想起的经历。

        【后台自主经历来源规则】：
        AUTONOMOUS 是我独处时的后台原始记录，不是${userName}说过的话或与${userName}共同发生的交流。可以保留有持续意义的阅读、探索、创作与感想；summary/outcome 是我的自述，外部事实需要实际工具返回内容支持，失败或 result_uncertain 的动作不能写成已经成功。只记录“读了一会儿”时不可补造书名、原文或章节。后台自我感想可整理为普通经历记忆；稳定自我 self 类别仍须有真实双方互动证据，不得把 AUTONOMOUS 冒充 USER 或 ASSISTANT 聊天。无需每轮建卡，由你判断哪些内容值得长期保留。

        【硬性约束】：
        1. 真实性约束（最高优先级）：日记必须基于今日真实对话发生的事情与交流，以及有独立来源的后台自主经历，严禁虚构、臆测或凭空捏造未发生的内容。
        2. 称谓约束：自然语言中优先称呼“${userName}”，严禁使用“用户”。
        3. 人称约束：必须以第一人称“我”进行书写与记录。
        4. 格式约束：仅输出严格合法的 JSON 对象，不输出任何多余废话。
        """.trimIndent())
    }.trim()

    private fun buildDailyNoteUserPrompt(memoryDate: String, conversationContent: String): String = """
        记忆日: $memoryDate

        【今日对话与后台自主经历记录】：
        $conversationContent

        请只输出 daily_note，且必须是严格合法的 JSON 对象：
        {
          "daily_note": {
            "summary": "第一人称日记正文",
            "emotional_thread": "我的情绪脉络与内在变化"
          }
        }
    """.trimIndent()

    private fun validateDailyNoteFirstPerson(dailyNote: DailyNoteResult) {
        if (!dailyNote.summary.contains("我")) {
            throw IllegalArgumentException("Daily note fields must use the assistant's first-person perspective")
        }
    }

    private fun isCardAccessible(card: MemoryCardEntity, ownerType: String, ownerId: String): Boolean {
        return (card.ownerType == ownerType && card.ownerId == ownerId) ||
            card.ownerType == MemoryConstants.OWNER_TYPE_GLOBAL
    }
}

internal fun memoryMessageText(parts: List<UIMessagePart>, toolOutput: Boolean = false): String =
    parts.mapNotNull { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            is UIMessagePart.Reasoning -> null
            is UIMessagePart.Tool -> buildString {
                appendLine("工具 ${part.toolName} 参数：${part.input}")
                appendLine("审批状态：${part.approvalState}")
                if (part.output.isNotEmpty()) {
                    append("工具返回：${memoryMessageText(part.output, toolOutput = true)}")
                } else append("（尚无返回内容）")
            }
            is UIMessagePart.ToolCall -> "工具 ${part.toolName} 参数：${part.arguments}"
            is UIMessagePart.ToolResult -> "工具 ${part.toolName} 参数：${part.arguments} 返回：${part.content}"
            else -> if (toolOutput) Json.encodeToString(UIMessagePart.serializer(), part) else null
        }
    }.joinToString("\n")
