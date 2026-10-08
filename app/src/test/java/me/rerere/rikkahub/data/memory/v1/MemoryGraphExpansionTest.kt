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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryGraphExpansionTest {

    private class FakeDao : MemoryV1DaoStub() {
        val cards = mutableMapOf<String, MemoryCardEntity>()
        val relations = mutableListOf<MemoryRelationEntity>()

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

    private val fakeDao = FakeDao()
    private val repo = object : MemoryRepositoryV1(database = null, dao = fakeDao) {}

    private fun createTestCard(
        id: String,
        content: String,
        status: String = "ACTIVE",
        factKey: String? = null,
        ownerId: String = "asst-1",
    ): MemoryCardEntity {
        val now = System.currentTimeMillis()
        val card = MemoryCardEntity(
            id = id,
            ownerType = "assistant",
            ownerId = ownerId,
            content = content,
            category = "preference",
            factKey = factKey,
            status = status,
            confidence = 1.0f,
            importance = 0.8f,
            createdAt = now,
            updatedAt = now,
            revision = 1,
            createdBy = "test",
            updatedBy = "test",
        )
        fakeDao.cards[id] = card
        return card
    }

    @Test
    fun `test 1-hop graph expansion from seed`() = runBlocking {
        val cardA = createTestCard("card-A", "小雨喜欢喝无糖乌龙茶")
        val cardB = createTestCard("card-B", "小雨习惯下午三点喝茶提神")
        val cardC = createTestCard("card-C", "小雨周末喜欢去公园跑步")

        repo.addRelation(
            ownerType = "assistant",
            ownerId = "asst-1",
            sourceCardId = cardA.id,
            targetCardId = cardB.id,
            relationType = "RELATES_TO",
            weight = 1.0f,
        )

        val expanded = repo.graphExpand(seedCardIds = listOf(cardA.id), maxHops = 1, limit = 3)
        assertEquals(1, expanded.size)
        assertEquals("card-B", expanded[0].id)
    }

    @Test
    fun `test 2-hop graph expansion`() = runBlocking {
        val cardA = createTestCard("card-A", "小雨正在准备软件工程考试")
        val cardB = createTestCard("card-B", "考试重点是设计模式与架构")
        val cardC = createTestCard("card-C", "复习参考书是Head First设计模式")

        // A -> B -> C
        repo.addRelation("assistant", "asst-1", cardA.id, cardB.id, "RELATES_TO", weight = 0.9f)
        repo.addRelation("assistant", "asst-1", cardB.id, cardC.id, "DEPENDS_ON", weight = 0.8f)

        val hop1 = repo.graphExpand(seedCardIds = listOf(cardA.id), maxHops = 1, limit = 5)
        assertEquals(listOf("card-B"), hop1.map { it.id })

        val hop2 = repo.graphExpand(seedCardIds = listOf(cardA.id), maxHops = 2, limit = 5)
        assertEquals(listOf("card-B", "card-C"), hop2.map { it.id })
    }

    @Test
    fun `test cycle and self prevention`() = runBlocking {
        val cardA = createTestCard("card-A", "小雨喜欢猫")
        val cardB = createTestCard("card-B", "小雨养了一只金渐层")

        // 环路 A <-> B
        repo.addRelation("assistant", "asst-1", cardA.id, cardB.id, "RELATES_TO")
        repo.addRelation("assistant", "asst-1", cardB.id, cardA.id, "RELATES_TO")

        val expanded = repo.graphExpand(seedCardIds = listOf(cardA.id), maxHops = 2, limit = 5)
        // 扩展结果不应包含种子 cardA，且 cardB 不重复
        assertEquals(1, expanded.size)
        assertEquals("card-B", expanded[0].id)
    }

    @Test
    fun `test filters inactive cards during expansion`() = runBlocking {
        val cardA = createTestCard("card-A", "小雨的手机是iPhone")
        val cardB = createTestCard("card-B", "旧手机是小米9", status = "SUPERSEDED")

        repo.addRelation("assistant", "asst-1", cardA.id, cardB.id, "SUPERSEDES")

        val expanded = repo.graphExpand(seedCardIds = listOf(cardA.id), maxHops = 1, limit = 3)
        // cardB 是 SUPERSEDED，不应进入活跃召回
        assertTrue(expanded.isEmpty())
    }

    @Test
    fun `test edge weight ordering and limit truncation`() = runBlocking {
        val cardSeed = createTestCard("seed", "小雨准备去杭州旅游")
        val card1 = createTestCard("card-1", "计划游览西湖")
        val card2 = createTestCard("card-2", "想去灵隐寺吃素面")
        val card3 = createTestCard("card-3", "打算住民宿")

        repo.addRelation("assistant", "asst-1", cardSeed.id, card1.id, "RELATES_TO", weight = 0.5f)
        repo.addRelation("assistant", "asst-1", cardSeed.id, card2.id, "RELATES_TO", weight = 0.9f)
        repo.addRelation("assistant", "asst-1", cardSeed.id, card3.id, "RELATES_TO", weight = 0.7f)

        // limit = 2，权重顺序应为 card2(0.9) -> card3(0.7)
        val expanded = repo.graphExpand(seedCardIds = listOf(cardSeed.id), maxHops = 1, limit = 2)
        assertEquals(2, expanded.size)
        assertEquals("card-2", expanded[0].id)
        assertEquals("card-3", expanded[1].id)
    }

    @Test
    fun `test high risk relations are filtered out from expansion`() = runBlocking {
        val cardSeed = createTestCard("seed-risk", "小雨喜欢喝咖啡")
        val cardSupported = createTestCard("card-sup", "咖啡有助于工作专注")
        val cardContradicted = createTestCard("card-contra", "小雨喝咖啡会失眠")

        // supports 和 contradicts 属于高风险逻辑推理关系，默认不自动进入图扩散
        repo.addRelation(
            ownerType = "assistant",
            ownerId = "asst-1",
            sourceCardId = cardSeed.id,
            targetCardId = cardSupported.id,
            relationType = "supports",
            weight = 1.0f,
            strength = 0.9f,
        )
        repo.addRelation(
            ownerType = "assistant",
            ownerId = "asst-1",
            sourceCardId = cardSeed.id,
            targetCardId = cardContradicted.id,
            relationType = "contradicts",
            weight = 1.0f,
            strength = 0.9f,
        )

        val expanded = repo.graphExpand(seedCardIds = listOf(cardSeed.id), maxHops = 1, limit = 5)
        assertTrue(expanded.isEmpty())
    }

    @Test
    fun `test weak strength and expired relations are filtered out`() = runBlocking {
        val cardSeed = createTestCard("seed-filter", "小雨在学日语")
        val cardWeak = createTestCard("card-weak", "五十音图还没背熟")
        val cardExpired = createTestCard("card-exp", "上周参加了日语角活动")

        val now = System.currentTimeMillis()

        // 弱边 (strength = 0.3 < 0.5)
        repo.addRelation(
            ownerType = "assistant",
            ownerId = "asst-1",
            sourceCardId = cardSeed.id,
            targetCardId = cardWeak.id,
            relationType = "RELATES_TO",
            strength = 0.3f,
            status = "ACTIVE",
        )

        // 已过期边 (validUntil < now)
        repo.addRelation(
            ownerType = "assistant",
            ownerId = "asst-1",
            sourceCardId = cardSeed.id,
            targetCardId = cardExpired.id,
            relationType = "RELATES_TO",
            strength = 0.8f,
            validUntil = now - 10_000L,
            status = "ACTIVE",
        )

        val expanded = repo.graphExpand(seedCardIds = listOf(cardSeed.id), maxHops = 1, limit = 5)
        assertTrue(expanded.isEmpty())
    }

    @Test
    fun `test graphExpand filters out cards belonging to other assistants`() = runBlocking {
        val cardSeed = createTestCard("seed-owner", "小雨喜欢喝咖啡", ownerId = "asst-1")
        val cardSameOwner = createTestCard("card-same", "常去的咖啡馆在学校附近", ownerId = "asst-1")
        val cardOtherOwner = createTestCard("card-other", "另一个助手的私有秘密", ownerId = "asst-2")

        repo.addRelation(
            ownerType = "assistant",
            ownerId = "asst-1",
            sourceCardId = cardSeed.id,
            targetCardId = cardSameOwner.id,
            relationType = "RELATES_TO",
            strength = 0.9f,
            status = "ACTIVE",
        )
        repo.addRelation(
            ownerType = "assistant",
            ownerId = "asst-1",
            sourceCardId = cardSeed.id,
            targetCardId = cardOtherOwner.id,
            relationType = "RELATES_TO",
            strength = 0.9f,
            status = "ACTIVE",
        )

        val expanded = repo.graphExpand(
            seedCardIds = listOf(cardSeed.id),
            maxHops = 1,
            limit = 5,
            ownerType = "assistant",
            ownerId = "asst-1",
            includeGlobal = false,
        )

        assertEquals(1, expanded.size)
        assertEquals("card-same", expanded.first().id)
    }
}

