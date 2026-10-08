/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.memory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.memory.v1.MemoryConstants
import me.rerere.rikkahub.data.memory.v1.MemoryEmbeddingService
import me.rerere.rikkahub.data.memory.v1.MemoryRepositoryV1
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryRelationEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryTimelineEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

class LocalMemoryManagerVMTest {

    private lateinit var testScope: CoroutineScope

    private class FakeMemoryRepository : MemoryRepositoryV1() {
        var lastActionLatch: CountDownLatch? = null
        var lastCreatedCard: MemoryCardEntity? = null
        var lastUpdatedCard: MemoryCardEntity? = null
        var lastRestoredCard: MemoryCardEntity? = null
        var lastArchivedId: String? = null
        var lastDeletedId: String? = null

        override suspend fun createCard(
            ownerType: String,
            ownerId: String,
            content: String,
            category: String,
            factKey: String?,
            confidence: Float,
            importance: Float,
            validFrom: Long?,
            validTo: Long?,
            actor: String,
            reason: String?,
            sourceConversationId: String?,
            sourceNodeId: String?,
            sourceMessageId: String?,
            sourceRole: String?,
            sourceKind: String,
            sourceHash: String?,
            status: String,
            alwaysInject: Boolean,
        ): MemoryCardEntity {
            val card = MemoryCardEntity(
                id = "card_new_1",
                ownerType = ownerType,
                ownerId = ownerId,
                content = content,
                category = category,
                factKey = factKey,
                status = status,
                alwaysInject = alwaysInject,
                confidence = confidence,
                importance = importance,
                validFrom = 0L,
                createdAt = 0L,
                updatedAt = 0L,
                createdBy = actor,
                updatedBy = actor,
                revision = 1,
            )
            lastCreatedCard = card
            lastActionLatch?.countDown()
            return card
        }

        override suspend fun updateCard(
            id: String,
            expectedRevision: Int,
            content: String,
            category: String?,
            factKey: String?,
            confidence: Float?,
            importance: Float?,
            validFrom: Long?,
            validTo: Long?,
            actor: String,
            reason: String?,
            sourceConversationId: String?,
            sourceNodeId: String?,
            sourceMessageId: String?,
            sourceRole: String?,
            sourceKind: String,
            sourceHash: String?
        ): MemoryCardEntity {
            val card = MemoryCardEntity(
                id = id,
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = "asst_1",
                content = content,
                category = category ?: MemoryConstants.CATEGORY_OTHER,
                factKey = factKey,
                status = MemoryConstants.STATUS_ACTIVE,
                confidence = confidence ?: 1.0f,
                importance = importance ?: 0.5f,
                validFrom = 0L,
                createdAt = 0L,
                updatedAt = 0L,
                createdBy = actor,
                updatedBy = actor,
                revision = expectedRevision + 1,
            )
            lastUpdatedCard = card
            lastActionLatch?.countDown()
            return card
        }

        override suspend fun restoreCard(
            id: String,
            expectedRevision: Int,
            actor: String,
            reason: String?
        ): MemoryCardEntity {
            val card = MemoryCardEntity(
                id = id,
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = "asst_1",
                content = "恢复的内容",
                category = MemoryConstants.CATEGORY_OTHER,
                status = MemoryConstants.STATUS_ACTIVE,
                confidence = 1.0f,
                importance = 0.5f,
                validFrom = 0L,
                createdAt = 0L,
                updatedAt = 0L,
                createdBy = actor,
                updatedBy = actor,
                revision = expectedRevision + 1,
            )
            lastRestoredCard = card
            lastActionLatch?.countDown()
            return card
        }

        override suspend fun archiveCard(
            id: String,
            expectedRevision: Int,
            actor: String,
            reason: String?
        ): MemoryCardEntity {
            lastArchivedId = id
            val card = MemoryCardEntity(
                id = id,
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = "asst_1",
                content = "归档内容",
                category = MemoryConstants.CATEGORY_OTHER,
                status = MemoryConstants.STATUS_ARCHIVED,
                confidence = 1.0f,
                importance = 0.5f,
                validFrom = 0L,
                createdAt = 0L,
                updatedAt = 0L,
                createdBy = actor,
                updatedBy = actor,
                revision = expectedRevision + 1,
            )
            lastActionLatch?.countDown()
            return card
        }

        override suspend fun deletePermanently(id: String) {
            lastDeletedId = id
            lastActionLatch?.countDown()
        }

        var lastDeletedDailyNoteDate: String? = null
        override suspend fun deleteDailyNote(ownerId: String, memoryDate: String) {
            lastDeletedDailyNoteDate = memoryDate
            lastActionLatch?.countDown()
        }

