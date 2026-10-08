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

class MemoryV2TimelineAndProposalTest {

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

        val factAudits = mutableMapOf<String, MemoryFactAuditEntity>()

        override suspend fun insertFactAudit(audit: MemoryFactAuditEntity) { factAudits[audit.id] = audit }
        override suspend fun insertFactAudits(audits: List<MemoryFactAuditEntity>) { audits.forEach { insertFactAudit(it) } }
        override suspend fun getFactAuditById(id: String): MemoryFactAuditEntity? = factAudits[id]
        override suspend fun getPendingFactAudits(ownerType: String, ownerId: String): List<MemoryFactAuditEntity> = factAudits.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == "PENDING" }
        override fun getFactAuditsByStatusFlow(ownerType: String, ownerId: String, status: String): Flow<List<MemoryFactAuditEntity>> = flowOf(factAudits.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status })
        override suspend fun updateFactAudit(audit: MemoryFactAuditEntity) { factAudits[audit.id] = audit }
        override suspend fun getFactAuditsByStatus(ownerType: String, ownerId: String, status: String): List<MemoryFactAuditEntity> = factAudits.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }
        override suspend fun getFactAuditsByFactKey(ownerType: String, ownerId: String, factKey: String): List<MemoryFactAuditEntity> = factAudits.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.factKey == factKey }
        override suspend fun getPendingFactAuditsByFactKeys(ownerType: String, ownerId: String, factKeys: List<String>): List<MemoryFactAuditEntity> =
            factAudits.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.factKey in factKeys && it.status == "PENDING" }
        override suspend fun getAllFactAudits(ownerType: String, ownerId: String): List<MemoryFactAuditEntity> = factAudits.values.filter { it.ownerType == ownerType && it.ownerId == ownerId }
        override suspend fun getAllPendingFactAudits(): List<MemoryFactAuditEntity> = factAudits.values.filter { it.status == "PENDING" }
        override suspend fun updateFactAuditStatus(id: String, status: String, resolvedAt: Long?, resolvedBy: String?) {
            factAudits[id]?.let { factAudits[id] = it.copy(status = status, resolvedAt = resolvedAt, resolvedBy = resolvedBy) }
        }
        override suspend fun deleteFactAuditById(id: String) { factAudits.remove(id) }

        override suspend fun getActiveSafeRelationsByCardIds(
            cardIds: List<String>,
            safeTypes: List<String>,
            minStrength: Float,
            now: Long,
        ): List<MemoryRelationEntity> = emptyList()
    }

    private val fakeDao = FakeDao()
    private val repo = object : MemoryRepositoryV1(database = null, dao = fakeDao) {}

    @Test
    fun `test timeline creation and membership`() = runBlocking {
        val ownerId = "asst-1"
        val timeline = repo.createTimeline(
            ownerType = "assistant",
            ownerId = ownerId,
            title = "毕业设计项目",
            summary = "关于知识图谱问答系统的毕设",
        )
        assertNotNull(timeline.id)
        assertEquals("毕业设计项目", timeline.title)

        val card1 = repo.createCard("assistant", ownerId, "开题报告已通过")
        val card2 = repo.createCard("assistant", ownerId, "中期答辩顺利完成")

        repo.addCardToTimeline(timeline.id, card1.id)
        repo.addCardToTimeline(timeline.id, card2.id)

        val cards = repo.getTimelineCards(timeline.id)
        assertEquals(2, cards.size)
        assertEquals("开题报告已通过", cards[0].content)
        assertEquals("中期答辩顺利完成", cards[1].content)

        val activeTimelines = repo.getActiveTimelines("assistant", ownerId)
        assertEquals(1, activeTimelines.size)
    }

    @Test
    fun `timeline rejects a card owned by another scope`() = runBlocking {
        val timeline = repo.createTimeline(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = "asst-1",
            title = "助手私有时间线",
        )
        val globalCard = repo.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_GLOBAL,
            ownerId = MemoryConstants.GLOBAL_OWNER_ID,
            content = "不应跨范围加入",
        )

        assertTrue(runCatching { repo.addCardToTimeline(timeline.id, globalCard.id) }.isFailure)
        assertTrue(fakeDao.getTimelineMembers(timeline.id).isEmpty())
    }

    @Test
    fun `timeline expansion uses only active same-owner members and respects global sharing`() = runBlocking {
        val ownerId = "timeline-expand-owner"
        val timeline = repo.createTimeline(MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId, "进行中的事项")
        val seed = repo.createCard(MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId, "事项起点")
        val active = repo.createCard(MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId, "事项进展")
        val review = repo.createCard(MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId, "待确认事项")
        fakeDao.updateCard(review.copy(status = MemoryConstants.STATUS_REVIEW))
        repo.addCardToTimeline(timeline.id, seed.id, startTime = 1L)
        repo.addCardToTimeline(timeline.id, active.id, startTime = 3L)
        repo.addCardToTimeline(timeline.id, review.id, startTime = 2L)

        val localResult = repo.expandTimelines(
            seedCardIds = listOf(seed.id),
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = ownerId,
            includeGlobal = false,
        )
        assertEquals(listOf(active.id), localResult.map { it.id })

        val globalTimeline = repo.createTimeline(
            MemoryConstants.OWNER_TYPE_GLOBAL,
            MemoryConstants.GLOBAL_OWNER_ID,
            "全局事项",
        )
        val globalSeed = repo.createCard(
            MemoryConstants.OWNER_TYPE_GLOBAL,
            MemoryConstants.GLOBAL_OWNER_ID,
            "全局事项起点",
        )
        val globalProgress = repo.createCard(
            MemoryConstants.OWNER_TYPE_GLOBAL,
            MemoryConstants.GLOBAL_OWNER_ID,
            "全局事项进展",
        )
        repo.addCardToTimeline(globalTimeline.id, globalSeed.id)
        repo.addCardToTimeline(globalTimeline.id, globalProgress.id)

        assertTrue(
            repo.expandTimelines(
                listOf(globalSeed.id),
                MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId,
                includeGlobal = false,
            ).isEmpty()
        )
        assertEquals(
            listOf(globalProgress.id),
            repo.expandTimelines(
                listOf(globalSeed.id),
                MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId,
                includeGlobal = true,
            ).map { it.id },
        )
    }

    @Test
    fun `test proposal approve and reject workflow`() = runBlocking {
        val ownerId = "asst-1"
        val proposalId = Uuid.random().toString()
        val proposal = MemoryProposalEntity(
            id = proposalId,
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-08",
            chunkId = null,
            type = "preference",
            title = "饮品偏好",
            content = "小雨最喜欢喝无糖茉莉乌龙",
            importance = 8,
            risk = "normal",
            evidence = "给我来一杯无糖茉莉乌龙",
            sourceMessageIds = "[]",
            timelineHint = null,
            relationHints = null,
            status = "REVIEW",
            createdAt = System.currentTimeMillis(),
        )

        repo.saveProposals(listOf(proposal))
        val pending = repo.getPendingProposals("assistant", ownerId)
        assertEquals(1, pending.size)

        // 批准提案 -> 应转化为长期记忆卡片
        val approvedCard = repo.approveProposal(proposalId, actor = "MANUAL")
        assertNotNull(approvedCard)
        assertEquals("小雨最喜欢喝无糖茉莉乌龙", approvedCard?.content)
        assertEquals("ACTIVE", approvedCard?.status)

        // proposal 状态变更为 ACCEPTED
        val updatedProposal = fakeDao.getProposalById(proposalId)
        assertEquals("ACCEPTED", updatedProposal?.status)
        assertNotNull(updatedProposal?.resolvedAt)
        assertEquals(approvedCard?.id, updatedProposal?.cardId)

        // 测试拒绝提案
        val rejectId = Uuid.random().toString()
        val proposalToReject = proposal.copy(id = rejectId, content = "错误的臆测")
        repo.saveProposals(listOf(proposalToReject))
        val rejected = repo.rejectProposal(rejectId, reason = "事实不符")
        assertTrue(rejected)
        assertEquals("REJECTED", fakeDao.getProposalById(rejectId)?.status)
    }

    @Test
    fun `test event chunk persistence and query`() = runBlocking {
        val now = System.currentTimeMillis()
        val chunk1 = MemoryEventChunkEntity(
            id = "chk-1",
            ownerType = "assistant",
            ownerId = "ast-1",
            conversationId = "conv-1",
            summary = "讨论计划",
            startTime = now - 10000,
            endTime = now - 5000,
            messageIds = "[]",
            status = "PROCESSED",
            fingerprint = "fp-1",
            createdAt = now,
        )
        repo.saveEventChunks(listOf(chunk1))

        val retrieved = repo.getEventChunks("assistant", "ast-1", now - 20000, now)
        assertEquals(1, retrieved.size)
        assertEquals("chk-1", retrieved.first().id)
        assertEquals("讨论计划", retrieved.first().summary)
    }

    @Test
    fun `test proposal approve faithfully replays UPDATE without creating duplicate card`() = runBlocking {
        val ownerId = "asst-1"
        // 1. 创建初始卡片
        val initialCard = repo.createCard(
            ownerType = "assistant",
            ownerId = ownerId,
            content = "小雨喜欢苹果",
            category = "preference",
            factKey = "user.fruit",
        )
        assertEquals(1, initialCard.revision)

        // 2. 创建一个针对该卡片 UPDATE 的提案
        val updateProposalId = Uuid.random().toString()
        val updateProposal = MemoryProposalEntity(
            id = updateProposalId,
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-08",
            type = "preference",
            title = "水果偏好更新",
            content = "小雨特别喜欢吃红富士苹果",
            evidence = "我只吃红富士",
            action = "UPDATE",
            targetId = initialCard.id,
            expectedRevision = 1,
            factKey = "user.fruit",
            createdAt = System.currentTimeMillis(),
        )
        repo.saveProposals(listOf(updateProposal))

        // 3. 审批该提案
        val approvedCard = repo.approveProposal(updateProposalId, actor = "MANUAL")
        assertNotNull(approvedCard)
        assertEquals(initialCard.id, approvedCard?.id)
        assertEquals("小雨特别喜欢吃红富士苹果", approvedCard?.content)
        assertEquals(2, approvedCard?.revision)

        // 确保数据库中仍然只有 1 张卡片，没有产生重复卡
        val allCards = fakeDao.getAllCards("assistant", ownerId)
        assertEquals(1, allCards.size)
    }

    @Test
    fun `test proposal approve faithfully replays SUPERSEDE and ARCHIVE`() = runBlocking {
        val ownerId = "asst-1"
        val oldCard = repo.createCard(
            ownerType = "assistant",
            ownerId = ownerId,
            content = "小雨住在旧地址A",
            category = "profile",
            factKey = "user.address",
        )

        // 1. 审批 SUPERSEDE 提案
        val supersedeProposalId = Uuid.random().toString()
        val supersedeProposal = MemoryProposalEntity(
            id = supersedeProposalId,
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-08",
            type = "profile",
            title = "地址变更",
            content = "小雨搬到了新地址B",
            evidence = "我已经搬到了新地址B",
            action = "SUPERSEDE",
            targetId = oldCard.id,
            expectedRevision = 1,
            factKey = "user.address",
            createdAt = System.currentTimeMillis(),
        )
        repo.saveProposals(listOf(supersedeProposal))

        val newCard = repo.approveProposal(supersedeProposalId, actor = "MANUAL")
        assertNotNull(newCard)
        assertTrue(newCard!!.id != oldCard.id)
        assertEquals("小雨搬到了新地址B", newCard.content)
        assertEquals("ACTIVE", newCard.status)

        // 旧卡应当被标记为 SUPERSEDED
        val reloadedOldCard = repo.getCardById(oldCard.id)
        assertEquals("SUPERSEDED", reloadedOldCard?.status)
        assertEquals(newCard.id, reloadedOldCard?.supersededBy)

        // 当前 ACTIVE 卡片依然只有 1 张
        val activeCards = fakeDao.getCardsByStatus("assistant", ownerId, "ACTIVE")
        assertEquals(1, activeCards.size)
        assertEquals(newCard.id, activeCards.first().id)

        // 2. 审批 ARCHIVE 提案（将该新卡归档）
        val archiveProposalId = Uuid.random().toString()
        val archiveProposal = MemoryProposalEntity(
            id = archiveProposalId,
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-08",
            type = "profile",
            title = "归档地址",
            content = "归档记忆卡片: ${newCard.id}",
            evidence = "不再需要记录地址",
            action = "ARCHIVE",
            targetId = newCard.id,
            expectedRevision = newCard.revision,
            createdAt = System.currentTimeMillis(),
        )
        repo.saveProposals(listOf(archiveProposal))

        val archivedCard = repo.approveProposal(archiveProposalId, actor = "MANUAL")
        assertNotNull(archivedCard)
        assertEquals("ARCHIVED", archivedCard?.status)
        assertEquals(newCard.id, archivedCard?.id)
        assertTrue(fakeDao.getCardsByStatus("assistant", ownerId, "ACTIVE").isEmpty())
    }

    @Test
    fun `test proposal approval restores archived target before executing`() = runBlocking {
        val ownerId = "asst-1"
        val archivedCard = repo.createCard(
            ownerType = "assistant",
            ownerId = ownerId,
            content = "旧的偏好内容",
            category = "preference",
        )
        val archived = repo.archiveCard(archivedCard.id, archivedCard.revision, actor = "MANUAL")
        val proposal = MemoryProposalEntity(
            id = Uuid.random().toString(),
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-08",
            type = "preference",
            title = "恢复并更新归档卡",
            content = "更新后的偏好内容",
            evidence = "人工确认",
            action = "UPDATE",
            targetId = archived.id,
            expectedRevision = archivedCard.revision,
            createdAt = System.currentTimeMillis(),
        )
        repo.saveProposals(listOf(proposal))

        val result = repo.approveProposal(proposal.id, actor = "MANUAL")

        assertEquals("更新后的偏好内容", result?.content)
        assertEquals("ACTIVE", result?.status)
        assertEquals("ACCEPTED", fakeDao.getProposalById(proposal.id)?.status)
    }

    @Test
    fun `test proposal approve faithfully replays ADD with existing factKey via safe supersede`() = runBlocking {
        val ownerId = "asst-1"
        val existingCard = repo.createCard(
            ownerType = "assistant",
            ownerId = ownerId,
            content = "小雨喜欢喝乌龙茶",
            category = "preference",
            factKey = "user.tea",
        )

        // 模型输出了一个 ADD，但带了同 factKey 且内容不同
        val addProposalId = Uuid.random().toString()
        val addProposal = MemoryProposalEntity(
            id = addProposalId,
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-08",
            type = "preference",
            title = "茶饮偏好",
            content = "小雨现在只喝白茶，不喝乌龙茶了",
            evidence = "我不喝乌龙茶了，现在只喝白茶",
            action = "ADD",
            factKey = "user.tea",
            createdAt = System.currentTimeMillis(),
        )
        repo.saveProposals(listOf(addProposal))

        // 人工审批该 ADD，应安全转换为演化旧卡（supersede），杜绝同 factKey 双活
        val approvedCard = repo.approveProposal(addProposalId, actor = "MANUAL")
        assertNotNull(approvedCard)
        assertEquals("小雨现在只喝白茶，不喝乌龙茶了", approvedCard?.content)
        assertEquals("ACTIVE", approvedCard?.status)

        val updatedOldCard = repo.getCardById(existingCard.id)
        assertEquals("SUPERSEDED", updatedOldCard?.status)
        assertEquals(approvedCard?.id, updatedOldCard?.supersededBy)

        val activeCards = fakeDao.getCardsByStatus("assistant", ownerId, "ACTIVE")
        assertEquals(1, activeCards.size)
        assertEquals(approvedCard?.id, activeCards.first().id)
    }

    @Test
    fun `test proposal approve faithfully replays MERGE`() = runBlocking {
        val ownerId = "asst-1"
        val mainCard = repo.createCard(
            ownerType = "assistant",
            ownerId = ownerId,
            content = "小雨喜欢喝茶",
            category = "preference",
            factKey = "user.tea",
        )
        val secCard1 = repo.createCard(
            ownerType = "assistant",
            ownerId = ownerId,
            content = "小雨喜欢绿茶",
            category = "preference",
        )
        val secCard2 = repo.createCard(
            ownerType = "assistant",
            ownerId = ownerId,
            content = "小雨喜欢红茶",
            category = "preference",
        )

        val mergeProposalId = Uuid.random().toString()
        val mergeProposal = MemoryProposalEntity(
            id = mergeProposalId,
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-08",
            type = "preference",
            title = "茶饮偏好合并",
            content = "小雨喜欢喝茶，包括绿茶和红茶",
            evidence = "我平时会喝绿茶和红茶",
            action = "MERGE",
            targetId = mainCard.id,
            expectedRevision = 1,
            secondaryCardIds = """["${secCard1.id}","${secCard2.id}"]""",
            factKey = "user.tea",
            createdAt = System.currentTimeMillis(),
        )
        repo.saveProposals(listOf(mergeProposal))

        val approvedCard = repo.approveProposal(mergeProposalId, actor = "MANUAL")
        assertNotNull(approvedCard)
        assertEquals(mainCard.id, approvedCard?.id)
        assertEquals("小雨喜欢喝茶，包括绿茶和红茶", approvedCard?.content)
        assertEquals(2, approvedCard?.revision)

        val updatedSec1 = repo.getCardById(secCard1.id)
        val updatedSec2 = repo.getCardById(secCard2.id)
        assertEquals("ARCHIVED", updatedSec1?.status)
        assertEquals("ARCHIVED", updatedSec2?.status)

        val activeCards = fakeDao.getCardsByStatus("assistant", ownerId, "ACTIVE")
        assertEquals(1, activeCards.size)
        assertEquals(mainCard.id, activeCards.first().id)

        val updatedProposal = fakeDao.getProposalById(mergeProposalId)
        assertEquals("ACCEPTED", updatedProposal?.status)
        assertEquals("MANUAL", updatedProposal?.resolvedBy)
        assertEquals(mainCard.id, updatedProposal?.cardId)
    }

    @Test
    fun `test proposal approve marks conflict and retains REVIEW when target missing`() = runBlocking {
        val ownerId = "asst-1"
        val missingTargetId = "non-existent-card-id"

        val actions = listOf("UPDATE", "SUPERSEDE", "MERGE", "ARCHIVE")
        for (action in actions) {
            val proposalId = Uuid.random().toString()
            val proposal = MemoryProposalEntity(
                id = proposalId,
                ownerType = "assistant",
                ownerId = ownerId,
                memoryDate = "2026-09-08",
                type = "profile",
                title = "测试失效候选 $action",
                content = "内容-$action",
                evidence = "证据",
                action = action,
                targetId = missingTargetId,
                expectedRevision = 1,
                secondaryCardIds = if (action == "MERGE") """["sec-1"]""" else null,
                createdAt = System.currentTimeMillis(),
            )
            repo.saveProposals(listOf(proposal))

            val result = repo.approveProposal(proposalId, actor = "MANUAL")
            assertNull("Action $action with missing target must return null", result)

            val updatedProposal = fakeDao.getProposalById(proposalId)
            assertEquals("Action $action must retain REVIEW status", "REVIEW", updatedProposal?.status)
            assertEquals("Action $action must mark missing target", "missing_target", updatedProposal?.risk)
            assertTrue(
                "Action $action must record conflict reason",
                updatedProposal?.resolvedBy?.contains("missing") == true
            )
        }

        val allCards = fakeDao.getAllCards("assistant", ownerId)
        assertTrue("No card should be created for missing targets", allCards.isEmpty())
    }

    @Test
    fun `test proposal approve uses current revision when proposal revision is expired`() = runBlocking {
        val ownerId = "asst-1"
        val actions = listOf("UPDATE", "SUPERSEDE", "MERGE", "ARCHIVE")
        for (action in actions) {
            val existingCard = repo.createCard(
                ownerType = "assistant",
                ownerId = ownerId,
                content = "原始卡片内容-$action",
                category = "profile",
            )
            fakeDao.updateCard(existingCard.copy(revision = 2))
            val proposalId = Uuid.random().toString()
            val proposal = MemoryProposalEntity(
                id = proposalId,
                ownerType = "assistant",
                ownerId = ownerId,
                memoryDate = "2026-09-08",
                type = "profile",
                title = "测试过期版本 $action",
                content = "新内容-$action",
                evidence = "证据",
                action = action,
                targetId = existingCard.id,
                expectedRevision = 1,
                secondaryCardIds = if (action == "MERGE") """["sec-dummy"]""" else null,
                createdAt = System.currentTimeMillis(),
            )
            repo.saveProposals(listOf(proposal))

            val result = repo.approveProposal(proposalId, actor = "MANUAL")
            assertNotNull("Action $action must use the current card revision", result)

            val updatedProposal = fakeDao.getProposalById(proposalId)
            assertEquals("Action $action must be accepted", "ACCEPTED", updatedProposal?.status)
        }
    }

    @Test
    fun `test proposal approve uses the current target even when proposal owner metadata is stale`() = runBlocking {
        // 创建一个归属于 asst-other 的卡片
        val otherCard = repo.createCard(
            ownerType = "assistant",
            ownerId = "asst-other",
            content = "其它助手的记忆",
        )

        // 构造一个由 asst-1 发起、但 target 试图指向 asst-other 卡片的 proposal
        val proposalId = Uuid.random().toString()
        val proposal = MemoryProposalEntity(
            id = proposalId,
            ownerType = "assistant",
            ownerId = "asst-1",
            memoryDate = "2026-09-08",
            type = "profile",
            title = "越权更新",
            content = "恶意修改内容",
            evidence = "证据",
            action = "UPDATE",
            targetId = otherCard.id,
            expectedRevision = otherCard.revision,
            createdAt = System.currentTimeMillis(),
        )
        repo.saveProposals(listOf(proposal))

        val result = repo.approveProposal(proposalId, actor = "MANUAL")
        assertNotNull("Manual approval must execute when the target still exists", result)

        val updatedProposal = fakeDao.getProposalById(proposalId)
        assertEquals("ACCEPTED", updatedProposal?.status)
        assertEquals("恶意修改内容", fakeDao.getCardById(otherCard.id)?.content)
    }

    @Test
    fun `test proposal approve creates memory sources evidence chain`() = runBlocking {
        val ownerId = "asst-1"
        val proposalId = Uuid.random().toString()
        val proposal = MemoryProposalEntity(
            id = proposalId,
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-08",
            type = "fact",
            title = "技术栈事实",
            content = "橘瓣采用 Kotlin 开发",
            evidence = "橘瓣是用 Kotlin 写的",
            sourceMessageIds = """["msg-101", "msg-102"]""",
            status = "REVIEW",
            createdAt = System.currentTimeMillis(),
        )
        repo.saveProposals(listOf(proposal))

        val approvedCard = repo.approveProposal(proposalId, actor = "MANUAL")
        assertNotNull(approvedCard)

        val sources = fakeDao.getSourcesByMemoryId(approvedCard!!.id)
        assertEquals(2, sources.size)
        val msgIds = sources.map { it.messageId }.toSet()
        assertTrue(msgIds.contains("msg-101"))
        assertTrue(msgIds.contains("msg-102"))
        sources.forEach {
            assertEquals(approvedCard.id, it.memoryId)
            assertEquals("NIGHTLY_MODEL", it.sourceKind)
        }
    }

    @Test
    fun `test resolveFactAudit materializes missing candidateCard from saved content`() = runBlocking {
        val ownerId = "asst-1"
        val existingCard = repo.createCard("assistant", ownerId, "用户现居北京")
        val auditId = Uuid.random().toString()
        val audit = MemoryFactAuditEntity(
            id = auditId,
            ownerType = "assistant",
            ownerId = ownerId,
            factKey = "user.city",
            existingCardId = existingCard.id,
            candidateCardId = "non-existent-candidate-id",
            candidateContent = "用户自称搬到了上海",
            modelSuggestion = "SUPERSEDE",
            reason = "用户自称搬到了上海",
            status = "PENDING",
            createdAt = System.currentTimeMillis(),
        )
        fakeDao.insertFactAudit(audit)

        // candidateCardId 不存在但有候选文本时，人工确认应直接物化候选并完成替代
        val resolved = repo.resolveFactAudit(auditId, resolution = "SUPERSEDE")
        assertTrue("resolveFactAudit should materialize the saved candidate content", resolved)

        val supersededCard = repo.getCardById(existingCard.id)
        assertEquals("SUPERSEDED", supersededCard?.status)
        assertNotNull(supersededCard?.supersededBy)

        val resolvedAudit = fakeDao.getFactAuditById(auditId)
        assertEquals("RESOLVED_SUPERSEDE", resolvedAudit?.status)
    }

    @Test
    fun `test resolveFactAudit restores archived cards before manual resolution`() = runBlocking {
        val ownerId = "asst-1"
        val existing = repo.createCard("assistant", ownerId, "用户现居北京", factKey = "user.city")
        val candidate = repo.createCard("assistant", ownerId, "用户现居上海", factKey = "user.city")
        repo.archiveCard(existing.id, existing.revision, actor = "MANUAL")
        repo.archiveCard(candidate.id, candidate.revision, actor = "MANUAL")
        val audit = MemoryFactAuditEntity(
            id = Uuid.random().toString(),
            ownerType = "assistant",
            ownerId = ownerId,
            factKey = "user.city",
            existingCardId = existing.id,
            candidateCardId = candidate.id,
            candidateContent = candidate.content,
            modelSuggestion = "SUPERSEDE",
            reason = "用户搬家",
            status = "PENDING",
            createdAt = System.currentTimeMillis(),
        )
        fakeDao.insertFactAudit(audit)

        assertTrue(repo.resolveFactAudit(audit.id, "SUPERSEDE"))

        assertEquals("SUPERSEDED", repo.getCardById(existing.id)?.status)
        assertEquals("ACTIVE", repo.getCardById(candidate.id)?.status)
        assertEquals("RESOLVED_SUPERSEDE", fakeDao.getFactAuditById(audit.id)?.status)
    }

    @Test
    fun `test fact audit with missing existing card remains pending`() = runBlocking {
        val audit = MemoryFactAuditEntity(
            id = Uuid.random().toString(),
            ownerType = "assistant",
            ownerId = "asst-1",
            factKey = "user.city",
            existingCardId = "missing-existing",
            candidateCardId = null,
            candidateContent = "用户现居上海",
            modelSuggestion = "SUPERSEDE",
            reason = "用户搬家",
            status = "PENDING",
            createdAt = System.currentTimeMillis(),
        )
        fakeDao.insertFactAudit(audit)

        assertFalse(repo.resolveFactAudit(audit.id, "SUPERSEDE"))
        assertEquals("PENDING", fakeDao.getFactAuditById(audit.id)?.status)
    }

    @Test
    fun `test deleteCardPermanently cascades timeline member cleanup`() = runBlocking {
        val ownerId = "asst-1"
        val timeline = repo.createTimeline("assistant", ownerId, "项目时间线")
        val card = repo.createCard("assistant", ownerId, "阶段目标达成")
        repo.addCardToTimeline(timeline.id, card.id)

        // 确认成员已存在
        assertEquals(1, fakeDao.getTimelineMembers(timeline.id).size)

        // 永久删除卡片
        repo.deletePermanently(card.id)

        // 验证级联删除了时间线成员
        val membersAfterDelete = fakeDao.getTimelineMembers(timeline.id)
        assertTrue("Timeline members must be cleaned up when card is permanently deleted", membersAfterDelete.isEmpty())
    }

    @Test
    fun `test expirePendingProposalsByDate on force reconsolidate`() = runBlocking {
        val ownerId = "asst-1"
        val p1 = MemoryProposalEntity(
            id = "p-1",
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-08",
            type = "profile",
            title = "待审提案1",
            content = "内容1",
            evidence = "证据1",
            status = "REVIEW",
            createdAt = 1000L,
        )
        val p2 = MemoryProposalEntity(
            id = "p-2",
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-08",
            type = "profile",
            title = "已采纳提案",
            content = "内容2",
            evidence = "证据2",
            status = "ACCEPTED",
            createdAt = 1000L,
        )
        fakeDao.insertProposals(listOf(p1, p2))

        val now = 2000L
        fakeDao.expirePendingProposalsByDate("assistant", ownerId, "2026-09-08", now)

        val updatedP1 = fakeDao.getProposalById("p-1")
        val updatedP2 = fakeDao.getProposalById("p-2")

        assertEquals("EXPIRED", updatedP1?.status)
        assertEquals(now, updatedP1?.resolvedAt)
        assertEquals("force_reconsolidate", updatedP1?.resolvedBy)

        assertEquals("ACCEPTED", updatedP2?.status)
    }

    @Test
    fun `test metabolism inspector deduplicates pending tasks by taskType`() = runBlocking {
        val ownerId = "asst-1"
        val card = repo.createCard("assistant", ownerId, "长期记忆")
        val now = System.currentTimeMillis()

        // 插入一个 STALE_AUDIT 的 PENDING 任务
        val task1 = MemoryMaintenanceTaskEntity(
            id = "task-1",
            ownerType = "assistant",
            ownerId = ownerId,
            taskType = "STALE_AUDIT",
            targetType = "CARD",
            targetId = card.id,
            title = "陈旧卡片巡检",
            suggestion = "建议归档或更新",
            reason = "久未访问",
            status = "PENDING",
            createdAt = now,
        )
        fakeDao.insertMaintenanceTask(task1)

        // 查询相同 taskType -> 应查出已存在的任务（拦截重复）
        val existingTask = fakeDao.getPendingMaintenanceTaskByTargetAndType(
            ownerType = "assistant",
            ownerId = ownerId,
            targetType = "CARD",
            targetId = card.id,
            taskType = "STALE_AUDIT"
        )
        assertNotNull(existingTask)
        assertEquals("task-1", existingTask?.id)

        // 查询不同 taskType (如 CONFLICT_RESOLUTION) -> 应返回 null，允许创建不同类型的维护任务
        val differentTypeTask = fakeDao.getPendingMaintenanceTaskByTargetAndType(
            ownerType = "assistant",
            ownerId = ownerId,
            targetType = "CARD",
            targetId = card.id,
            taskType = "CONFLICT_RESOLUTION"
        )
        assertNull(differentTypeTask)
    }

    @Test
    fun `test approve proposal creates source with role field support`() = runBlocking {
        val ownerId = "asst-1"
        val p = MemoryProposalEntity(
            id = "prop-source-role",
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-08",
            type = "preference",
            title = "用户偏好",
            content = "用户喜欢吃橘子",
            evidence = "我说：我最爱吃砂糖橘",
            action = "ADD",
            importance = 8,
            status = "REVIEW",
            sourceMessageIds = """["msg-1", "msg-2"]""",
            createdAt = System.currentTimeMillis()
        )
        fakeDao.insertProposal(p)

        val createdCard = repo.approveProposal(
            proposalId = "prop-source-role",
            actor = "USER"
        )
        assertNotNull(createdCard)

        val sources = fakeDao.sources
        assertEquals(2, sources.size)
        sources.forEach { src ->
            assertEquals("NIGHTLY_MODEL", src.sourceKind)
            assertNotNull(src.messageId)
            assertTrue(src.role == null || src.role in setOf("USER", "ASSISTANT", "TOOL", "SYSTEM"))
        }
    }
}
