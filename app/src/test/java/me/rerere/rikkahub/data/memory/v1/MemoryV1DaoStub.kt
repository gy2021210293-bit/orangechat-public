/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryConsolidationRunEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryDailyNoteEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEProposalEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEmbeddingEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEmbeddingProjection
import me.rerere.rikkahub.data.memory.v1.db.MemoryEventChunkEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryFactAuditEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryMaintenanceTaskEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryProposalEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryRelationEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryRevisionEntity
import me.rerere.rikkahub.data.memory.v1.db.MemorySourceEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryTimelineEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryTimelineMemberEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryV1Dao

open class MemoryV1DaoStub : MemoryV1Dao {
    override suspend fun insertCard(card: MemoryCardEntity) {}
    override suspend fun insertCards(cards: List<MemoryCardEntity>) {}
    override suspend fun updateCard(card: MemoryCardEntity) {}
    override suspend fun getCardById(id: String): MemoryCardEntity? = null
    override suspend fun getCardsByIds(ids: List<String>): List<MemoryCardEntity> = emptyList()
    override fun getCardsByIdsFlow(ids: List<String>): Flow<List<MemoryCardEntity>> = flowOf(emptyList())
    override suspend fun getAllCards(ownerType: String, ownerId: String): List<MemoryCardEntity> = emptyList()
    override fun getAllCardsFlow(ownerType: String, ownerId: String): Flow<List<MemoryCardEntity>> = flowOf(emptyList())
    override suspend fun getCardsByStatus(ownerType: String, ownerId: String, status: String): List<MemoryCardEntity> = emptyList()
    override fun getCardsByStatusFlow(ownerType: String, ownerId: String, status: String): Flow<List<MemoryCardEntity>> = flowOf(emptyList())
    override suspend fun getCardsByStatusPaged(ownerType: String, ownerId: String, status: String, limit: Int, offset: Int): List<MemoryCardEntity> = emptyList()
    override suspend fun countCardsByStatus(ownerType: String, ownerId: String, status: String): Int = 0
    override suspend fun getCardsByStatusList(ownerType: String, ownerId: String, statusList: List<String>): List<MemoryCardEntity> = emptyList()
    override fun getCardsByStatusListFlow(ownerType: String, ownerId: String, statusList: List<String>): Flow<List<MemoryCardEntity>> = flowOf(emptyList())
    override suspend fun getCardByFactKey(ownerType: String, ownerId: String, factKey: String, status: String): MemoryCardEntity? = null
    override suspend fun getAlwaysInjectSelfCards(ownerType: String, ownerId: String): List<MemoryCardEntity> = emptyList()
    override suspend fun updateCardAlwaysInject(cardId: String, alwaysInject: Boolean) {}
    override suspend fun getCardsByCategoryAndStatus(category: String, status: String): List<MemoryCardEntity> = emptyList()
    override suspend fun getActiveCardsWithFactKey(ownerType: String, ownerId: String): List<MemoryCardEntity> = emptyList()
    override suspend fun deleteCardById(id: String) {}

    override suspend fun insertSource(source: MemorySourceEntity) {}
    override suspend fun insertSources(sources: List<MemorySourceEntity>) {}
    override suspend fun getSourcesByMemoryId(memoryId: String): List<MemorySourceEntity> = emptyList()
    override suspend fun deleteSourcesByMemoryId(memoryId: String) {}
    override suspend fun getMemoryIdsWithSources(memoryIds: List<String>): List<String> = emptyList()
    override suspend fun getSourcesForMemories(memoryIds: List<String>): List<MemorySourceEntity> = emptyList()

    override suspend fun insertRevision(revision: MemoryRevisionEntity) {}
    override suspend fun getRevisionsByMemoryId(memoryId: String): List<MemoryRevisionEntity> = emptyList()
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

    override suspend fun getCardsByFactKey(ownerType: String, ownerId: String, factKey: String): List<MemoryCardEntity> = emptyList()
    override suspend fun getActiveCardsByFactKeys(ownerType: String, ownerId: String, factKeys: List<String>): List<MemoryCardEntity> = emptyList()
    override suspend fun insertRelation(relation: MemoryRelationEntity) {}
    override suspend fun insertRelations(relations: List<MemoryRelationEntity>) {}
    override suspend fun getRelationsByCardId(cardId: String): List<MemoryRelationEntity> = emptyList()
    override suspend fun getRelationsByCardIds(cardIds: List<String>): List<MemoryRelationEntity> = emptyList()
    override suspend fun getAllRelations(ownerType: String, ownerId: String): List<MemoryRelationEntity> = emptyList()
    override suspend fun deleteRelationById(id: String) {}
    override suspend fun deleteRelationsByCardId(cardId: String) {}
    override suspend fun deleteRelationsBySource(ownerType: String, ownerId: String, source: String) {}
    override suspend fun findRelation(ownerType: String, ownerId: String, sourceCardId: String, targetCardId: String, relationType: String): MemoryRelationEntity? = null
    override suspend fun getRelationById(id: String): MemoryRelationEntity? = null
    override fun getPendingRelationsFlow(ownerType: String, ownerId: String): Flow<List<MemoryRelationEntity>> = flowOf(emptyList())
    override suspend fun getPendingRelations(ownerType: String, ownerId: String): List<MemoryRelationEntity> = emptyList()
    override suspend fun updateRelationStatus(id: String, status: String) {}
    override suspend fun getActiveSafeRelationsByCardIds(cardIds: List<String>, safeTypes: List<String>, minStrength: Float, now: Long): List<MemoryRelationEntity> = emptyList()

