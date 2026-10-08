/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.memory.v1.ConsolidationServiceResult
import me.rerere.rikkahub.data.memory.v1.MemoryConsolidationService
import me.rerere.rikkahub.data.memory.v1.MemoryConstants
import me.rerere.rikkahub.data.memory.v1.MemoryDateUtils
import me.rerere.rikkahub.data.memory.v1.MemoryEmbeddingService
import me.rerere.rikkahub.data.memory.v1.MemoryRepositoryV1
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryDailyNoteEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEProposalEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryFactAuditEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryMaintenanceTaskEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryProposalEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryRelationEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryRevisionEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryTimelineEntity
import me.rerere.rikkahub.data.model.MemoryMode

@OptIn(ExperimentalCoroutinesApi::class)
class LocalMemoryManagerVM(
    val ownerType: String,
    val ownerId: String,
    private val repository: MemoryRepositoryV1,
    private val settingsStore: SettingsStore? = null,
    private val embeddingService: MemoryEmbeddingService? = null,
    private val consolidationService: MemoryConsolidationService? = null,
    private val currentSettingsProvider: (() -> Settings?)? = null,
    coroutineScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope = coroutineScope ?: viewModelScope

    val searchQuery = MutableStateFlow("")
    val selectedStatus = MutableStateFlow<String?>(null)

    private val _revisions = MutableStateFlow<List<MemoryRevisionEntity>>(emptyList())
    val revisions: StateFlow<List<MemoryRevisionEntity>> = _revisions.asStateFlow()

    private val _activeCardForHistory = MutableStateFlow<MemoryCardEntity?>(null)
    val activeCardForHistory: StateFlow<MemoryCardEntity?> = _activeCardForHistory.asStateFlow()

    val dailyNotes: StateFlow<List<MemoryDailyNoteEntity>> = repository.getDailyNotesFlow(ownerType, ownerId)
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val pendingProposals: StateFlow<List<MemoryProposalEntity>> = repository.getPendingProposalsFlow(ownerType, ownerId)
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val pendingFactAudits: StateFlow<List<MemoryFactAuditEntity>> = repository.getPendingFactAuditsFlow(ownerType, ownerId)
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val pendingMaintenanceTasks: StateFlow<List<MemoryMaintenanceTaskEntity>> = repository.getPendingMaintenanceTasksFlow(ownerType, ownerId)
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val pendingRelations: StateFlow<List<MemoryRelationEntity>> = repository.getPendingRelationsFlow(ownerType, ownerId)
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val pendingEProposals: StateFlow<List<MemoryEProposalEntity>> = repository.getPendingEProposalsFlow(ownerType, ownerId)
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val pendingProposalCards: StateFlow<Map<String, MemoryCardEntity>> = pendingProposals
        .mapLatest { proposals ->
            val ids = proposals.mapNotNull { it.targetId }.filter { it.isNotBlank() }.distinct()
            ids
        }
        .flatMapLatest { ids ->
            repository.getCardsByIdsFlow(ids).mapLatest { cards -> cards.associateBy { it.id } }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    val pendingRelationCards: StateFlow<Map<String, MemoryCardEntity>> = pendingRelations
        .mapLatest { rels ->
            val ids = rels.flatMap { listOf(it.sourceCardId, it.targetCardId) }.distinct()
            if (ids.isEmpty()) {
                emptyMap()
            } else {
                repository.getCardsByIds(ids).associateBy { it.id }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, emptyMap())

    val pendingFactAuditCards: StateFlow<Map<String, MemoryCardEntity>> = pendingFactAudits
        .mapLatest { audits ->
            val ids = audits.flatMap { listOfNotNull(it.existingCardId, it.candidateCardId) }.distinct()
            ids
        }
        .flatMapLatest { ids ->
            repository.getCardsByIdsFlow(ids).mapLatest { cards -> cards.associateBy { it.id } }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, emptyMap())

    val maintenanceTargets: StateFlow<Map<String, String>> = pendingMaintenanceTasks
        .mapLatest { tasks ->
            tasks.associate { task ->
                val description = when (task.targetType) {
                    "CARD", "EXPERIENCE" -> repository.getCardById(task.targetId)?.content
                    "PROPOSAL" -> repository.getPendingProposals(ownerType, ownerId)
                        .firstOrNull { it.id == task.targetId }?.let { "${it.title}\n${it.content}" }
                    "TIMELINE" -> repository.getTimeline(task.targetId)?.let { "${it.title}\n${it.summary}" }
                    "FACT_KEY" -> "事实键：${task.targetId}"
                    else -> null
                }
                task.id to (description ?: "目标已移除或未找到（${task.targetType}：${task.targetId}）")
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, emptyMap())

    private val _isConsolidating = MutableStateFlow(false)
    val isConsolidating: StateFlow<Boolean> = _isConsolidating.asStateFlow()

    private val _consolidationMessage = MutableStateFlow<String?>(null)
    val consolidationMessage: StateFlow<String?> = _consolidationMessage.asStateFlow()

    val cards: StateFlow<List<MemoryCardEntity>> = combine(
        searchQuery,
        selectedStatus,
    ) { query, status ->
        Pair(query.trim(), status)
    }.flatMapLatest { (query, status) ->
        if (query.isEmpty()) {
            repository.getCardsFlow(ownerType, ownerId, status)
        } else {
            val statusList = if (status != null) listOf(status) else null
            flow {
                emit(repository.search(query, ownerType, ownerId, statusList))
            }
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val cardExperiences: StateFlow<Map<String, MemoryExperienceCoordinateEntity>> = cards
        .mapLatest { cardList ->
            if (cardList.isEmpty()) {
                emptyMap()
            } else {
                repository.getCurrentExperiencesForCards(cardList.map { it.id }).associateBy { it.memoryCardId }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Lazily, emptyMap())

    private fun triggerSyncEmbedding(card: MemoryCardEntity) {
        val service = embeddingService ?: return
        val settings = currentSettingsProvider?.invoke() ?: settingsStore?.settingsFlow?.value ?: return
        if (ownerType == MemoryConstants.OWNER_TYPE_ASSISTANT) {
            val assistant = settings.assistants.firstOrNull { it.id.toString() == ownerId }
            val modelId = assistant?.memoryEmbeddingModelId ?: return
            service.enqueueSyncCard(card, modelId, settings)
        } else if (ownerType == MemoryConstants.OWNER_TYPE_GLOBAL) {
            val modelIds = settings.assistants
                .filter { it.effectiveMemoryMode == MemoryMode.LOCAL_V1 && it.useGlobalMemory && it.memoryEmbeddingModelId != null }
                .mapNotNull { it.memoryEmbeddingModelId }
                .distinct()
            modelIds.forEach { modelId ->
                service.enqueueSyncCard(card, modelId, settings)
            }
        }
    }

    fun createCard(
        content: String,
        category: String,
        factKey: String?,
        alwaysInject: Boolean = false,
    ) {
        scope.launch(Dispatchers.IO) {
            val card = repository.createCard(
                ownerType = ownerType,
                ownerId = ownerId,
                content = content,
                category = category,
                factKey = factKey,
                alwaysInject = alwaysInject,
                actor = MemoryConstants.ACTOR_MANUAL,
                reason = "手动创建",
            )
            triggerSyncEmbedding(card)
        }
    }

    fun updateCard(
        id: String,
        expectedRevision: Int,
        content: String,
        category: String,
        factKey: String?,
        alwaysInject: Boolean? = null,
    ) {
        scope.launch(Dispatchers.IO) {
            val updated = repository.updateCard(
                id = id,
                expectedRevision = expectedRevision,
                content = content,
                category = category,
                factKey = factKey,
                actor = MemoryConstants.ACTOR_MANUAL,
                reason = "手动编辑",
            )
            val finalCard = if (alwaysInject != null && updated.category == MemoryConstants.CATEGORY_SELF && updated.status == MemoryConstants.STATUS_ACTIVE && updated.alwaysInject != alwaysInject) {
                repository.setSelfAlwaysInject(id, alwaysInject)
            } else {
                updated
            }
            triggerSyncEmbedding(finalCard)
        }
    }

    fun setSelfAlwaysInject(cardId: String, enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                repository.setSelfAlwaysInject(cardId, enabled)
            }
        }
    }

    fun approveReviewCard(cardId: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val approved = repository.approveReviewCard(cardId, actor = MemoryConstants.ACTOR_USER)
                triggerSyncEmbedding(approved)
            }
        }
    }

    fun archiveCard(id: String, expectedRevision: Int) {
        scope.launch(Dispatchers.IO) {
            repository.archiveCard(
                id = id,
                expectedRevision = expectedRevision,
                actor = MemoryConstants.ACTOR_MANUAL,
                reason = "手动归档",
            )
            embeddingService?.cancelSyncJob(id)
        }
    }

    fun restoreCard(id: String, expectedRevision: Int) {
        scope.launch(Dispatchers.IO) {
            val restored = repository.restoreCard(
                id = id,
                expectedRevision = expectedRevision,
                actor = MemoryConstants.ACTOR_MANUAL,
                reason = "手动恢复",
            )
            triggerSyncEmbedding(restored)
        }
    }

    fun deletePermanently(id: String) {
        scope.launch(Dispatchers.IO) {
            repository.deletePermanently(id)
            embeddingService?.cancelSyncJob(id)
        }
    }

    fun showHistory(card: MemoryCardEntity) {
        _activeCardForHistory.value = card
        scope.launch(Dispatchers.IO) {
            _revisions.value = repository.getRevisions(card.id)
        }
    }

    fun dismissHistory() {
        _activeCardForHistory.value = null
        _revisions.value = emptyList()
    }

    fun consolidateDate(
        date: String = MemoryDateUtils.getYesterdayMemoryDate(),
        force: Boolean = true,
        onComplete: ((Boolean, String) -> Unit)? = null,
    ) {
        if (ownerType != MemoryConstants.OWNER_TYPE_ASSISTANT) {
            _consolidationMessage.value = "每日整理仅支持助手专属记忆"
            onComplete?.invoke(false, "每日整理仅支持助手专属记忆")
            return
        }
        val service = consolidationService ?: return
        if (_isConsolidating.value) return
        scope.launch(Dispatchers.IO) {
            _isConsolidating.value = true
            _consolidationMessage.value = "正在整理 $date 对话与长期事实..."
            val result = try {
                service.consolidateDate(ownerType, ownerId, date, force)
            } catch (e: Exception) {
                ConsolidationServiceResult.Failed(e.javaClass.simpleName, e.message ?: "未知异常")
            }
            _isConsolidating.value = false
            when (result) {
                is ConsolidationServiceResult.Success -> {
                    val msg = if (result.skippedByFingerprint) {
                        "$date 无新对话，已保持最新"
                    } else {
                        "$date 整理成功：新增日记，应用 ${result.appliedPatches} 条记忆变更"
                    }
                    _consolidationMessage.value = msg
                    onComplete?.invoke(true, msg)
                }
                is ConsolidationServiceResult.Skipped -> {
                    val msg = "跳过整理: ${result.reason}"
                    _consolidationMessage.value = msg
                    onComplete?.invoke(true, msg)
                }
                is ConsolidationServiceResult.Failed -> {
                    val msg = "整理失败: ${result.message}"
                    _consolidationMessage.value = msg
                    onComplete?.invoke(false, msg)
                }
            }
        }
    }

    fun deleteDailyNote(memoryDate: String) {
        scope.launch(Dispatchers.IO) {
            repository.deleteDailyNote(ownerId, memoryDate)
        }
    }

    fun approveProposal(proposalId: String) {
        scope.launch(Dispatchers.IO) {
            val card = repository.approveProposal(proposalId, actor = MemoryConstants.ACTOR_USER)
            if (card != null) {
                triggerSyncEmbedding(card)
            }
        }
    }

    fun rejectProposal(proposalId: String, reason: String? = null) {
        scope.launch(Dispatchers.IO) {
            repository.rejectProposal(proposalId, reason ?: "用户手动忽略")
        }
    }

    fun resolveFactAudit(auditId: String, resolution: String) {
        scope.launch(Dispatchers.IO) {
            repository.resolveFactAudit(auditId, resolution, resolvedBy = MemoryConstants.ACTOR_USER)
        }
    }

    fun resolveMaintenanceTask(taskId: String, status: String = "RESOLVED") {
        scope.launch(Dispatchers.IO) {
            repository.resolveMaintenanceTask(taskId, status, resolvedBy = MemoryConstants.ACTOR_USER)
        }
    }

    fun approveRelation(id: String) {
        scope.launch(Dispatchers.IO) {
            repository.approveRelation(id)
        }
    }

    fun rejectRelation(id: String) {
        scope.launch(Dispatchers.IO) {
            repository.rejectRelation(id)
        }
    }

    suspend fun getCardRelations(cardId: String): List<MemoryRelationEntity> = withContext(Dispatchers.IO) {
        repository.getCardRelations(cardId)
    }

    suspend fun getCardsByIds(ids: List<String>): List<MemoryCardEntity> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) emptyList() else repository.getCardsByIds(ids)
    }

    fun clearConsolidationMessage() {
        _consolidationMessage.value = null
    }

    suspend fun getActiveTimelines(): List<MemoryTimelineEntity> = withContext(Dispatchers.IO) {
        repository.getActiveTimelines(ownerType, ownerId)
    }

    suspend fun getCardTimeline(cardId: String): MemoryTimelineEntity? = withContext(Dispatchers.IO) {
        val members = repository.getCardRelations(cardId) // check timeline members
        val tlMember = repository.getActiveTimelines(ownerType, ownerId).firstOrNull { tl ->
            repository.getTimelineCards(tl.id).any { it.id == cardId }
        }
        tlMember
    }

    fun createTimeline(title: String, summary: String = "", onCreated: ((MemoryTimelineEntity) -> Unit)? = null) {
        if (title.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val existing = repository.getTimelineByTitle(ownerType, ownerId, title.trim())
            val tl = existing ?: repository.createTimeline(
                ownerType = ownerType,
                ownerId = ownerId,
                title = title.trim(),
                summary = summary.trim(),
            )
            withContext(Dispatchers.Main) {
                onCreated?.invoke(tl)
            }
        }
    }

    fun assignCardToTimeline(timelineId: String, cardId: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                repository.addCardToTimeline(timelineId, cardId)
            }
        }
    }

    fun removeCardFromTimeline(timelineId: String, cardId: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                repository.removeCardFromTimeline(timelineId, cardId)
            }
        }
    }

    fun acceptEProposal(proposalId: Long, priority: Int? = null, meaning: String? = null) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                repository.acceptEProposal(proposalId, eInitialPriority = priority, meaning = meaning)
            }
        }
    }

    fun rejectEProposal(proposalId: Long) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                repository.rejectEProposal(proposalId)
            }
        }
    }

    private val _mapUiState = MutableStateFlow(MemoryMapUiState())
    val mapUiState: StateFlow<MemoryMapUiState> = _mapUiState.asStateFlow()

    private var cachedMapCards: List<MemoryCardEntity> = emptyList()
    private var cachedMapRelations: List<MemoryRelationEntity> = emptyList()

    fun loadMemoryMapData() {
        scope.launch(Dispatchers.IO) {
            _mapUiState.update { it.copy(isLoading = true) }
            try {
                val allCards = repository.getAllCards(ownerType, ownerId)
                    .filter { it.ownerType == ownerType && it.ownerId == ownerId }
                val cardMap = allCards.associateBy { it.id }

                val activeTimelines = repository.getActiveTimelines(ownerType, ownerId)
                    .filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == MemoryConstants.STATUS_ACTIVE }

                val timelinesWithCards = activeTimelines.map { tl ->
                    val rawCards = repository.getTimelineCards(tl.id)
                    val validCards = rawCards.filter { card ->
                        card.ownerType == ownerType && card.ownerId == ownerId && cardMap.containsKey(card.id)
                    }.sortedBy { it.validFrom ?: it.createdAt }
                    MemoryTimelineWithCards(timeline = tl, cards = validCards)
                }

                val rawRelations = repository.getAllRelations(ownerType, ownerId)
                val validRelations = rawRelations.filter { rel ->
                    rel.ownerType == ownerType && rel.ownerId == ownerId &&
                        (rel.status == MemoryConstants.RELATION_STATUS_ACTIVE || rel.status == MemoryConstants.RELATION_STATUS_REVIEW) &&
                        cardMap.containsKey(rel.sourceCardId) && cardMap.containsKey(rel.targetCardId)
                }

                cachedMapCards = allCards
                cachedMapRelations = validRelations

                val currentCenterId = _mapUiState.value.selectedCenterCardId
                val effectiveCenterCard = when {
                    currentCenterId != null && cardMap.containsKey(currentCenterId) -> cardMap[currentCenterId]
                    allCards.isNotEmpty() -> allCards.first()
                    else -> null
                }
                val effectiveCenterId = effectiveCenterCard?.id

                val oneHopRels = computeOneHopRelations(effectiveCenterId, cardMap, validRelations)

                val factCards = allCards.filter { !it.factKey.isNullOrBlank() }
                val evolutionChains = factCards.groupBy { it.factKey!!.trim() }
                    .map { (factKey, groupCards) ->
                        val historyCards = groupCards.filter {
                            it.status == MemoryConstants.STATUS_SUPERSEDED ||
                                it.status == MemoryConstants.STATUS_ARCHIVED ||
                                it.status == "EXPIRED"
                        }.sortedBy { it.validFrom ?: it.createdAt }

                        val currentCards = groupCards.filter {
                            it.status == MemoryConstants.STATUS_ACTIVE
                        }.sortedBy { it.validFrom ?: it.createdAt }

                        val pendingCards = groupCards.filter {
                            it.status == MemoryConstants.STATUS_REVIEW ||
                                it.status == "PENDING"
                        }.sortedBy { it.validFrom ?: it.createdAt }

                        MemoryFactEvolutionChain(
                            factKey = factKey,
                            historyCards = historyCards,
                            currentCards = currentCards,
                            pendingCards = pendingCards,
                        )
                    }.sortedBy { it.factKey }

                _mapUiState.update {
                    it.copy(
                        isLoading = false,
                        timelines = timelinesWithCards,
                        allCards = allCards,
                        selectedCenterCardId = effectiveCenterId,
                        selectedCenterCard = effectiveCenterCard,
                        oneHopRelations = oneHopRels,
                        factEvolutionChains = evolutionChains,
                    )
                }
            } catch (e: Exception) {
                _mapUiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun selectCenterCard(cardId: String?) {
        val cardMap = cachedMapCards.associateBy { it.id }
        val centerCard = cardId?.let { cardMap[it] }
        val effectiveId = centerCard?.id
        val oneHop = computeOneHopRelations(effectiveId, cardMap, cachedMapRelations)
        _mapUiState.update {
            it.copy(
                selectedCenterCardId = effectiveId,
                selectedCenterCard = centerCard,
                oneHopRelations = oneHop,
            )
        }
    }

    private fun computeOneHopRelations(
        centerCardId: String?,
        cardMap: Map<String, MemoryCardEntity>,
        relations: List<MemoryRelationEntity>,
    ): List<MemoryOneHopRelation> {
        if (centerCardId == null) return emptyList()
        val centerCard = cardMap[centerCardId] ?: return emptyList()

        return relations.mapNotNull { rel ->
            if (rel.sourceCardId == centerCardId) {
                val target = cardMap[rel.targetCardId] ?: return@mapNotNull null
                MemoryOneHopRelation(
                    relation = rel,
                    sourceCard = centerCard,
                    targetCard = target,
                    isOutgoing = true,
                )
            } else if (rel.targetCardId == centerCardId) {
                val source = cardMap[rel.sourceCardId] ?: return@mapNotNull null
                MemoryOneHopRelation(
                    relation = rel,
                    sourceCard = source,
                    targetCard = centerCard,
                    isOutgoing = false,
                )
            } else null
        }
    }
}

data class MemoryTimelineWithCards(
    val timeline: MemoryTimelineEntity,
    val cards: List<MemoryCardEntity>,
)

data class MemoryOneHopRelation(
    val relation: MemoryRelationEntity,
    val sourceCard: MemoryCardEntity,
    val targetCard: MemoryCardEntity,
    val isOutgoing: Boolean,
)

data class MemoryFactEvolutionChain(
    val factKey: String,
    val historyCards: List<MemoryCardEntity>,
    val currentCards: List<MemoryCardEntity>,
    val pendingCards: List<MemoryCardEntity>,
)

data class MemoryMapUiState(
    val isLoading: Boolean = false,
    val timelines: List<MemoryTimelineWithCards> = emptyList(),
    val allCards: List<MemoryCardEntity> = emptyList(),
    val selectedCenterCardId: String? = null,
    val selectedCenterCard: MemoryCardEntity? = null,
    val oneHopRelations: List<MemoryOneHopRelation> = emptyList(),
    val factEvolutionChains: List<MemoryFactEvolutionChain> = emptyList(),
)
