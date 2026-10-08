/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 *
 * Portions adapted from Living Memory Coordinate-5 (LMC-5),
 * upstream commit fb3e72c9ee7357c8b17311097b0750082a8a1237, GNU AGPL v3.
 * Adapted for Kotlin/Room on 2026-09-13.
 */

package me.rerere.rikkahub.data.memory.v1

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEProposalEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity
import me.rerere.rikkahub.data.memory.v1.db.MemorySourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryExperienceRepositoryTest {

    private class TestDao : MemoryV1DaoStub() {
        val cards = mutableMapOf<String, MemoryCardEntity>()
        val experiences = mutableMapOf<Long, MemoryExperienceCoordinateEntity>()
        var experienceIdSeq = 1L
        val eProposals = mutableMapOf<Long, MemoryEProposalEntity>()
        var eProposalIdSeq = 1L
        val sources = mutableListOf<MemorySourceEntity>()

        override suspend fun insertCard(card: MemoryCardEntity) { cards[card.id] = card }
        override suspend fun getCardById(id: String): MemoryCardEntity? = cards[id]

        override suspend fun insertSource(source: MemorySourceEntity) { sources.add(source) }
        override suspend fun insertSources(sources: List<MemorySourceEntity>) { this.sources.addAll(sources) }
        override suspend fun getSourcesByMemoryId(memoryId: String): List<MemorySourceEntity> = sources.filter { it.memoryId == memoryId }
        override suspend fun getSourcesForMemories(memoryIds: List<String>): List<MemorySourceEntity> = sources.filter { it.memoryId in memoryIds }

        override suspend fun insertExperienceCoordinate(coord: MemoryExperienceCoordinateEntity): Long {
            val id = if (coord.id == 0L) experienceIdSeq++ else coord.id
            experiences[id] = coord.copy(id = id)
            return id
        }

        override suspend fun getCurrentExperienceForCard(cardId: String): MemoryExperienceCoordinateEntity? {
            return experiences.values.firstOrNull { it.memoryCardId == cardId && it.status == MemoryExperienceCoordinateEntity.STATUS_CURRENT }
        }

        override suspend fun getCurrentExperiencesForCards(cardIds: List<String>): List<MemoryExperienceCoordinateEntity> {
            return experiences.values.filter { it.memoryCardId in cardIds && it.status == MemoryExperienceCoordinateEntity.STATUS_CURRENT }
        }

        override suspend fun getCurrentExperiencesForOwner(ownerType: String, ownerId: String): List<MemoryExperienceCoordinateEntity> {
            return experiences.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == MemoryExperienceCoordinateEntity.STATUS_CURRENT }
                .sortedByDescending { it.eInitialPriority }
        }

        override suspend fun getExperiencesByCardId(cardId: String): List<MemoryExperienceCoordinateEntity> {
            return experiences.values.filter { it.memoryCardId == cardId }.sortedByDescending { it.createdAt }
        }

        override suspend fun updateExperienceStatus(id: Long, status: String, supersededBy: Long?) {
            experiences[id]?.let { experiences[id] = it.copy(status = status, supersededBy = supersededBy) }
        }

        override suspend fun insertEProposal(proposal: MemoryEProposalEntity): Long {
            val id = if (proposal.id == 0L) eProposalIdSeq++ else proposal.id
            eProposals[id] = proposal.copy(id = id)
            return id
        }

        override suspend fun getPendingEProposals(ownerType: String, ownerId: String): List<MemoryEProposalEntity> {
            return eProposals.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == MemoryEProposalEntity.STATUS_PENDING }
                .sortedByDescending { it.createdAt }
        }

        override fun getPendingEProposalsFlow(ownerType: String, ownerId: String) = kotlinx.coroutines.flow.flowOf(
            eProposals.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == MemoryEProposalEntity.STATUS_PENDING }
                .sortedByDescending { it.createdAt }
        )

        override suspend fun getEProposalById(id: Long): MemoryEProposalEntity? = eProposals[id]

        override suspend fun updateEProposalStatus(id: Long, status: String, reviewedAt: Long) {
            eProposals[id]?.let { eProposals[id] = it.copy(status = status, reviewedAt = reviewedAt) }
        }
    }

    private lateinit var dao: TestDao
    private lateinit var repository: MemoryRepositoryV1

    @Before
    fun setUp() {
        dao = TestDao()
        repository = MemoryRepositoryV1(database = null, dao = dao)
    }

    private fun createValidCard(
        id: String = "card_1",
        ownerType: String = "assistant",
        ownerId: String = "ast_1",
        status: String = MemoryConstants.STATUS_ACTIVE,
    ): MemoryCardEntity {
        return MemoryCardEntity(
            id = id,
            ownerType = ownerType,
            ownerId = ownerId,
            content = "测试记忆内容",
            category = MemoryConstants.CATEGORY_EXPERIENCE,
            status = status,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            createdBy = "USER",
            updatedBy = "USER",
        )
    }

    private suspend fun insertRealSourceForCard(
        cardId: String,
        convId: String = "conv_1",
        msgId: String = "msg_1",
    ) {
        dao.insertSource(
            MemorySourceEntity(
                id = "src_$cardId",
                memoryId = cardId,
                conversationId = convId,
                messageId = msgId,
                role = "user",
                sourceKind = "USER_INPUT",
                sourceHash = "conv:$convId:msg:$msgId",
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    @Test
    fun testAddValidExperienceCoordinate(): Unit = runBlocking {
        val card = createValidCard()
        dao.insertCard(card)
        insertRealSourceForCard(card.id, "conv_1", "msg_1")

        val authority = ExperienceAuthority.PrimaryAgent("ast_1", "conv_1")
        val coord = MemoryExperienceCoordinateEntity(
            ownerType = card.ownerType,
            ownerId = card.ownerId,
            memoryCardId = card.id,
            meaning = "当时那次互动让我意识到要更信任小雨",
            eAuthoredBy = authority.authorIdentifier,
            eInitialPriority = 85,
            valence = 0.8,
            arousal = 0.6,
            tension = 0.2,
            emotionConfidence = 0.9,
            responseTendency = MemoryExperienceCoordinateEntity.TENDENCY_ENGAGE,
            growthDelta = MemoryExperienceCoordinateEntity.GROWTH_GROWTH,
            sourceEpisodeKey = "conv:conv_1:msg:msg_1",
            status = MemoryExperienceCoordinateEntity.STATUS_CURRENT,
            createdAt = System.currentTimeMillis(),
        )

        val saved = repository.addExperienceCoordinate(coord, authority)
        assertTrue(saved.id > 0)
        assertEquals(MemoryExperienceCoordinateEntity.STATUS_CURRENT, saved.status)

        val current = repository.getCurrentExperienceForCard(card.id)
        assertNotNull(current)
        assertEquals(saved.id, current?.id)
        assertEquals("当时那次互动让我意识到要更信任小雨", current?.meaning)
        assertEquals(85, current?.eInitialPriority)
    }

    @Test
    fun testDuplicateAddRejectedWithoutSuccessor(): Unit = runBlocking {
        val card = createValidCard()
        dao.insertCard(card)
        insertRealSourceForCard(card.id, "conv_1", "msg_1")

        val authority = ExperienceAuthority.PrimaryAgent("ast_1", "conv_1")
        val coord = MemoryExperienceCoordinateEntity(
            ownerType = card.ownerType,
            ownerId = card.ownerId,
            memoryCardId = card.id,
            meaning = "这让我学到了耐心",
            eAuthoredBy = authority.authorIdentifier,
            eInitialPriority = 50,
            valence = 0.5,
            arousal = 0.3,
            tension = 0.1,
            emotionConfidence = 0.8,
            responseTendency = MemoryExperienceCoordinateEntity.TENDENCY_COMFORT,
            growthDelta = MemoryExperienceCoordinateEntity.GROWTH_STABLE,
            sourceEpisodeKey = "conv:conv_1:msg:msg_1",
            status = MemoryExperienceCoordinateEntity.STATUS_CURRENT,
            createdAt = System.currentTimeMillis(),
        )
        repository.addExperienceCoordinate(coord, authority)

        // Attempting to add another CURRENT coordinate to the same card must fail
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.addExperienceCoordinate(coord.copy(meaning = "我试图直接覆盖"), authority)
            }
        }
    }

    @Test
    fun testAddExperienceSuccessorImmutability(): Unit = runBlocking {
        val card = createValidCard()
        dao.insertCard(card)
        insertRealSourceForCard(card.id, "conv_1", "msg_1")

        val authority = ExperienceAuthority.PrimaryAgent("ast_1", "conv_1")
        val oldCoord = repository.addExperienceCoordinate(
            MemoryExperienceCoordinateEntity(
                ownerType = card.ownerType,
                ownerId = card.ownerId,
                memoryCardId = card.id,
                meaning = "起初我觉得这件事让我很紧张",
                eAuthoredBy = authority.authorIdentifier,
                eInitialPriority = 60,
                valence = -0.4,
                arousal = 0.7,
                tension = 0.8,
                emotionConfidence = 0.9,
                responseTendency = MemoryExperienceCoordinateEntity.TENDENCY_WITHDRAW,
                growthDelta = MemoryExperienceCoordinateEntity.GROWTH_SETBACK,
                sourceEpisodeKey = "conv:conv_1:msg:msg_1",
                status = MemoryExperienceCoordinateEntity.STATUS_CURRENT,
                createdAt = 1000L,
            ),
            authority = authority,
        )

        // Add successor with second real interaction
        insertRealSourceForCard(card.id, "conv_1", "msg_2")
        val newCoord = repository.addExperienceSuccessor(
            oldExperienceId = oldCoord.id,
            newCoordinate = oldCoord.copy(
                meaning = "后来经过深聊，我认识到那是一次宝贵的成长契机",
                valence = 0.6,
                tension = 0.2,
                responseTendency = MemoryExperienceCoordinateEntity.TENDENCY_ENGAGE,
                growthDelta = MemoryExperienceCoordinateEntity.GROWTH_GROWTH,
                sourceEpisodeKey = "conv:conv_1:msg:msg_2",
            ),
            authority = authority,
        )

        assertTrue(newCoord.id > oldCoord.id)
        assertEquals(MemoryExperienceCoordinateEntity.STATUS_CURRENT, newCoord.status)

        // Check old coordinate is superseded with supersededBy pointer
        val history = repository.getExperiencesByCardId(card.id)
        assertEquals(2, history.size)
        val fetchedOld = history.find { it.id == oldCoord.id }
        assertEquals(MemoryExperienceCoordinateEntity.STATUS_SUPERSEDED, fetchedOld?.status)
        assertEquals(newCoord.id, fetchedOld?.supersededBy)

        // Current coordinate query returns the new successor
        val fetchedCurrent = repository.getCurrentExperienceForCard(card.id)
        assertEquals(newCoord.id, fetchedCurrent?.id)
        assertEquals("后来经过深聊，我认识到那是一次宝贵的成长契机", fetchedCurrent?.meaning)
    }

    @Test
    fun testValidationEnforcesConstraints(): Unit = runBlocking {
        val card = createValidCard()
        dao.insertCard(card)
        insertRealSourceForCard(card.id, "conv_1", "msg_1")

        val authority = ExperienceAuthority.PrimaryAgent("ast_1", "conv_1")
        val base = MemoryExperienceCoordinateEntity(
            ownerType = card.ownerType,
            ownerId = card.ownerId,
            memoryCardId = card.id,
            meaning = "我感到温暖",
            eAuthoredBy = authority.authorIdentifier,
            eInitialPriority = 50,
            valence = 0.0,
            arousal = 0.5,
            tension = 0.0,
            emotionConfidence = 1.0,
            responseTendency = MemoryExperienceCoordinateEntity.TENDENCY_ENGAGE,
            growthDelta = MemoryExperienceCoordinateEntity.GROWTH_STABLE,
            sourceEpisodeKey = "conv:conv_1:msg:msg_1",
            status = MemoryExperienceCoordinateEntity.STATUS_CURRENT,
            createdAt = System.currentTimeMillis(),
        )

        // Missing '我' in meaning
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.addExperienceCoordinate(base.copy(meaning = "小雨很喜欢这个"), authority)
            }
        }

        // Out-of-bounds e_initial_priority
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.addExperienceCoordinate(base.copy(eInitialPriority = 101), authority)
            }
        }

        // Out-of-bounds valence
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.addExperienceCoordinate(base.copy(valence = 1.5), authority)
            }
        }

        // NaN / Infinite values
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.addExperienceCoordinate(base.copy(valence = Double.NaN), authority)
            }
        }

        // Blank author or source episode
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.addExperienceCoordinate(base.copy(eAuthoredBy = ""), authority)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.addExperienceCoordinate(base.copy(sourceEpisodeKey = ""), authority)
            }
        }

        // Owner mismatch
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.addExperienceCoordinate(base.copy(ownerId = "other_ast"), authority)
            }
        }
    }

    @Test
    fun testUntrustedAuthorWithoutAuthorityRejected(): Unit = runBlocking {
        val card = createValidCard()
        dao.insertCard(card)
        insertRealSourceForCard(card.id, "conv_1", "msg_1")

        // 试图传未受信任的 author 字符串（如 ast_1 或 nightly-worker）
        val coord = MemoryExperienceCoordinateEntity(
            ownerType = card.ownerType,
            ownerId = card.ownerId,
            memoryCardId = card.id,
            meaning = "这让我学到了耐心",
            eAuthoredBy = "ast_1",
            eInitialPriority = 50,
            sourceEpisodeKey = "conv:conv_1:msg:msg_1",
            status = MemoryExperienceCoordinateEntity.STATUS_CURRENT,
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.addExperienceCoordinate(coord)
            }
        }
    }

    @Test
    fun testFakeEpisodeOrUnverifiedSourceRejected(): Unit = runBlocking {
        val card = createValidCard()
        dao.insertCard(card)
        // 故意不为卡片建立真实 source 证据

        val authority = ExperienceAuthority.PrimaryAgent("ast_1", "conv_1")
        val coord = MemoryExperienceCoordinateEntity(
            ownerType = card.ownerType,
            ownerId = card.ownerId,
            memoryCardId = card.id,
            meaning = "我感到非常欣慰",
            eAuthoredBy = authority.authorIdentifier,
            eInitialPriority = 50,
            sourceEpisodeKey = "conv:conv_1:msg:fake_unverified_msg",
            status = MemoryExperienceCoordinateEntity.STATUS_CURRENT,
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.addExperienceCoordinate(coord, authority)
            }
        }
    }

    @Test
    fun testProposalFlowAndAcceptanceRequiresPrimaryReauthorship(): Unit = runBlocking {
        val card = createValidCard()
        dao.insertCard(card)
        insertRealSourceForCard(card.id, "conv_1", "msg_1")

        // Housekeeper creates a proposal
        val proposal = repository.addEProposal(
            MemoryEProposalEntity(
                ownerType = card.ownerType,
                ownerId = card.ownerId,
                memoryCardId = card.id,
                valence = 0.7,
                arousal = 0.4,
                tension = 0.1,
                responseTendency = MemoryExperienceCoordinateEntity.TENDENCY_CELEBRATE,
                growthDelta = MemoryExperienceCoordinateEntity.GROWTH_GROWTH,
                confidence = 0.85,
                proposer = "housekeeper:nightly",
                rubricVersion = "v1",
                reason = "检测到庆祝时刻",
                sourceEpisodeKey = "conv:conv_1:msg:msg_1",
                status = MemoryEProposalEntity.STATUS_PENDING,
                createdAt = System.currentTimeMillis(),
            )
        )
        assertTrue(proposal.id > 0)

        val pending = repository.getPendingEProposals(card.ownerType, card.ownerId)
        assertEquals(1, pending.size)
        assertEquals(proposal.id, pending[0].id)

        // Accept proposal with user review
        val userAuth = ExperienceAuthority.UserReview()
        val acceptedCoord = repository.acceptEProposal(
            proposalId = proposal.id,
            eAuthoredBy = userAuth.authorIdentifier,
            meaning = "这次成功让我和小雨都由衷地开心，是一次共同的胜利",
            eInitialPriority = 90,
            authority = userAuth,
        )

        assertEquals(MemoryExperienceCoordinateEntity.STATUS_CURRENT, acceptedCoord.status)
        assertEquals(90, acceptedCoord.eInitialPriority)
        assertEquals("USER", acceptedCoord.eAuthoredBy)
        assertEquals("conv:conv_1:msg:msg_1", acceptedCoord.sourceEpisodeKey)

        // Proposal is now accepted and removed from pending flow
        val pendingAfter = repository.getPendingEProposalsFlow(card.ownerType, card.ownerId).first()
        assertTrue(pendingAfter.isEmpty())
    }

    @Test
    fun testManualProposalAcceptanceDoesNotRequireStoredDialogueEvidence(): Unit = runBlocking {
        val card = createValidCard()
        dao.insertCard(card)
        // 卡片无有效对话 sources，且提案无有效来源
        val proposal = repository.addEProposal(
            MemoryEProposalEntity(
                ownerType = card.ownerType,
                ownerId = card.ownerId,
                memoryCardId = card.id,
                meaning = "我意识到陪伴很重要",
                proposer = "consolidation",
                sourceEpisodeKey = "consolidation_ast_1_2026-09-13",
                status = MemoryEProposalEntity.STATUS_PENDING,
                createdAt = System.currentTimeMillis(),
            )
        )

        val accepted = repository.acceptEProposal(proposal.id)

        assertEquals("manual-review:${proposal.id}", accepted.sourceEpisodeKey)
        assertEquals(MemoryExperienceCoordinateEntity.STATUS_CURRENT, accepted.status)
    }

    @Test
    fun testConsolidationKeyForbiddenForPrimaryAgent(): Unit = runBlocking {
        val card = createValidCard()
        dao.insertCard(card)
        insertRealSourceForCard(card.id, "conv_1", "msg_1")

        val authority = ExperienceAuthority.PrimaryAgent("ast_1", "conv_1")
        val coord = MemoryExperienceCoordinateEntity(
            ownerType = card.ownerType,
            ownerId = card.ownerId,
            memoryCardId = card.id,
            meaning = "我很喜欢小雨",
            eAuthoredBy = authority.authorIdentifier,
            eInitialPriority = 50,
            valence = 0.5,
            arousal = 0.5,
            responseTendency = MemoryExperienceCoordinateEntity.TENDENCY_ENGAGE,
            sourceEpisodeKey = "consolidation_ast_1_2026-09-13",
            status = MemoryExperienceCoordinateEntity.STATUS_CURRENT,
            createdAt = System.currentTimeMillis(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.addExperienceCoordinate(coord, authority)
            }
        }
    }

    @Test
    fun testInvalidResponseTendencyRejected(): Unit = runBlocking {
        val card = createValidCard()
        dao.insertCard(card)
        insertRealSourceForCard(card.id, "conv_1", "msg_1")

        val authority = ExperienceAuthority.PrimaryAgent("ast_1", "conv_1")
        val coord = MemoryExperienceCoordinateEntity(
            ownerType = card.ownerType,
            ownerId = card.ownerId,
            memoryCardId = card.id,
            meaning = "我很喜欢小雨",
            eAuthoredBy = authority.authorIdentifier,
            eInitialPriority = 50,
            valence = 0.5,
            arousal = 0.5,
            responseTendency = "INVALID_TENDENCY",
            sourceEpisodeKey = "conv:conv_1:msg:msg_1",
            status = MemoryExperienceCoordinateEntity.STATUS_CURRENT,
            createdAt = System.currentTimeMillis(),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.addExperienceCoordinate(coord, authority)
            }
        }
    }

    @Test
    fun testProposalPersistsMeaningAndUsedInAccept(): Unit = runBlocking {
        val card = createValidCard()
        dao.insertCard(card)
        insertRealSourceForCard(card.id, "conv_1", "msg_1")

        val proposal = repository.addEProposal(
            memoryCardId = card.id,
            ownerType = card.ownerType,
            ownerId = card.ownerId,
            sourceEpisodeKey = "conv:conv_1:msg:msg_1",
            proposer = "consolidation",
            meaning = "我意识到在小雨疲惫时，静静陪伴最重要",
            initialPriority = 80,
            responseTendency = "OBSERVE",
            valence = 0.2f,
            arousal = 0.3f,
            tension = 0.1f,
        )

        assertEquals("我意识到在小雨疲惫时，静静陪伴最重要", proposal.meaning)
        assertEquals(80, proposal.initialPriority)

        val accepted = repository.acceptEProposal(proposal.id)
        assertEquals("我意识到在小雨疲惫时，静静陪伴最重要", accepted.meaning)
        assertEquals(80, accepted.eInitialPriority)
        assertEquals(MemoryExperienceCoordinateEntity.TENDENCY_OBSERVE, accepted.responseTendency)
        assertEquals("conv:conv_1:msg:msg_1", accepted.sourceEpisodeKey)
    }

    @Test
    fun testProposalAcceptanceFallsBackFromUnverifiedEpisodeKeyToCardEvidence(): Unit = runBlocking {
        val card = createValidCard()
        dao.insertCard(card)
        insertRealSourceForCard(card.id, "conv_1", "msg_1")

        val proposal = repository.addEProposal(
            memoryCardId = card.id,
            ownerType = card.ownerType,
            ownerId = card.ownerId,
            sourceEpisodeKey = "conv:other_conversation:msg:other_message",
            proposer = "consolidation",
            meaning = "我从这次交流中体会到耐心回应很重要",
            initialPriority = 70,
            responseTendency = "ENGAGE",
            valence = 0.3f,
            arousal = 0.4f,
            tension = 0.1f,
        )

        val accepted = repository.acceptEProposal(proposal.id)

        assertEquals("conv:conv_1:msg:msg_1", accepted.sourceEpisodeKey)
        assertEquals(MemoryExperienceCoordinateEntity.STATUS_CURRENT, accepted.status)
    }
}