    override suspend fun updateCardMetabolism(cardId: String, hitCount: Int, lastRecalledAt: Long, heat: Float) {}
    override suspend fun updateCardProtection(cardId: String, isProtected: Boolean) {}
    override suspend fun updateCardLastReviewedAt(cardId: String, lastReviewedAt: Long) {}

    override suspend fun insertMaintenanceTask(task: MemoryMaintenanceTaskEntity) {}
    override suspend fun insertMaintenanceTasks(tasks: List<MemoryMaintenanceTaskEntity>) {}
    override suspend fun getMaintenanceTaskById(id: String): MemoryMaintenanceTaskEntity? = null
    override suspend fun getMaintenanceTasksByStatus(ownerType: String, ownerId: String, status: String): List<MemoryMaintenanceTaskEntity> = emptyList()
    override suspend fun getAllMaintenanceTasks(ownerType: String, ownerId: String): List<MemoryMaintenanceTaskEntity> = emptyList()
    override suspend fun getPendingMaintenanceTaskByTarget(ownerType: String, ownerId: String, targetType: String, targetId: String): MemoryMaintenanceTaskEntity? = null
    override suspend fun getPendingMaintenanceTaskByTargetAndType(ownerType: String, ownerId: String, targetType: String, targetId: String, taskType: String): MemoryMaintenanceTaskEntity? = null
    override suspend fun getLatestMaintenanceTaskByTargetAndType(ownerType: String, ownerId: String, targetType: String, targetId: String, taskType: String): MemoryMaintenanceTaskEntity? = null
    override fun getMaintenanceTasksByStatusFlow(ownerType: String, ownerId: String, status: String): Flow<List<MemoryMaintenanceTaskEntity>> = flowOf(emptyList())
    override suspend fun updateMaintenanceTaskStatus(id: String, status: String, resolvedAt: Long?, resolvedBy: String?) {}
    override suspend fun deleteMaintenanceTaskById(id: String) {}

    override suspend fun insertFactAudit(audit: MemoryFactAuditEntity) {}
    override suspend fun getPendingFactAudits(ownerType: String, ownerId: String): List<MemoryFactAuditEntity> = emptyList()
    override fun getFactAuditsByStatusFlow(ownerType: String, ownerId: String, status: String): Flow<List<MemoryFactAuditEntity>> = flowOf(emptyList())
    override suspend fun getFactAuditById(id: String): MemoryFactAuditEntity? = null
    override suspend fun updateFactAudit(audit: MemoryFactAuditEntity) {}
    override suspend fun insertFactAudits(audits: List<MemoryFactAuditEntity>) {}
    override suspend fun getFactAuditsByStatus(ownerType: String, ownerId: String, status: String): List<MemoryFactAuditEntity> = emptyList()
    override suspend fun getFactAuditsByFactKey(ownerType: String, ownerId: String, factKey: String): List<MemoryFactAuditEntity> = emptyList()
    override suspend fun getPendingFactAuditsByFactKeys(ownerType: String, ownerId: String, factKeys: List<String>): List<MemoryFactAuditEntity> = emptyList()
    override suspend fun getAllFactAudits(ownerType: String, ownerId: String): List<MemoryFactAuditEntity> = emptyList()
    override suspend fun getAllPendingFactAudits(): List<MemoryFactAuditEntity> = emptyList()
    override suspend fun updateFactAuditStatus(id: String, status: String, resolvedAt: Long?, resolvedBy: String?) {}
    override suspend fun deleteFactAuditById(id: String) {}

    // E-Axis (LMC-5)
    override suspend fun insertExperienceCoordinate(coord: MemoryExperienceCoordinateEntity): Long = 0L
    override suspend fun insertExperienceCoordinates(coords: List<MemoryExperienceCoordinateEntity>): List<Long> = emptyList()
    override suspend fun getCurrentExperienceForCard(cardId: String): MemoryExperienceCoordinateEntity? = null
    override suspend fun getCurrentExperiencesForCards(cardIds: List<String>): List<MemoryExperienceCoordinateEntity> = emptyList()
    override suspend fun getCurrentExperiencesForOwner(ownerType: String, ownerId: String): List<MemoryExperienceCoordinateEntity> = emptyList()
    override suspend fun getExperiencesByCardId(cardId: String): List<MemoryExperienceCoordinateEntity> = emptyList()
    override suspend fun getExperienceById(id: Long): MemoryExperienceCoordinateEntity? = null
    override suspend fun updateExperienceStatus(id: Long, status: String, supersededBy: Long?) {}
    override suspend fun deleteExperienceByCardId(cardId: String) {}
    override suspend fun insertEProposal(proposal: MemoryEProposalEntity): Long = 0L
    override suspend fun insertEProposals(proposals: List<MemoryEProposalEntity>): List<Long> = emptyList()
    override suspend fun getPendingEProposals(ownerType: String, ownerId: String): List<MemoryEProposalEntity> = emptyList()
    override fun getPendingEProposalsFlow(ownerType: String, ownerId: String): Flow<List<MemoryEProposalEntity>> = flowOf(emptyList())
    override suspend fun getEProposalById(id: Long): MemoryEProposalEntity? = null
    override suspend fun updateEProposalStatus(id: Long, status: String, reviewedAt: Long) {}
    override suspend fun deleteEProposalById(id: Long) {}
}
