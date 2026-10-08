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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryRepositoryConsolidationTest {

    private class FakeDao : MemoryV1DaoStub() {
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
        override fun getAllCardsFlow(ownerType: String, ownerId: String): Flow<List<MemoryCardEntity>> = flowOf(cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId })
        override suspend fun getCardsByStatus(ownerType: String, ownerId: String, status: String): List<MemoryCardEntity> = cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }
        override fun getCardsByStatusFlow(ownerType: String, ownerId: String, status: String): Flow<List<MemoryCardEntity>> = flowOf(cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status })
        override suspend fun getCardsByStatusPaged(ownerType: String, ownerId: String, status: String, limit: Int, offset: Int): List<MemoryCardEntity> = cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }.drop(offset).take(limit)
        override suspend fun countCardsByStatus(ownerType: String, ownerId: String, status: String): Int = cards.values.count { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }
        override suspend fun getCardsByStatusList(ownerType: String, ownerId: String, statusList: List<String>): List<MemoryCardEntity> = cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status in statusList }
        override fun getCardsByStatusListFlow(ownerType: String, ownerId: String, statusList: List<String>): Flow<List<MemoryCardEntity>> = flowOf(cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status in statusList })
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
        override fun getDailyNotesFlow(ownerType: String, ownerId: String): Flow<List<MemoryDailyNoteEntity>> = flowOf(notes.values.filter { it.ownerType == ownerType && it.ownerId == ownerId })
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

        val factAudits = mutableListOf<MemoryFactAuditEntity>()

        override suspend fun insertFactAudit(audit: MemoryFactAuditEntity) { factAudits.add(audit) }
        override suspend fun insertFactAudits(audits: List<MemoryFactAuditEntity>) { factAudits.addAll(audits) }
        override suspend fun getFactAuditById(id: String): MemoryFactAuditEntity? = factAudits.firstOrNull { it.id == id }
        override suspend fun getPendingFactAudits(ownerType: String, ownerId: String): List<MemoryFactAuditEntity> = factAudits.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == "PENDING" }
        override fun getFactAuditsByStatusFlow(ownerType: String, ownerId: String, status: String): Flow<List<MemoryFactAuditEntity>> = flowOf(factAudits.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status })
        override suspend fun updateFactAudit(audit: MemoryFactAuditEntity) {
            factAudits.removeAll { it.id == audit.id }
            factAudits.add(audit)
        }
        override suspend fun getFactAuditsByStatus(ownerType: String, ownerId: String, status: String): List<MemoryFactAuditEntity> = factAudits.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }
        override suspend fun getFactAuditsByFactKey(ownerType: String, ownerId: String, factKey: String): List<MemoryFactAuditEntity> = factAudits.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.factKey == factKey }
        override suspend fun getPendingFactAuditsByFactKeys(ownerType: String, ownerId: String, factKeys: List<String>): List<MemoryFactAuditEntity> =
            factAudits.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.factKey in factKeys && it.status == "PENDING" }
        override suspend fun getAllFactAudits(ownerType: String, ownerId: String): List<MemoryFactAuditEntity> = factAudits.filter { it.ownerType == ownerType && it.ownerId == ownerId }
        override suspend fun getAllPendingFactAudits(): List<MemoryFactAuditEntity> = factAudits.filter { it.status == "PENDING" }
        override suspend fun updateFactAuditStatus(id: String, status: String, resolvedAt: Long?, resolvedBy: String?) {
            factAudits.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { idx ->
                factAudits[idx] = factAudits[idx].copy(status = status, resolvedAt = resolvedAt, resolvedBy = resolvedBy)
            }
        }
        override suspend fun deleteFactAuditById(id: String) { factAudits.removeAll { it.id == id } }

        override suspend fun getActiveSafeRelationsByCardIds(
            cardIds: List<String>,
            safeTypes: List<String>,
            minStrength: Float,
            now: Long,
        ): List<MemoryRelationEntity> = emptyList()
    }

    private class TestableMemoryRepository(val fakeDao: FakeDao) : MemoryRepositoryV1(database = null, dao = fakeDao)

    @Test
    fun testLeaseAcquisitionAndExpiry() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)
        val ownerId = "asst_1"
        val date = "2026-09-08"

        // First acquire should succeed and return runToken
        val firstToken = repo.acquireConsolidationLease(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            memoryDate = date,
            leaseDurationMs = 5000L,
        )
        assertNotNull(firstToken)

        val run = repo.getConsolidationRun(ownerId, date)
        assertNotNull(run)
        assertEquals(MemoryConstants.RUN_STATUS_RUNNING, run!!.status)
        assertEquals(1, run.attemptCount)
        assertEquals(firstToken, run.runToken)
        assertTrue(run.leaseUntil ?: 0L > System.currentTimeMillis())

        // Concurrent acquire while lease active should return null
        val secondToken = repo.acquireConsolidationLease(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            memoryDate = date,
            leaseDurationMs = 5000L,
        )
        assertNull(secondToken)

        // Finish run as SUCCEEDED with matching runToken
        repo.finishConsolidationRun(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            memoryDate = date,
            status = MemoryConstants.RUN_STATUS_SUCCEEDED,
            fingerprint = "fp_123",
            runToken = firstToken,
        )
        val finishedRun = repo.getConsolidationRun(ownerId, date)
        assertEquals(MemoryConstants.RUN_STATUS_SUCCEEDED, finishedRun!!.status)
        assertEquals("fp_123", finishedRun.sourceFingerprint)
        assertNull(finishedRun.leaseUntil)
    }

    @Test
    fun testMarkDatePendingConsolidation() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)
        val ownerId = "asst_1"
        val date = "2026-09-08"

        repo.markDatePendingConsolidation(MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId, date)
        val run = repo.getConsolidationRun(ownerId, date)
        assertNotNull(run)
        assertEquals(MemoryConstants.RUN_STATUS_PENDING, run!!.status)

        // Acquire lease -> RUNNING
        repo.acquireConsolidationLease(MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId, date)

        // Calling markDatePending while RUNNING with active lease should not overwrite
        repo.markDatePendingConsolidation(MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId, date)
        val stillRunning = repo.getConsolidationRun(ownerId, date)
        assertEquals(MemoryConstants.RUN_STATUS_RUNNING, stillRunning!!.status)
    }

    @Test
    fun testApplyConsolidationBatch() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)
        val ownerId = "asst_1"
        val date = "2026-09-08"

        // Create an existing card c1 (rev 1) and c2 (rev 1)
        val c1 = repo.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            content = "旧内容1",
            category = "preference",
            factKey = "p1",
            actor = MemoryConstants.ACTOR_MANUAL,
        )
        val c2 = repo.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            content = "待合并内容2",
            category = "preference",
            actor = MemoryConstants.ACTOR_MANUAL,
        )

        val dailyNote = DailyNoteResult(
            summary = "小雨今天整理了长期记忆",
            emotionalThread = "积极专注",
            unfinishedTopics = listOf("话题A", "话题B")
        )

        val patches = listOf(
            // 1. ADD patch
            MemoryPatch(
                action = MemoryPatchAction.ADD,
                category = "plan",
                content = "小雨的新计划卡片",
                sourceIds = listOf("msg_1"),
                confidence = 0.9f,
                importance = 0.8f,
                reason = "今日新计划"
            ),
            // 2. UPDATE patch with valid revision
            MemoryPatch(
                action = MemoryPatchAction.UPDATE,
                targetId = c1.id,
                expectedRevision = c1.revision,
                content = "旧内容1已细化更新",
                reason = "补充信息"
            ),
            // 3. UPDATE patch with INVALID revision (should be counted in conflicted and skipped)
            MemoryPatch(
                action = MemoryPatchAction.UPDATE,
                targetId = c1.id,
                expectedRevision = 999, // conflict!
                content = "不应被写入的内容",
            ),
            // 4. MERGE patch merging c2 into c1
            MemoryPatch(
                action = MemoryPatchAction.MERGE,
                targetId = c1.id,
                secondaryIds = listOf(c2.id),
                expectedRevision = 2, // after step 2, c1 revision is 2!
                content = "c1与c2合并后的全新完整事实",
                reason = "合并重复项"
            ),
            // 5. NOOP
            MemoryPatch(action = MemoryPatchAction.NOOP)
        )

        val batchResult = repo.applyConsolidationBatch(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            memoryDate = date,
            dailyNoteResult = dailyNote,
            patches = patches,
            sourceFingerprint = "fp_abc",
        )

        // Verify Daily Note saved
        assertEquals("小雨今天整理了长期记忆", batchResult.dailyNote.summary)
        assertEquals("积极专注", batchResult.dailyNote.emotionalThread)
        assertEquals("fp_abc", batchResult.dailyNote.sourceFingerprint)

        // Verify batch stats: 3 applied (ADD, UPDATE, MERGE), 1 conflicted (invalid rev), NOOP does nothing
        assertEquals(3, batchResult.appliedPatches)
        assertEquals(1, batchResult.conflictedPatches)
        assertEquals(3, batchResult.affectedCards.size)

        // Verify c2 is archived because it was merged into c1
        val c2Archived = repo.getCardById(c2.id)
        assertNotNull(c2Archived)
        assertEquals(MemoryConstants.STATUS_ARCHIVED, c2Archived!!.status)

        // Verify c1 has final merged content
        val c1Final = repo.getCardById(c1.id)
        assertNotNull(c1Final)
        assertEquals("c1与c2合并后的全新完整事实", c1Final!!.content)
        assertEquals(3, c1Final.revision) // rev 1 -> rev 2 (update) -> rev 3 (merge)
    }

    @Test
    fun testDeleteDailyNoteCascadesDeleteRun() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)
        val ownerId = "asst_1"
        val date = "2026-09-08"

        dao.insertOrUpdateDailyNote(
            MemoryDailyNoteEntity(
                id = "note_1",
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = ownerId,
                memoryDate = date,
                summary = "测试日记",
                emotionalThread = "正常",
                unfinishedTopics = "[]",
                sourceFingerprint = "fp_1",
                createdAt = 1000L,
                updatedAt = 1000L,
            )
        )
        dao.insertOrReplaceRun(
            MemoryConsolidationRunEntity(
                id = "run_1",
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = ownerId,
                memoryDate = date,
                status = MemoryConstants.RUN_STATUS_SUCCEEDED,
                sourceFingerprint = "fp_1",
                attemptCount = 1,
                leaseUntil = null,
                errorCode = null,
            )
        )

        assertNotNull(repo.getDailyNote(ownerId, date))
        assertNotNull(repo.getConsolidationRun(ownerId, date))

        // 执行删除
        repo.deleteDailyNote(ownerId, date)

        // 验证日记与 run 均被级联删除
        assertNull(repo.getDailyNote(ownerId, date))
        assertNull(repo.getConsolidationRun(ownerId, date))
    }

    @Test
    fun testHeartbeatRenewal() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)
        val ownerId = "asst_1"
        val date = "2026-09-08"

        val token = repo.acquireConsolidationLease(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            memoryDate = date,
            leaseDurationMs = 1000L,
        )
        assertNotNull(token)
        val initialLease = repo.getConsolidationRun(ownerId, date)!!.leaseUntil!!

        // 正确令牌续租成功
        val renewed = repo.renewConsolidationLease(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            memoryDate = date,
            runToken = token!!,
            leaseDurationMs = 60_000L,
        )
        assertTrue(renewed)
        val renewedLease = repo.getConsolidationRun(ownerId, date)!!.leaseUntil!!
        assertTrue(renewedLease > initialLease)

        // 错误令牌续租失败
        val renewedWrong = repo.renewConsolidationLease(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            memoryDate = date,
            runToken = "wrong-token",
            leaseDurationMs = 60_000L,
        )
        assertFalse(renewedWrong)

        // 租约已过期即使 token 正确也续租失败（防止已过期任务在无人抢租时复活）
        dao.insertOrReplaceRun(repo.getConsolidationRun(ownerId, date)!!.copy(leaseUntil = System.currentTimeMillis() - 1000L))
        val renewedExpired = repo.renewConsolidationLease(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            memoryDate = date,
            runToken = token!!,
            leaseDurationMs = 60_000L,
        )
        assertFalse(renewedExpired)
    }

    @Test
    fun testTokenFencingRejectsExpiredRunner() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)
        val ownerId = "asst_1"
        val date = "2026-09-08"

        // 任务 A 获取租约
        val tokenA = repo.acquireConsolidationLease(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            memoryDate = date,
            leaseDurationMs = -100L, // 立即过期
        )
        assertNotNull(tokenA)

        // 任务 B 接管租约
        val tokenB = repo.acquireConsolidationLease(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            memoryDate = date,
            leaseDurationMs = 10_000L,
        )
        assertNotNull(tokenB)
        assertTrue(tokenA != tokenB)

        // 任务 A 尝试提交批处理，必须被抛出 IllegalStateException 拒绝
        var batchRejected = false
        try {
            repo.applyConsolidationBatch(
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = ownerId,
                memoryDate = date,
                dailyNoteResult = DailyNoteResult("A的日记"),
                patches = emptyList(),
                runToken = tokenA,
            )
        } catch (e: IllegalStateException) {
            batchRejected = true
        }
        assertTrue("Expired tokenA should be rejected from applyConsolidationBatch", batchRejected)

        // 任务 A 尝试调用 finish，必须被抛出 IllegalStateException 拒绝，且不能覆盖任务 B 的租约与状态
        var finishRejected = false
        try {
            repo.finishConsolidationRun(
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = ownerId,
                memoryDate = date,
                status = MemoryConstants.RUN_STATUS_FAILED,
                errorCode = "A_FINISHED",
                runToken = tokenA,
            )
        } catch (e: IllegalStateException) {
            finishRejected = true
        }
        assertTrue("Expired tokenA should be rejected from finishConsolidationRun", finishRejected)
        val currentRun = repo.getConsolidationRun(ownerId, date)!!
        assertEquals(MemoryConstants.RUN_STATUS_RUNNING, currentRun.status)
        assertEquals(tokenB, currentRun.runToken)
        assertNotNull(currentRun.leaseUntil)
    }

    @Test
    fun testUpdateCardDeletesStaleEmbedding() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)
        val ownerId = "asst_1"

        val card = repo.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            content = "小雨喜欢吃甜食",
            category = MemoryConstants.CATEGORY_PREFERENCE,
        )
        dao.insertOrReplaceEmbedding(
            MemoryEmbeddingEntity(
                id = "emb_1",
                memoryId = card.id,
                ownerType = card.ownerType,
                ownerId = card.ownerId,
                embeddingProvider = "openai",
                embeddingModel = "text-embedding-3-small",
                dimension = 1536,
                inputType = "DOCUMENT",
                contentHash = "hash1",
                vectorBlob = ByteArray(0),
                status = "ACTIVE",
                updatedAt = 1000L,
            )
        )
        assertNotNull(dao.getEmbeddingByMemoryId(card.id))

        // 内容未变更新（例如仅修改 category），不应清除向量
        repo.updateCard(
            id = card.id,
            expectedRevision = card.revision,
            content = "小雨喜欢吃甜食",
            category = MemoryConstants.CATEGORY_PROFILE,
        )
        assertNotNull(dao.getEmbeddingByMemoryId(card.id))

        // 内容改变更新，立即删除旧向量
        repo.updateCard(
            id = card.id,
            expectedRevision = 2,
            content = "小雨其实不怎么喜欢吃甜食，更偏好清淡口味",
            category = MemoryConstants.CATEGORY_PREFERENCE,
        )
        assertNull(dao.getEmbeddingByMemoryId(card.id))
    }

    @Test
    fun testAddPatchWithExistingFactKeyDoesNotOverwriteAndCreatesFactAudit() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)
        val ownerId = "asst_1"
        val date = "2026-09-08"

        val initialCard = repo.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            content = "小雨正在读研一",
            category = MemoryConstants.CATEGORY_PROFILE,
            factKey = "user_education_status",
        )
        assertEquals(1, initialCard.revision)

        val token = repo.acquireConsolidationLease(MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId, date)!!

        val batchResult = repo.applyConsolidationBatch(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            memoryDate = date,
            dailyNoteResult = DailyNoteResult("日记"),
            patches = listOf(
                MemoryPatch(
                    action = MemoryPatchAction.ADD,
                    factKey = "user_education_status",
                    content = "小雨正在读研二阶段，专注于深度代码审查",
                    category = MemoryConstants.CATEGORY_PROFILE,
                )
            ),
            runToken = token,
        )

        // 核心断言：检测到已有同 factKey 且内容不同的 ACTIVE 卡片时，绝不静默 updateCard！
        // 批处理记录冲突并生成待人工确认的事实审计记录
        assertEquals(0, batchResult.appliedPatches)
        assertEquals(1, batchResult.conflictedPatches)

        // 验证原卡片内容与版本严禁被篡改
        val currentCard = dao.getCardByFactKey(MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId, "user_education_status", MemoryConstants.STATUS_ACTIVE)
        assertNotNull(currentCard)
        assertEquals(initialCard.id, currentCard!!.id)
        assertEquals(1, currentCard.revision)
        assertEquals("小雨正在读研一", currentCard.content)

        // 验证生成了一条待人工审计的事实演化记录
        val audits = dao.getPendingFactAudits(MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId)
        assertEquals(1, audits.size)
        val audit = audits.first()
        assertEquals("user_education_status", audit.factKey)
        assertEquals(initialCard.id, audit.existingCardId)
        assertEquals("小雨正在读研二阶段，专注于深度代码审查", audit.candidateContent)
        assertEquals("PENDING", audit.status)

        // 进一步验证幂等分支：如果内容完全相同，则幂等命中，不产生冲突也不篡改
        val idempotentResult = repo.applyConsolidationBatch(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            memoryDate = date,
            dailyNoteResult = DailyNoteResult("日记"),
            patches = listOf(
                MemoryPatch(
                    action = MemoryPatchAction.ADD,
                    factKey = "user_education_status",
                    content = "小雨正在读研一",
                    category = MemoryConstants.CATEGORY_PROFILE,
                )
            ),
            runToken = token,
        )
        assertEquals(1, idempotentResult.appliedPatches)
        assertEquals(0, idempotentResult.conflictedPatches)
        val unChangedCard = dao.getCardByFactKey(MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId, "user_education_status", MemoryConstants.STATUS_ACTIVE)
        assertEquals(1, unChangedCard?.revision)
    }

    @Test
    fun testGetFailedRunsQuery() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)
        val ownerId = "asst_1"

        dao.insertOrReplaceRun(
            MemoryConsolidationRunEntity(
                id = "r1",
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = ownerId,
                memoryDate = "2026-09-05",
                status = MemoryConstants.RUN_STATUS_SUCCEEDED,
                attemptCount = 1,
            )
        )
        dao.insertOrReplaceRun(
            MemoryConsolidationRunEntity(
                id = "r2",
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = ownerId,
                memoryDate = "2026-09-06",
                status = MemoryConstants.RUN_STATUS_FAILED,
                attemptCount = 2,
            )
        )
        dao.insertOrReplaceRun(
            MemoryConsolidationRunEntity(
                id = "r3",
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = ownerId,
                memoryDate = "2026-09-07",
                status = MemoryConstants.RUN_STATUS_FAILED,
                attemptCount = 4,
            )
        )

        val failed = repo.getFailedRuns(MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId, maxAttempts = 3)
        assertEquals(1, failed.size)
        assertEquals("2026-09-06", failed[0].memoryDate)
        assertEquals(2, failed[0].attemptCount)
    }
}


