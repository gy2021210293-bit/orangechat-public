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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRepositoryStableSelfTest {

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
        override suspend fun getCardsByFactKey(ownerType: String, ownerId: String, factKey: String): List<MemoryCardEntity> =
            cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.factKey == factKey }
        override suspend fun getActiveCardsWithFactKey(ownerType: String, ownerId: String): List<MemoryCardEntity> =
            cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && !it.factKey.isNullOrBlank() && it.status == "ACTIVE" }
        override suspend fun getActiveCardsByFactKeys(ownerType: String, ownerId: String, factKeys: List<String>): List<MemoryCardEntity> =
            cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.factKey in factKeys && it.status == "ACTIVE" }
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

    private val defaultDailyNote = DailyNoteResult(
        summary = "今日对话总结",
        emotionalThread = "平和温润",
        unfinishedTopics = emptyList(),
    )

    @Test
    fun testAddSelfCategoryCardInitialReviewAndConfidenceCap() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)

        val patch = MemoryPatch(
            action = MemoryPatchAction.ADD,
            category = "self",
            factKey = "self.comfort.prefer_listening_before_advice",
            content = "面对难过时，我自然更愿意先听对方说完，而不是马上给出解决办法。",
            confidence = 0.95f,
            importance = 0.8f,
            sourceIds = listOf("u1", "a1"),
            reason = "AI 初次提炼自身特点",
        )

        val sourceRefs = mapOf(
            "u1" to MemorySourceReference(
                conversationId = "conv-1",
                nodeId = null,
                messageId = "u1",
                role = "user",
                messageCreatedAt = 1700000000000L,
            ),
            "a1" to MemorySourceReference(
                conversationId = "conv-1",
                nodeId = null,
                messageId = "a1",
                role = "assistant",
                messageCreatedAt = 1700000001000L,
            )
        )

        val result = repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = "ast-1",
            memoryDate = "2026-03-01",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patch),
            sourceReferences = sourceRefs,
        )

        assertEquals(1, result.appliedPatches)
        val createdCard = dao.cards.values.firstOrNull()
        assertNotNull(createdCard)
        assertEquals("self", createdCard!!.category)
        assertEquals("self.comfort.prefer_listening_before_advice", createdCard.factKey)
        // 首次写入必须为 REVIEW 状态
        assertEquals(MemoryConstants.STATUS_REVIEW, createdCard.status)
        // 绝不可自动常驻
        assertFalse(createdCard.alwaysInject)
        // 初次置信度上限不超过 0.60
        assertTrue(createdCard.confidence <= 0.60f)
    }

    @Test
    fun testAddSelfCategoryWithInvalidFactKeyIsRejected() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)

        val patch = MemoryPatch(
            action = MemoryPatchAction.ADD,
            category = "self",
            factKey = "user.preference.tea", // 非 self.* 格式
            content = "我喜欢喝茶",
            confidence = 0.8f,
            importance = 0.5f,
            reason = "非法键值",
        )

        val result = repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = "ast-1",
            memoryDate = "2026-03-01",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patch),
        )

        // 非法 factKey 被拦截，计入 conflictedPatches
        assertEquals(0, result.appliedPatches)
        assertEquals(1, result.conflictedPatches)
        assertTrue(dao.cards.isEmpty())
    }

    @Test
    fun testSelfCardEvidenceAccumulationAndPromotion() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)

        // 1. 初次 ADD 创建 REVIEW 卡片（来源于 conv-1，日期 2026-03-01）
        val patchAdd = MemoryPatch(
            action = MemoryPatchAction.ADD,
            category = "self",
            factKey = "self.preference.enjoy_small_daily_stories",
            content = "我喜欢听别人向我分享细小而具体的日常，这会让我感到亲近和踏实。",
            confidence = 0.90f,
            importance = 0.7f,
            sourceIds = listOf("u1", "a1"),
            reason = "初次提炼",
        )
        val source1 = mapOf(
            "u1" to MemorySourceReference(
                conversationId = "conv-1",
                nodeId = null,
                messageId = "u1",
                role = "user",
                messageCreatedAt = 1700000000000L,
            ),
            "a1" to MemorySourceReference(
                conversationId = "conv-1",
                nodeId = null,
                messageId = "a1",
                role = "assistant",
                messageCreatedAt = 1700000001000L,
            )
        )
        repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = "ast-1",
            memoryDate = "2026-03-01",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patchAdd),
            sourceReferences = source1,
        )

        val card = dao.cards.values.first()
        assertEquals(MemoryConstants.STATUS_REVIEW, card.status)
        assertEquals(0.60f, card.confidence, 0.01f)

        // 2. 第二次提炼：同一天 2026-03-01，同一会话不同 anchor -> 2 个 episode，但只有 1 个记忆日，保持 REVIEW
        val patchUpdate1 = MemoryPatch(
            action = MemoryPatchAction.UPDATE,
            targetId = card.id,
            expectedRevision = card.revision,
            content = "我喜欢听别人向我分享细小而具体的日常，这会让我感到亲近和踏实。",
            confidence = 0.95f,
            sourceIds = listOf("u2", "a2"),
            reason = "同一天再次表现",
        )
        val source2 = mapOf(
            "u2" to MemorySourceReference(
                conversationId = "conv-1",
                nodeId = null,
                messageId = "u2",
                role = "user",
                messageCreatedAt = 1700000010000L,
            ),
            "a2" to MemorySourceReference(
                conversationId = "conv-1",
                nodeId = null,
                messageId = "a2",
                role = "assistant",
                messageCreatedAt = 1700000011000L,
            )
        )
        repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = "ast-1",
            memoryDate = "2026-03-01",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patchUpdate1),
            sourceReferences = source2,
        )

        val cardAfterUpdate1 = dao.cards[card.id]!!
        assertEquals(MemoryConstants.STATUS_REVIEW, cardAfterUpdate1.status)
        assertEquals(0.75f, cardAfterUpdate1.confidence, 0.01f) // 2 个 episode，上限提升至 0.75

        // 3. 第三次提炼：跨记忆日 2026-03-02，来自独立会话 conv-2 -> 达成 3 个 episode，2 个记忆日！无冲突，自动晋升为 ACTIVE！
        val patchUpdate2 = MemoryPatch(
            action = MemoryPatchAction.UPDATE,
            targetId = card.id,
            expectedRevision = cardAfterUpdate1.revision,
            content = "我喜欢听别人向我分享细小而具体的日常，这会让我感到亲近和踏实。",
            confidence = 0.95f,
            sourceIds = listOf("u3", "a3"),
            reason = "跨日再次确认",
        )
        val source3 = mapOf(
            "u3" to MemorySourceReference(
                conversationId = "conv-2",
                nodeId = null,
                messageId = "u3",
                role = "user",
                messageCreatedAt = 1700000000000L + 86400000L,
            ),
            "a3" to MemorySourceReference(
                conversationId = "conv-2",
                nodeId = null,
                messageId = "a3",
                role = "assistant",
                messageCreatedAt = 1700000000000L + 86400000L + 1000L,
            )
        )
        repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = "ast-1",
            memoryDate = "2026-03-02",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patchUpdate2),
            sourceReferences = source3,
        )

        val cardAfterUpdate2 = dao.cards[card.id]!!
        // 达成成熟门槛：自动晋升为 ACTIVE！
        assertEquals(MemoryConstants.STATUS_ACTIVE, cardAfterUpdate2.status)
        assertEquals(0.90f, cardAfterUpdate2.confidence, 0.01f) // >= 3 episode，上限 0.90
        // 晋升为 ACTIVE 依然不自动常驻，需用户手动勾选
        assertFalse(cardAfterUpdate2.alwaysInject)

        // 验证 revision 理由中记录了 episodes/days 格式
        val latestRevision = dao.revisions.last { it.memoryId == card.id }
        assertTrue(
            "Revision 理由应包含 (episodes=3, days=2)",
            latestRevision.reason?.contains("episodes=3, days=2") == true
        )
    }

    @Test
    fun testPendingFactAuditBlocksPromotion() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)

        val factKey = "self.comfort.prefer_listening_before_advice"

        // 初次 ADD 创建 REVIEW 卡片
        val patchAdd = MemoryPatch(
            action = MemoryPatchAction.ADD,
            category = "self",
            factKey = factKey,
            content = "面对难过时，我自然更愿意先听对方说完。",
            confidence = 0.90f,
            importance = 0.7f,
            sourceIds = listOf("u1", "a1"),
            reason = "初次提炼",
        )
        val source1 = mapOf(
            "u1" to MemorySourceReference("conv-1", null, "u1", "user", 1700000000000L),
            "a1" to MemorySourceReference("conv-1", null, "a1", "assistant", 1700000001000L),
        )
        repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = "ast-1",
            memoryDate = "2026-03-01",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patchAdd),
            sourceReferences = source1,
        )

        val card = dao.cards.values.first()

        // 注入同 factKey 的未决冲突审计记录 (status = PENDING)
        dao.insertFactAudit(
            MemoryFactAuditEntity(
                id = "audit-1",
                ownerType = "assistant",
                ownerId = "ast-1",
                factKey = factKey,
                existingCardId = card.id,
                candidateContent = "近期表现出立即给出解决办法的倾向，与既有卡片矛盾",
                modelSuggestion = "SUPERSEDE",
                reason = "检测到矛盾表现",
                status = "PENDING",
                createdAt = 1700000005000L,
            )
        )

        // 追加第 2 个与第 3 个跨天 episode
        val source2 = mapOf(
            "u2" to MemorySourceReference("conv-1", null, "u2", "user", 1700000010000L),
            "a2" to MemorySourceReference("conv-1", null, "a2", "assistant", 1700000011000L),
        )
        repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = "ast-1",
            memoryDate = "2026-03-01",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(
                MemoryPatch(
                    action = MemoryPatchAction.UPDATE,
                    targetId = card.id,
                    expectedRevision = card.revision,
                    content = "面对难过时，我自然更愿意先听对方说完。",
                    sourceIds = listOf("u2", "a2"),
                )
            ),
            sourceReferences = source2,
        )

        val cardAfter2 = dao.cards[card.id]!!
        val source3 = mapOf(
            "u3" to MemorySourceReference("conv-2", null, "u3", "user", 1700000000000L + 86400000L),
            "a3" to MemorySourceReference("conv-2", null, "a3", "assistant", 1700000000000L + 86400000L + 1000L),
        )
        repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = "ast-1",
            memoryDate = "2026-03-02",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(
                MemoryPatch(
                    action = MemoryPatchAction.UPDATE,
                    targetId = card.id,
                    expectedRevision = cardAfter2.revision,
                    content = "面对难过时，我自然更愿意先听对方说完。",
                    sourceIds = listOf("u3", "a3"),
                )
            ),
            sourceReferences = source3,
        )

        val cardFinal = dao.cards[card.id]!!
        // 关键断言：虽然满足 3 episodes + 2 dates，但因未决审计冲突阻断，严禁成熟，必须保持 REVIEW！
        assertEquals(MemoryConstants.STATUS_REVIEW, cardFinal.status)
        // 冲突状态下置信度受限在 0.60
        assertTrue(cardFinal.confidence <= 0.60f)
    }

    @Test
    fun testReconcileLegacyTrialSelfCards() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)

        // 1. 卡片 A：用户手动批准/创建的 ACTIVE self -> 应该保留 ACTIVE
        val cardManual = repo.createCard(
            ownerType = "assistant",
            ownerId = "ast-1",
            content = "面对难过时，我更喜欢先听对方把感受说完。",
            category = "self",
            factKey = "self.comfort.listen",
            status = MemoryConstants.STATUS_ACTIVE,
            actor = MemoryConstants.ACTOR_MANUAL,
            alwaysInject = true,
        )

        // 2. 卡片 B：旧算法自动晋升、从未人工确认的 ACTIVE self -> 应该降级为 REVIEW 且取消常驻
        val cardAuto = repo.createCard(
            ownerType = "assistant",
            ownerId = "ast-1",
            content = "我喜欢开玩笑缓解紧张氛围。",
            category = "self",
            factKey = "self.expression.humor",
            status = MemoryConstants.STATUS_ACTIVE,
            actor = MemoryConstants.ACTOR_NIGHTLY_MODEL,
            alwaysInject = true,
        )

        // 3. 卡片 C：原本就是 REVIEW 的 self -> 保持 REVIEW
        val cardReview = repo.createCard(
            ownerType = "assistant",
            ownerId = "ast-1",
            content = "我很珍惜小习惯。",
            category = "self",
            factKey = "self.attachment.habits",
            status = MemoryConstants.STATUS_REVIEW,
            actor = MemoryConstants.ACTOR_NIGHTLY_MODEL,
            alwaysInject = false,
        )

        // 4. 卡片 D：由用户在 UI 上批准（ACTOR_USER）的 ACTIVE self -> 必须保留 ACTIVE
        val cardUserApproved = repo.createCard(
            ownerType = "assistant",
            ownerId = "ast-1",
            content = "面对分别时，我更希望好好说再见。",
            category = "self",
            factKey = "self.attachment.farewell",
            status = MemoryConstants.STATUS_ACTIVE,
            actor = MemoryConstants.ACTOR_USER,
            alwaysInject = true,
        )

        val demotedCards = repo.reconcileLegacyTrialSelfCards("assistant", "ast-1")
        assertEquals(1, demotedCards.size)

        // 卡片 A 保持 ACTIVE 和 alwaysInject (ACTOR_MANUAL)
        val afterA = dao.cards[cardManual.id]!!
        assertEquals(MemoryConstants.STATUS_ACTIVE, afterA.status)
        assertTrue(afterA.alwaysInject)

        // 卡片 D 保持 ACTIVE 和 alwaysInject (ACTOR_USER)
        val afterD = dao.cards[cardUserApproved.id]!!
        assertEquals(MemoryConstants.STATUS_ACTIVE, afterD.status)
        assertTrue(afterD.alwaysInject)

        // 卡片 B 降回 REVIEW 且 alwaysInject 强制重置为 false
        val afterB = dao.cards[cardAuto.id]!!
        assertEquals(MemoryConstants.STATUS_REVIEW, afterB.status)
        assertFalse(afterB.alwaysInject)

        // 卡片 C 保持 REVIEW
        val afterC = dao.cards[cardReview.id]!!
        assertEquals(MemoryConstants.STATUS_REVIEW, afterC.status)
    }

    @Test
    fun testSetSelfAlwaysInjectValidation() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)

        // 1. 创建 REVIEW self 卡片
        val reviewCard = repo.createCard(
            ownerType = "assistant",
            ownerId = "ast-1",
            content = "自我认识待定",
            category = "self",
            status = MemoryConstants.STATUS_REVIEW,
        )
        // REVIEW 卡片禁止开启常驻
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.setSelfAlwaysInject(reviewCard.id, true) }
        }

        // 2. 创建非 self 的 ACTIVE 卡片
        val prefCard = repo.createCard(
            ownerType = "assistant",
            ownerId = "ast-1",
            content = "小雨喜欢美式咖啡",
            category = "preference",
            status = MemoryConstants.STATUS_ACTIVE,
        )
        // 非 self 卡片禁止开启常驻
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.setSelfAlwaysInject(prefCard.id, true) }
        }

        // 3. 用户手动批准 reviewCard 成为 ACTIVE
        val approvedCard = repo.approveReviewCard(reviewCard.id, actor = MemoryConstants.ACTOR_USER)
        assertEquals(MemoryConstants.STATUS_ACTIVE, approvedCard.status)

        // 4. 对 ACTIVE self 卡片开启常驻
        val injectedCard = repo.setSelfAlwaysInject(approvedCard.id, true)
        assertTrue(injectedCard.alwaysInject)

        // 5. 校验 getAlwaysInjectSelfCards 查询
        val list = repo.getAlwaysInjectSelfCards("assistant", "ast-1")
        assertEquals(1, list.size)
        assertEquals(approvedCard.id, list[0].id)

        // 6. 关闭常驻
        val turnedOffCard = repo.setSelfAlwaysInject(approvedCard.id, false)
        assertFalse(turnedOffCard.alwaysInject)
        assertTrue(repo.getAlwaysInjectSelfCards("assistant", "ast-1").isEmpty())
    }

    @Test
    fun testCardMutationResetsAlwaysInject() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)

        // 创建并激活常驻 self 卡片
        val card = repo.createCard(
            ownerType = "assistant",
            ownerId = "ast-1",
            content = "我是一个细心的助手",
            category = "self",
            status = MemoryConstants.STATUS_ACTIVE,
            alwaysInject = true,
        )
        assertTrue(card.alwaysInject)

        // 1. 更新卡片类别离开 self -> alwaysInject 重置为 false
        val updatedOther = repo.updateCard(
            id = card.id,
            expectedRevision = card.revision,
            content = "我是一个细心的助手",
            category = "other",
        )
        assertFalse(updatedOther.alwaysInject)

        // 重新开启常驻（改回 self）
        val updatedSelf = repo.updateCard(
            id = updatedOther.id,
            expectedRevision = updatedOther.revision,
            content = "我是一个细心的助手",
            category = "self",
        )
        repo.setSelfAlwaysInject(updatedSelf.id, true)
        assertTrue(dao.cards[card.id]!!.alwaysInject)

        // 2. 软归档 -> alwaysInject 重置为 false
        val archived = repo.archiveCard(card.id, expectedRevision = dao.cards[card.id]!!.revision)
        assertFalse(archived.alwaysInject)

        // 恢复并重新开启常驻
        val restored = repo.restoreCard(card.id, expectedRevision = archived.revision)
        repo.setSelfAlwaysInject(restored.id, true)
        assertTrue(dao.cards[card.id]!!.alwaysInject)

        // 3. supersede -> 旧卡片 alwaysInject 重置为 false，新卡片也默认为 false
        val (newCard, oldCard) = repo.supersedeCard(
            oldCardId = card.id,
            expectedRevision = dao.cards[card.id]!!.revision,
            newContent = "我是一个温和且细心的助手",
            actor = MemoryConstants.ACTOR_MANUAL,
        )
        assertFalse(oldCard.alwaysInject)
        assertFalse(newCard.alwaysInject)
    }

    @Test
    fun testToolSafetyBoundaries() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)

        // 1. 工具调用 supersedeCard（actor = ACTOR_TOOL）修改 self 卡片
        val originalCard = repo.createCard(
            ownerType = "assistant",
            ownerId = "ast-1",
            content = "我习惯早睡",
            category = "self",
            status = MemoryConstants.STATUS_ACTIVE,
        )

        val (newCard, oldCard) = repo.supersedeCard(
            oldCardId = originalCard.id,
            expectedRevision = originalCard.revision,
            newContent = "我经常熬夜",
            category = "self",
            confidence = 0.99f,
            actor = MemoryConstants.ACTOR_TOOL,
        )

        // 非人工 actor 替代 self 卡片必须落 REVIEW，且置信度上限不超过 0.60
        assertEquals(MemoryConstants.STATUS_REVIEW, newCard.status)
        assertTrue(newCard.confidence <= 0.60f)
        assertFalse(newCard.alwaysInject)
    }

    @Test
    fun testReconcileAllLegacyTrialSelfCardsAcrossOwners() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)

        // owner1 下自动卡与手动卡
        repo.createCard(
            ownerType = "assistant",
            ownerId = "ast-1",
            content = "自动晋升的旧卡1",
            category = "self",
            status = MemoryConstants.STATUS_ACTIVE,
            actor = MemoryConstants.ACTOR_NIGHTLY_MODEL,
        )
        repo.createCard(
            ownerType = "assistant",
            ownerId = "ast-1",
            content = "手动卡1",
            category = "self",
            status = MemoryConstants.STATUS_ACTIVE,
            actor = MemoryConstants.ACTOR_MANUAL,
        )

        // owner2 下自动卡
        repo.createCard(
            ownerType = "assistant",
            ownerId = "ast-2",
            content = "自动晋升的旧卡2",
            category = "self",
            status = MemoryConstants.STATUS_ACTIVE,
            actor = MemoryConstants.ACTOR_NIGHTLY_MODEL,
        )

        val demoted = repo.reconcileAllLegacyTrialSelfCards()
        assertEquals(2, demoted.size)
        assertTrue(demoted.any { it.ownerId == "ast-1" })
        assertTrue(demoted.any { it.ownerId == "ast-2" })
    }

    @Test
    fun testInvalidEpisodeStopsAutomaticCardMutation() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)

        // 已经存在的 ACTIVE 卡片
        val activeCard = repo.createCard(
            ownerType = "assistant",
            ownerId = "ast-1",
            content = "成熟自我事实",
            category = "self",
            factKey = "self.comfort.listening",
            status = MemoryConstants.STATUS_ACTIVE,
            alwaysInject = true,
        )

        // 仅提供 USER 消息（无效 episode，缺少 ASSISTANT）
        val patch = MemoryPatch(
            action = MemoryPatchAction.UPDATE,
            targetId = activeCard.id,
            expectedRevision = activeCard.revision,
            category = "self",
            factKey = "self.comfort.listening",
            content = "试图用不合格证据改写内容",
            sourceIds = listOf("u1"),
        )
        val sourceRefs = mapOf(
            "u1" to MemorySourceReference("c1", null, "u1", "user", 1000L)
        )

        val batchResult = repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = "ast-1",
            memoryDate = "2026-09-11",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patch),
            sourceReferences = sourceRefs,
        )

        // 必须被记为冲突，未能应用
        assertEquals(1, batchResult.conflictedPatches)
        assertEquals(0, batchResult.appliedPatches)

        // 检查原卡片：content 与 revision 绝对未变，未插入无效来源
        val cardAfter = dao.cards[activeCard.id]!!
        assertEquals("成熟自我事实", cardAfter.content)
        assertEquals(MemoryConstants.STATUS_ACTIVE, cardAfter.status)
        assertTrue(cardAfter.alwaysInject)
        assertTrue(dao.sources.isEmpty())
    }

    @Test
    fun testMutatingActiveSelfCreatesReviewChangeCandidate() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)

        // 已经存在的成熟 ACTIVE 卡片
        val activeCard = repo.createCard(
            ownerType = "assistant",
            ownerId = "ast-1",
            content = "我喜欢安静的清晨",
            category = "self",
            factKey = "self.preference.morning",
            status = MemoryConstants.STATUS_ACTIVE,
            alwaysInject = true,
        )

        // 提交一个具有合格 episode（USER + ASSISTANT）且内容不同的 UPDATE patch
        val patch = MemoryPatch(
            action = MemoryPatchAction.UPDATE,
            targetId = activeCard.id,
            expectedRevision = activeCard.revision,
            category = "self",
            factKey = "self.preference.morning",
            content = "我其实更喜欢傍晚的晚霞",
            sourceIds = listOf("u1", "a1"),
        )
        val sourceRefs = mapOf(
            "u1" to MemorySourceReference("c1", null, "u1", "user", 1000L),
            "a1" to MemorySourceReference("c1", null, "a1", "assistant", 2000L),
        )

        val batchResult = repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = "ast-1",
            memoryDate = "2026-09-11",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patch),
            sourceReferences = sourceRefs,
        )

        assertEquals(1, batchResult.appliedPatches)

        // 原 ACTIVE 卡片绝对未被覆盖！保持原内容与常驻
        val originalAfter = dao.cards[activeCard.id]!!
        assertEquals("我喜欢安静的清晨", originalAfter.content)
        assertEquals(MemoryConstants.STATUS_ACTIVE, originalAfter.status)
        assertTrue(originalAfter.alwaysInject)

        // 系统生成了同 factKey 的 REVIEW 变更候选卡
        val candidateCard = dao.cards.values.first { it.id != activeCard.id }
        assertEquals("我其实更喜欢傍晚的晚霞", candidateCard.content)
        assertEquals(MemoryConstants.STATUS_REVIEW, candidateCard.status)
        assertEquals("self.preference.morning", candidateCard.factKey)
        assertFalse(candidateCard.alwaysInject)
    }

    @Test
    fun testMatureChangeCandidateSupersedesPreviousActiveCardAndInheritsAlwaysInject() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)

        // 原 ACTIVE 常驻卡
        val originalActiveCard = repo.createCard(
            ownerType = "assistant",
            ownerId = "ast-1",
            content = "我喜欢安静的清晨",
            category = "self",
            factKey = "self.preference.morning",
            status = MemoryConstants.STATUS_ACTIVE,
            alwaysInject = true,
        )

        // 同 factKey 的 REVIEW 候选卡（已有2个来自不同日期的有效 episode）
        val candidateCard = repo.createCard(
            ownerType = "assistant",
            ownerId = "ast-1",
            content = "我其实更喜欢傍晚的晚霞",
            category = "self",
            factKey = "self.preference.morning",
            status = MemoryConstants.STATUS_REVIEW,
            alwaysInject = false,
        )
        dao.insertSource(MemorySourceEntity("s1", candidateCard.id, "c1", null, "m1", "assistant", 10000L, "NIGHTLY_MODEL", "self_episode_v2:ep1", 10000L))
        dao.insertSource(MemorySourceEntity("s2", candidateCard.id, "c1", null, "m2", "assistant", 200000000L, "NIGHTLY_MODEL", "self_episode_v2:ep2", 200000000L))

        // 本次第 3 个合格 episode 到来，使得该候选卡满足成熟门槛（3 episodes, 2 days）
        val patch = MemoryPatch(
            action = MemoryPatchAction.UPDATE,
            targetId = candidateCard.id,
            expectedRevision = candidateCard.revision,
            category = "self",
            factKey = "self.preference.morning",
            content = "我其实更喜欢傍晚的晚霞",
            sourceIds = listOf("u3", "a3"),
        )
        val sourceRefs = mapOf(
            "u3" to MemorySourceReference("c1", null, "u3", "user", 300000000L),
            "a3" to MemorySourceReference("c1", null, "a3", "assistant", 300001000L),
        )

        repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = "ast-1",
            memoryDate = "2026-09-12",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patch),
            sourceReferences = sourceRefs,
        )

        // 1. 候选卡成熟晋升为 ACTIVE，并继承原卡片的 alwaysInject = true
        val candidateAfter = dao.cards[candidateCard.id]!!
        assertEquals(MemoryConstants.STATUS_ACTIVE, candidateAfter.status)
        assertTrue("新成熟的自我卡必须继承原 ACTIVE 卡的 alwaysInject", candidateAfter.alwaysInject)

        // 2. 原 ACTIVE 卡片建立 SUPERSEDED/supersededBy 演化关系
        val originalAfter = dao.cards[originalActiveCard.id]!!
        assertEquals(MemoryConstants.STATUS_SUPERSEDED, originalAfter.status)
        assertEquals(candidateCard.id, originalAfter.supersededBy)
        assertFalse(originalAfter.alwaysInject)
    }

    @Test
    fun testThreeContradictorySelfEpisodesDoNotMergeAndDoNotMature() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)

        val factKey = "self.preference.communication"

        // 第 1 次互动：产生 "我喜欢先倾听"
        val patch1 = MemoryPatch(
            action = MemoryPatchAction.ADD,
            category = "self",
            factKey = factKey,
            content = "我喜欢先倾听",
            sourceIds = listOf("u1", "a1"),
        )
        val sourceRefs1 = mapOf(
            "u1" to MemorySourceReference("c1", null, "u1", "user", 1725800000000L),
            "a1" to MemorySourceReference("c1", null, "a1", "assistant", 1725800010000L),
        )
        val res1 = repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = "ast-1",
            memoryDate = "2026-09-08",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patch1),
            sourceReferences = sourceRefs1,
        )
        assertEquals(1, res1.appliedPatches)
        assertEquals(0, res1.conflictedPatches)
        val cardAfter1 = dao.cards.values.first { it.factKey == factKey }
        assertEquals("我喜欢先倾听", cardAfter1.content)
        assertEquals(MemoryConstants.STATUS_REVIEW, cardAfter1.status)
        val card1Sources = dao.sources.filter { it.memoryId == cardAfter1.id }
        assertEquals(2, card1Sources.size) // 1 个 episode（含 u1, a1 两条消息）

        // 第 2 次互动：同一 factKey，内容变为矛盾的 "我喜欢共同分析"
        val patch2 = MemoryPatch(
            action = MemoryPatchAction.ADD,
            category = "self",
            factKey = factKey,
            content = "我喜欢共同分析",
            sourceIds = listOf("u2", "a2"),
        )
        val sourceRefs2 = mapOf(
            "u2" to MemorySourceReference("c2", null, "u2", "user", 1725886400000L),
            "a2" to MemorySourceReference("c2", null, "a2", "assistant", 1725886410000L),
        )
        val res2 = repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = "ast-1",
            memoryDate = "2026-09-09",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patch2),
            sourceReferences = sourceRefs2,
        )
        // 判定为冲突：不能累积到原卡，不能覆写原卡
        assertEquals(0, res2.appliedPatches)
        assertEquals(1, res2.conflictedPatches)

        val cardAfter2 = dao.cards.values.first { it.factKey == factKey }
        assertEquals("原候选正文绝不可被矛盾内容覆盖", "我喜欢先倾听", cardAfter2.content)
        assertEquals(MemoryConstants.STATUS_REVIEW, cardAfter2.status)
        val card2Sources = dao.sources.filter { it.memoryId == cardAfter2.id }
        assertEquals("原候选卡绝不可累积矛盾证据", 2, card2Sources.size)

        // 验证已生成 PENDING 状态的事实审计
        val pendingAudits2 = dao.factAudits.filter { it.factKey == factKey && it.status == MemoryConstants.AUDIT_STATUS_PENDING }
        assertEquals(1, pendingAudits2.size)
        assertEquals("我喜欢共同分析", pendingAudits2[0].candidateContent)

        // 第 3 次互动：同一 factKey，内容又变为矛盾的 "我倾向直接给建议"
        val patch3 = MemoryPatch(
            action = MemoryPatchAction.ADD,
            category = "self",
            factKey = factKey,
            content = "我倾向直接给建议",
            sourceIds = listOf("u3", "a3"),
        )
        val sourceRefs3 = mapOf(
            "u3" to MemorySourceReference("c3", null, "u3", "user", 1725972800000L),
            "a3" to MemorySourceReference("c3", null, "a3", "assistant", 1725972810000L),
        )
        val res3 = repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = "ast-1",
            memoryDate = "2026-09-10",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patch3),
            sourceReferences = sourceRefs3,
        )
        assertEquals(0, res3.appliedPatches)
        assertEquals(1, res3.conflictedPatches)

        // 核心断言：三次相互矛盾内容绝不得借用证据成熟，系统中绝对不存在 STATUS_ACTIVE 的卡片
        val activeCards = dao.cards.values.filter { it.factKey == factKey && it.status == MemoryConstants.STATUS_ACTIVE }
        assertTrue("三次相互矛盾内容严禁自动成熟晋升为 ACTIVE", activeCards.isEmpty())

        val cardAfter3 = dao.cards.values.first { it.factKey == factKey }
        assertEquals("我喜欢先倾听", cardAfter3.content)
        assertEquals(MemoryConstants.STATUS_REVIEW, cardAfter3.status)
        val card3Sources = dao.sources.filter { it.memoryId == cardAfter3.id }
        assertEquals("原卡仍然只保留最初支持自身的 1 个 episode", 2, card3Sources.size)
    }

    @Test
    fun testContradictoryUpdateAgainstActiveSelfWithCandidateDoesNotMature() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)

        val factKey = "self.preference.communication"

        // 已经存在成熟的 ACTIVE 自我
        val activeCard = repo.createCard(
            ownerType = "assistant",
            ownerId = "ast-1",
            content = "我喜欢先倾听",
            category = "self",
            factKey = factKey,
            status = MemoryConstants.STATUS_ACTIVE,
            alwaysInject = true,
        )

        // 第 2 次：引入候选 "我喜欢共同分析"
        val patch2 = MemoryPatch(
            action = MemoryPatchAction.UPDATE,
            targetId = activeCard.id,
            expectedRevision = activeCard.revision,
            category = "self",
            factKey = factKey,
            content = "我喜欢共同分析",
            sourceIds = listOf("u2", "a2"),
        )
        val sourceRefs2 = mapOf(
            "u2" to MemorySourceReference("c2", null, "u2", "user", 1725886400000L),
            "a2" to MemorySourceReference("c2", null, "a2", "assistant", 1725886410000L),
        )
        val res2 = repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = "ast-1",
            memoryDate = "2026-09-09",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patch2),
            sourceReferences = sourceRefs2,
        )
        assertEquals(1, res2.appliedPatches)
        val candidateCard = dao.cards.values.first { it.id != activeCard.id && it.status == MemoryConstants.STATUS_REVIEW }
        assertEquals("我喜欢共同分析", candidateCard.content)

        // 第 3 次：再次传入与 active 和 candidate 均矛盾的 "我倾向直接给建议"
        val patch3 = MemoryPatch(
            action = MemoryPatchAction.UPDATE,
            targetId = activeCard.id,
            expectedRevision = activeCard.revision,
            category = "self",
            factKey = factKey,
            content = "我倾向直接给建议",
            sourceIds = listOf("u3", "a3"),
        )
        val sourceRefs3 = mapOf(
            "u3" to MemorySourceReference("c3", null, "u3", "user", 1725972800000L),
            "a3" to MemorySourceReference("c3", null, "a3", "assistant", 1725972810000L),
        )
        val res3 = repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = "ast-1",
            memoryDate = "2026-09-10",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patch3),
            sourceReferences = sourceRefs3,
        )
        assertEquals(0, res3.appliedPatches)
        assertEquals(1, res3.conflictedPatches)

        // 原 ACTIVE 卡绝对完好无损
        val activeAfter = dao.cards[activeCard.id]!!
        assertEquals(MemoryConstants.STATUS_ACTIVE, activeAfter.status)
        assertEquals("我喜欢先倾听", activeAfter.content)
        assertTrue(activeAfter.alwaysInject)

        // 候选卡未被覆盖，且未成熟
        val candidateAfter = dao.cards[candidateCard.id]!!
        assertEquals(MemoryConstants.STATUS_REVIEW, candidateAfter.status)
        assertEquals("我喜欢共同分析", candidateAfter.content)

        // 存在未决审计
        val audits = dao.factAudits.filter { it.factKey == factKey && it.status == MemoryConstants.AUDIT_STATUS_PENDING }
        assertTrue("必须创建未决审计记录分歧", audits.isNotEmpty())
    }

    @Test
    fun testSelfContradictoryAuditResolvedFromUIUnblocksMaturity() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)
        val ownerId = "ast-1"
        val factKey = "self.preference.communication"

        // 1. 创建初始 ACTIVE 自我卡
        val activeCard = repo.createCard(
            ownerType = "assistant",
            ownerId = ownerId,
            content = "我喜欢先倾听",
            category = "self",
            factKey = factKey,
            confidence = 0.95f,
            importance = 0.8f,
            status = MemoryConstants.STATUS_ACTIVE,
            alwaysInject = true,
        )

        // 2. 第 1 个变更互动 (2026-09-08)：产生 REVIEW 候选 "我喜欢共同分析"
        val patch1 = MemoryPatch(
            action = MemoryPatchAction.UPDATE,
            targetId = activeCard.id,
            expectedRevision = activeCard.revision,
            category = "self",
            factKey = factKey,
            content = "我喜欢共同分析",
            sourceIds = listOf("u1", "a1"),
        )
        val sourceRefs1 = mapOf(
            "u1" to MemorySourceReference("c1", null, "u1", "user", 1725800000000L),
            "a1" to MemorySourceReference("c1", null, "a1", "assistant", 1725800010000L),
        )
        val res1 = repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-08",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patch1),
            sourceReferences = sourceRefs1,
        )
        assertEquals(1, res1.appliedPatches)
        val candidateCard = dao.cards.values.first { it.id != activeCard.id && it.status == MemoryConstants.STATUS_REVIEW }
        assertEquals("我喜欢共同分析", candidateCard.content)

        // 3. 第 2 个冲突互动 (2026-09-09)：传入矛盾命题 "我倾向直接给建议" -> 产生 candidateCardId == null 的审计
        val patch2 = MemoryPatch(
            action = MemoryPatchAction.UPDATE,
            targetId = activeCard.id,
            expectedRevision = activeCard.revision,
            category = "self",
            factKey = factKey,
            content = "我倾向直接给建议",
            sourceIds = listOf("u2", "a2"),
        )
        val sourceRefs2 = mapOf(
            "u2" to MemorySourceReference("c2", null, "u2", "user", 1725886400000L),
            "a2" to MemorySourceReference("c2", null, "a2", "assistant", 1725886410000L),
        )
        val res2 = repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-09",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patch2),
            sourceReferences = sourceRefs2,
        )
        assertEquals(0, res2.appliedPatches)
        assertEquals(1, res2.conflictedPatches)

        // 验证生成了 candidateCardId == null 的未决审计，且候选卡被阻止成熟
        val pendingAudits = dao.getPendingFactAudits("assistant", ownerId)
        assertEquals(1, pendingAudits.size)
        val audit = pendingAudits.first()
        assertEquals(factKey, audit.factKey)
        assertNull("矛盾互动产生的审计 candidateCardId 为 null", audit.candidateCardId)
        assertEquals("我倾向直接给建议", audit.candidateContent)
        assertEquals(candidateCard.id, audit.existingCardId)

        // 4. 模拟 UI 操作：用户点击“拒绝新事实”(REJECT)
        val rejectSuccess = repo.resolveFactAudit(
            auditId = audit.id,
            resolution = MemoryConstants.AUDIT_RESOLUTION_REJECT,
            resolvedBy = MemoryConstants.ACTOR_USER,
        )
        assertTrue("UI 解决无 candidateCardId 事实审计 (REJECT) 必须成功返回 true", rejectSuccess)

        // 验证审计记录状态更新为 REJECTED，且无待处理审计
        val updatedAudit = dao.getFactAuditById(audit.id)!!
        assertEquals(MemoryConstants.AUDIT_STATUS_REJECTED, updatedAudit.status)
        assertEquals(MemoryConstants.ACTOR_USER, updatedAudit.resolvedBy)
        assertTrue(dao.getPendingFactAudits("assistant", ownerId).isEmpty())

        // 5. 候选卡继续积累证据：第 2 次支持证据 (2026-09-09)
        val patch3 = MemoryPatch(
            action = MemoryPatchAction.UPDATE,
            targetId = activeCard.id,
            expectedRevision = activeCard.revision,
            category = "self",
            factKey = factKey,
            content = "我喜欢共同分析",
            sourceIds = listOf("u3", "a3"),
        )
        val sourceRefs3 = mapOf(
            "u3" to MemorySourceReference("c3", null, "u3", "user", 1725886500000L),
            "a3" to MemorySourceReference("c3", null, "a3", "assistant", 1725886510000L),
        )
        val res3 = repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-09",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patch3),
            sourceReferences = sourceRefs3,
        )
        assertEquals(1, res3.appliedPatches)

        // 6. 候选卡继续积累证据：第 3 次支持证据 (2026-09-10)，满足 3 次 episode 跨 3 个自然日
        val patch4 = MemoryPatch(
            action = MemoryPatchAction.UPDATE,
            targetId = activeCard.id,
            expectedRevision = activeCard.revision,
            category = "self",
            factKey = factKey,
            content = "我喜欢共同分析",
            sourceIds = listOf("u4", "a4"),
        )
        val sourceRefs4 = mapOf(
            "u4" to MemorySourceReference("c4", null, "u4", "user", 1725972800000L),
            "a4" to MemorySourceReference("c4", null, "a4", "assistant", 1725972810000L),
        )
        val res4 = repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-10",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patch4),
            sourceReferences = sourceRefs4,
        )
        assertEquals(1, res4.appliedPatches)

        // 验证：审计解除后，候选卡成功成熟晋升为 ACTIVE，原 ACTIVE 卡转为 SUPERSEDED，且继承 alwaysInject
        val matureCard = dao.cards[candidateCard.id]!!
        assertEquals(MemoryConstants.STATUS_ACTIVE, matureCard.status)
        assertEquals("我喜欢共同分析", matureCard.content)
        assertTrue("成熟自我卡成功继承 alwaysInject", matureCard.alwaysInject)

        val oldActive = dao.cards[activeCard.id]!!
        assertEquals(MemoryConstants.STATUS_SUPERSEDED, oldActive.status)
        assertEquals(matureCard.id, oldActive.supersededBy)
        assertFalse("旧卡不再保留 alwaysInject", oldActive.alwaysInject)
    }

    @Test
    fun testSelfContradictoryAuditResolvedFromUIWithKeepBothAndSupersede() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)
        val ownerId = "ast-2"
        val factKey = "self.preference.tone"

        val activeCard = repo.createCard(
            ownerType = "assistant",
            ownerId = ownerId,
            content = "温柔克制",
            category = "self",
            factKey = factKey,
            confidence = 0.95f,
            importance = 0.8f,
            status = MemoryConstants.STATUS_ACTIVE,
            alwaysInject = true,
        )

        // 产生冲突审计 (candidateCardId == null)
        val auditId = "audit-keep-both"
        val auditKeepBoth = MemoryFactAuditEntity(
            id = auditId,
            ownerType = "assistant",
            ownerId = ownerId,
            factKey = factKey,
            existingCardId = activeCard.id,
            candidateCardId = null,
            candidateContent = "幽默风趣",
            modelSuggestion = MemoryConstants.AUDIT_SUGGESTION_UNCERTAIN,
            reason = "矛盾性格测试",
            status = MemoryConstants.AUDIT_STATUS_PENDING,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertFactAudit(auditKeepBoth)

        // UI 解决: KEEP_BOTH
        val keepBothSuccess = repo.resolveFactAudit(auditId, MemoryConstants.AUDIT_RESOLUTION_KEEP_BOTH, resolvedBy = MemoryConstants.ACTOR_USER)
        assertTrue(keepBothSuccess)
        assertEquals(MemoryConstants.AUDIT_STATUS_RESOLVED_KEEP_BOTH, dao.getFactAuditById(auditId)?.status)

        // 验证新卡物化且 factKey 置空，原卡不受影响
        val newCard = dao.cards.values.firstOrNull { it.content == "幽默风趣" }
        assertNotNull(newCard)
        assertNull(newCard!!.factKey)
        assertEquals("ACTIVE", dao.cards[activeCard.id]?.status)

        // 再次测试 SUPERSEDE 无 candidateCardId 审计
        val supersedeAuditId = "audit-supersede"
        val auditSupersede = MemoryFactAuditEntity(
            id = supersedeAuditId,
            ownerType = "assistant",
            ownerId = ownerId,
            factKey = factKey,
            existingCardId = activeCard.id,
            candidateCardId = null,
            candidateContent = "理性严谨",
            modelSuggestion = MemoryConstants.AUDIT_SUGGESTION_SUPERSEDE,
            reason = "性格转变",
            status = MemoryConstants.AUDIT_STATUS_PENDING,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertFactAudit(auditSupersede)

        val supersedeSuccess = repo.resolveFactAudit(supersedeAuditId, MemoryConstants.AUDIT_RESOLUTION_SUPERSEDE, resolvedBy = MemoryConstants.ACTOR_USER)
        assertTrue(supersedeSuccess)
        assertEquals(MemoryConstants.AUDIT_STATUS_RESOLVED_SUPERSEDE, dao.getFactAuditById(supersedeAuditId)?.status)

        val supersededCard = dao.cards[activeCard.id]!!
        assertEquals(MemoryConstants.STATUS_SUPERSEDED, supersededCard.status)

        val materializedSupersedeCard = dao.cards.values.firstOrNull { it.content == "理性严谨" }
        assertNotNull(materializedSupersedeCard)
        assertEquals(MemoryConstants.STATUS_ACTIVE, materializedSupersedeCard!!.status)
        assertEquals(factKey, materializedSupersedeCard.factKey)
        assertTrue(materializedSupersedeCard.alwaysInject)
        assertEquals(materializedSupersedeCard.id, supersededCard.supersededBy)
    }

    @Test
    fun testSupersedeClosesObsoletePendingAuditsAndDeduplicatesAudits() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)
        val ownerId = "ast-3"
        val factKey = "self.preference.listening"

        // 1. 创建 ACTIVE 自我卡片
        val activeCard = repo.createCard(
            ownerType = "assistant",
            ownerId = ownerId,
            content = "我喜欢先倾听",
            category = "self",
            factKey = factKey,
            confidence = 0.95f,
            importance = 0.8f,
            status = MemoryConstants.STATUS_ACTIVE,
            alwaysInject = true,
        )

        // 2. 连续传入相同的反证 "我喜欢先表达"（验证去重：不应重复创建同一 pending audit）
        val patch1 = MemoryPatch(
            action = MemoryPatchAction.UPDATE,
            targetId = activeCard.id,
            expectedRevision = activeCard.revision,
            category = "self",
            factKey = factKey,
            content = "我喜欢先表达",
            sourceIds = listOf("u1", "a1"),
        )
        val sourceRefs1 = mapOf(
            "u1" to MemorySourceReference("c1", null, "u1", "user", 1725800000000L),
            "a1" to MemorySourceReference("c1", null, "a1", "assistant", 1725800010000L),
        )
        // 第一次生成变更候选卡
        repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-08",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patch1),
            sourceReferences = sourceRefs1,
        )
        val candidateCard = dao.cards.values.first { it.id != activeCard.id && it.status == MemoryConstants.STATUS_REVIEW }

        // 产生第 1 个反证审计：传入与候选卡冲突的 "反证命题A"
        val patchA = MemoryPatch(
            action = MemoryPatchAction.UPDATE,
            targetId = activeCard.id,
            expectedRevision = activeCard.revision,
            category = "self",
            factKey = factKey,
            content = "反证命题A",
            sourceIds = listOf("u2", "a2"),
        )
        val sourceRefs2 = mapOf(
            "u2" to MemorySourceReference("c2", null, "u2", "user", 1725886400000L),
            "a2" to MemorySourceReference("c2", null, "a2", "assistant", 1725886410000L),
        )
        repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-09",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patchA),
            sourceReferences = sourceRefs2,
        )

        // 再次传入完全相同的 "反证命题A" -> 验证去重：pending 审计数量仍为 1，不重复创建
        repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-09",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patchA),
            sourceReferences = sourceRefs2,
        )
        val auditsAfterDup = dao.getPendingFactAudits("assistant", ownerId)
        assertEquals("完全相同的反证不得重复创建审计", 1, auditsAfterDup.size)
        val auditA = auditsAfterDup.first()
        assertEquals("反证命题A", auditA.candidateContent)

        // 产生第 2 个不同反证审计：传入 "反证命题B"
        val patchB = MemoryPatch(
            action = MemoryPatchAction.UPDATE,
            targetId = activeCard.id,
            expectedRevision = activeCard.revision,
            category = "self",
            factKey = factKey,
            content = "反证命题B",
            sourceIds = listOf("u3", "a3"),
        )
        val sourceRefs3 = mapOf(
            "u3" to MemorySourceReference("c3", null, "u3", "user", 1725972800000L),
            "a3" to MemorySourceReference("c3", null, "a3", "assistant", 1725972810000L),
        )
        repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-10",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patchB),
            sourceReferences = sourceRefs3,
        )

        val auditsBeforeResolve = dao.getPendingFactAudits("assistant", ownerId)
        assertEquals("此时应存在两条不同的 PENDING 审计 A 和 B", 2, auditsBeforeResolve.size)
        val auditB = auditsBeforeResolve.first { it.id != auditA.id }
        assertEquals("反证命题B", auditB.candidateContent)

        // 3. 用户选择先对审计 A 执行 SUPERSEDE
        val resolveSuccess = repo.resolveFactAudit(auditA.id, MemoryConstants.AUDIT_RESOLUTION_SUPERSEDE, resolvedBy = MemoryConstants.ACTOR_USER)
        assertTrue("SUPERSEDE 审计 A 成功", resolveSuccess)

        // 4. 核心断言：
        // 审计 A 状态为 RESOLVED_SUPERSEDE
        val resolvedAuditA = dao.getFactAuditById(auditA.id)!!
        assertEquals(MemoryConstants.AUDIT_STATUS_RESOLVED_SUPERSEDE, resolvedAuditA.status)

        // 审计 B 必须被原子关闭（标记为已失效/REJECTED 并记录原因），绝不保持 PENDING 悬空
        val closedAuditB = dao.getFactAuditById(auditB.id)!!
        assertEquals("审计 B 必须被原子关闭为 REJECTED", MemoryConstants.AUDIT_STATUS_REJECTED, closedAuditB.status)
        assertTrue("审计 B 记录废弃原因", closedAuditB.reason.contains("Auto-closed: obsolete due to SUPERSEDE"))

        // 当前 owner 下不再有任何 PENDING 事实审计！
        val remainingPending = dao.getPendingFactAudits("assistant", ownerId)
        assertTrue("同 factKey 的所有关联 PENDING 审计已被全部清空", remainingPending.isEmpty())

        // 5. 验证新卡物化生效为 ACTIVE，旧 ACTIVE 卡与旧 REVIEW 候选均已 SUPERSEDED
        val newActiveCard = dao.cards.values.first { it.content == "反证命题A" && it.status == MemoryConstants.STATUS_ACTIVE }
        assertEquals(factKey, newActiveCard.factKey)
        assertTrue("新卡继承 alwaysInject", newActiveCard.alwaysInject)

        val oldActiveCard = dao.cards[activeCard.id]!!
        assertEquals(MemoryConstants.STATUS_SUPERSEDED, oldActiveCard.status)
        assertEquals(newActiveCard.id, oldActiveCard.supersededBy)

        val oldReviewCard = dao.cards[candidateCard.id]!!
        assertEquals(MemoryConstants.STATUS_SUPERSEDED, oldReviewCard.status)
        assertEquals(newActiveCard.id, oldReviewCard.supersededBy)
    }

    @Test
    fun testSimulatedV38DanglingAuditUpgradedToV39UnblocksCandidateGrowth() = runBlocking {
        val dao = FakeDao()
        val repo = TestableMemoryRepository(dao)
        val ownerId = "ast-upgrade-test"
        val factKey = "self.preference.coding_style"

        // 1. 构造 v38 存量数据库状态：
        // 旧事实卡片已经在过去被置为 SUPERSEDED，但当年遗留了一条 PENDING 审计
        val oldSupersededCard = repo.createCard(
            ownerType = "assistant",
            ownerId = ownerId,
            content = "我喜欢手写 SQL",
            category = "self",
            factKey = factKey,
            confidence = 0.90f,
            importance = 0.8f,
            status = MemoryConstants.STATUS_SUPERSEDED,
            alwaysInject = false,
        )

        // 同时存在遗留的孤立 REVIEW 候选卡
        val orphanReviewCard = repo.createCard(
            ownerType = "assistant",
            ownerId = ownerId,
            content = "我喜欢使用 ORM",
            category = "self",
            factKey = factKey,
            confidence = 0.60f,
            importance = 0.8f,
            status = MemoryConstants.STATUS_REVIEW,
            alwaysInject = false,
        )

        // 存量悬空 PENDING 审计指向已被替代的旧卡和孤立 REVIEW 候选卡
        val legacyAudit = MemoryFactAuditEntity(
            id = "legacy-audit-v38",
            ownerType = "assistant",
            ownerId = ownerId,
            factKey = factKey,
            existingCardId = oldSupersededCard.id,
            candidateCardId = orphanReviewCard.id,
            candidateContent = orphanReviewCard.content,
            modelSuggestion = MemoryConstants.AUDIT_SUGGESTION_SUPERSEDE,
            reason = "v38 遗留冲突未决",
            status = MemoryConstants.AUDIT_STATUS_PENDING,
            createdAt = 1000L,
        )
        dao.insertFactAudit(legacyAudit)

        // 验证：在清扫/升级前，若直接查询，确实存在 PENDING 审计
        val preAudits = dao.getPendingFactAudits("assistant", ownerId)
        assertEquals(1, preAudits.size)

        // 2. 模拟系统升级/清扫生效 (或成长评估自动触发 sweepObsoletePendingAudits)
        // 创建新的新候选卡（例如：用户近期反复表达 "我倾向直接用轻量 ORM"），已有 2 个历史有效 episode
        val candidateCard = repo.createCard(
            ownerType = "assistant",
            ownerId = ownerId,
            content = "我倾向直接用轻量 ORM",
            category = "self",
            factKey = factKey,
            confidence = 0.90f,
            importance = 0.8f,
            status = MemoryConstants.STATUS_REVIEW,
            alwaysInject = false,
        )
        // 历史有效 episode 1 (2026-09-08) 与 episode 2 (2026-09-09)
        dao.insertSource(MemorySourceEntity("s1", candidateCard.id, "c1", null, "m1", "assistant", 1725800000000L, "NIGHTLY_MODEL", "self_episode_v2:ep1", 1725800000000L))
        dao.insertSource(MemorySourceEntity("s2", candidateCard.id, "c1", null, "m2", "assistant", 1725886400000L, "NIGHTLY_MODEL", "self_episode_v2:ep2", 1725886400000L))

        // 3. 第 3 次独立事件交互积累证据 (episode 3, 日期 2026-09-10, USER+ASSISTANT) -> 达到成熟门槛！
        val patch3 = MemoryPatch(
            action = MemoryPatchAction.UPDATE,
            targetId = candidateCard.id,
            expectedRevision = candidateCard.revision,
            category = "self",
            factKey = factKey,
            content = "我倾向直接用轻量 ORM",
            sourceIds = listOf("u3", "a3"),
        )
        val sourceRefs3 = mapOf(
            "u3" to MemorySourceReference("c1", null, "u3", "user", 1725972800000L),
            "a3" to MemorySourceReference("c1", null, "a3", "assistant", 1725972810000L),
        )
        val res3 = repo.applyConsolidationBatch(
            ownerType = "assistant",
            ownerId = ownerId,
            memoryDate = "2026-09-10",
            dailyNoteResult = defaultDailyNote,
            patches = listOf(patch3),
            sourceReferences = sourceRefs3,
        )
        assertEquals(1, res3.appliedPatches)

        // 核心验证：
        // 1) 存量悬空审计 legacyAudit 已被清扫自动关闭为 REJECTED
        val auditAfter = dao.getFactAuditById(legacyAudit.id)!!
        assertEquals("存量悬空审计必须已被清扫为 REJECTED", MemoryConstants.AUDIT_STATUS_REJECTED, auditAfter.status)
        assertTrue(auditAfter.reason.contains("Auto-closed"))

        // 2) 存量孤立 REVIEW 候选卡 orphanReviewCard 已被自动归档为 ARCHIVED，绝不作为 duplicateReviewCards 干扰冲突计算
        val orphanCardAfter = dao.cards[orphanReviewCard.id]!!
        assertEquals("存量孤立 REVIEW 候选卡必须已被自动归档", MemoryConstants.STATUS_ARCHIVED, orphanCardAfter.status)

        // 3) 候选卡成功解除阻断，成熟晋升为 ACTIVE！绝不被旧数据库悬空数据永久卡在 REVIEW
        val promotedCard = dao.cards[candidateCard.id]!!
        assertEquals("候选卡必须顺利成熟晋升为 ACTIVE", MemoryConstants.STATUS_ACTIVE, promotedCard.status)
        assertTrue("置信度不再被阻断在 0.60 以下", promotedCard.confidence >= 0.85f)
    }
}