        override fun getDailyNotesFlow(ownerType: String, ownerId: String): kotlinx.coroutines.flow.Flow<List<me.rerere.rikkahub.data.memory.v1.db.MemoryDailyNoteEntity>> =
            kotlinx.coroutines.flow.emptyFlow()

        override fun getCardsFlow(ownerType: String, ownerId: String, status: String?): kotlinx.coroutines.flow.Flow<List<MemoryCardEntity>> =
            kotlinx.coroutines.flow.emptyFlow()

        override fun getPendingProposalsFlow(ownerType: String, ownerId: String): kotlinx.coroutines.flow.Flow<List<me.rerere.rikkahub.data.memory.v1.db.MemoryProposalEntity>> =
            kotlinx.coroutines.flow.emptyFlow()

        override fun getPendingFactAuditsFlow(ownerType: String, ownerId: String): kotlinx.coroutines.flow.Flow<List<me.rerere.rikkahub.data.memory.v1.db.MemoryFactAuditEntity>> =
            kotlinx.coroutines.flow.emptyFlow()

        override fun getPendingMaintenanceTasksFlow(ownerType: String, ownerId: String): kotlinx.coroutines.flow.Flow<List<me.rerere.rikkahub.data.memory.v1.db.MemoryMaintenanceTaskEntity>> =
            kotlinx.coroutines.flow.emptyFlow()

        override fun getPendingRelationsFlow(ownerType: String, ownerId: String): kotlinx.coroutines.flow.Flow<List<me.rerere.rikkahub.data.memory.v1.db.MemoryRelationEntity>> =
            kotlinx.coroutines.flow.emptyFlow()

        override fun getPendingEProposalsFlow(ownerType: String, ownerId: String): kotlinx.coroutines.flow.Flow<List<me.rerere.rikkahub.data.memory.v1.db.MemoryEProposalEntity>> =
            kotlinx.coroutines.flow.emptyFlow()

        var lastAcceptedEProposalId: Long? = null
        override suspend fun acceptEProposal(
            proposalId: Long,
            eAuthoredBy: String,
            meaning: String?,
            eInitialPriority: Int?,
            growthDelta: String?,
            responseTendency: String?,
        ): me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity {
            lastAcceptedEProposalId = proposalId
            lastActionLatch?.countDown()
            return me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity(
                ownerType = "assistant",
                ownerId = "owner_1",
                memoryCardId = "card_1",
                meaning = meaning ?: "我记住了",
                eAuthoredBy = eAuthoredBy,
                eInitialPriority = eInitialPriority ?: 50,
                status = me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity.STATUS_CURRENT,
            )
        }

        var lastRejectedEProposalId: Long? = null
        override suspend fun rejectEProposal(proposalId: Long) {
            lastRejectedEProposalId = proposalId
            lastActionLatch?.countDown()
        }

        var lastApprovedRelationId: String? = null
        override suspend fun approveRelation(relationId: String): Boolean {
            lastApprovedRelationId = relationId
            lastActionLatch?.countDown()
            return true
        }

        var lastRejectedRelationId: String? = null
        override suspend fun rejectRelation(relationId: String): Boolean {
            lastRejectedRelationId = relationId
            lastActionLatch?.countDown()
            return true
        }

        var lastApprovedProposalId: String? = null
        override suspend fun approveProposal(proposalId: String, actor: String): MemoryCardEntity? {
            lastApprovedProposalId = proposalId
            lastActionLatch?.countDown()
            return null
        }

        var lastRejectedProposalId: String? = null
        override suspend fun rejectProposal(proposalId: String, reason: String?): Boolean {
            lastRejectedProposalId = proposalId
            lastActionLatch?.countDown()
            return true
        }

        var lastResolvedFactAuditId: String? = null
        override suspend fun resolveFactAudit(auditId: String, resolution: String, resolvedBy: String): Boolean {
            lastResolvedFactAuditId = auditId
            lastActionLatch?.countDown()
            return true
        }

        var lastResolvedTaskId: String? = null
        override suspend fun resolveMaintenanceTask(id: String, status: String, resolvedBy: String?) {
            lastResolvedTaskId = id
            lastActionLatch?.countDown()
        }

        val cardsList = mutableListOf<MemoryCardEntity>()
        val timelinesList = mutableListOf<MemoryTimelineEntity>()
        val timelineCardsMap = mutableMapOf<String, List<MemoryCardEntity>>()
        val relationsList = mutableListOf<MemoryRelationEntity>()

        override suspend fun getAllCards(ownerType: String, ownerId: String): List<MemoryCardEntity> {
            return cardsList.filter { it.ownerType == ownerType && it.ownerId == ownerId }
        }

        override suspend fun getActiveTimelines(ownerType: String, ownerId: String): List<MemoryTimelineEntity> {
            return timelinesList.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == MemoryConstants.STATUS_ACTIVE }
        }

