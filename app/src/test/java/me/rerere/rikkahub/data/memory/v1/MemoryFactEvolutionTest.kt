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
import me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEProposalEntity
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

class MemoryFactEvolutionTest {

    private class FakeDao : MemoryV1DaoStub() {
        val cards = mutableMapOf<String, MemoryCardEntity>()
        val revisions = mutableListOf<MemoryRevisionEntity>()
        val relations = mutableListOf<MemoryRelationEntity>()
        val factAudits = mutableMapOf<String, MemoryFactAuditEntity>()

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
        override suspend fun getCardsByFactKey(ownerType: String, ownerId: String, factKey: String): List<MemoryCardEntity> = cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.factKey == factKey }
        override suspend fun getActiveCardsWithFactKey(ownerType: String, ownerId: String): List<MemoryCardEntity> = cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && !it.factKey.isNullOrBlank() && it.status == "ACTIVE" }
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

        override suspend fun insertSource(source: MemorySourceEntity) {}
        override suspend fun insertSources(sources: List<MemorySourceEntity>) {}
        override suspend fun getSourcesByMemoryId(memoryId: String): List<MemorySourceEntity> = emptyList()
        override suspend fun deleteSourcesByMemoryId(memoryId: String) {}
        override suspend fun getMemoryIdsWithSources(memoryIds: List<String>): List<String> = emptyList()
        override suspend fun getSourcesForMemories(memoryIds: List<String>): List<MemorySourceEntity> = emptyList()

        override suspend fun insertRevision(revision: MemoryRevisionEntity) { revisions.add(revision) }
        override suspend fun getRevisionsByMemoryId(memoryId: String): List<MemoryRevisionEntity> = revisions.filter { it.memoryId == memoryId }
        override suspend fun deleteRevisionsByMemoryId(memoryId: String) {}

        override suspend fun insertOrUpdateDailyNote(note: MemoryDailyNoteEntity) {}
        override suspend fun getDailyNote(ownerId: String, memoryDate: String): MemoryDailyNoteEntity? = null
        override suspend fun getDailyNotes(ownerType: String, ownerId: String): List<MemoryDailyNoteEntity> = emptyList()
        override fun getDailyNotesFlow(ownerType: String, ownerId: String): Flow<List<MemoryDailyNoteEntity>> = flowOf(emptyList())
        override suspend fun deleteDailyNote(ownerId: String, memoryDate: String) {}
        override suspend fun searchDailyNotes(ownerType: String, ownerId: String, query: String?, dateFrom: String?, dateTo: String?, limit: Int): List<MemoryDailyNoteEntity> = emptyList()

        override suspend fun insertOrReplaceRun(run: MemoryConsolidationRunEntity) {}
        override suspend fun getRun(ownerId: String, memoryDate: String): MemoryConsolidationRunEntity? = null
        override suspend fun getAllPendingRuns(): List<MemoryConsolidationRunEntity> = emptyList()
        override suspend fun getPendingRuns(ownerType: String, ownerId: String): List<MemoryConsolidationRunEntity> = emptyList()
        override suspend fun getFailedRuns(ownerType: String, ownerId: String, maxAttempts: Int): List<MemoryConsolidationRunEntity> = emptyList()
        override suspend fun deleteRun(ownerId: String, memoryDate: String) {}

        override suspend fun insertOrReplaceEmbedding(embedding: MemoryEmbeddingEntity) {}
        override suspend fun getEmbedding(memoryId: String, embeddingProvider: String, embeddingModel: String): MemoryEmbeddingEntity? = null
        override suspend fun getEmbeddingByMemoryId(memoryId: String): MemoryEmbeddingEntity? = null
        override suspend fun getEmbeddingByContentHash(contentHash: String, embeddingModel: String): MemoryEmbeddingEntity? = null
        override suspend fun getActiveEmbeddingProjections(ownerType: String, ownerId: String, embeddingProvider: String, embeddingModel: String, includeGlobal: Boolean, limit: Int, offset: Int): List<MemoryEmbeddingProjection> = emptyList()
        override suspend fun deleteEmbedding(memoryId: String, embeddingProvider: String, embeddingModel: String) {}
        override suspend fun deleteEmbeddingByMemoryId(memoryId: String) {}

        override suspend fun insertTimeline(timeline: MemoryTimelineEntity) {}
        override suspend fun updateTimeline(timeline: MemoryTimelineEntity) {}
        override suspend fun getTimelineById(id: String): MemoryTimelineEntity? = null
        override suspend fun getTimelinesByStatus(ownerType: String, ownerId: String, status: String): List<MemoryTimelineEntity> = emptyList()
        override suspend fun getTimelineByTitle(ownerType: String, ownerId: String, title: String): MemoryTimelineEntity? = null
        override suspend fun getAllTimelines(ownerType: String, ownerId: String): List<MemoryTimelineEntity> = emptyList()
        override suspend fun deleteTimelineById(id: String) {}

        override suspend fun insertTimelineMember(member: MemoryTimelineMemberEntity) {}
        override suspend fun insertTimelineMembers(members: List<MemoryTimelineMemberEntity>) {}
        override suspend fun getTimelineMembers(timelineId: String): List<MemoryTimelineMemberEntity> = emptyList()
        override suspend fun getTimelineMembersByCardId(cardId: String): List<MemoryTimelineMemberEntity> = emptyList()
        override suspend fun deleteTimelineMember(timelineId: String, cardId: String) {}
        override suspend fun deleteTimelineMembersByTimeline(timelineId: String) {}
        override suspend fun deleteTimelineMembersByCardId(cardId: String) {}

        override suspend fun insertEventChunk(chunk: MemoryEventChunkEntity) {}
        override suspend fun insertEventChunks(chunks: List<MemoryEventChunkEntity>) {}
        override suspend fun getEventChunkById(id: String): MemoryEventChunkEntity? = null
        override suspend fun getEventChunks(ownerType: String, ownerId: String, startMs: Long, endMs: Long): List<MemoryEventChunkEntity> = emptyList()
        override suspend fun deleteEventChunksByDateRange(ownerType: String, ownerId: String, startMs: Long, endMs: Long) {}

        override suspend fun insertProposal(proposal: MemoryProposalEntity) {}
        override suspend fun insertProposals(proposals: List<MemoryProposalEntity>) {}
        override suspend fun getProposalById(id: String): MemoryProposalEntity? = null
        override suspend fun getProposalsByStatus(ownerType: String, ownerId: String, status: String): List<MemoryProposalEntity> = emptyList()
        override fun getProposalsByStatusFlow(ownerType: String, ownerId: String, status: String): Flow<List<MemoryProposalEntity>> = flowOf(emptyList())
        override suspend fun expirePendingProposalsByDate(ownerType: String, ownerId: String, memoryDate: String, now: Long) {}
        override suspend fun getAllProposals(ownerType: String, ownerId: String): List<MemoryProposalEntity> = emptyList()
        override suspend fun updateProposal(proposal: MemoryProposalEntity) {}
        override suspend fun updateProposalStatus(id: String, status: String, resolvedAt: Long?, resolvedBy: String?, cardId: String?) {}
        override suspend fun deleteProposalById(id: String) {}

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

        override suspend fun insertFactAudit(audit: MemoryFactAuditEntity) {
            factAudits[audit.id] = audit
        }
        override suspend fun getPendingFactAudits(ownerType: String, ownerId: String): List<MemoryFactAuditEntity> =
            factAudits.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == "PENDING" }
                .sortedByDescending { it.createdAt }
        override fun getFactAuditsByStatusFlow(ownerType: String, ownerId: String, status: String): Flow<List<MemoryFactAuditEntity>> =
            flowOf(factAudits.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status })
        override suspend fun getFactAuditById(id: String): MemoryFactAuditEntity? = factAudits[id]
        override suspend fun updateFactAudit(audit: MemoryFactAuditEntity) {
            factAudits[audit.id] = audit
        }
        override suspend fun insertFactAudits(audits: List<MemoryFactAuditEntity>) {
            audits.forEach { insertFactAudit(it) }
        }
        override suspend fun getFactAuditsByStatus(ownerType: String, ownerId: String, status: String): List<MemoryFactAuditEntity> =
            factAudits.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }
        override suspend fun getFactAuditsByFactKey(ownerType: String, ownerId: String, factKey: String): List<MemoryFactAuditEntity> =
            factAudits.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.factKey == factKey }
        override suspend fun getPendingFactAuditsByFactKeys(ownerType: String, ownerId: String, factKeys: List<String>): List<MemoryFactAuditEntity> =
            factAudits.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.factKey in factKeys && it.status == "PENDING" }
        override suspend fun getAllFactAudits(ownerType: String, ownerId: String): List<MemoryFactAuditEntity> =
            factAudits.values.filter { it.ownerType == ownerType && it.ownerId == ownerId }
        override suspend fun getAllPendingFactAudits(): List<MemoryFactAuditEntity> =
            factAudits.values.filter { it.status == "PENDING" }
        override suspend fun updateFactAuditStatus(id: String, status: String, resolvedAt: Long?, resolvedBy: String?) {
            factAudits[id]?.let { factAudits[id] = it.copy(status = status, resolvedAt = resolvedAt, resolvedBy = resolvedBy) }
        }
        override suspend fun deleteFactAuditById(id: String) {
            factAudits.remove(id)
        }

        val experiences = mutableMapOf<Long, MemoryExperienceCoordinateEntity>()
        var experienceIdSeq = 1L
        val eProposals = mutableMapOf<Long, MemoryEProposalEntity>()
        var eProposalIdSeq = 1L

        override suspend fun insertExperienceCoordinate(coord: MemoryExperienceCoordinateEntity): Long {
            val id = if (coord.id == 0L) experienceIdSeq++ else coord.id
            experiences[id] = coord.copy(id = id)
            return id
        }
        override suspend fun insertExperienceCoordinates(coords: List<MemoryExperienceCoordinateEntity>): List<Long> = coords.map { insertExperienceCoordinate(it) }
        override suspend fun getCurrentExperienceForCard(cardId: String): MemoryExperienceCoordinateEntity? =
            experiences.values.firstOrNull { it.memoryCardId == cardId && it.status == "CURRENT" }
        override suspend fun getCurrentExperiencesForCards(cardIds: List<String>): List<MemoryExperienceCoordinateEntity> =
            experiences.values.filter { it.memoryCardId in cardIds && it.status == "CURRENT" }
        override suspend fun getCurrentExperiencesForOwner(ownerType: String, ownerId: String): List<MemoryExperienceCoordinateEntity> =
            experiences.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == "CURRENT" }.sortedByDescending { it.eInitialPriority }
        override suspend fun getExperiencesByCardId(cardId: String): List<MemoryExperienceCoordinateEntity> =
            experiences.values.filter { it.memoryCardId == cardId }.sortedByDescending { it.createdAt }
        override suspend fun updateExperienceStatus(id: Long, status: String, supersededBy: Long?) {
            experiences[id]?.let { experiences[id] = it.copy(status = status, supersededBy = supersededBy) }
        }
        override suspend fun deleteExperienceByCardId(cardId: String) {
            experiences.values.filter { it.memoryCardId == cardId }.map { it.id }.forEach { experiences.remove(it) }
        }
        override suspend fun insertEProposal(proposal: MemoryEProposalEntity): Long {
            val id = if (proposal.id == 0L) eProposalIdSeq++ else proposal.id
            eProposals[id] = proposal.copy(id = id)
            return id
        }
        override suspend fun insertEProposals(proposals: List<MemoryEProposalEntity>): List<Long> = proposals.map { insertEProposal(it) }
        override suspend fun getPendingEProposals(ownerType: String, ownerId: String): List<MemoryEProposalEntity> =
            eProposals.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == "pending" }.sortedByDescending { it.createdAt }
        override fun getPendingEProposalsFlow(ownerType: String, ownerId: String) =
            kotlinx.coroutines.flow.flowOf(eProposals.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == "pending" }.sortedByDescending { it.createdAt })
        override suspend fun getEProposalById(id: Long): MemoryEProposalEntity? = eProposals[id]
        override suspend fun updateEProposalStatus(id: Long, status: String, reviewedAt: Long) {
            eProposals[id]?.let { eProposals[id] = it.copy(status = status, reviewedAt = reviewedAt) }
        }
        override suspend fun deleteEProposalById(id: Long) { eProposals.remove(id) }

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

    private val fakeDao = FakeDao()
    private val repo = object : MemoryRepositoryV1(database = null, dao = fakeDao) {}

    @Test
    fun `test fact conflict audit creates pending audit without mutating cards`() = runBlocking {
        val ownerId = "asst-1"
        val factKey = "profile.city"

        // 创建较旧的事实卡片 (updatedAt = 1000)
        val oldCard = MemoryCardEntity(
            id = "card-old",
            ownerType = "assistant",
            ownerId = ownerId,
            content = "小雨目前生活在上海",
            category = "profile",
            factKey = factKey,
            status = "ACTIVE",
            confidence = 1.0f,
            importance = 0.8f,
            createdAt = 1000L,
            updatedAt = 1000L,
            revision = 1,
            createdBy = "test",
            updatedBy = "test",
        )
        fakeDao.cards[oldCard.id] = oldCard

        // 创建更新的事实卡片 (updatedAt = 5000)
        val newCard = MemoryCardEntity(
            id = "card-new",
            ownerType = "assistant",
            ownerId = ownerId,
            content = "小雨搬家去北京了",
            category = "profile",
            factKey = factKey,
            status = "ACTIVE",
            confidence = 1.0f,
            importance = 0.9f,
            createdAt = 5000L,
            updatedAt = 5000L,
            revision = 2,
            createdBy = "test",
            updatedBy = "test",
        )
        fakeDao.cards[newCard.id] = newCard

        // 执行 Z 轴事实演化审计：遵循“冲突发现与事实变更分离”原则
        val result = repo.auditFactEvolution("assistant", ownerId)

        assertEquals(1, result.auditedKeysCount)
        assertEquals(1, result.conflictedKeysCount)
        assertEquals(1, result.pendingAuditsCount)
        assertEquals(0, result.supersededCardsCount) // 绝不自动替代

        // 验证两张卡片依然都保持 ACTIVE（旧卡未被静默修改）
        assertEquals("ACTIVE", fakeDao.cards["card-old"]!!.status)
        assertEquals("ACTIVE", fakeDao.cards["card-new"]!!.status)

        // 验证生成了 PENDING 状态的事实演化审计记录
        val pendingAudits = repo.getPendingFactAudits("assistant", ownerId)
        assertEquals(1, pendingAudits.size)
        val audit = pendingAudits.first()
        assertEquals(factKey, audit.factKey)
        assertEquals("card-old", audit.existingCardId)
        assertEquals("card-new", audit.candidateCardId)
        assertEquals(MemoryConstants.AUDIT_SUGGESTION_SUPERSEDE, audit.modelSuggestion)
        assertEquals(MemoryConstants.AUDIT_STATUS_PENDING, audit.status)

        // 模拟人工在 UI 确认 SUPERSEDE 裁决
        val resolved = repo.resolveFactAudit(audit.id, MemoryConstants.AUDIT_RESOLUTION_SUPERSEDE, resolvedBy = "user")
        assertTrue(resolved)

        // 验证旧卡状态此时更新为 SUPERSEDED，且 supersededBy 指向新卡
        val updatedOldCard = fakeDao.cards["card-old"]!!
        assertEquals("SUPERSEDED", updatedOldCard.status)
        assertEquals("card-new", updatedOldCard.supersededBy)
        assertEquals(2, updatedOldCard.revision)
        assertNotNull(updatedOldCard.validTo)

        // 验证新卡依然保持 ACTIVE
        val keptNewCard = fakeDao.cards["card-new"]!!
        assertEquals("ACTIVE", keptNewCard.status)

        // 验证建立了演化关系边：card-new -> SUPERSEDES -> card-old
        val relations = fakeDao.relations.filter { it.relationType == "SUPERSEDES" }
        assertEquals(1, relations.size)
        assertEquals("card-new", relations[0].sourceCardId)
        assertEquals("card-old", relations[0].targetCardId)

        // 验证记录了修订历史
        val rev = fakeDao.revisions.find { it.memoryId == "card-old" && it.operation == "SUPERSEDE" }
        assertNotNull(rev)

        // 验证审计记录状态更新为 RESOLVED_SUPERSEDE
        val updatedAudit = fakeDao.factAudits[audit.id]!!
        assertEquals(MemoryConstants.AUDIT_STATUS_RESOLVED_SUPERSEDE, updatedAudit.status)
        assertEquals("user", updatedAudit.resolvedBy)
    }

    @Test
    fun `test fact audit resolve keep both clears candidate factKey`() = runBlocking {
        val ownerId = "asst-1"
        val factKey = "pref.music"

        val card1 = MemoryCardEntity(
            id = "c1",
            ownerType = "assistant",
            ownerId = ownerId,
            content = "小雨喜欢古典音乐",
            category = "preference",
            factKey = factKey,
            status = "ACTIVE",
            createdAt = 1000L,
            updatedAt = 1000L,
            createdBy = "test",
            updatedBy = "test",
        )
        val card2 = MemoryCardEntity(
            id = "c2",
            ownerType = "assistant",
            ownerId = ownerId,
            content = "小雨也喜欢摇滚音乐",
            category = "preference",
            factKey = factKey,
            status = "ACTIVE",
            createdAt = 2000L,
            updatedAt = 2000L,
            createdBy = "test",
            updatedBy = "test",
        )
        fakeDao.cards[card1.id] = card1
        fakeDao.cards[card2.id] = card2

        val result = repo.auditFactEvolution("assistant", ownerId)
        assertEquals(1, result.pendingAuditsCount)
        val audit = result.pendingAudits.first()

        val resolved = repo.resolveFactAudit(audit.id, MemoryConstants.AUDIT_RESOLUTION_KEEP_BOTH, resolvedBy = "user")
        assertTrue(resolved)

        assertEquals("ACTIVE", fakeDao.cards["c1"]!!.status)
        assertEquals(factKey, fakeDao.cards["c1"]!!.factKey)

        assertEquals("ACTIVE", fakeDao.cards["c2"]!!.status)
        // c2 作为 candidate，factKey 被清空以允许共存
        assertEquals(null, fakeDao.cards["c2"]!!.factKey)
        assertEquals(MemoryConstants.AUDIT_STATUS_RESOLVED_KEEP_BOTH, fakeDao.factAudits[audit.id]!!.status)
    }

    @Test
    fun `test fact audit resolve reject archives candidate card`() = runBlocking {
        val ownerId = "asst-1"
        val factKey = "profile.car"

        val card1 = MemoryCardEntity(
            id = "c1",
            ownerType = "assistant",
            ownerId = ownerId,
            content = "小雨开白色轿车",
            category = "profile",
            factKey = factKey,
            status = "ACTIVE",
            createdAt = 1000L,
            updatedAt = 1000L,
            createdBy = "test",
            updatedBy = "test",
        )
        val card2 = MemoryCardEntity(
            id = "c2",
            ownerType = "assistant",
            ownerId = ownerId,
            content = "小雨开红色跑车",
            category = "profile",
            factKey = factKey,
            status = "ACTIVE",
            createdAt = 2000L,
            updatedAt = 2000L,
            createdBy = "test",
            updatedBy = "test",
        )
        fakeDao.cards[card1.id] = card1
        fakeDao.cards[card2.id] = card2

        val result = repo.auditFactEvolution("assistant", ownerId)
        val audit = result.pendingAudits.first()

        val resolved = repo.resolveFactAudit(audit.id, MemoryConstants.AUDIT_RESOLUTION_REJECT, resolvedBy = "user")
        assertTrue(resolved)

        assertEquals("ACTIVE", fakeDao.cards["c1"]!!.status)
        assertEquals("ARCHIVED", fakeDao.cards["c2"]!!.status)
        assertEquals(MemoryConstants.AUDIT_STATUS_REJECTED, fakeDao.factAudits[audit.id]!!.status)
    }

    @Test
    fun `test fact evolution audit leaves unique facts unchanged`() = runBlocking {
        val ownerId = "asst-1"

        val card1 = MemoryCardEntity(
            id = "card-1",
            ownerType = "assistant",
            ownerId = ownerId,
            content = "小雨喜欢苹果",
            category = "preference",
            factKey = "pref.fruit",
            status = "ACTIVE",
            confidence = 1.0f,
            importance = 0.5f,
            createdAt = 1000L,
            updatedAt = 1000L,
            revision = 1,
            createdBy = "test",
            updatedBy = "test",
        )
        val card2 = MemoryCardEntity(
            id = "card-2",
            ownerType = "assistant",
            ownerId = ownerId,
            content = "小雨不喜欢香菜",
            category = "preference",
            factKey = "pref.herb",
            status = "ACTIVE",
            confidence = 1.0f,
            importance = 0.5f,
            createdAt = 1000L,
            updatedAt = 1000L,
            revision = 1,
            createdBy = "test",
            updatedBy = "test",
        )
        fakeDao.cards[card1.id] = card1
        fakeDao.cards[card2.id] = card2

        val result = repo.auditFactEvolution("assistant", ownerId)
        assertEquals(2, result.auditedKeysCount)
        assertEquals(0, result.conflictedKeysCount)
        assertEquals(0, result.supersededCardsCount)
        assertEquals(0, result.pendingAuditsCount)
        assertEquals("ACTIVE", fakeDao.cards["card-1"]?.status)
        assertEquals("ACTIVE", fakeDao.cards["card-2"]?.status)
    }

    @Test
    fun `test fact audit rejects resolution when card was edited after audit creation`() = runBlocking {
        val ownerId = "asst-1"
        val factKey = "pref.city"

        // 步骤 1: 产生旧卡与新卡冲突
        val cardOld = MemoryCardEntity(
            id = "card-old-edited",
            ownerType = "assistant",
            ownerId = ownerId,
            content = "用户住在北京",
            category = "preference",
            factKey = factKey,
            status = "ACTIVE",
            confidence = 1.0f,
            importance = 0.8f,
            createdAt = 1000L,
            updatedAt = 1000L,
            revision = 1,
            createdBy = "user",
            updatedBy = "user",
        )
        val cardNew = MemoryCardEntity(
            id = "card-new-city",
            ownerType = "assistant",
            ownerId = ownerId,
            content = "用户住在上海",
            category = "preference",
            factKey = factKey,
            status = "ACTIVE",
            confidence = 1.0f,
            importance = 0.8f,
            createdAt = 1500L,
            updatedAt = 1500L,
            revision = 1,
            createdBy = "user",
            updatedBy = "user",
        )
        fakeDao.cards[cardOld.id] = cardOld
        fakeDao.cards[cardNew.id] = cardNew

        // 模拟检测冲突并创建未决审核 (createdAt = 2000L)
        val audit = MemoryFactAuditEntity(
            id = "audit-expired-test",
            ownerType = "assistant",
            ownerId = ownerId,
            factKey = factKey,
            existingCardId = cardOld.id,
            candidateCardId = cardNew.id,
            candidateContent = cardNew.content,
            modelSuggestion = MemoryConstants.AUDIT_SUGGESTION_SUPERSEDE,
            reason = "冲突检测",
            status = MemoryConstants.AUDIT_STATUS_PENDING,
            createdAt = 2000L,
        )
        fakeDao.factAudits[audit.id] = audit

        // 步骤 2: 模拟用户在审核创建之后，将旧卡修改为“用户住在杭州”，updatedAt 变为 3000L (> audit.createdAt)
        val editedOldCard = cardOld.copy(
            content = "用户住在杭州",
            updatedAt = 3000L,
            revision = 2,
        )
        fakeDao.cards[cardOld.id] = editedOldCard

        // 步骤 3: 尝试执行过期决议 SUPERSEDE
        val resolved = repo.resolveFactAudit(audit.id, MemoryConstants.AUDIT_RESOLUTION_SUPERSEDE, resolvedBy = "user")

        // 人工确认以当前卡片为准，不因审核创建后发生编辑而拦截
        assertTrue("Manual resolution must use the current card state", resolved)

        // 当前卡片被人工确认的候选替代
        val survivingOldCard = fakeDao.cards[cardOld.id]!!
        assertEquals("SUPERSEDED", survivingOldCard.status)
        assertEquals("用户住在杭州", survivingOldCard.content)
        assertEquals(3, survivingOldCard.revision)
        assertEquals(cardNew.id, survivingOldCard.supersededBy)

        val resolvedAudit = fakeDao.factAudits[audit.id]!!
        assertEquals(MemoryConstants.AUDIT_STATUS_RESOLVED_SUPERSEDE, resolvedAudit.status)
    }

    @Test
    fun `test sweepObsoletePendingAudits cleans up dangling audits and archives orphan review cards`() = runBlocking {
        val ownerId = "asst-sweep"
        val factKey1 = "user.city"
        val factKey2 = "user.food"

        // 场景 1: existingCard 已经被置为 SUPERSEDED，candidateCard 仍是 REVIEW
        val cardSuperseded = MemoryCardEntity(
            id = "c-superseded",
            ownerType = "assistant",
            ownerId = ownerId,
            content = "住在北京",
            category = "profile",
            factKey = factKey1,
            status = "SUPERSEDED",
            confidence = 1.0f,
            importance = 0.8f,
            createdAt = 1000L,
            updatedAt = 2000L,
            revision = 2,
            createdBy = "test",
            updatedBy = "test",
        )
        val candReview1 = MemoryCardEntity(
            id = "c-review-1",
            ownerType = "assistant",
            ownerId = ownerId,
            content = "住在上海",
            category = "profile",
            factKey = factKey1,
            status = "REVIEW",
            confidence = 0.6f,
            importance = 0.8f,
            createdAt = 2500L,
            updatedAt = 2500L,
            revision = 1,
            createdBy = "test",
            updatedBy = "test",
        )
        fakeDao.cards[cardSuperseded.id] = cardSuperseded
        fakeDao.cards[candReview1.id] = candReview1

        val danglingAudit1 = MemoryFactAuditEntity(
            id = "audit-dangling-1",
            ownerType = "assistant",
            ownerId = ownerId,
            factKey = factKey1,
            existingCardId = cardSuperseded.id,
            candidateCardId = candReview1.id,
            candidateContent = candReview1.content,
            modelSuggestion = MemoryConstants.AUDIT_SUGGESTION_SUPERSEDE,
            reason = "历史冲突",
            status = MemoryConstants.AUDIT_STATUS_PENDING,
            createdAt = 3000L,
        )
        fakeDao.factAudits[danglingAudit1.id] = danglingAudit1

        // 场景 2: existingCard 已经被 ARCHIVED，candidateCardId 为 null
        val cardArchived = MemoryCardEntity(
            id = "c-archived",
            ownerType = "assistant",
            ownerId = ownerId,
            content = "喜欢吃面",
            category = "preference",
            factKey = factKey2,
            status = "ARCHIVED",
            confidence = 1.0f,
            importance = 0.5f,
            createdAt = 1000L,
            updatedAt = 2000L,
            revision = 2,
            createdBy = "test",
            updatedBy = "test",
        )
        fakeDao.cards[cardArchived.id] = cardArchived
        val danglingAudit2 = MemoryFactAuditEntity(
            id = "audit-dangling-2",
            ownerType = "assistant",
            ownerId = ownerId,
            factKey = factKey2,
            existingCardId = cardArchived.id,
            candidateCardId = null,
            candidateContent = "喜欢吃米饭",
            modelSuggestion = MemoryConstants.AUDIT_SUGGESTION_SUPERSEDE,
            reason = "历史冲突2",
            status = MemoryConstants.AUDIT_STATUS_PENDING,
            createdAt = 3000L,
        )
        fakeDao.factAudits[danglingAudit2.id] = danglingAudit2

        // 场景 3: 正常的活跃审计（existingCard 是 ACTIVE），不应被清扫
        val cardActive = MemoryCardEntity(
            id = "c-active",
            ownerType = "assistant",
            ownerId = ownerId,
            content = "喜欢看书",
            category = "preference",
            factKey = "user.hobby",
            status = "ACTIVE",
            confidence = 1.0f,
            importance = 0.5f,
            createdAt = 1000L,
            updatedAt = 1000L,
            revision = 1,
            createdBy = "test",
            updatedBy = "test",
        )
        fakeDao.cards[cardActive.id] = cardActive
        val validAudit = MemoryFactAuditEntity(
            id = "audit-valid",
            ownerType = "assistant",
            ownerId = ownerId,
            factKey = "user.hobby",
            existingCardId = cardActive.id,
            candidateCardId = null,
            candidateContent = "喜欢看电影",
            modelSuggestion = MemoryConstants.AUDIT_SUGGESTION_SUPERSEDE,
            reason = "当前活跃冲突",
            status = MemoryConstants.AUDIT_STATUS_PENDING,
            createdAt = 3000L,
        )
        fakeDao.factAudits[validAudit.id] = validAudit

        // 执行清扫
        val swept = repo.sweepObsoletePendingAudits(ownerType = "assistant", ownerId = ownerId)
        assertEquals(2, swept)

        // 验证 danglingAudit1 已被关闭为 REJECTED
        val auditAfter1 = fakeDao.factAudits[danglingAudit1.id]!!
        assertEquals(MemoryConstants.AUDIT_STATUS_REJECTED, auditAfter1.status)
        assertTrue(auditAfter1.reason.contains("Auto-closed"))
        assertEquals(MemoryConstants.ACTOR_SYSTEM, auditAfter1.resolvedBy)

        // 验证与其关联的独立 candidateCard (c-review-1) 已被归档为 ARCHIVED 并有修订历史
        val candCardAfter1 = fakeDao.cards[candReview1.id]!!
        assertEquals("ARCHIVED", candCardAfter1.status)
        assertEquals(2, candCardAfter1.revision)
        assertNotNull(candCardAfter1.validTo)
        val candRev = fakeDao.revisions.find { it.memoryId == candReview1.id && it.operation == "ARCHIVE" }
        assertNotNull(candRev)

        // 验证 danglingAudit2 已被关闭为 REJECTED
        val auditAfter2 = fakeDao.factAudits[danglingAudit2.id]!!
        assertEquals(MemoryConstants.AUDIT_STATUS_REJECTED, auditAfter2.status)

        // 验证正常的 validAudit 依然维持 PENDING 状态
        val validAuditAfter = fakeDao.factAudits[validAudit.id]!!
        assertEquals(MemoryConstants.AUDIT_STATUS_PENDING, validAuditAfter.status)
    }

    @Test
    fun `test resolveFactAudit gracefully closes dangling audit instead of hanging in PENDING`() = runBlocking {
        val ownerId = "asst-graceful"
        val factKey = "user.work"

        // existingCard 为 SUPERSEDED
        val cardSuperseded = MemoryCardEntity(
            id = "c-sup-work",
            ownerType = "assistant",
            ownerId = ownerId,
            content = "工程师",
            category = "profile",
            factKey = factKey,
            status = "SUPERSEDED",
            confidence = 1.0f,
            importance = 0.8f,
            createdAt = 1000L,
            updatedAt = 2000L,
            revision = 2,
            createdBy = "test",
            updatedBy = "test",
        )
        val candReview = MemoryCardEntity(
            id = "c-rev-work",
            ownerType = "assistant",
            ownerId = ownerId,
            content = "架构师",
            category = "profile",
            factKey = factKey,
            status = "REVIEW",
            confidence = 0.6f,
            importance = 0.8f,
            createdAt = 2500L,
            updatedAt = 2500L,
            revision = 1,
            createdBy = "test",
            updatedBy = "test",
        )
        fakeDao.cards[cardSuperseded.id] = cardSuperseded
        fakeDao.cards[candReview.id] = candReview

        val audit = MemoryFactAuditEntity(
            id = "audit-dangling-resolve",
            ownerType = "assistant",
            ownerId = ownerId,
            factKey = factKey,
            existingCardId = cardSuperseded.id,
            candidateCardId = candReview.id,
            candidateContent = candReview.content,
            modelSuggestion = MemoryConstants.AUDIT_SUGGESTION_SUPERSEDE,
            reason = "存量冲突",
            status = MemoryConstants.AUDIT_STATUS_PENDING,
            createdAt = 3000L,
        )
        fakeDao.factAudits[audit.id] = audit

        // 用户在 UI 点击解决该悬空审计：应当自愈关闭并返回 true，而不是返回 false 导致永久卡死
        val success = repo.resolveFactAudit(audit.id, MemoryConstants.AUDIT_RESOLUTION_REJECT, resolvedBy = "user")
        assertTrue("针对悬空审计的解决操作必须成功自愈关闭", success)

        val updatedAudit = fakeDao.factAudits[audit.id]!!
        assertEquals(MemoryConstants.AUDIT_STATUS_REJECTED, updatedAudit.status)
        assertEquals("user", updatedAudit.resolvedBy)

        // 独立候选卡自动归档
        assertEquals("ARCHIVED", fakeDao.cards[candReview.id]!!.status)
    }

    @Test
    fun `test dangling audit cleanup is completely idempotent`() = runBlocking {
        val ownerId = "asst-idempotent"
        val factKey = "user.lang"

        val audit = MemoryFactAuditEntity(
            id = "audit-non-existent",
            ownerType = "assistant",
            ownerId = ownerId,
            factKey = factKey,
            existingCardId = "non-existent-card-id",
            candidateCardId = null,
            candidateContent = "Kotlin",
            modelSuggestion = MemoryConstants.AUDIT_SUGGESTION_SUPERSEDE,
            reason = "卡片已丢失的悬空审计",
            status = MemoryConstants.AUDIT_STATUS_PENDING,
            createdAt = 1000L,
        )
        fakeDao.factAudits[audit.id] = audit

        // 第一次清扫：成功清理 1 条
        val count1 = repo.sweepObsoletePendingAudits(ownerType = "assistant", ownerId = ownerId)
        assertEquals(1, count1)
        assertEquals(MemoryConstants.AUDIT_STATUS_REJECTED, fakeDao.factAudits[audit.id]!!.status)

        // 第二次清扫：幂等安全，清理 0 条
        val count2 = repo.sweepObsoletePendingAudits(ownerType = "assistant", ownerId = ownerId)
        assertEquals(0, count2)
    }
}
