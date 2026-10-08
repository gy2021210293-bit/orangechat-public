/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryV1Dao {

    // --- Memory Cards ---

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCard(card: MemoryCardEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCards(cards: List<MemoryCardEntity>)

    @Update
    suspend fun updateCard(card: MemoryCardEntity)

    @Query("SELECT * FROM memory_cards WHERE id = :id")
    suspend fun getCardById(id: String): MemoryCardEntity?

    @Query("SELECT * FROM memory_cards WHERE id IN (:ids)")
    suspend fun getCardsByIds(ids: List<String>): List<MemoryCardEntity>

    @Query("SELECT * FROM memory_cards WHERE id IN (:ids)")
    fun getCardsByIdsFlow(ids: List<String>): Flow<List<MemoryCardEntity>>

    @Query("SELECT * FROM memory_cards WHERE owner_type = :ownerType AND owner_id = :ownerId ORDER BY updated_at DESC")
    suspend fun getAllCards(ownerType: String, ownerId: String): List<MemoryCardEntity>

    @Query("SELECT * FROM memory_cards WHERE owner_type = :ownerType AND owner_id = :ownerId ORDER BY updated_at DESC")
    fun getAllCardsFlow(ownerType: String, ownerId: String): Flow<List<MemoryCardEntity>>

    @Query("SELECT * FROM memory_cards WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = :status ORDER BY updated_at DESC")
    suspend fun getCardsByStatus(ownerType: String, ownerId: String, status: String): List<MemoryCardEntity>

    @Query("SELECT * FROM memory_cards WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = :status ORDER BY updated_at DESC")
    fun getCardsByStatusFlow(ownerType: String, ownerId: String, status: String): Flow<List<MemoryCardEntity>>

    @Query("SELECT * FROM memory_cards WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = :status ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getCardsByStatusPaged(
        ownerType: String,
        ownerId: String,
        status: String,
        limit: Int,
        offset: Int,
    ): List<MemoryCardEntity>

    @Query("SELECT COUNT(*) FROM memory_cards WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = :status")
    suspend fun countCardsByStatus(
        ownerType: String,
        ownerId: String,
        status: String,
    ): Int

    @Query("SELECT * FROM memory_cards WHERE owner_type = :ownerType AND owner_id = :ownerId AND status IN (:statusList) ORDER BY updated_at DESC")
    suspend fun getCardsByStatusList(ownerType: String, ownerId: String, statusList: List<String>): List<MemoryCardEntity>

    @Query("SELECT * FROM memory_cards WHERE owner_type = :ownerType AND owner_id = :ownerId AND status IN (:statusList) ORDER BY updated_at DESC")
    fun getCardsByStatusListFlow(ownerType: String, ownerId: String, statusList: List<String>): Flow<List<MemoryCardEntity>>

    @Query("SELECT * FROM memory_cards WHERE owner_type = :ownerType AND owner_id = :ownerId AND fact_key = :factKey AND status = :status LIMIT 1")
    suspend fun getCardByFactKey(ownerType: String, ownerId: String, factKey: String, status: String): MemoryCardEntity?

    @Query("SELECT * FROM memory_cards WHERE owner_type = :ownerType AND owner_id = :ownerId AND fact_key = :factKey ORDER BY updated_at DESC")
    suspend fun getCardsByFactKey(ownerType: String, ownerId: String, factKey: String): List<MemoryCardEntity>

    @Query("SELECT * FROM memory_cards WHERE owner_type = :ownerType AND owner_id = :ownerId AND fact_key IS NOT NULL AND status = 'ACTIVE' ORDER BY updated_at DESC")
    suspend fun getActiveCardsWithFactKey(ownerType: String, ownerId: String): List<MemoryCardEntity>

    @Query("SELECT * FROM memory_cards WHERE owner_type = :ownerType AND owner_id = :ownerId AND fact_key IN (:factKeys) AND status = 'ACTIVE' ORDER BY updated_at DESC")
    suspend fun getActiveCardsByFactKeys(ownerType: String, ownerId: String, factKeys: List<String>): List<MemoryCardEntity>

    @Query("UPDATE memory_cards SET hit_count = :hitCount, last_recalled_at = :lastRecalledAt, heat = :heat WHERE id = :cardId")
    suspend fun updateCardMetabolism(cardId: String, hitCount: Int, lastRecalledAt: Long, heat: Float)

    @Query("UPDATE memory_cards SET is_protected = :isProtected WHERE id = :cardId")
    suspend fun updateCardProtection(cardId: String, isProtected: Boolean)

    @Query("UPDATE memory_cards SET last_reviewed_at = :lastReviewedAt WHERE id = :cardId")
    suspend fun updateCardLastReviewedAt(cardId: String, lastReviewedAt: Long)

    @Query("""
        SELECT * FROM memory_cards
        WHERE owner_type = :ownerType
          AND owner_id = :ownerId
          AND category = 'self'
          AND status = 'ACTIVE'
          AND always_inject = 1
        ORDER BY created_at ASC, id ASC
    """)
    suspend fun getAlwaysInjectSelfCards(ownerType: String, ownerId: String): List<MemoryCardEntity>

    @Query("UPDATE memory_cards SET always_inject = :alwaysInject WHERE id = :cardId")
    suspend fun updateCardAlwaysInject(cardId: String, alwaysInject: Boolean)

    @Query("SELECT * FROM memory_cards WHERE category = :category AND status = :status ORDER BY updated_at DESC")
    suspend fun getCardsByCategoryAndStatus(category: String, status: String): List<MemoryCardEntity>

    @Query("DELETE FROM memory_cards WHERE id = :id")
    suspend fun deleteCardById(id: String)

    // --- Memory Sources ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: MemorySourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSources(sources: List<MemorySourceEntity>)

    @Query("SELECT * FROM memory_sources WHERE memory_id = :memoryId ORDER BY created_at ASC")
    suspend fun getSourcesByMemoryId(memoryId: String): List<MemorySourceEntity>

    @Query("DELETE FROM memory_sources WHERE memory_id = :memoryId")
    suspend fun deleteSourcesByMemoryId(memoryId: String)

    @Query("SELECT DISTINCT memory_id FROM memory_sources WHERE memory_id IN (:memoryIds)")
    suspend fun getMemoryIdsWithSources(memoryIds: List<String>): List<String>

    @Query("SELECT * FROM memory_sources WHERE memory_id IN (:memoryIds)")
    suspend fun getSourcesForMemories(memoryIds: List<String>): List<MemorySourceEntity>

    // --- Memory Revisions ---

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(revision: MemoryRevisionEntity)

    @Query("SELECT * FROM memory_revisions WHERE memory_id = :memoryId ORDER BY revision ASC")
    suspend fun getRevisionsByMemoryId(memoryId: String): List<MemoryRevisionEntity>

    @Query("DELETE FROM memory_revisions WHERE memory_id = :memoryId")
    suspend fun deleteRevisionsByMemoryId(memoryId: String)

    // --- Memory Daily Notes ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateDailyNote(note: MemoryDailyNoteEntity)

    @Query("SELECT * FROM memory_daily_notes WHERE owner_id = :ownerId AND memory_date = :memoryDate LIMIT 1")
    suspend fun getDailyNote(ownerId: String, memoryDate: String): MemoryDailyNoteEntity?

    @Query("SELECT * FROM memory_daily_notes WHERE owner_type = :ownerType AND owner_id = :ownerId ORDER BY memory_date DESC")
    suspend fun getDailyNotes(ownerType: String, ownerId: String): List<MemoryDailyNoteEntity>

    @Query("SELECT * FROM memory_daily_notes WHERE owner_type = :ownerType AND owner_id = :ownerId ORDER BY memory_date DESC")
    fun getDailyNotesFlow(ownerType: String, ownerId: String): Flow<List<MemoryDailyNoteEntity>>

    @Query("""
        SELECT * FROM memory_daily_notes
        WHERE owner_type = :ownerType
          AND owner_id = :ownerId
          AND (:dateFrom IS NULL OR memory_date >= :dateFrom)
          AND (:dateTo IS NULL OR memory_date <= :dateTo)
          AND (
              :query IS NULL
              OR :query = ''
              OR summary LIKE '%' || :query || '%'
              OR emotional_thread LIKE '%' || :query || '%'
          )
        ORDER BY memory_date DESC
        LIMIT :limit
    """)
    suspend fun searchDailyNotes(
        ownerType: String,
        ownerId: String,
        query: String?,
        dateFrom: String?,
        dateTo: String?,
        limit: Int = 3,
    ): List<MemoryDailyNoteEntity>

    // --- Memory Consolidation Runs ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceRun(run: MemoryConsolidationRunEntity)

    @Query("SELECT * FROM memory_consolidation_runs WHERE owner_id = :ownerId AND memory_date = :memoryDate LIMIT 1")
    suspend fun getRun(ownerId: String, memoryDate: String): MemoryConsolidationRunEntity?

    @Query("SELECT * FROM memory_consolidation_runs WHERE status = 'PENDING' ORDER BY memory_date ASC")
    suspend fun getAllPendingRuns(): List<MemoryConsolidationRunEntity>

    @Query("SELECT * FROM memory_consolidation_runs WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = 'PENDING' ORDER BY memory_date ASC")
    suspend fun getPendingRuns(ownerType: String, ownerId: String): List<MemoryConsolidationRunEntity>

    @Query("SELECT * FROM memory_consolidation_runs WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = 'FAILED' AND attempt_count < :maxAttempts ORDER BY memory_date ASC")
    suspend fun getFailedRuns(ownerType: String, ownerId: String, maxAttempts: Int): List<MemoryConsolidationRunEntity>

    @Query("DELETE FROM memory_daily_notes WHERE owner_id = :ownerId AND memory_date = :memoryDate")
    suspend fun deleteDailyNote(ownerId: String, memoryDate: String)

    @Query("DELETE FROM memory_consolidation_runs WHERE owner_id = :ownerId AND memory_date = :memoryDate")
    suspend fun deleteRun(ownerId: String, memoryDate: String)

    // --- Memory Embeddings ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceEmbedding(embedding: MemoryEmbeddingEntity)

    @Query("SELECT * FROM memory_embeddings WHERE memory_id = :memoryId AND embedding_provider = :embeddingProvider AND embedding_model = :embeddingModel LIMIT 1")
    suspend fun getEmbedding(memoryId: String, embeddingProvider: String, embeddingModel: String): MemoryEmbeddingEntity?

    @Query("SELECT * FROM memory_embeddings WHERE memory_id = :memoryId ORDER BY updated_at DESC LIMIT 1")
    suspend fun getEmbeddingByMemoryId(memoryId: String): MemoryEmbeddingEntity?

    @Query("SELECT * FROM memory_embeddings WHERE content_hash = :contentHash AND embedding_model = :embeddingModel LIMIT 1")
    suspend fun getEmbeddingByContentHash(contentHash: String, embeddingModel: String): MemoryEmbeddingEntity?

    @Query("""
        SELECT e.memory_id, e.dimension, e.vector_blob
        FROM memory_embeddings e
        JOIN memory_cards c ON e.memory_id = c.id
        WHERE c.status = 'ACTIVE'
          AND e.embedding_provider = :embeddingProvider
          AND e.embedding_model = :embeddingModel
          AND (
            (e.owner_type = :ownerType AND e.owner_id = :ownerId)
            OR (:includeGlobal = 1 AND e.owner_type = 'GLOBAL')
          )
        ORDER BY e.id ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getActiveEmbeddingProjections(
        ownerType: String,
        ownerId: String,
        embeddingProvider: String,
        embeddingModel: String,
        includeGlobal: Boolean,
        limit: Int,
        offset: Int,
    ): List<MemoryEmbeddingProjection>

    @Query("DELETE FROM memory_embeddings WHERE memory_id = :memoryId AND embedding_provider = :embeddingProvider AND embedding_model = :embeddingModel")
    suspend fun deleteEmbedding(memoryId: String, embeddingProvider: String, embeddingModel: String)

    @Query("DELETE FROM memory_embeddings WHERE memory_id = :memoryId")
    suspend fun deleteEmbeddingByMemoryId(memoryId: String)

    // --- Timelines ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimeline(timeline: MemoryTimelineEntity)

    @Update
    suspend fun updateTimeline(timeline: MemoryTimelineEntity)

    @Query("SELECT * FROM memory_timelines WHERE id = :id")
    suspend fun getTimelineById(id: String): MemoryTimelineEntity?

    @Query("SELECT * FROM memory_timelines WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = :status ORDER BY updated_at DESC")
    suspend fun getTimelinesByStatus(ownerType: String, ownerId: String, status: String): List<MemoryTimelineEntity>

    @Query("SELECT * FROM memory_timelines WHERE owner_type = :ownerType AND owner_id = :ownerId AND title = :title LIMIT 1")
    suspend fun getTimelineByTitle(ownerType: String, ownerId: String, title: String): MemoryTimelineEntity?

    @Query("SELECT * FROM memory_timelines WHERE owner_type = :ownerType AND owner_id = :ownerId ORDER BY updated_at DESC")
    suspend fun getAllTimelines(ownerType: String, ownerId: String): List<MemoryTimelineEntity>

    @Query("DELETE FROM memory_timelines WHERE id = :id")
    suspend fun deleteTimelineById(id: String)

    // --- Timeline Members ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimelineMember(member: MemoryTimelineMemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimelineMembers(members: List<MemoryTimelineMemberEntity>)

    @Query("SELECT * FROM memory_timeline_members WHERE timeline_id = :timelineId ORDER BY start_time ASC, created_at ASC")
    suspend fun getTimelineMembers(timelineId: String): List<MemoryTimelineMemberEntity>

    @Query("SELECT * FROM memory_timeline_members WHERE card_id = :cardId")
    suspend fun getTimelineMembersByCardId(cardId: String): List<MemoryTimelineMemberEntity>

    @Query("DELETE FROM memory_timeline_members WHERE timeline_id = :timelineId AND card_id = :cardId")
    suspend fun deleteTimelineMember(timelineId: String, cardId: String)

    @Query("DELETE FROM memory_timeline_members WHERE timeline_id = :timelineId")
    suspend fun deleteTimelineMembersByTimeline(timelineId: String)

    @Query("DELETE FROM memory_timeline_members WHERE card_id = :cardId")
    suspend fun deleteTimelineMembersByCardId(cardId: String)

    // --- Event Chunks ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEventChunk(chunk: MemoryEventChunkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEventChunks(chunks: List<MemoryEventChunkEntity>)

    @Query("SELECT * FROM memory_event_chunks WHERE id = :id")
    suspend fun getEventChunkById(id: String): MemoryEventChunkEntity?

    @Query("SELECT * FROM memory_event_chunks WHERE owner_type = :ownerType AND owner_id = :ownerId AND start_time >= :startMs AND end_time <= :endMs ORDER BY start_time ASC")
    suspend fun getEventChunks(ownerType: String, ownerId: String, startMs: Long, endMs: Long): List<MemoryEventChunkEntity>

    // 每日重建聊天分块时保留后台原始经历证据。
    @Query("DELETE FROM memory_event_chunks WHERE owner_type = :ownerType AND owner_id = :ownerId AND start_time >= :startMs AND end_time <= :endMs AND status != 'AUTONOMOUS_SOURCE'")
    suspend fun deleteEventChunksByDateRange(ownerType: String, ownerId: String, startMs: Long, endMs: Long)

    // --- Proposals ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProposal(proposal: MemoryProposalEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProposals(proposals: List<MemoryProposalEntity>)

    @Query("SELECT * FROM memory_proposals WHERE id = :id")
    suspend fun getProposalById(id: String): MemoryProposalEntity?

    @Query("SELECT * FROM memory_proposals WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = :status ORDER BY created_at DESC")
    suspend fun getProposalsByStatus(ownerType: String, ownerId: String, status: String): List<MemoryProposalEntity>

    @Query("SELECT * FROM memory_proposals WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = :status ORDER BY created_at DESC")
    fun getProposalsByStatusFlow(ownerType: String, ownerId: String, status: String): Flow<List<MemoryProposalEntity>>

    @Query("UPDATE memory_proposals SET status = 'EXPIRED', resolved_at = :now, resolved_by = 'force_reconsolidate' WHERE owner_type = :ownerType AND owner_id = :ownerId AND memory_date = :memoryDate AND status = 'REVIEW'")
    suspend fun expirePendingProposalsByDate(ownerType: String, ownerId: String, memoryDate: String, now: Long)

    @Query("SELECT * FROM memory_proposals WHERE owner_type = :ownerType AND owner_id = :ownerId ORDER BY created_at DESC")
    suspend fun getAllProposals(ownerType: String, ownerId: String): List<MemoryProposalEntity>

    @Update
    suspend fun updateProposal(proposal: MemoryProposalEntity)

    @Query("UPDATE memory_proposals SET status = :status, resolved_at = :resolvedAt, resolved_by = :resolvedBy, card_id = :cardId WHERE id = :id")
    suspend fun updateProposalStatus(
        id: String,
        status: String,
        resolvedAt: Long?,
        resolvedBy: String? = null,
        cardId: String? = null,
    )

    @Query("DELETE FROM memory_proposals WHERE id = :id")
    suspend fun deleteProposalById(id: String)

    // --- Memory Relations ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelation(relation: MemoryRelationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelations(relations: List<MemoryRelationEntity>)

    @Query("SELECT * FROM memory_relations WHERE source_card_id = :cardId OR target_card_id = :cardId")
    suspend fun getRelationsByCardId(cardId: String): List<MemoryRelationEntity>

    @Query("SELECT * FROM memory_relations WHERE source_card_id IN (:cardIds) OR target_card_id IN (:cardIds)")
    suspend fun getRelationsByCardIds(cardIds: List<String>): List<MemoryRelationEntity>

    @Query("SELECT * FROM memory_relations WHERE owner_type = :ownerType AND owner_id = :ownerId ORDER BY created_at DESC")
    suspend fun getAllRelations(ownerType: String, ownerId: String): List<MemoryRelationEntity>

    @Query("SELECT * FROM memory_relations WHERE id = :id")
    suspend fun getRelationById(id: String): MemoryRelationEntity?

    @Query("SELECT * FROM memory_relations WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = 'REVIEW' ORDER BY created_at DESC")
    fun getPendingRelationsFlow(ownerType: String, ownerId: String): Flow<List<MemoryRelationEntity>>

    @Query("SELECT * FROM memory_relations WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = 'REVIEW' ORDER BY created_at DESC")
    suspend fun getPendingRelations(ownerType: String, ownerId: String): List<MemoryRelationEntity>

    @Query("UPDATE memory_relations SET status = :status WHERE id = :id")
    suspend fun updateRelationStatus(id: String, status: String)

    @Query("DELETE FROM memory_relations WHERE id = :id")
    suspend fun deleteRelationById(id: String)

    @Query("DELETE FROM memory_relations WHERE owner_type = :ownerType AND owner_id = :ownerId AND source = :source")
    suspend fun deleteRelationsBySource(ownerType: String, ownerId: String, source: String)

    @Query("SELECT * FROM memory_relations WHERE owner_type = :ownerType AND owner_id = :ownerId AND source_card_id = :sourceCardId AND target_card_id = :targetCardId AND relation_type = :relationType LIMIT 1")
    suspend fun findRelation(ownerType: String, ownerId: String, sourceCardId: String, targetCardId: String, relationType: String): MemoryRelationEntity?

    @Query("DELETE FROM memory_relations WHERE source_card_id = :cardId OR target_card_id = :cardId")
    suspend fun deleteRelationsByCardId(cardId: String)

    // --- Memory Maintenance Tasks ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMaintenanceTask(task: MemoryMaintenanceTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMaintenanceTasks(tasks: List<MemoryMaintenanceTaskEntity>)

    @Query("SELECT * FROM memory_maintenance_tasks WHERE id = :id")
    suspend fun getMaintenanceTaskById(id: String): MemoryMaintenanceTaskEntity?

    @Query("SELECT * FROM memory_maintenance_tasks WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = :status ORDER BY created_at DESC")
    suspend fun getMaintenanceTasksByStatus(ownerType: String, ownerId: String, status: String): List<MemoryMaintenanceTaskEntity>

    @Query("SELECT * FROM memory_maintenance_tasks WHERE owner_type = :ownerType AND owner_id = :ownerId ORDER BY created_at DESC")
    suspend fun getAllMaintenanceTasks(ownerType: String, ownerId: String): List<MemoryMaintenanceTaskEntity>

    @Query("SELECT * FROM memory_maintenance_tasks WHERE owner_type = :ownerType AND owner_id = :ownerId AND target_type = :targetType AND target_id = :targetId AND status = 'PENDING' LIMIT 1")
    suspend fun getPendingMaintenanceTaskByTarget(ownerType: String, ownerId: String, targetType: String, targetId: String): MemoryMaintenanceTaskEntity?

    @Query("SELECT * FROM memory_maintenance_tasks WHERE owner_type = :ownerType AND owner_id = :ownerId AND target_type = :targetType AND target_id = :targetId AND task_type = :taskType AND status = 'PENDING' LIMIT 1")
    suspend fun getPendingMaintenanceTaskByTargetAndType(ownerType: String, ownerId: String, targetType: String, targetId: String, taskType: String): MemoryMaintenanceTaskEntity?

    @Query("SELECT * FROM memory_maintenance_tasks WHERE owner_type = :ownerType AND owner_id = :ownerId AND target_type = :targetType AND target_id = :targetId AND task_type = :taskType ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestMaintenanceTaskByTargetAndType(ownerType: String, ownerId: String, targetType: String, targetId: String, taskType: String): MemoryMaintenanceTaskEntity?

    @Query("SELECT * FROM memory_maintenance_tasks WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = :status ORDER BY created_at DESC")
    fun getMaintenanceTasksByStatusFlow(ownerType: String, ownerId: String, status: String): Flow<List<MemoryMaintenanceTaskEntity>>

    @Query("UPDATE memory_maintenance_tasks SET status = :status, resolved_at = :resolvedAt, resolved_by = :resolvedBy WHERE id = :id")
    suspend fun updateMaintenanceTaskStatus(id: String, status: String, resolvedAt: Long?, resolvedBy: String?)

    @Query("DELETE FROM memory_maintenance_tasks WHERE id = :id")
    suspend fun deleteMaintenanceTaskById(id: String)

    @Query("""
        SELECT * FROM memory_relations
        WHERE (source_card_id IN (:cardIds) OR target_card_id IN (:cardIds))
          AND status = 'ACTIVE'
          AND relation_type IN (:safeTypes)
          AND strength >= :minStrength
          AND (valid_until IS NULL OR valid_until > :now)
    """)
    suspend fun getActiveSafeRelationsByCardIds(
        cardIds: List<String>,
        safeTypes: List<String>,
        minStrength: Float = 0.5f,
        now: Long = System.currentTimeMillis(),
    ): List<MemoryRelationEntity>

    // --- Memory Fact Audits ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFactAudit(audit: MemoryFactAuditEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFactAudits(audits: List<MemoryFactAuditEntity>)

    @Query("SELECT * FROM memory_fact_audits WHERE id = :id")
    suspend fun getFactAuditById(id: String): MemoryFactAuditEntity?

    @Query("SELECT * FROM memory_fact_audits WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = 'PENDING' ORDER BY created_at DESC")
    suspend fun getPendingFactAudits(ownerType: String, ownerId: String): List<MemoryFactAuditEntity>

    @Query("SELECT * FROM memory_fact_audits WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = :status ORDER BY created_at DESC")
    fun getFactAuditsByStatusFlow(ownerType: String, ownerId: String, status: String): Flow<List<MemoryFactAuditEntity>>

    @Update
    suspend fun updateFactAudit(audit: MemoryFactAuditEntity)

    @Query("SELECT * FROM memory_fact_audits WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = :status ORDER BY created_at DESC")
    suspend fun getFactAuditsByStatus(ownerType: String, ownerId: String, status: String): List<MemoryFactAuditEntity>

    @Query("SELECT * FROM memory_fact_audits WHERE owner_type = :ownerType AND owner_id = :ownerId AND fact_key = :factKey ORDER BY created_at DESC")
    suspend fun getFactAuditsByFactKey(ownerType: String, ownerId: String, factKey: String): List<MemoryFactAuditEntity>

    @Query("SELECT * FROM memory_fact_audits WHERE owner_type = :ownerType AND owner_id = :ownerId AND fact_key IN (:factKeys) AND status = 'PENDING'")
    suspend fun getPendingFactAuditsByFactKeys(ownerType: String, ownerId: String, factKeys: List<String>): List<MemoryFactAuditEntity>

    @Query("SELECT * FROM memory_fact_audits WHERE owner_type = :ownerType AND owner_id = :ownerId ORDER BY created_at DESC")
    suspend fun getAllFactAudits(ownerType: String, ownerId: String): List<MemoryFactAuditEntity>

    @Query("SELECT * FROM memory_fact_audits WHERE status = 'PENDING' ORDER BY created_at DESC")
    suspend fun getAllPendingFactAudits(): List<MemoryFactAuditEntity>

    @Query("UPDATE memory_fact_audits SET status = :status, resolved_at = :resolvedAt, resolved_by = :resolvedBy WHERE id = :id")
    suspend fun updateFactAuditStatus(id: String, status: String, resolvedAt: Long?, resolvedBy: String?)

    @Query("DELETE FROM memory_fact_audits WHERE id = :id")
    suspend fun deleteFactAuditById(id: String)

    // --- E-Axis Experience Coordinates (LMC-5) ---

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExperienceCoordinate(coord: MemoryExperienceCoordinateEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExperienceCoordinates(coords: List<MemoryExperienceCoordinateEntity>): List<Long>

    @Query("SELECT * FROM memory_experience_coordinates WHERE memory_card_id = :cardId AND status = 'CURRENT' LIMIT 1")
    suspend fun getCurrentExperienceForCard(cardId: String): MemoryExperienceCoordinateEntity?

    @Query("SELECT * FROM memory_experience_coordinates WHERE memory_card_id IN (:cardIds) AND status = 'CURRENT'")
    suspend fun getCurrentExperiencesForCards(cardIds: List<String>): List<MemoryExperienceCoordinateEntity>

    @Query("SELECT * FROM memory_experience_coordinates WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = 'CURRENT' ORDER BY e_initial_priority DESC")
    suspend fun getCurrentExperiencesForOwner(ownerType: String, ownerId: String): List<MemoryExperienceCoordinateEntity>

    @Query("SELECT * FROM memory_experience_coordinates WHERE memory_card_id = :cardId ORDER BY created_at DESC")
    suspend fun getExperiencesByCardId(cardId: String): List<MemoryExperienceCoordinateEntity>

    @Query("SELECT * FROM memory_experience_coordinates WHERE id = :id")
    suspend fun getExperienceById(id: Long): MemoryExperienceCoordinateEntity?

    @Query("UPDATE memory_experience_coordinates SET status = :status, superseded_by = :supersededBy WHERE id = :id")
    suspend fun updateExperienceStatus(id: Long, status: String, supersededBy: Long? = null)

    @Query("DELETE FROM memory_experience_coordinates WHERE memory_card_id = :cardId")
    suspend fun deleteExperienceByCardId(cardId: String)

    // --- E-Axis Proposals ---

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEProposal(proposal: MemoryEProposalEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEProposals(proposals: List<MemoryEProposalEntity>): List<Long>

    @Query("SELECT * FROM memory_e_proposals WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = 'pending' ORDER BY created_at DESC")
    suspend fun getPendingEProposals(ownerType: String, ownerId: String): List<MemoryEProposalEntity>

    @Query("SELECT * FROM memory_e_proposals WHERE owner_type = :ownerType AND owner_id = :ownerId AND status = 'pending' ORDER BY created_at DESC")
    fun getPendingEProposalsFlow(ownerType: String, ownerId: String): Flow<List<MemoryEProposalEntity>>

    @Query("SELECT * FROM memory_e_proposals WHERE id = :id")
    suspend fun getEProposalById(id: Long): MemoryEProposalEntity?

    @Query("UPDATE memory_e_proposals SET status = :status, reviewed_at = :reviewedAt WHERE id = :id")
    suspend fun updateEProposalStatus(id: Long, status: String, reviewedAt: Long)

    @Query("DELETE FROM memory_e_proposals WHERE id = :id")
    suspend fun deleteEProposalById(id: Long)
}

data class MemoryEmbeddingProjection(
    @androidx.room.ColumnInfo(name = "memory_id")
    val memoryId: String,
    @androidx.room.ColumnInfo(name = "dimension")
    val dimension: Int,
    @androidx.room.ColumnInfo(name = "vector_blob", typeAffinity = androidx.room.ColumnInfo.BLOB)
    val vectorBlob: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MemoryEmbeddingProjection
        if (memoryId != other.memoryId) return false
        if (dimension != other.dimension) return false
        if (!vectorBlob.contentEquals(other.vectorBlob)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = memoryId.hashCode()
        result = 31 * result + dimension
        result = 31 * result + vectorBlob.contentHashCode()
        return result
    }
}