        override suspend fun getTimelineCards(timelineId: String): List<MemoryCardEntity> {
            return timelineCardsMap[timelineId] ?: emptyList()
        }

        override suspend fun getAllRelations(ownerType: String, ownerId: String): List<MemoryRelationEntity> {
            return relationsList.filter { it.ownerType == ownerType && it.ownerId == ownerId }
        }

        override suspend fun getCardRelations(cardId: String): List<MemoryRelationEntity> {
            return relationsList.filter { it.sourceCardId == cardId || it.targetCardId == cardId }
        }

        override suspend fun getCardsByIds(ids: List<String>): List<MemoryCardEntity> {
            val set = ids.toSet()
            return cardsList.filter { it.id in set }
        }

        override fun getCardsByIdsFlow(ids: List<String>): Flow<List<MemoryCardEntity>> {
            val set = ids.toSet()
            return flowOf(cardsList.filter { it.id in set })
        }
    }

    private class FakeMemoryEmbeddingService(
        repo: MemoryRepositoryV1,
    ) : MemoryEmbeddingService(repository = repo) {
        var lastEmbeddingLatch: CountDownLatch? = null
        val enqueuedCards = mutableListOf<Pair<MemoryCardEntity, Uuid>>()
        val deletedCardIds = mutableListOf<String>()
        val canceledCardIds = mutableListOf<String>()

        override fun enqueueSyncCard(
            card: MemoryCardEntity,
            embeddingModelId: Uuid,
            settings: Settings
        ): Job {
            enqueuedCards.add(card to embeddingModelId)
            lastEmbeddingLatch?.countDown()
            return Job().apply { complete() }
        }

        override suspend fun deleteCardEmbedding(memoryId: String) {
            deletedCardIds.add(memoryId)
            lastEmbeddingLatch?.countDown()
        }

        override fun cancelSyncJob(memoryId: String) {
            canceledCardIds.add(memoryId)
            lastEmbeddingLatch?.countDown()
        }
    }

    private val repository = FakeMemoryRepository()
    private val embeddingService = FakeMemoryEmbeddingService(repository)

    private val asstId = Uuid.random()
    private val modelUuid = Uuid.random()
    private val providerUuid = Uuid.random()
    private val testSettings = Settings(
        providers = listOf(
            ProviderSetting.OpenAI(
                id = providerUuid,
                name = "P1",
                models = listOf(Model(id = modelUuid, modelId = "text-embedding-3")),
            )
        ),
        assistants = listOf(
            Assistant(
                id = asstId,
                name = "测试助手",
                memoryMode = MemoryMode.LOCAL_V1,
                memoryEmbeddingModelId = modelUuid,
                useGlobalMemory = true,
            )
        ),
    )

    @Before
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob())
        repository.cardsList.clear()
        repository.timelinesList.clear()
        repository.timelineCardsMap.clear()
        repository.relationsList.clear()
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun `createCard triggers repository create and enqueues embedding sync`() {
        val actionLatch = CountDownLatch(1)
        val embeddingLatch = CountDownLatch(1)
        repository.lastActionLatch = actionLatch
        embeddingService.lastEmbeddingLatch = embeddingLatch

        val vm = LocalMemoryManagerVM(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = asstId.toString(),
            repository = repository,
            settingsStore = null,
            embeddingService = embeddingService,
            currentSettingsProvider = { testSettings },
            coroutineScope = testScope,
        )

        vm.createCard("小雨爱喝橙汁", MemoryConstants.CATEGORY_PREFERENCE, null)
        assertTrue(actionLatch.await(3, TimeUnit.SECONDS))
        assertTrue(embeddingLatch.await(3, TimeUnit.SECONDS))

        assertEquals("小雨爱喝橙汁", repository.lastCreatedCard?.content)
        assertEquals(1, embeddingService.enqueuedCards.size)
        assertEquals("card_new_1", embeddingService.enqueuedCards[0].first.id)
        assertEquals(modelUuid, embeddingService.enqueuedCards[0].second)
    }

    @Test
    fun `updateCard triggers repository update and enqueues embedding sync`() {
        val actionLatch = CountDownLatch(1)
        val embeddingLatch = CountDownLatch(1)
        repository.lastActionLatch = actionLatch
        embeddingService.lastEmbeddingLatch = embeddingLatch

        val vm = LocalMemoryManagerVM(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = asstId.toString(),
            repository = repository,
            settingsStore = null,
            embeddingService = embeddingService,
            currentSettingsProvider = { testSettings },
            coroutineScope = testScope,
        )

        vm.updateCard("card_edit_1", 1, "小雨更爱喝葡萄汁", MemoryConstants.CATEGORY_PREFERENCE, null)
        assertTrue(actionLatch.await(3, TimeUnit.SECONDS))
        assertTrue(embeddingLatch.await(3, TimeUnit.SECONDS))

        assertEquals("小雨更爱喝葡萄汁", repository.lastUpdatedCard?.content)
        assertEquals(1, embeddingService.enqueuedCards.size)
        assertEquals("card_edit_1", embeddingService.enqueuedCards[0].first.id)
        assertEquals(modelUuid, embeddingService.enqueuedCards[0].second)
    }

    @Test
    fun `restoreCard triggers repository restore and enqueues embedding sync`() {
        val actionLatch = CountDownLatch(1)
        val embeddingLatch = CountDownLatch(1)
        repository.lastActionLatch = actionLatch
        embeddingService.lastEmbeddingLatch = embeddingLatch

        val vm = LocalMemoryManagerVM(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = asstId.toString(),
            repository = repository,
            settingsStore = null,
            embeddingService = embeddingService,
            currentSettingsProvider = { testSettings },
            coroutineScope = testScope,
        )

        vm.restoreCard("card_res_1", 2)
        assertTrue(actionLatch.await(3, TimeUnit.SECONDS))
        assertTrue(embeddingLatch.await(3, TimeUnit.SECONDS))

        assertEquals("card_res_1", repository.lastRestoredCard?.id)
        assertEquals(1, embeddingService.enqueuedCards.size)
        assertEquals("card_res_1", embeddingService.enqueuedCards[0].first.id)
    }

    @Test
    fun `archiveCard and deletePermanently cancel in-flight jobs after repository update`() {
        val vm = LocalMemoryManagerVM(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = asstId.toString(),
            repository = repository,
            settingsStore = null,
            embeddingService = embeddingService,
            currentSettingsProvider = { testSettings },
            coroutineScope = testScope,
        )

        val archActionLatch = CountDownLatch(1)
        val archEmbeddingLatch = CountDownLatch(1)
        repository.lastActionLatch = archActionLatch
        embeddingService.lastEmbeddingLatch = archEmbeddingLatch

        vm.archiveCard("card_arch_1", 1)
        assertTrue(archActionLatch.await(3, TimeUnit.SECONDS))
        assertTrue(archEmbeddingLatch.await(3, TimeUnit.SECONDS))
        assertEquals("card_arch_1", repository.lastArchivedId)
        assertTrue(embeddingService.canceledCardIds.contains("card_arch_1"))

        val delActionLatch = CountDownLatch(1)
        val delEmbeddingLatch = CountDownLatch(1)
        repository.lastActionLatch = delActionLatch
        embeddingService.lastEmbeddingLatch = delEmbeddingLatch

        vm.deletePermanently("card_del_1")
        assertTrue(delActionLatch.await(3, TimeUnit.SECONDS))
        assertTrue(delEmbeddingLatch.await(3, TimeUnit.SECONDS))
        assertEquals("card_del_1", repository.lastDeletedId)
        assertTrue(embeddingService.canceledCardIds.contains("card_del_1"))
    }

    @Test
    fun `global card triggers embedding sync for all assistants using global memory with different models`() {
        val modelUuid2 = Uuid.random()
        val asstId2 = Uuid.random()
        val modelUuid3 = Uuid.random()
        val asstId3 = Uuid.random()
        val multiSettings = testSettings.copy(
            assistants = listOf(
                Assistant(
                    id = asstId,
                    name = "助手1",
                    memoryMode = MemoryMode.LOCAL_V1,
                    memoryEmbeddingModelId = modelUuid,
                    useGlobalMemory = true,
                ),
                Assistant(
                    id = asstId2,
                    name = "助手2",
                    memoryMode = MemoryMode.LOCAL_V1,
                    memoryEmbeddingModelId = modelUuid2,
                    useGlobalMemory = true,
                ),
                Assistant(
                    id = asstId3,
                    name = "助手3(已关闭本地记忆)",
                    memoryMode = MemoryMode.OFF,
                    memoryEmbeddingModelId = modelUuid3,
                    useGlobalMemory = true,
                ),
            )
        )

        val actionLatch = CountDownLatch(1)
        val embeddingLatch = CountDownLatch(2) // 应当触发两次入队
        repository.lastActionLatch = actionLatch
        embeddingService.lastEmbeddingLatch = embeddingLatch

        val vm = LocalMemoryManagerVM(
            ownerType = MemoryConstants.OWNER_TYPE_GLOBAL,
            ownerId = MemoryConstants.GLOBAL_OWNER_ID,
            repository = repository,
            settingsStore = null,
            embeddingService = embeddingService,
            currentSettingsProvider = { multiSettings },
            coroutineScope = testScope,
        )

        vm.createCard("全局事实：小雨的项目进度很棒", MemoryConstants.CATEGORY_PROFILE, null)
        assertTrue(actionLatch.await(3, TimeUnit.SECONDS))
        assertTrue(embeddingLatch.await(3, TimeUnit.SECONDS))

        assertEquals(2, embeddingService.enqueuedCards.size)
        assertEquals("card_new_1", embeddingService.enqueuedCards[0].first.id)
        assertEquals("card_new_1", embeddingService.enqueuedCards[1].first.id)
        val enqueuedModelIds = embeddingService.enqueuedCards.map { it.second }.toSet()
        assertEquals(setOf(modelUuid, modelUuid2), enqueuedModelIds)
    }

    @Test
    fun `deleteDailyNote delegates to repository`() {
        val actionLatch = CountDownLatch(1)
        repository.lastActionLatch = actionLatch

        val vm = LocalMemoryManagerVM(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = asstId.toString(),
            repository = repository,
            settingsStore = null,
            embeddingService = embeddingService,
            currentSettingsProvider = { testSettings },
            coroutineScope = testScope,
        )

        vm.deleteDailyNote("2026-09-08")
        assertTrue(actionLatch.await(3, TimeUnit.SECONDS))
        assertEquals("2026-09-08", repository.lastDeletedDailyNoteDate)
    }

    @Test
    fun `consolidateDate intercepts non-assistant ownerType`() {
        var completedCalled = false
        var completedSuccess: Boolean? = null
        var completedMsg: String? = null

        val vm = LocalMemoryManagerVM(
            ownerType = MemoryConstants.OWNER_TYPE_GLOBAL,
            ownerId = MemoryConstants.GLOBAL_OWNER_ID,
            repository = repository,
            settingsStore = null,
            embeddingService = embeddingService,
            currentSettingsProvider = { testSettings },
            coroutineScope = testScope,
        )

        vm.consolidateDate("2026-09-08") { success, msg ->
            completedCalled = true
            completedSuccess = success
            completedMsg = msg
        }

        assertTrue(completedCalled)
        assertEquals(false, completedSuccess)
        assertEquals("每日整理仅支持助手专属记忆", completedMsg)
    }

    @Test
    fun `pending management actions delegate to repository`() {
        val vm = LocalMemoryManagerVM(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = asstId.toString(),
            repository = repository,
            settingsStore = null,
            embeddingService = embeddingService,
            currentSettingsProvider = { testSettings },
            coroutineScope = testScope,
        )

        // 1. approveProposal
        val latch1 = CountDownLatch(1)
        repository.lastActionLatch = latch1
        vm.approveProposal("prop_1")
        assertTrue(latch1.await(3, TimeUnit.SECONDS))
        assertEquals("prop_1", repository.lastApprovedProposalId)

        // 2. rejectProposal
        val latch2 = CountDownLatch(1)
        repository.lastActionLatch = latch2
        vm.rejectProposal("prop_2", reason = "事实不符")
        assertTrue(latch2.await(3, TimeUnit.SECONDS))
        assertEquals("prop_2", repository.lastRejectedProposalId)

        // 3. resolveFactAudit
        val latch3 = CountDownLatch(1)
        repository.lastActionLatch = latch3
        vm.resolveFactAudit("audit_1", MemoryConstants.AUDIT_RESOLUTION_SUPERSEDE)
        assertTrue(latch3.await(3, TimeUnit.SECONDS))
        assertEquals("audit_1", repository.lastResolvedFactAuditId)

        // 4. resolveMaintenanceTask
        val latch4 = CountDownLatch(1)
        repository.lastActionLatch = latch4
        vm.resolveMaintenanceTask("task_1", "RESOLVED")
        assertTrue(latch4.await(3, TimeUnit.SECONDS))
        assertEquals("task_1", repository.lastResolvedTaskId)

        // 5. acceptEProposal
        val latch5 = CountDownLatch(1)
        repository.lastActionLatch = latch5
        vm.acceptEProposal(101L)
        assertTrue(latch5.await(3, TimeUnit.SECONDS))
        assertEquals(101L, repository.lastAcceptedEProposalId)

        // 6. rejectEProposal
        val latch6 = CountDownLatch(1)
        repository.lastActionLatch = latch6
        vm.rejectEProposal(102L)
        assertTrue(latch6.await(3, TimeUnit.SECONDS))
        assertEquals(102L, repository.lastRejectedEProposalId)
    }

    @Test
    fun `map data isolates strictly by ownerType and ownerId`() {
        val currentOwnerId = asstId.toString()
        val otherOwnerId = "other_assistant_99"

        val myCard1 = MemoryCardEntity(
            id = "c1", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            content = "我的卡片1", category = MemoryConstants.CATEGORY_PREFERENCE, factKey = "fav_color",
            status = MemoryConstants.STATUS_ACTIVE, confidence = 1f, importance = 0.5f,
            createdAt = 100L, updatedAt = 100L, createdBy = "MANUAL", updatedBy = "MANUAL"
        )
        val otherCard = MemoryCardEntity(
            id = "c_other", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = otherOwnerId,
            content = "他人卡片", category = MemoryConstants.CATEGORY_PREFERENCE, factKey = "fav_color",
            status = MemoryConstants.STATUS_ACTIVE, confidence = 1f, importance = 0.5f,
            createdAt = 100L, updatedAt = 100L, createdBy = "MANUAL", updatedBy = "MANUAL"
        )
        repository.cardsList.addAll(listOf(myCard1, otherCard))

        val myTl = MemoryTimelineEntity(
            id = "tl_1", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            title = "我的时间线", status = MemoryConstants.STATUS_ACTIVE, createdAt = 100L, updatedAt = 100L
        )
        val otherTl = MemoryTimelineEntity(
            id = "tl_other", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = otherOwnerId,
            title = "他人时间线", status = MemoryConstants.STATUS_ACTIVE, createdAt = 100L, updatedAt = 100L
        )
        repository.timelinesList.addAll(listOf(myTl, otherTl))
        repository.timelineCardsMap["tl_1"] = listOf(myCard1)
        repository.timelineCardsMap["tl_other"] = listOf(otherCard)

        val myRel = MemoryRelationEntity(
            id = "rel_1", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            sourceCardId = "c1", targetCardId = "c1", relationType = "same_event", status = MemoryConstants.RELATION_STATUS_ACTIVE,
            createdAt = 100L
        )
        val otherRel = MemoryRelationEntity(
            id = "rel_other", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = otherOwnerId,
            sourceCardId = "c_other", targetCardId = "c_other", relationType = "same_event", status = MemoryConstants.RELATION_STATUS_ACTIVE,
            createdAt = 100L
        )
        repository.relationsList.addAll(listOf(myRel, otherRel))

        val vm = LocalMemoryManagerVM(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = currentOwnerId,
            repository = repository,
            settingsStore = null,
            embeddingService = embeddingService,
            currentSettingsProvider = { testSettings },
            coroutineScope = testScope,
        )

        vm.loadMemoryMapData()
        Thread.sleep(200)

        val state = vm.mapUiState.value
        assertEquals(1, state.allCards.size)
        assertEquals("c1", state.allCards.first().id)

        assertEquals(1, state.timelines.size)
        assertEquals("tl_1", state.timelines.first().timeline.id)

        assertEquals(1, state.factEvolutionChains.size)
        assertEquals("fav_color", state.factEvolutionChains.first().factKey)
        assertEquals(1, state.factEvolutionChains.first().currentCards.size)
        assertEquals("c1", state.factEvolutionChains.first().currentCards.first().id)
    }

    @Test
    fun `timeline view only displays active timelines and valid member cards`() {
        val currentOwnerId = asstId.toString()

        val validCard = MemoryCardEntity(
            id = "c1", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            content = "有效卡片", category = MemoryConstants.CATEGORY_PREFERENCE,
            status = MemoryConstants.STATUS_ACTIVE, confidence = 1f, importance = 0.5f,
            validFrom = 200L, createdAt = 100L, updatedAt = 100L, createdBy = "MANUAL", updatedBy = "MANUAL"
        )
        val foreignCard = MemoryCardEntity(
            id = "c_foreign", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = "someone_else",
            content = "跨owner卡片", category = MemoryConstants.CATEGORY_PREFERENCE,
            status = MemoryConstants.STATUS_ACTIVE, confidence = 1f, importance = 0.5f,
            createdAt = 100L, updatedAt = 100L, createdBy = "MANUAL", updatedBy = "MANUAL"
        )
        repository.cardsList.addAll(listOf(validCard, foreignCard))

        val activeTl = MemoryTimelineEntity(
            id = "tl_active", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            title = "活跃时间线", status = MemoryConstants.STATUS_ACTIVE, createdAt = 100L, updatedAt = 100L
        )
        val archivedTl = MemoryTimelineEntity(
            id = "tl_archived", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            title = "已归档时间线", status = "ARCHIVED", createdAt = 100L, updatedAt = 100L
        )
        repository.timelinesList.addAll(listOf(activeTl, archivedTl))

        val missingCard = MemoryCardEntity(
            id = "c_missing", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            content = "未落库卡片", category = MemoryConstants.CATEGORY_PREFERENCE,
            status = MemoryConstants.STATUS_ACTIVE, confidence = 1f, importance = 0.5f,
            createdAt = 100L, updatedAt = 100L, createdBy = "MANUAL", updatedBy = "MANUAL"
        )
        repository.timelineCardsMap["tl_active"] = listOf(validCard, foreignCard, missingCard)

        val vm = LocalMemoryManagerVM(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = currentOwnerId,
            repository = repository,
            settingsStore = null,
            embeddingService = embeddingService,
            currentSettingsProvider = { testSettings },
            coroutineScope = testScope,
        )

        vm.loadMemoryMapData()
        Thread.sleep(200)

        val state = vm.mapUiState.value
        assertEquals(1, state.timelines.size)
        val tl = state.timelines.first()
        assertEquals("tl_active", tl.timeline.id)
        assertEquals(1, tl.cards.size)
        assertEquals("c1", tl.cards.first().id)
    }

    @Test
    fun `relation view correctly identifies incoming and outgoing directions and filters invalid endpoints`() {
        val currentOwnerId = asstId.toString()

        val centerCard = MemoryCardEntity(
            id = "center", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            content = "中心卡片", category = MemoryConstants.CATEGORY_PREFERENCE,
            status = MemoryConstants.STATUS_ACTIVE, confidence = 1f, importance = 0.5f,
            createdAt = 100L, updatedAt = 100L, createdBy = "MANUAL", updatedBy = "MANUAL"
        )
        val targetCard = MemoryCardEntity(
            id = "target_1", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            content = "目标卡片", category = MemoryConstants.CATEGORY_PROFILE,
            status = MemoryConstants.STATUS_ACTIVE, confidence = 1f, importance = 0.5f,
            createdAt = 100L, updatedAt = 100L, createdBy = "MANUAL", updatedBy = "MANUAL"
        )
        val sourceCard = MemoryCardEntity(
            id = "source_1", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            content = "来源卡片", category = MemoryConstants.CATEGORY_PLAN,
            status = MemoryConstants.STATUS_ACTIVE, confidence = 1f, importance = 0.5f,
            createdAt = 100L, updatedAt = 100L, createdBy = "MANUAL", updatedBy = "MANUAL"
        )
        repository.cardsList.addAll(listOf(centerCard, targetCard, sourceCard))

        val outgoingRel = MemoryRelationEntity(
            id = "r_out", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            sourceCardId = "center", targetCardId = "target_1", relationType = "derived_from",
            status = MemoryConstants.RELATION_STATUS_ACTIVE, createdAt = 100L
        )
        val incomingRel = MemoryRelationEntity(
            id = "r_in", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            sourceCardId = "source_1", targetCardId = "center", relationType = "temporal_sequence",
            status = MemoryConstants.RELATION_STATUS_REVIEW, createdAt = 100L
        )
        val danglingRel = MemoryRelationEntity(
            id = "r_dangling", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            sourceCardId = "center", targetCardId = "ghost_card", relationType = "same_event",
            status = MemoryConstants.RELATION_STATUS_ACTIVE, createdAt = 100L
        )
        val archivedRel = MemoryRelationEntity(
            id = "r_archived", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            sourceCardId = "center", targetCardId = "target_1", relationType = "same_event",
            status = MemoryConstants.RELATION_STATUS_ARCHIVED, createdAt = 100L
        )
        repository.relationsList.addAll(listOf(outgoingRel, incomingRel, danglingRel, archivedRel))

        val vm = LocalMemoryManagerVM(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = currentOwnerId,
            repository = repository,
            settingsStore = null,
            embeddingService = embeddingService,
            currentSettingsProvider = { testSettings },
            coroutineScope = testScope,
        )

        vm.loadMemoryMapData()
        Thread.sleep(200)

        vm.selectCenterCard("center")
        val state = vm.mapUiState.value
        assertEquals("center", state.selectedCenterCardId)
        assertEquals(centerCard, state.selectedCenterCard)

        assertEquals(2, state.oneHopRelations.size)

        val outHop = state.oneHopRelations.first { it.relation.id == "r_out" }
        assertTrue(outHop.isOutgoing)
        assertEquals("center", outHop.sourceCard.id)
        assertEquals("target_1", outHop.targetCard.id)

        val inHop = state.oneHopRelations.first { it.relation.id == "r_in" }
        assertEquals(false, inHop.isOutgoing)
        assertEquals("source_1", inHop.sourceCard.id)
        assertEquals("center", inHop.targetCard.id)
    }

    @Test
    fun `fact evolution chains group by factKey and sort versions correctly`() {
        val currentOwnerId = asstId.toString()

        val historyCard = MemoryCardEntity(
            id = "c_hist", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            content = "旧地址：北京", category = MemoryConstants.CATEGORY_PROFILE, factKey = "user_city",
            status = MemoryConstants.STATUS_SUPERSEDED, confidence = 1f, importance = 0.5f,
            validFrom = 100L, createdAt = 100L, updatedAt = 100L, createdBy = "MANUAL", updatedBy = "MANUAL"
        )
        val currentCard = MemoryCardEntity(
            id = "c_curr", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            content = "现地址：上海", category = MemoryConstants.CATEGORY_PROFILE, factKey = "user_city",
            status = MemoryConstants.STATUS_ACTIVE, confidence = 1f, importance = 0.5f,
            validFrom = 200L, createdAt = 200L, updatedAt = 200L, createdBy = "MANUAL", updatedBy = "MANUAL"
        )
        val reviewCard = MemoryCardEntity(
            id = "c_rev", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            content = "拟搬迁：深圳", category = MemoryConstants.CATEGORY_PROFILE, factKey = "user_city",
            status = MemoryConstants.STATUS_REVIEW, confidence = 1f, importance = 0.5f,
            validFrom = 300L, createdAt = 300L, updatedAt = 300L, createdBy = "MANUAL", updatedBy = "MANUAL"
        )
        val noFactKeyCard = MemoryCardEntity(
            id = "c_nofact", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            content = "无事实键卡片", category = MemoryConstants.CATEGORY_OTHER, factKey = null,
            status = MemoryConstants.STATUS_ACTIVE, confidence = 1f, importance = 0.5f,
            createdAt = 400L, updatedAt = 400L, createdBy = "MANUAL", updatedBy = "MANUAL"
        )
        repository.cardsList.addAll(listOf(historyCard, currentCard, reviewCard, noFactKeyCard))

        val vm = LocalMemoryManagerVM(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = currentOwnerId,
            repository = repository,
            settingsStore = null,
            embeddingService = embeddingService,
            currentSettingsProvider = { testSettings },
            coroutineScope = testScope,
        )

        vm.loadMemoryMapData()
        Thread.sleep(200)

        val state = vm.mapUiState.value
        assertEquals(1, state.factEvolutionChains.size)
        val chain = state.factEvolutionChains.first()
        assertEquals("user_city", chain.factKey)

        assertEquals(1, chain.historyCards.size)
        assertEquals("c_hist", chain.historyCards.first().id)

        assertEquals(1, chain.currentCards.size)
        assertEquals("c_curr", chain.currentCards.first().id)

        assertEquals(1, chain.pendingCards.size)
        assertEquals("c_rev", chain.pendingCards.first().id)
    }

    @Test
    fun `map data handles empty database, single card, and dangling relations gracefully without crash`() {
        val currentOwnerId = asstId.toString()

        val vm = LocalMemoryManagerVM(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = currentOwnerId,
            repository = repository,
            settingsStore = null,
            embeddingService = embeddingService,
            currentSettingsProvider = { testSettings },
            coroutineScope = testScope,
        )

        // 1. 空数据库场景
        vm.loadMemoryMapData()
        Thread.sleep(100)
        var state = vm.mapUiState.value
        assertTrue(state.allCards.isEmpty())
        assertTrue(state.timelines.isEmpty())
        assertTrue(state.oneHopRelations.isEmpty())
        assertTrue(state.factEvolutionChains.isEmpty())
        assertNull(state.selectedCenterCardId)

        // 2. 只有孤立关系与悬空端点
        repository.relationsList.add(
            MemoryRelationEntity(
                id = "dangling_only", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
                sourceCardId = "phantom_1", targetCardId = "phantom_2", relationType = "same_event",
                status = MemoryConstants.RELATION_STATUS_ACTIVE, createdAt = 100L
            )
        )
        vm.loadMemoryMapData()
        Thread.sleep(100)
        state = vm.mapUiState.value
        assertTrue(state.oneHopRelations.isEmpty())

        // 3. 只有单张卡片
        val singleCard = MemoryCardEntity(
            id = "single_1", ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT, ownerId = currentOwnerId,
            content = "孤立卡片", category = MemoryConstants.CATEGORY_OTHER, factKey = null,
            status = MemoryConstants.STATUS_ACTIVE, confidence = 1f, importance = 0.5f,
            createdAt = 100L, updatedAt = 100L, createdBy = "MANUAL", updatedBy = "MANUAL"
        )
        repository.cardsList.add(singleCard)
        vm.loadMemoryMapData()
        Thread.sleep(100)
        state = vm.mapUiState.value
        assertEquals(1, state.allCards.size)
        assertEquals("single_1", state.selectedCenterCardId)
        assertTrue(state.oneHopRelations.isEmpty())

        // 4. 选择不存在卡片作为中心
        vm.selectCenterCard("non_existent_id")
        state = vm.mapUiState.value
        assertNull(state.selectedCenterCardId)
        assertNull(state.selectedCenterCard)
        assertTrue(state.oneHopRelations.isEmpty())
    }
}

