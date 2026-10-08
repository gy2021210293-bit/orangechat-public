/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryConsolidationRunEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryDailyNoteEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEmbeddingEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEmbeddingProjection
import me.rerere.rikkahub.data.memory.v1.db.MemoryEventChunkEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryFactAuditEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryMaintenanceTaskEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryProposalEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryRelationEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryRevisionEntity
import me.rerere.rikkahub.data.memory.v1.db.MemorySourceEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryTimelineEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryTimelineMemberEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryV1Dao
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class DailyNotesSearchTest {

    private class FakeSearchDao : MemoryV1DaoStub() {
        val cards = mutableMapOf<String, MemoryCardEntity>()
        val revisions = mutableListOf<MemoryRevisionEntity>()
        val sources = mutableListOf<MemorySourceEntity>()
        val notes = mutableMapOf<String, MemoryDailyNoteEntity>()
        val runs = mutableMapOf<String, MemoryConsolidationRunEntity>()
        val embeddings = mutableMapOf<String, MemoryEmbeddingEntity>()
        val timelines = mutableMapOf<String, MemoryTimelineEntity>()
        val timelineMembers = mutableListOf<MemoryTimelineMemberEntity>()
        val eventChunks = mutableListOf<MemoryEventChunkEntity>()
        val proposals = mutableMapOf<String, MemoryProposalEntity>()

        override suspend fun insertCard(card: MemoryCardEntity) { cards[card.id] = card }
        override suspend fun insertCards(cards: List<MemoryCardEntity>) { cards.forEach { insertCard(it) } }
        override suspend fun updateCard(card: MemoryCardEntity) { cards[card.id] = card }
        override suspend fun getCardById(id: String): MemoryCardEntity? = cards[id]
        override suspend fun getCardsByIds(ids: List<String>): List<MemoryCardEntity> = ids.mapNotNull { cards[it] }
        override suspend fun getAllCards(ownerType: String, ownerId: String): List<MemoryCardEntity> = cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId }
        override fun getAllCardsFlow(ownerType: String, ownerId: String) = flowOf(cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId })
        override suspend fun getCardsByStatus(ownerType: String, ownerId: String, status: String): List<MemoryCardEntity> = cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }
        override fun getCardsByStatusFlow(ownerType: String, ownerId: String, status: String) = flowOf(cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status })
        override suspend fun getCardsByStatusPaged(ownerType: String, ownerId: String, status: String, limit: Int, offset: Int): List<MemoryCardEntity> = cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }.drop(offset).take(limit)
        override suspend fun countCardsByStatus(ownerType: String, ownerId: String, status: String): Int = cards.values.count { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }
        override suspend fun getCardsByStatusList(ownerType: String, ownerId: String, statusList: List<String>): List<MemoryCardEntity> = cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status in statusList }
        override fun getCardsByStatusListFlow(ownerType: String, ownerId: String, statusList: List<String>) = flowOf(cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status in statusList })
        override suspend fun getCardByFactKey(ownerType: String, ownerId: String, factKey: String, status: String): MemoryCardEntity? = cards.values.firstOrNull { it.ownerType == ownerType && it.ownerId == ownerId && it.factKey == factKey && it.status == status }
        override suspend fun getAlwaysInjectSelfCards(ownerType: String, ownerId: String): List<MemoryCardEntity> =
            cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.category == "self" && it.status == "ACTIVE" && it.alwaysInject }
        override suspend fun updateCardAlwaysInject(cardId: String, alwaysInject: Boolean) {
            cards[cardId]?.let { cards[cardId] = it.copy(alwaysInject = alwaysInject) }
        }
        override suspend fun getCardsByCategoryAndStatus(category: String, status: String): List<MemoryCardEntity> =
            cards.values.filter { it.category == category && it.status == status }
        override suspend fun deleteCardById(id: String) { cards.remove(id) }

        override suspend fun insertSource(source: MemorySourceEntity) { sources.add(source) }
        override suspend fun insertSources(sources: List<MemorySourceEntity>) { this.sources.addAll(sources) }
        override suspend fun getSourcesByMemoryId(memoryId: String): List<MemorySourceEntity> = sources.filter { it.memoryId == memoryId }
        override suspend fun deleteSourcesByMemoryId(memoryId: String) { sources.removeAll { it.memoryId == memoryId } }
        override suspend fun getMemoryIdsWithSources(memoryIds: List<String>): List<String> = sources.filter { it.memoryId in memoryIds }.map { it.memoryId }.distinct()
        override suspend fun getSourcesForMemories(memoryIds: List<String>): List<MemorySourceEntity> = sources.filter { it.memoryId in memoryIds }

        override suspend fun insertRevision(revision: MemoryRevisionEntity) { revisions.add(revision) }
        override suspend fun getRevisionsByMemoryId(memoryId: String): List<MemoryRevisionEntity> = revisions.filter { it.memoryId == memoryId }
        override suspend fun deleteRevisionsByMemoryId(memoryId: String) { revisions.removeAll { it.memoryId == memoryId } }

        override suspend fun insertOrUpdateDailyNote(note: MemoryDailyNoteEntity) { notes["${note.ownerId}_${note.memoryDate}"] = note }
        override suspend fun getDailyNote(ownerId: String, memoryDate: String): MemoryDailyNoteEntity? = notes["${ownerId}_${memoryDate}"]
        override suspend fun getDailyNotes(ownerType: String, ownerId: String): List<MemoryDailyNoteEntity> = notes.values.filter { it.ownerType == ownerType && it.ownerId == ownerId }
        override fun getDailyNotesFlow(ownerType: String, ownerId: String) = flowOf(notes.values.filter { it.ownerType == ownerType && it.ownerId == ownerId })
        override suspend fun deleteDailyNote(ownerId: String, memoryDate: String) { notes.remove("${ownerId}_${memoryDate}") }
        override suspend fun searchDailyNotes(ownerType: String, ownerId: String, query: String?, dateFrom: String?, dateTo: String?, limit: Int): List<MemoryDailyNoteEntity> =
            notes.values.filter { note ->
                note.ownerType == ownerType && note.ownerId == ownerId &&
                    (dateFrom == null || note.memoryDate >= dateFrom) &&
                    (dateTo == null || note.memoryDate <= dateTo) &&
                    (query.isNullOrBlank() || note.summary.contains(query, ignoreCase = true) || (note.emotionalThread?.contains(query, ignoreCase = true) == true))
            }.sortedByDescending { it.memoryDate }.take(limit)

        override suspend fun insertOrReplaceRun(run: MemoryConsolidationRunEntity) { runs["${run.ownerId}_${run.memoryDate}"] = run }
        override suspend fun getRun(ownerId: String, memoryDate: String): MemoryConsolidationRunEntity? = runs["${ownerId}_${memoryDate}"]
        override suspend fun getAllPendingRuns(): List<MemoryConsolidationRunEntity> = runs.values.filter { it.status == "PENDING" }
        override suspend fun getPendingRuns(ownerType: String, ownerId: String): List<MemoryConsolidationRunEntity> = runs.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == "PENDING" }
        override suspend fun getFailedRuns(ownerType: String, ownerId: String, maxAttempts: Int): List<MemoryConsolidationRunEntity> = runs.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == "FAILED" && it.attemptCount < maxAttempts }
        override suspend fun deleteRun(ownerId: String, memoryDate: String) { runs.remove("${ownerId}_${memoryDate}") }

        override suspend fun insertOrReplaceEmbedding(embedding: MemoryEmbeddingEntity) { embeddings["${embedding.memoryId}_${embedding.embeddingProvider}_${embedding.embeddingModel}"] = embedding }
        override suspend fun getEmbedding(memoryId: String, embeddingProvider: String, embeddingModel: String): MemoryEmbeddingEntity? = embeddings["${memoryId}_${embeddingProvider}_${embeddingModel}"]
        override suspend fun getEmbeddingByMemoryId(memoryId: String): MemoryEmbeddingEntity? = embeddings.values.firstOrNull { it.memoryId == memoryId }
        override suspend fun getEmbeddingByContentHash(contentHash: String, embeddingModel: String): MemoryEmbeddingEntity? = embeddings.values.firstOrNull { it.contentHash == contentHash && it.embeddingModel == embeddingModel }
        override suspend fun getActiveEmbeddingProjections(ownerType: String, ownerId: String, embeddingProvider: String, embeddingModel: String, includeGlobal: Boolean, limit: Int, offset: Int): List<MemoryEmbeddingProjection> = emptyList()
        override suspend fun deleteEmbedding(memoryId: String, embeddingProvider: String, embeddingModel: String) { embeddings.remove("${memoryId}_${embeddingProvider}_${embeddingModel}") }
        override suspend fun deleteEmbeddingByMemoryId(memoryId: String) { embeddings.keys.filter { it.startsWith("${memoryId}_") }.forEach { embeddings.remove(it) } }

        override suspend fun insertTimeline(timeline: MemoryTimelineEntity) { timelines[timeline.id] = timeline }
        override suspend fun updateTimeline(timeline: MemoryTimelineEntity) { timelines[timeline.id] = timeline }
        override suspend fun getTimelineById(id: String): MemoryTimelineEntity? = timelines[id]
        override suspend fun getTimelinesByStatus(ownerType: String, ownerId: String, status: String): List<MemoryTimelineEntity> = timelines.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }
        override suspend fun getTimelineByTitle(ownerType: String, ownerId: String, title: String): MemoryTimelineEntity? = timelines.values.firstOrNull { it.ownerType == ownerType && it.ownerId == ownerId && it.title == title }
        override suspend fun getAllTimelines(ownerType: String, ownerId: String): List<MemoryTimelineEntity> = timelines.values.filter { it.ownerType == ownerType && it.ownerId == ownerId }
        override suspend fun deleteTimelineById(id: String) { timelines.remove(id) }

        override suspend fun insertTimelineMember(member: MemoryTimelineMemberEntity) { timelineMembers.add(member) }
        override suspend fun insertTimelineMembers(members: List<MemoryTimelineMemberEntity>) { timelineMembers.addAll(members) }
        override suspend fun getTimelineMembers(timelineId: String): List<MemoryTimelineMemberEntity> = timelineMembers.filter { it.timelineId == timelineId }
        override suspend fun getTimelineMembersByCardId(cardId: String): List<MemoryTimelineMemberEntity> = timelineMembers.filter { it.cardId == cardId }
        override suspend fun deleteTimelineMember(timelineId: String, cardId: String) { timelineMembers.removeAll { it.timelineId == timelineId && it.cardId == cardId } }
        override suspend fun deleteTimelineMembersByTimeline(timelineId: String) { timelineMembers.removeAll { it.timelineId == timelineId } }
        override suspend fun deleteTimelineMembersByCardId(cardId: String) { timelineMembers.removeAll { it.cardId == cardId } }

        override suspend fun insertEventChunk(chunk: MemoryEventChunkEntity) { eventChunks.add(chunk) }
        override suspend fun insertEventChunks(chunks: List<MemoryEventChunkEntity>) { eventChunks.addAll(chunks) }
        override suspend fun getEventChunkById(id: String): MemoryEventChunkEntity? = eventChunks.firstOrNull { it.id == id }
        override suspend fun getEventChunks(ownerType: String, ownerId: String, startMs: Long, endMs: Long): List<MemoryEventChunkEntity> = eventChunks.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.startTime >= startMs && it.endTime <= endMs }
        override suspend fun deleteEventChunksByDateRange(ownerType: String, ownerId: String, startMs: Long, endMs: Long) { eventChunks.removeAll { it.ownerType == ownerType && it.ownerId == ownerId && it.startTime >= startMs && it.endTime <= endMs } }

        override suspend fun insertProposal(proposal: MemoryProposalEntity) { proposals[proposal.id] = proposal }
        override suspend fun insertProposals(proposals: List<MemoryProposalEntity>) { proposals.forEach { insertProposal(it) } }
        override suspend fun getProposalById(id: String): MemoryProposalEntity? = proposals[id]
        override suspend fun getProposalsByStatus(ownerType: String, ownerId: String, status: String): List<MemoryProposalEntity> = proposals.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }
        override fun getProposalsByStatusFlow(ownerType: String, ownerId: String, status: String): Flow<List<MemoryProposalEntity>> = flowOf(proposals.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status })
        override suspend fun expirePendingProposalsByDate(ownerType: String, ownerId: String, memoryDate: String, now: Long) {
            proposals.values
                .filter { it.ownerType == ownerType && it.ownerId == ownerId && it.memoryDate == memoryDate && it.status == "REVIEW" }
                .forEach { proposals[it.id] = it.copy(status = "EXPIRED", resolvedAt = now, resolvedBy = "force_reconsolidate") }
        }
        override suspend fun getAllProposals(ownerType: String, ownerId: String): List<MemoryProposalEntity> = proposals.values.filter { it.ownerType == ownerType && it.ownerId == ownerId }
        override suspend fun updateProposal(proposal: MemoryProposalEntity) { proposals[proposal.id] = proposal }
        override suspend fun updateProposalStatus(
            id: String,
            status: String,
            resolvedAt: Long?,
            resolvedBy: String?,
            cardId: String?,
        ) {
            proposals[id]?.let {
                proposals[id] = it.copy(status = status, resolvedAt = resolvedAt, resolvedBy = resolvedBy, cardId = cardId)
            }
        }
        override suspend fun deleteProposalById(id: String) { proposals.remove(id) }

        val relations = mutableListOf<MemoryRelationEntity>()

        override suspend fun getCardsByFactKey(ownerType: String, ownerId: String, factKey: String): List<MemoryCardEntity> =
            cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.factKey == factKey }

        override suspend fun getActiveCardsWithFactKey(ownerType: String, ownerId: String): List<MemoryCardEntity> =
            cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && !it.factKey.isNullOrBlank() && it.status == "ACTIVE" }

        override suspend fun getActiveCardsByFactKeys(ownerType: String, ownerId: String, factKeys: List<String>): List<MemoryCardEntity> =
            cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.factKey in factKeys && it.status == "ACTIVE" }

        override suspend fun insertRelation(relation: MemoryRelationEntity) {
            relations.removeAll { it.id == relation.id }
            relations.add(relation)
        }
        override suspend fun insertRelations(relations: List<MemoryRelationEntity>) {
            relations.forEach { insertRelation(it) }
        }
        override suspend fun getRelationsByCardId(cardId: String): List<MemoryRelationEntity> =
            relations.filter { it.sourceCardId == cardId || it.targetCardId == cardId }
        override suspend fun getRelationsByCardIds(cardIds: List<String>): List<MemoryRelationEntity> =
            relations.filter { it.sourceCardId in cardIds || it.targetCardId in cardIds }
        override suspend fun getAllRelations(ownerType: String, ownerId: String): List<MemoryRelationEntity> =
            relations.filter { it.ownerType == ownerType && it.ownerId == ownerId }
        override suspend fun deleteRelationById(id: String) {
            relations.removeAll { it.id == id }
        }
        override suspend fun deleteRelationsByCardId(cardId: String) {
            relations.removeAll { it.sourceCardId == cardId || it.targetCardId == cardId }
        }
        override suspend fun deleteRelationsBySource(ownerType: String, ownerId: String, source: String) {
            relations.removeAll { it.ownerType == ownerType && it.ownerId == ownerId && it.source == source }
        }
        override suspend fun findRelation(ownerType: String, ownerId: String, sourceCardId: String, targetCardId: String, relationType: String): MemoryRelationEntity? =
            relations.firstOrNull { it.ownerType == ownerType && it.ownerId == ownerId && it.sourceCardId == sourceCardId && it.targetCardId == targetCardId && it.relationType == relationType }
        override suspend fun getRelationById(id: String): MemoryRelationEntity? =
            relations.firstOrNull { it.id == id }
        override fun getPendingRelationsFlow(ownerType: String, ownerId: String): Flow<List<MemoryRelationEntity>> =
            flowOf(relations.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == "REVIEW" })
        override suspend fun getPendingRelations(ownerType: String, ownerId: String): List<MemoryRelationEntity> =
            relations.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == "REVIEW" }
        override suspend fun updateRelationStatus(id: String, status: String) {
            val idx = relations.indexOfFirst { it.id == id }
            if (idx != -1) {
                relations[idx] = relations[idx].copy(status = status)
            }
        }

        val maintenanceTasks = mutableMapOf<String, MemoryMaintenanceTaskEntity>()

        override suspend fun updateCardMetabolism(cardId: String, hitCount: Int, lastRecalledAt: Long, heat: Float) {
            cards[cardId]?.let {
                cards[cardId] = it.copy(hitCount = hitCount, lastRecalledAt = lastRecalledAt, heat = heat)
            }
        }
        override suspend fun updateCardProtection(cardId: String, isProtected: Boolean) {
            cards[cardId]?.let {
                cards[cardId] = it.copy(isProtected = isProtected)
            }
        }
        override suspend fun updateCardLastReviewedAt(cardId: String, lastReviewedAt: Long) {
            cards[cardId]?.let {
                cards[cardId] = it.copy(lastReviewedAt = lastReviewedAt)
            }
        }

        override suspend fun insertMaintenanceTask(task: MemoryMaintenanceTaskEntity) {
            maintenanceTasks[task.id] = task
        }
        override suspend fun insertMaintenanceTasks(tasks: List<MemoryMaintenanceTaskEntity>) {
            tasks.forEach { insertMaintenanceTask(it) }
        }
        override suspend fun getMaintenanceTaskById(id: String): MemoryMaintenanceTaskEntity? = maintenanceTasks[id]
        override suspend fun getMaintenanceTasksByStatus(ownerType: String, ownerId: String, status: String): List<MemoryMaintenanceTaskEntity> =
            maintenanceTasks.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }
        override suspend fun getAllMaintenanceTasks(ownerType: String, ownerId: String): List<MemoryMaintenanceTaskEntity> =
            maintenanceTasks.values.filter { it.ownerType == ownerType && it.ownerId == ownerId }
        override suspend fun getPendingMaintenanceTaskByTarget(ownerType: String, ownerId: String, targetType: String, targetId: String): MemoryMaintenanceTaskEntity? =
            maintenanceTasks.values.firstOrNull { it.ownerType == ownerType && it.ownerId == ownerId && it.targetType == targetType && it.targetId == targetId && it.status == "PENDING" }
        override suspend fun getPendingMaintenanceTaskByTargetAndType(ownerType: String, ownerId: String, targetType: String, targetId: String, taskType: String): MemoryMaintenanceTaskEntity? =
            maintenanceTasks.values.firstOrNull { it.ownerType == ownerType && it.ownerId == ownerId && it.targetType == targetType && it.targetId == targetId && it.taskType == taskType && it.status == "PENDING" }
        override suspend fun getLatestMaintenanceTaskByTargetAndType(ownerType: String, ownerId: String, targetType: String, targetId: String, taskType: String): MemoryMaintenanceTaskEntity? =
            maintenanceTasks.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.targetType == targetType && it.targetId == targetId && it.taskType == taskType }
                .maxByOrNull { it.createdAt }
        override fun getMaintenanceTasksByStatusFlow(ownerType: String, ownerId: String, status: String): Flow<List<MemoryMaintenanceTaskEntity>> =
            flowOf(maintenanceTasks.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status })
        override suspend fun updateMaintenanceTaskStatus(id: String, status: String, resolvedAt: Long?, resolvedBy: String?) {
            maintenanceTasks[id]?.let {
                maintenanceTasks[id] = it.copy(status = status, resolvedAt = resolvedAt, resolvedBy = resolvedBy)
            }
        }
        override suspend fun deleteMaintenanceTaskById(id: String) {
            maintenanceTasks.remove(id)
        }

        override suspend fun insertFactAudit(audit: MemoryFactAuditEntity) {}
        override suspend fun insertFactAudits(audits: List<MemoryFactAuditEntity>) {}
        override suspend fun getFactAuditById(id: String): MemoryFactAuditEntity? = null
        override suspend fun getPendingFactAudits(ownerType: String, ownerId: String): List<MemoryFactAuditEntity> = emptyList()
        override fun getFactAuditsByStatusFlow(ownerType: String, ownerId: String, status: String): Flow<List<MemoryFactAuditEntity>> = flowOf(emptyList())
        override suspend fun updateFactAudit(audit: MemoryFactAuditEntity) {}
        override suspend fun getFactAuditsByStatus(ownerType: String, ownerId: String, status: String): List<MemoryFactAuditEntity> = emptyList()
        override suspend fun getFactAuditsByFactKey(ownerType: String, ownerId: String, factKey: String): List<MemoryFactAuditEntity> = emptyList()
        override suspend fun getPendingFactAuditsByFactKeys(ownerType: String, ownerId: String, factKeys: List<String>): List<MemoryFactAuditEntity> = emptyList()
        override suspend fun getAllFactAudits(ownerType: String, ownerId: String): List<MemoryFactAuditEntity> = emptyList()
        override suspend fun getAllPendingFactAudits(): List<MemoryFactAuditEntity> = emptyList()
        override suspend fun updateFactAuditStatus(id: String, status: String, resolvedAt: Long?, resolvedBy: String?) {}
        override suspend fun deleteFactAuditById(id: String) {}

        override suspend fun getActiveSafeRelationsByCardIds(
            cardIds: List<String>,
            safeTypes: List<String>,
            minStrength: Float,
            now: Long,
        ): List<MemoryRelationEntity> {
            val safeSet = safeTypes.map { it.lowercase() }.toSet()
            return relations.filter {
                (it.sourceCardId in cardIds || it.targetCardId in cardIds) &&
                it.status == "ACTIVE" &&
                it.relationType.lowercase() in safeSet &&
                it.strength >= minStrength &&
                (it.validUntil == null || it.validUntil > now)
            }
        }
    }

    @Test
    fun testSearchDailyNotesByQueryAndDateRange() = runBlocking {
        val dao = FakeSearchDao()
        val ownerId = "assistant-1"
        val otherOwnerId = "assistant-2"

        dao.insertOrUpdateDailyNote(
            MemoryDailyNoteEntity(
                id = Uuid.random().toString(),
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = ownerId,
                memoryDate = "2026-09-01",
                summary = "今天讨论了周末去游乐园的计划",
                emotionalThread = "感到非常期待与开心",
                createdAt = 1000L,
                updatedAt = 1000L,
            )
        )
        dao.insertOrUpdateDailyNote(
            MemoryDailyNoteEntity(
                id = Uuid.random().toString(),
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = ownerId,
                memoryDate = "2026-09-05",
                summary = "今天一起看了科幻电影，聊了宇宙的尽头",
                emotionalThread = "有些深沉但也充满好奇",
                createdAt = 2000L,
                updatedAt = 2000L,
            )
        )
        dao.insertOrUpdateDailyNote(
            MemoryDailyNoteEntity(
                id = Uuid.random().toString(),
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = ownerId,
                memoryDate = "2026-09-10",
                summary = "今天天气很好，去公园散步喝了无糖乌龙茶",
                emotionalThread = "非常轻松平静",
                createdAt = 3000L,
                updatedAt = 3000L,
            )
        )
        // Note from another assistant
        dao.insertOrUpdateDailyNote(
            MemoryDailyNoteEntity(
                id = Uuid.random().toString(),
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = otherOwnerId,
                memoryDate = "2026-09-10",
                summary = "另一个助手的日记：无糖乌龙茶",
                emotionalThread = "平静",
                createdAt = 4000L,
                updatedAt = 4000L,
            )
        )

        // 1. Query by summary content
        val queryResult = dao.searchDailyNotes(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            query = "乌龙茶",
            dateFrom = null,
            dateTo = null,
            limit = 10,
        )
        assertEquals(1, queryResult.size)
        assertEquals("2026-09-10", queryResult.first().memoryDate)

        // 2. Query by emotionalThread
        val emotionResult = dao.searchDailyNotes(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            query = "好奇",
            dateFrom = null,
            dateTo = null,
            limit = 10,
        )
        assertEquals(1, emotionResult.size)
        assertEquals("2026-09-05", emotionResult.first().memoryDate)

        // 3. Date range filter
        val rangeResult = dao.searchDailyNotes(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            query = null,
            dateFrom = "2026-09-02",
            dateTo = "2026-09-09",
            limit = 10,
        )
        assertEquals(1, rangeResult.size)
        assertEquals("2026-09-05", rangeResult.first().memoryDate)

        // 4. Limit capping and descending date order
        val allResult = dao.searchDailyNotes(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            query = null,
            dateFrom = null,
            dateTo = null,
            limit = 2,
        )
        assertEquals(2, allResult.size)
        assertEquals("2026-09-10", allResult[0].memoryDate)
        assertEquals("2026-09-05", allResult[1].memoryDate)

        // 5. Owner isolation: other owner's note must not leak
        val otherOwnerResult = dao.searchDailyNotes(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = otherOwnerId,
            query = null,
            dateFrom = null,
            dateTo = null,
            limit = 10,
        )
        assertEquals(1, otherOwnerResult.size)
        assertEquals(otherOwnerId, otherOwnerResult.first().ownerId)
    }
}
