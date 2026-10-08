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
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMetabolismInspectorTest {

    private class FakeDao : MemoryV1DaoStub() {
        val cards = mutableMapOf<String, MemoryCardEntity>()
        val proposals = mutableMapOf<String, MemoryProposalEntity>()
        val timelines = mutableMapOf<String, MemoryTimelineEntity>()
        val timelineMembers = mutableListOf<MemoryTimelineMemberEntity>()
        val maintenanceTasks = mutableMapOf<String, MemoryMaintenanceTaskEntity>()
        val eProposals = mutableMapOf<Long, me.rerere.rikkahub.data.memory.v1.db.MemoryEProposalEntity>()
        val experiences = mutableMapOf<Long, me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity>()

        override suspend fun insertEProposal(proposal: me.rerere.rikkahub.data.memory.v1.db.MemoryEProposalEntity): Long {
            val id = (eProposals.keys.maxOrNull() ?: 0L) + 1L
            eProposals[id] = proposal.copy(id = id)
            return id
        }

        override suspend fun getPendingEProposals(
            ownerType: String,
            ownerId: String,
        ): List<me.rerere.rikkahub.data.memory.v1.db.MemoryEProposalEntity> =
            eProposals.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == me.rerere.rikkahub.data.memory.v1.db.MemoryEProposalEntity.STATUS_PENDING }

        override suspend fun insertExperienceCoordinate(coord: me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity): Long {
            val id = (experiences.keys.maxOrNull() ?: 0L) + 1L
            experiences[id] = coord.copy(id = id)
            return id
        }

        override suspend fun getCurrentExperiencesForOwner(
            ownerType: String,
            ownerId: String,
        ): List<me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity> =
            experiences.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == "CURRENT" }

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

        override suspend fun insertEventChunk(chunk: MemoryEventChunkEntity) {}
        override suspend fun insertEventChunks(chunks: List<MemoryEventChunkEntity>) {}
        override suspend fun getEventChunkById(id: String): MemoryEventChunkEntity? = null
        override suspend fun getEventChunks(ownerType: String, ownerId: String, startMs: Long, endMs: Long): List<MemoryEventChunkEntity> = emptyList()
        override suspend fun deleteEventChunksByDateRange(ownerType: String, ownerId: String, startMs: Long, endMs: Long) {}

        override suspend fun insertProposal(proposal: MemoryProposalEntity) { proposals[proposal.id] = proposal }
        override suspend fun insertProposals(proposals: List<MemoryProposalEntity>) { proposals.forEach { insertProposal(it) } }
        override suspend fun getProposalById(id: String): MemoryProposalEntity? = proposals[id]
        override suspend fun getProposalsByStatus(ownerType: String, ownerId: String, status: String): List<MemoryProposalEntity> = proposals.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }
        override fun getProposalsByStatusFlow(ownerType: String, ownerId: String, status: String): Flow<List<MemoryProposalEntity>> = flowOf(proposals.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status })
        override suspend fun expirePendingProposalsByDate(ownerType: String, ownerId: String, memoryDate: String, now: Long) {}
        override suspend fun getAllProposals(ownerType: String, ownerId: String): List<MemoryProposalEntity> = proposals.values.filter { it.ownerType == ownerType && it.ownerId == ownerId }
        override suspend fun updateProposal(proposal: MemoryProposalEntity) { proposals[proposal.id] = proposal }
        override suspend fun updateProposalStatus(id: String, status: String, resolvedAt: Long?, resolvedBy: String?, cardId: String?) {
            proposals[id]?.let { proposals[id] = it.copy(status = status, resolvedAt = resolvedAt, resolvedBy = resolvedBy, cardId = cardId) }
        }
        override suspend fun deleteProposalById(id: String) { proposals.remove(id) }

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

        override suspend fun insertMaintenanceTask(task: MemoryMaintenanceTaskEntity) { maintenanceTasks[task.id] = task }
        override suspend fun insertMaintenanceTasks(tasks: List<MemoryMaintenanceTaskEntity>) { tasks.forEach { insertMaintenanceTask(it) } }
        override suspend fun getMaintenanceTaskById(id: String): MemoryMaintenanceTaskEntity? = maintenanceTasks[id]
        override suspend fun getMaintenanceTasksByStatus(ownerType: String, ownerId: String, status: String): List<MemoryMaintenanceTaskEntity> = maintenanceTasks.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }
        override suspend fun getAllMaintenanceTasks(ownerType: String, ownerId: String): List<MemoryMaintenanceTaskEntity> = maintenanceTasks.values.filter { it.ownerType == ownerType && it.ownerId == ownerId }
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
            maintenanceTasks[id]?.let { maintenanceTasks[id] = it.copy(status = status, resolvedAt = resolvedAt, resolvedBy = resolvedBy) }
        }
        override suspend fun deleteMaintenanceTaskById(id: String) { maintenanceTasks.remove(id) }

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
        ): List<MemoryRelationEntity> = emptyList()
    }

    private val fakeDao = FakeDao()
    private val inspector = MemoryMetabolismInspector(fakeDao)
    private val repo = object : MemoryRepositoryV1(database = null, dao = fakeDao) {}

    private val ownerType = "assistant"
    private val ownerId = "asst-1"
    private val dayMs = 86_400_000L

    @Test
    fun `test fact conflict does not generate duplicate task in metabolism inspector`() = runBlocking {
        val now = 1_000_000_000L
        val factKey = "profile.city"

        // 两个相同 factKey 的活跃卡片
        val card1 = createCard("c1", "小雨生活在上海", factKey = factKey, status = "ACTIVE")
        val card2 = createCard("c2", "小雨生活在北京", factKey = factKey, status = "ACTIVE")
        fakeDao.insertCards(listOf(card1, card2))

        val tasks = inspector.inspect(ownerType, ownerId, currentTimeMs = now)

        val conflictTask = tasks.find { it.taskType == MemoryMetabolismInspector.TASK_FACT_CONFLICT }
        org.junit.Assert.assertNull("Should NOT create duplicate FACT_CONFLICT task in metabolism inspector", conflictTask)
    }

    @Test
    fun `test persistent suppression for ignored and resolved tasks`() = runBlocking {
        val now = 100 * dayMs

        val coldCard = createCard(
            id = "c-suppress",
            content = "备忘内容",
            importance = 0.2f,
            createdAt = now - 50 * dayMs,
            updatedAt = now - 50 * dayMs,
            lastRecalledAt = null,
            isProtected = false,
        )
        fakeDao.insertCard(coldCard)

        // 模拟曾经存在一条被用户 IGNORED 的任务 (发生于 5 天前，处于 14 天抑制期内)
        val ignoredTask = MemoryMaintenanceTaskEntity(
            id = "task-ignored-1",
            ownerType = ownerType,
            ownerId = ownerId,
            taskType = MemoryMetabolismInspector.TASK_COLD_INACTIVE,
            targetType = MemoryMetabolismInspector.TARGET_CARD,
            targetId = coldCard.id,
            title = "冷记忆归档建议",
            suggestion = "归档",
            reason = "cold",
            status = "IGNORED",
            createdAt = now - 6 * dayMs,
            resolvedAt = now - 5 * dayMs,
        )
        fakeDao.insertMaintenanceTask(ignoredTask)

        val tasks1 = inspector.inspect(ownerType, ownerId, currentTimeMs = now)
        assertEquals("Ignored task within suppression duration should not re-generate", 0, tasks1.size)

        // 模拟 RESOLVED 任务：卡片未被更新时继续抑制
        val resolvedTask = ignoredTask.copy(
            id = "task-resolved-1",
            status = "RESOLVED",
            createdAt = now - 3 * dayMs,
            resolvedAt = now - 2 * dayMs,
        )
        fakeDao.insertMaintenanceTask(resolvedTask)

        val tasks2 = inspector.inspect(ownerType, ownerId, currentTimeMs = now)
        assertEquals("Resolved task with no card update should not re-generate", 0, tasks2.size)

        // 卡片发生新修订 (updatedAt > resolvedAt) 后，允许重新触发
        fakeDao.updateCard(coldCard.copy(updatedAt = now - 1 * dayMs))
        val tasks3 = inspector.inspect(ownerType, ownerId, currentTimeMs = now)
        assertEquals("Card updated after resolution should allow re-generating maintenance task", 1, tasks3.size)
    }

    @Test
    fun `test frequent correction detection`() = runBlocking {
        val now = 1_000_000_000L

        // 修订版本达到 3 的活跃卡片
        val highRevCard = createCard("c-rev3", "小雨喜欢美式咖啡", revision = 3, status = "ACTIVE")
        val lowRevCard = createCard("c-rev1", "小雨喜欢猫", revision = 1, status = "ACTIVE")
        fakeDao.insertCards(listOf(highRevCard, lowRevCard))

        val tasks = inspector.inspect(ownerType, ownerId, currentTimeMs = now)

        val frequentTask = tasks.find { it.taskType == MemoryMetabolismInspector.TASK_FREQUENT_CORRECTION }
        assertNotNull("Should create FREQUENT_CORRECTION task", frequentTask)
        assertEquals(highRevCard.id, frequentTask?.targetId)
        assertEquals(MemoryMetabolismInspector.SEVERITY_WARNING, frequentTask?.severity)
    }

    @Test
    fun `test cold memory detection and protection exemption`() = runBlocking {
        val now = 100 * dayMs

        // 冷记忆 1: 创建于 50 天前，未受保护，低重要度 (0.2)，从未被召回 -> 应当报告
        val coldCard = createCard(
            id = "c-cold",
            content = "一条无关紧要的旧备忘",
            importance = 0.2f,
            createdAt = now - 50 * dayMs,
            lastRecalledAt = null,
            isProtected = false,
        )

        // 冷记忆 2: 创建于 50 天前，低重要度，但已受保护 (isProtected = true) -> 应当豁免！
        val protectedCard = createCard(
            id = "c-protected",
            content = "小雨的重要个人喜好",
            importance = 0.2f,
            createdAt = now - 50 * dayMs,
            lastRecalledAt = null,
            isProtected = true,
        )

        // 活跃记忆 3: 高重要度 (0.9) -> 豁免
        val importantCard = createCard(
            id = "c-important",
            content = "小雨的核心生日信息",
            importance = 0.9f,
            createdAt = now - 50 * dayMs,
            lastRecalledAt = null,
            isProtected = false,
        )

        fakeDao.insertCards(listOf(coldCard, protectedCard, importantCard))

        val tasks = inspector.inspect(ownerType, ownerId, currentTimeMs = now)

        val coldTasks = tasks.filter { it.taskType == MemoryMetabolismInspector.TASK_COLD_INACTIVE }
        assertEquals(1, coldTasks.size)
        assertEquals(coldCard.id, coldTasks[0].targetId)
    }

    @Test
    fun `test companion cold memory protection for self relationship boundary and active timeline`() = runBlocking {
        val now = 100 * dayMs

        // 1. self 类别冷卡：未受保护，低重要度 (0.2)，从未被唤醒 -> 豁免（不生成建议）
        val selfCard = createCard(
            id = "c-self-cold",
            content = "我喜欢在下雨天喝热咖啡",
            category = MemoryConstants.CATEGORY_SELF,
            importance = 0.2f,
            createdAt = now - 50 * dayMs,
            lastRecalledAt = null,
            isProtected = false,
        )

        // 2. relationship 类别冷卡：豁免
        val relCard = createCard(
            id = "c-rel-cold",
            content = "我们约定了每年春天一起去公园",
            category = MemoryConstants.CATEGORY_RELATIONSHIP,
            importance = 0.2f,
            createdAt = now - 50 * dayMs,
            lastRecalledAt = null,
            isProtected = false,
        )

        // 3. boundary 类别冷卡：豁免
        val boundaryCard = createCard(
            id = "c-boundary-cold",
            content = "小雨不希望在工作时间被打扰",
            category = MemoryConstants.CATEGORY_BOUNDARY,
            importance = 0.2f,
            createdAt = now - 50 * dayMs,
            lastRecalledAt = null,
            isProtected = false,
        )

        // 4. 属于 ACTIVE 时间线的普通卡片：豁免
        val activeTimeline = MemoryTimelineEntity(
            id = "tl-active-1",
            ownerType = ownerType,
            ownerId = ownerId,
            title = "共同生活点滴",
            status = "ACTIVE",
            createdAt = now - 60 * dayMs,
            updatedAt = now,
        )
        fakeDao.insertTimeline(activeTimeline)

        val timelineMemberCard = createCard(
            id = "c-timeline-member",
            content = "共同经历的一个小故事",
            category = MemoryConstants.CATEGORY_EXPERIENCE,
            importance = 0.2f,
            createdAt = now - 50 * dayMs,
            lastRecalledAt = null,
            isProtected = false,
        )
        fakeDao.insertTimelineMember(
            MemoryTimelineMemberEntity(
                id = "tm-1",
                timelineId = activeTimeline.id,
                cardId = timelineMemberCard.id,
                createdAt = now - 50 * dayMs,
            )
        )

        // 5. 属于 ARCHIVED 时间线但不在 ACTIVE 时间线的普通冷卡：应当生成建议
        val archivedTimeline = MemoryTimelineEntity(
            id = "tl-archived-1",
            ownerType = ownerType,
            ownerId = ownerId,
            title = "已结束的往事",
            status = "ARCHIVED",
            createdAt = now - 60 * dayMs,
            updatedAt = now,
        )
        fakeDao.insertTimeline(archivedTimeline)

        val archivedTimelineMemberCard = createCard(
            id = "c-archived-timeline-member",
            content = "很久以前的某次旧计划",
            category = MemoryConstants.CATEGORY_PLAN,
            importance = 0.2f,
            createdAt = now - 50 * dayMs,
            lastRecalledAt = null,
            isProtected = false,
        )
        fakeDao.insertTimelineMember(
            MemoryTimelineMemberEntity(
                id = "tm-2",
                timelineId = archivedTimeline.id,
                cardId = archivedTimelineMemberCard.id,
                createdAt = now - 50 * dayMs,
            )
        )

        // 6. 普通低重要度冷卡 (experience)：无保护、无 ACTIVE 时间线 -> 应当生成建议
        val ordinaryColdCard = createCard(
            id = "c-ordinary-cold",
            content = "某次随手买的打折零食",
            category = MemoryConstants.CATEGORY_EXPERIENCE,
            importance = 0.2f,
            createdAt = now - 50 * dayMs,
            lastRecalledAt = null,
            isProtected = false,
        )

        fakeDao.insertCards(
            listOf(
                selfCard,
                relCard,
                boundaryCard,
                timelineMemberCard,
                archivedTimelineMemberCard,
                ordinaryColdCard,
            )
        )

        val tasks = inspector.inspect(ownerType, ownerId, currentTimeMs = now)
        val coldTasks = tasks.filter { it.taskType == MemoryMetabolismInspector.TASK_COLD_INACTIVE }

        // 断言：只有属于 ARCHIVED 时间线的卡片和普通冷卡产生了归档建议，其余均受冷记忆保护豁免
        val reportedCardIds = coldTasks.map { it.targetId }.toSet()
        assertEquals(setOf(archivedTimelineMemberCard.id, ordinaryColdCard.id), reportedCardIds)

        // 针对纯函数 isColdMemoryProtected 的边界校验
        val activeIds = setOf(timelineMemberCard.id)
        assertTrue(inspector.isColdMemoryProtected(selfCard, activeIds))
        assertTrue(inspector.isColdMemoryProtected(selfCard.copy(category = "SELF"), activeIds))
        assertTrue(inspector.isColdMemoryProtected(relCard, activeIds))
        assertTrue(inspector.isColdMemoryProtected(relCard.copy(category = " Relationship "), activeIds))
        assertTrue(inspector.isColdMemoryProtected(boundaryCard, activeIds))
        assertTrue(inspector.isColdMemoryProtected(timelineMemberCard, activeIds))
        assertTrue(inspector.isColdMemoryProtected(ordinaryColdCard.copy(isProtected = true), activeIds))
        assertTrue(inspector.isColdMemoryProtected(ordinaryColdCard.copy(importance = 0.8f), activeIds))
        assertFalse(inspector.isColdMemoryProtected(ordinaryColdCard, activeIds))
        assertFalse(inspector.isColdMemoryProtected(archivedTimelineMemberCard, activeIds))
    }

    @Test
    fun `test archived card recently accessed detection`() = runBlocking {
        val now = 100 * dayMs

        // 归档卡片 1: 3 天前刚被访问 (now - 3*dayMs) -> 应当触发回潮提示
        val recentlyAccessedArchived = createCard(
            id = "c-archived-hot",
            content = "已归档但近期又被触及的事实",
            status = "ARCHIVED",
            lastRecalledAt = now - 3 * dayMs,
        )

        // 归档卡片 2: 20 天前访问过 -> 不触发
        val coldArchived = createCard(
            id = "c-archived-cold",
            content = "久远的归档事实",
            status = "ARCHIVED",
            lastRecalledAt = now - 20 * dayMs,
        )

        fakeDao.insertCards(listOf(recentlyAccessedArchived, coldArchived))

        val tasks = inspector.inspect(ownerType, ownerId, currentTimeMs = now)

        val archivedAccessedTasks = tasks.filter { it.taskType == MemoryMetabolismInspector.TASK_ARCHIVED_ACCESSED }
        assertEquals(1, archivedAccessedTasks.size)
        assertEquals(recentlyAccessedArchived.id, archivedAccessedTasks[0].targetId)
    }

    @Test
    fun `test stale review proposals detection`() = runBlocking {
        val now = 100 * dayMs

        // 积压提议: 20 天前创建，仍处于 REVIEW
        val staleProposal = MemoryProposalEntity(
            id = "prop-stale",
            ownerType = ownerType,
            ownerId = ownerId,
            memoryDate = "2026-09-08",
            type = "CARD_UPDATE",
            title = "积压修改建议",
            content = "修改某些事实",
            evidence = "证据文本",
            status = "REVIEW",
            createdAt = now - 20 * dayMs,
        )

        // 新鲜提议: 3 天前创建
        val freshProposal = MemoryProposalEntity(
            id = "prop-fresh",
            ownerType = ownerType,
            ownerId = ownerId,
            memoryDate = "2026-09-08",
            type = "CARD_ADD",
            title = "新鲜建议",
            content = "新增某些事实",
            evidence = "证据文本",
            status = "REVIEW",
            createdAt = now - 3 * dayMs,
        )

        fakeDao.insertProposals(listOf(staleProposal, freshProposal))

        val tasks = inspector.inspect(ownerType, ownerId, currentTimeMs = now)

        val staleTasks = tasks.filter { it.taskType == MemoryMetabolismInspector.TASK_STALE_REVIEW }
        assertEquals(1, staleTasks.size)
        assertEquals(staleProposal.id, staleTasks[0].targetId)
    }

    @Test
    fun `test timeline restructure for oversized and empty timelines`() = runBlocking {
        val now = 100 * dayMs

        // 超大时间线: > 20 个成员
        val largeTimeline = MemoryTimelineEntity(
            id = "tl-large",
            ownerType = ownerType,
            ownerId = ownerId,
            title = "超长跨度项目",
            createdAt = now - 50 * dayMs,
            updatedAt = now,
        )
        fakeDao.insertTimeline(largeTimeline)
        for (i in 1..25) {
            fakeDao.insertTimelineMember(
                MemoryTimelineMemberEntity(
                    id = "m-$i",
                    timelineId = largeTimeline.id,
                    cardId = "card-$i",
                    createdAt = now,
                )
            )
        }

        // 空时间线: 建立超过 7 天但无任何成员
        val emptyTimeline = MemoryTimelineEntity(
            id = "tl-empty",
            ownerType = ownerType,
            ownerId = ownerId,
            title = "废弃的空时间线",
            createdAt = now - 10 * dayMs,
            updatedAt = now - 10 * dayMs,
        )
        fakeDao.insertTimeline(emptyTimeline)

        val tasks = inspector.inspect(ownerType, ownerId, currentTimeMs = now)

        val restructureTasks = tasks.filter { it.taskType == MemoryMetabolismInspector.TASK_TIMELINE_RESTRUCTURE }
        assertEquals(2, restructureTasks.size)
        val targetIds = restructureTasks.map { it.targetId }.toSet()
        assertTrue(targetIds.contains(largeTimeline.id))
        assertTrue(targetIds.contains(emptyTimeline.id))
    }

    @Test
    fun `test inspector deduplication avoids redundant pending tasks`() = runBlocking {
        val now = 1_000_000_000L

        val card1 = createCard("c1", "小雨生活在上海", revision = 3, status = "ACTIVE")
        fakeDao.insertCard(card1)

        // 第一轮巡检: 产出 1 个任务 (频繁纠正)
        val tasksRound1 = inspector.inspect(ownerType, ownerId, currentTimeMs = now)
        assertEquals(1, tasksRound1.size)

        // 第二轮巡检: 相同状态，由于存在未决 PENDING 任务，跳过重复生成
        val tasksRound2 = inspector.inspect(ownerType, ownerId, currentTimeMs = now)
        assertEquals(0, tasksRound2.size)
    }

    @Test
    fun `test card recall metabolism updates hitCount and heat with half-life decay`() = runBlocking {
        val now = 100 * dayMs
        val card = createCard(
            id = "c-recall",
            content = "测试召回代谢更新",
            hitCount = 0,
            lastRecalledAt = null,
            heat = 0.0f,
        )
        fakeDao.insertCard(card)

        // 第一次召回：hitCount 从 0 变为 1，heat 增加 1.0
        repo.recordCardRecalls(listOf(card.id), recallTimeMs = now)

        val updated1 = fakeDao.getCardById(card.id)
        assertNotNull(updated1)
        assertEquals(1, updated1?.hitCount)
        assertEquals(now, updated1?.lastRecalledAt)
        assertEquals(1.0f, updated1?.heat ?: 0f, 0.01f)

        // 7 天后第二次召回：经过一个半衰期 (7 天)，旧 heat (1.0) 衰减为 0.5，加上新召回 (+1.0) -> heat 约 1.5
        val sevenDaysLater = now + 7 * dayMs
        repo.recordCardRecalls(listOf(card.id), recallTimeMs = sevenDaysLater)

        val updated2 = fakeDao.getCardById(card.id)
        assertNotNull(updated2)
        assertEquals(2, updated2?.hitCount)
        assertEquals(sevenDaysLater, updated2?.lastRecalledAt)
        assertEquals(1.5f, updated2?.heat ?: 0f, 0.02f)
    }

    @Test
    fun `test card protection and last reviewed updates`() = runBlocking {
        val card = createCard(
            id = "c-protect",
            content = "测试卡片保护与审核",
            isProtected = false,
            lastReviewedAt = null,
        )
        fakeDao.insertCard(card)

        // 开启保护
        repo.setCardProtection(card.id, true)
        assertEquals(true, fakeDao.getCardById(card.id)?.isProtected)

        // 取消保护
        repo.setCardProtection(card.id, false)
        assertEquals(false, fakeDao.getCardById(card.id)?.isProtected)

        // 记录人工审核
        val reviewTime = 123456789L
        repo.recordCardReviewed(card.id, reviewTime)
        assertEquals(reviewTime, fakeDao.getCardById(card.id)?.lastReviewedAt)
    }

    @Test
    fun inspect_staleEProposals_createsMaintenanceTask() = runBlocking {
        val now = 100_000_000L
        val staleCreated = now - (15 * MemoryMetabolismInspector.DAY_MS)
        val proposal = me.rerere.rikkahub.data.memory.v1.db.MemoryEProposalEntity(
            id = 1L,
            ownerType = ownerType,
            ownerId = ownerId,
            memoryCardId = "c-1",
            valence = 0.5,
            arousal = 0.5,
            tension = 0.5,
            confidence = 0.8,
            proposer = "consolidation",
            sourceEpisodeKey = "ep1",
            status = me.rerere.rikkahub.data.memory.v1.db.MemoryEProposalEntity.STATUS_PENDING,
            createdAt = staleCreated,
        )
        fakeDao.insertEProposal(proposal)

        val tasks = inspector.inspect(ownerType, ownerId, now)
        val staleTask = tasks.find { it.taskType == MemoryMetabolismInspector.TASK_EXPERIENCE_PROPOSAL_STALE }
        assertNotNull(staleTask)
        assertEquals("1", staleTask!!.targetId)
    }

    @Test
    fun inspect_orphanAndMultipleCurrentExperiences_createsAnomalyMaintenanceTask() = runBlocking {
        val now = 100_000_000L
        val orphanExp = me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity(
            id = 10L,
            ownerType = ownerType,
            ownerId = ownerId,
            memoryCardId = "non_existent_card",
            meaning = "我是孤儿经验",
            eAuthoredBy = "MAIN_AI",
            eInitialPriority = 80,
            valence = 0.5,
            arousal = 0.5,
            tension = 0.5,
            emotionConfidence = 0.8,
            responseTendency = "COMFORT",
            growthDelta = "STABLE",
            sourceEpisodeKey = "ep1",
            status = "CURRENT",
            createdAt = now - 1000L,
        )
        fakeDao.insertExperienceCoordinate(orphanExp)

        val tasks = inspector.inspect(ownerType, ownerId, now)
        val orphanTask = tasks.find { it.taskType == MemoryMetabolismInspector.TASK_EXPERIENCE_ANOMALY && it.targetId == "non_existent_card" }
        assertNotNull(orphanTask)
        assertTrue(orphanTask!!.reason.contains("null or ARCHIVED"))
    }

    private fun createCard(
        id: String,
        content: String,
        status: String = "ACTIVE",
        factKey: String? = null,
        revision: Int = 1,
        importance: Float = 0.8f,
        confidence: Float = 1.0f,
        createdAt: Long = 1000L,
        updatedAt: Long = createdAt,
        hitCount: Int = 0,
        lastRecalledAt: Long? = null,
        heat: Float = 0.0f,
        isProtected: Boolean = false,
        lastReviewedAt: Long? = null,
        category: String = "preference",
    ): MemoryCardEntity {
        return MemoryCardEntity(
            id = id,
            ownerType = ownerType,
            ownerId = ownerId,
            content = content,
            category = category,
            factKey = factKey,
            status = status,
            confidence = confidence,
            importance = importance,
            createdAt = createdAt,
            updatedAt = updatedAt,
            revision = revision,
            createdBy = "test",
            updatedBy = "test",
            hitCount = hitCount,
            lastRecalledAt = lastRecalledAt,
            heat = heat,
            isProtected = isProtected,
            lastReviewedAt = lastReviewedAt,
        )
    }
}
