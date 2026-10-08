/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
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
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryConsolidationServiceTest {

    @Test
    fun `background reading becomes a grounded memory with its original source retained`() = runBlocking {
        val dao = FakeDao()
        val assistantId = Uuid.random()
        val model = Model(id = Uuid.random(), displayName = "TestModel", modelId = "test-model")
        val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "TestProvider", models = listOf(model))
        val settings = Settings(assistants = listOf(Assistant(id = assistantId)), providers = listOf(provider), chatModelId = model.id)
        val run = me.rerere.rikkahub.data.ai.desire.db.AutonomousWakeRunEntity(
            id = "reading", assistantId = assistantId.toString(), scheduledAt = MemoryDateUtils.getMemoryDateRange("2026-09-26").first,
            finishedAt = MemoryDateUtils.getMemoryDateRange("2026-09-26").first + 200L,
            status = "completed", summary = "我独处时读了一章书", outcome = "我读到狐狸谈到关系需要时间和耐心",
        )
        val chunk = autonomousWakeMemoryChunk(run)!!
        dao.eventChunks.add(chunk)
        val service = TestableService(dao, settings = settings)
        service.mockedMessages = listOf(chunk.asAutonomousMemoryMessage()!!)
        service.mockedModelOutputs = listOf(
            """{"patches":[{"action":"ADD","category":"other","content":"我读到狐狸谈到关系需要时间和耐心","source_ids":["autonomous:reading"],"evidence_snippet":"我读到狐狸谈到关系需要时间和耐心","confidence":0.95,"importance":0.8}],"timelines":[],"relations":[]}""",
            """{"daily_note":{"summary":"我独处时读了一章书，想到关系需要耐心。","emotional_thread":"我有所触动"}}""",
        )
        val result = service.consolidateDate(MemoryConstants.OWNER_TYPE_ASSISTANT, assistantId.toString(), "2026-09-26")
        assertTrue("$result", result is ConsolidationServiceResult.Success)
        assertTrue(dao.cards.values.any { it.content == run.outcome && it.status == MemoryConstants.STATUS_ACTIVE })
        assertTrue(dao.sources.any { it.messageId == chunk.id && it.role == "AUTONOMOUS" })
        assertTrue(dao.eventChunks.contains(chunk))
        assertTrue(service.modelCalls.all { it.userPrompt.contains("AUTONOMOUS") })
    }

    @Test
    fun `archived background experience is loaded without any conversation`() = runBlocking {
        val assistantId = Uuid.random()
        val dao = FakeDao()
        val repo = MemoryRepositoryV1(database = null, dao = dao)
        val run = me.rerere.rikkahub.data.ai.desire.db.AutonomousWakeRunEntity(
            id = "background-only", assistantId = assistantId.toString(), scheduledAt = 100L,
            finishedAt = 200L, status = "completed", summary = "我读了一章书", outcome = "我想到关系需要耐心",
        )
        val source = autonomousWakeMemoryChunk(run)!!
        repo.saveEventChunks(listOf(source))
        val service = MemoryConsolidationService(repository = repo)
        val loaded = service.loadAndCleanMessages(assistantId, 100L, 300L)
        assertEquals(1, loaded.size)
        assertEquals("AUTONOMOUS", loaded.single().role)
        assertTrue(loaded.single().content.contains(run.outcome))
        assertTrue(service.loadAndCleanMessages(Uuid.random(), 100L, 300L).isEmpty())
        assertTrue(service.loadAndCleanMessages(assistantId, 300L, 400L).isEmpty())
        repo.deleteEventChunksByDateRange(MemoryConstants.OWNER_TYPE_ASSISTANT, assistantId.toString(), 100L, 300L)
        assertEquals(source, repo.getEventChunkById(source.id))
    }

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
        val eProposals = mutableMapOf<Long, MemoryEProposalEntity>()
        private var nextEProposalId = 1L
        val experiences = mutableMapOf<Long, MemoryExperienceCoordinateEntity>()
        private var nextExpId = 1L

        override suspend fun insertEProposal(proposal: MemoryEProposalEntity): Long {
            val id = if (proposal.id != 0L) proposal.id else nextEProposalId++
            eProposals[id] = proposal.copy(id = id)
            return id
        }
        override suspend fun insertEProposals(proposals: List<MemoryEProposalEntity>): List<Long> {
            return proposals.map { insertEProposal(it) }
        }
        override suspend fun getPendingEProposals(ownerType: String, ownerId: String): List<MemoryEProposalEntity> =
            eProposals.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == "pending" }
        override fun getPendingEProposalsFlow(ownerType: String, ownerId: String): Flow<List<MemoryEProposalEntity>> =
            flowOf(eProposals.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == "pending" })
        override suspend fun getEProposalById(id: Long): MemoryEProposalEntity? = eProposals[id]
        override suspend fun updateEProposalStatus(id: Long, status: String, reviewedAt: Long) {
            eProposals[id]?.let { eProposals[id] = it.copy(status = status, reviewedAt = reviewedAt) }
        }
        override suspend fun deleteEProposalById(id: Long) { eProposals.remove(id) }

        override suspend fun insertExperienceCoordinate(coord: MemoryExperienceCoordinateEntity): Long {
            val id = if (coord.id != 0L) coord.id else nextExpId++
            experiences[id] = coord.copy(id = id)
            return id
        }
        override suspend fun insertExperienceCoordinates(coords: List<MemoryExperienceCoordinateEntity>): List<Long> {
            return coords.map { insertExperienceCoordinate(it) }
        }
        override suspend fun getCurrentExperienceForCard(cardId: String): MemoryExperienceCoordinateEntity? =
            experiences.values.firstOrNull { it.memoryCardId == cardId && it.status == "CURRENT" }
        override suspend fun getCurrentExperiencesForCards(cardIds: List<String>): List<MemoryExperienceCoordinateEntity> =
            experiences.values.filter { it.memoryCardId in cardIds && it.status == "CURRENT" }
        override suspend fun getCurrentExperiencesForOwner(ownerType: String, ownerId: String): List<MemoryExperienceCoordinateEntity> =
            experiences.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == "CURRENT" }
        override suspend fun getExperiencesByCardId(cardId: String): List<MemoryExperienceCoordinateEntity> =
            experiences.values.filter { it.memoryCardId == cardId }
        override suspend fun getExperienceById(id: Long): MemoryExperienceCoordinateEntity? = experiences[id]
        override suspend fun updateExperienceStatus(id: Long, status: String, supersededBy: Long?) {
            experiences[id]?.let { experiences[id] = it.copy(status = status, supersededBy = supersededBy) }
        }
        override suspend fun deleteExperienceByCardId(cardId: String) {
            val toRemove = experiences.filterValues { it.memoryCardId == cardId }.keys
            toRemove.forEach { experiences.remove(it) }
        }

        override suspend fun insertCard(card: MemoryCardEntity) { cards[card.id] = card }
        override suspend fun insertCards(cards: List<MemoryCardEntity>) { cards.forEach { insertCard(it) } }
        override suspend fun updateCard(card: MemoryCardEntity) { cards[card.id] = card }
        override suspend fun getCardById(id: String): MemoryCardEntity? = cards[id]
        override suspend fun getCardsByIds(ids: List<String>): List<MemoryCardEntity> = ids.mapNotNull { cards[it] }
        override suspend fun getAllCards(ownerType: String, ownerId: String): List<MemoryCardEntity> = cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId }
        override fun getAllCardsFlow(ownerType: String, ownerId: String) = kotlinx.coroutines.flow.flowOf(cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId })
        override suspend fun getCardsByStatus(ownerType: String, ownerId: String, status: String): List<MemoryCardEntity> = cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }
        override fun getCardsByStatusFlow(ownerType: String, ownerId: String, status: String) = kotlinx.coroutines.flow.flowOf(cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status })
        override suspend fun getCardsByStatusPaged(ownerType: String, ownerId: String, status: String, limit: Int, offset: Int): List<MemoryCardEntity> = cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }.drop(offset).take(limit)
        override suspend fun countCardsByStatus(ownerType: String, ownerId: String, status: String): Int = cards.values.count { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }
        override suspend fun getCardsByStatusList(ownerType: String, ownerId: String, statusList: List<String>): List<MemoryCardEntity> = cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status in statusList }
        override fun getCardsByStatusListFlow(ownerType: String, ownerId: String, statusList: List<String>) = kotlinx.coroutines.flow.flowOf(cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status in statusList })
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
        override fun getDailyNotesFlow(ownerType: String, ownerId: String) = kotlinx.coroutines.flow.flowOf(notes.values.filter { it.ownerType == ownerType && it.ownerId == ownerId })
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
        override suspend fun deleteEventChunksByDateRange(ownerType: String, ownerId: String, startMs: Long, endMs: Long) { eventChunks.removeAll { it.ownerType == ownerType && it.ownerId == ownerId && it.startTime >= startMs && it.endTime <= endMs && it.status != "AUTONOMOUS_SOURCE" } }

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

    private class FakeEmbeddingService(repo: MemoryRepositoryV1) : MemoryEmbeddingService(repo) {
        val enqueuedCards = mutableListOf<Pair<MemoryCardEntity, Uuid>>()
        override fun enqueueSyncCard(card: MemoryCardEntity, embeddingModelId: Uuid, settings: Settings): kotlinx.coroutines.Job {
            enqueuedCards.add(card to embeddingModelId)
            return kotlinx.coroutines.Job().apply { complete() }
        }
    }

    private class TestableService(
        val fakeDao: FakeDao,
        var mockedModelOutput: String = "",
        var mockedMessages: List<CleanedDialogueMessage> = emptyList(),
        settings: Settings,
        embeddingService: MemoryEmbeddingService? = null,
    ) : MemoryConsolidationService(
        repository = MemoryRepositoryV1(database = null, dao = fakeDao),
        testSettingsProvider = { settings },
        embeddingService = embeddingService,
    ) {
        override suspend fun loadAndCleanMessages(assistantId: Uuid, startMs: Long, endMs: Long): List<CleanedDialogueMessage> {
            return mockedMessages
        }

        var onGenerateModelText: (() -> Unit)? = null
        val modelCalls = mutableListOf<ModelCall>()
        var mockedModelOutputs: List<String>? = null

        data class ModelCall(
            val systemPrompt: String,
            val userPrompt: String,
            val temperature: Float,
            val reasoningLevel: ReasoningLevel,
        )

        override suspend fun generateModelText(
            providerSetting: ProviderSetting,
            model: Model,
            systemPrompt: String,
            userPrompt: String,
            temperature: Float,
            reasoningLevel: ReasoningLevel,
        ): String {
            if (mockedModelOutput == "CANCEL") {
                throw kotlinx.coroutines.CancellationException("Test cancellation")
            }
            modelCalls += ModelCall(systemPrompt, userPrompt, temperature, reasoningLevel)
            onGenerateModelText?.invoke()
            return mockedModelOutputs?.let { outputs ->
                outputs.getOrNull(if (userPrompt.contains("\"daily_note\"")) 1 else 0)
            } ?: mockedModelOutput
        }
    }

    @Test
    fun `consolidation uses separate structured and daily note calls then merges their results`() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val assistant = Assistant(id = assistantId, name = "橘瓣助手")
        val model = Model(id = Uuid.random(), displayName = "TestModel", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "TestProvider", models = listOf(model))
        val settings = Settings(assistants = listOf(assistant), providers = listOf(provider), chatModelId = model.id)
        val service = TestableService(fakeDao, settings = settings)
        service.mockedMessages = listOf(CleanedDialogueMessage("c1", "m1", "USER", "我决定开始学日语", 1000L))
        service.mockedModelOutputs = listOf(
            """
                {
                  "patches": [{ "action": "NOOP", "reason": "暂不建立长期事实" }],
                  "timelines": [],
                  "relations": []
                }
            """.trimIndent(),
            """
                {
                  "daily_note": {
                    "summary": "今天我和小雨聊起学习日语的开始。",
                    "emotional_thread": "我感到期待",
                    "unfinished_topics": ["我希望继续制定学习计划"]
                  }
                }
            """.trimIndent(),
        )

        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = "2026-09-08",
        )

        assertTrue(result is ConsolidationServiceResult.Success)
        assertEquals("今天我和小雨聊起学习日语的开始。", (result as ConsolidationServiceResult.Success).dailyNote.summary)
        assertEquals(2, service.modelCalls.size)
        val dailyNoteCall = service.modelCalls[0]
        val structuredCall = service.modelCalls[1]
        assertEquals(0.2f, structuredCall.temperature)
        assertEquals(ReasoningLevel.AUTO, structuredCall.reasoningLevel)
        assertEquals(0.7f, dailyNoteCall.temperature)
        assertEquals(ReasoningLevel.AUTO, dailyNoteCall.reasoningLevel)
        assertTrue(structuredCall.userPrompt.contains("\"patches\""))
        assertTrue(structuredCall.userPrompt.contains("【今日日记参考】"))
        assertTrue(structuredCall.userPrompt.contains("今天我和小雨聊起学习日语的开始。"))
        assertTrue(structuredCall.userPrompt.contains("不得将日记中的措辞、推断或情绪当作证据"))
        assertTrue(structuredCall.systemPrompt.contains("第一人称“我”"))
        assertTrue(dailyNoteCall.userPrompt.contains("\"daily_note\""))
        assertFalse(dailyNoteCall.userPrompt.contains("\"patches\""))
        assertTrue(dailyNoteCall.systemPrompt.contains("第一人称“我”"))
    }

    @Test
    fun `consolidation writes nothing when either split response is invalid`() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val assistant = Assistant(id = assistantId, name = "橘瓣助手")
        val model = Model(id = Uuid.random(), displayName = "TestModel", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "TestProvider", models = listOf(model))
        val settings = Settings(assistants = listOf(assistant), providers = listOf(provider), chatModelId = model.id)
        val service = TestableService(fakeDao, settings = settings)
        service.mockedMessages = listOf(CleanedDialogueMessage("c1", "m1", "USER", "今天聊了计划", 1000L))
        service.mockedModelOutputs = listOf(
            "{ \"patches\": [], \"timelines\": [], \"relations\": [] }",
            "{ \"daily_note\": { \"emotional_thread\": \"平静\" } }",
        )

        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = "2026-09-08",
        )

        assertTrue(result is ConsolidationServiceResult.Failed)
        assertTrue(fakeDao.notes.isEmpty())
        assertTrue(fakeDao.cards.isEmpty())
        assertTrue(fakeDao.relations.isEmpty())
        assertTrue(fakeDao.timelines.isEmpty())
    }

    @Test
    fun `consolidation fails without a first person daily note`() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val assistant = Assistant(id = assistantId, name = "橘瓣助手")
        val model = Model(id = Uuid.random(), displayName = "TestModel", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "TestProvider", models = listOf(model))
        val settings = Settings(assistants = listOf(assistant), providers = listOf(provider), chatModelId = model.id)
        val service = TestableService(fakeDao, settings = settings)
        service.mockedMessages = listOf(CleanedDialogueMessage("c1", "m1", "USER", "今天聊了计划", 1000L))
        service.mockedModelOutputs = listOf(
            "{ \"patches\": [], \"timelines\": [], \"relations\": [] }",
            "{ \"daily_note\": { \"summary\": \"今天聊了计划\" } }",
        )

        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = "2026-09-08",
        )

        assertTrue(result is ConsolidationServiceResult.Failed)
        assertTrue(fakeDao.notes.isEmpty())
        assertTrue(fakeDao.cards.isEmpty())
    }

    @Test
    fun `consolidation succeeds when daily note emotion or unfinished topic lacks first person`() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val assistant = Assistant(id = assistantId, name = "橘瓣助手")
        val model = Model(id = Uuid.random(), displayName = "TestModel", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "TestProvider", models = listOf(model))
        val settings = Settings(assistants = listOf(assistant), providers = listOf(provider), chatModelId = model.id)
        val service = TestableService(fakeDao, settings = settings)
        service.mockedMessages = listOf(CleanedDialogueMessage("c1", "m1", "USER", "今天聊了计划", 1000L))
        service.mockedModelOutputs = listOf(
            "{ \"patches\": [], \"timelines\": [], \"relations\": [] }",
            """
                {
                  "daily_note": {
                    "summary": "我记录了今天的计划。",
                    "emotional_thread": "期待",
                    "unfinished_topics": ["继续讨论计划"]
                  }
                }
            """.trimIndent(),
        )

        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = "2026-09-08",
        )

        assertTrue(result is ConsolidationServiceResult.Success)
        assertEquals(1, fakeDao.notes.size)
        assertEquals("我记录了今天的计划。", fakeDao.notes.values.first().summary)
        assertEquals("期待", fakeDao.notes.values.first().emotionalThread)
    }

    @Test
    fun testMessageCleaningExcludesSystemAndInternalContexts() {
        val fakeDao = FakeDao()
        val service = MemoryConsolidationService(
            repository = MemoryRepositoryV1(database = null, dao = fakeDao)
        )

        val nowMs = System.currentTimeMillis()
        val startMs = nowMs - 10_000L
        val endMs = nowMs + 10_000L
        val currentLdt = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(TimeZone.currentSystemDefault())

        // 1. SYSTEM message should be excluded
        val systemMsg = UIMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text("system instruction")),
            createdAt = currentLdt,
        )
        assertNull(service.cleanMessageOrNull("conv_1", systemMsg, startMs, endMs))

        // 2. dynamic_environment metadata should be excluded
        val envMsg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("[设备状态]")),
            createdAt = currentLdt,
            metadata = buildJsonObject { put("dynamic_environment", "true") },
        )
        assertNull(service.cleanMessageOrNull("conv_1", envMsg, startMs, endMs))

        // 3. memory_context metadata should be excluded
        val memoryContextMsg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("<local_memory_context>")),
            createdAt = currentLdt,
            metadata = buildJsonObject { put("memory_context", "true") },
        )
        assertNull(service.cleanMessageOrNull("conv_1", memoryContextMsg, startMs, endMs))

        // 4. plugin_context metadata should be excluded
        val pluginMsg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("<plugin_context>")),
            createdAt = currentLdt,
            metadata = buildJsonObject { put("plugin_context", "true") },
        )
        assertNull(service.cleanMessageOrNull("conv_1", pluginMsg, startMs, endMs))

        // 5. Out of range message should be excluded
        val oldLdt = Instant.fromEpochMilliseconds(startMs - 50_000L).toLocalDateTime(TimeZone.currentSystemDefault())
        val oldMsg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("昨天的旧消息")),
            createdAt = oldLdt,
        )
        assertNull(service.cleanMessageOrNull("conv_1", oldMsg, startMs, endMs))

        // 6. Normal USER message should be preserved
        val validUserMsg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("小雨今天想去吃火锅")),
            createdAt = currentLdt,
        )
        val cleaned = service.cleanMessageOrNull("conv_1", validUserMsg, startMs, endMs)
        assertNotNull(cleaned)
        assertEquals("USER", cleaned?.role)
        assertEquals("小雨今天想去吃火锅", cleaned?.content)
    }

    @Test
    fun testFingerprintDeterministicAndSensitiveToChanges() {
        val fakeDao = FakeDao()
        val service = MemoryConsolidationService(
            repository = MemoryRepositoryV1(database = null, dao = fakeDao)
        )

        val list1 = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "你好呀", 1000L),
            CleanedDialogueMessage("c1", "m2", "ASSISTANT", "你好，小雨！", 2000L),
        )
        val list2 = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "你好呀", 1000L),
            CleanedDialogueMessage("c1", "m2", "ASSISTANT", "你好，小雨！", 2000L),
        )
        val list3 = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "你好呀修改了", 1000L),
            CleanedDialogueMessage("c1", "m2", "ASSISTANT", "你好，小雨！", 2000L),
        )

        val fp1 = service.computeFingerprint(list1)
        val fp2 = service.computeFingerprint(list2)
        val fp3 = service.computeFingerprint(list3)

        assertEquals(fp1, fp2)
        assertFalse(fp1 == fp3)
    }

    @Test
    fun testFingerprintSkipWhenUnchangedAndForceOverride() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val assistant = Assistant(
            id = assistantId,
            name = "橘瓣助手",
        )
        val model = Model(id = Uuid.random(), displayName = "TestModel", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "TestProvider",
            models = listOf(model),
        )
        val settings = Settings(
            assistants = listOf(assistant),
            providers = listOf(provider),
            chatModelId = model.id,
        )

        val service = TestableService(fakeDao, settings = settings)
        val memoryDate = "2026-09-08"

        service.mockedMessages = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "今天天气真好", 1000L)
        )
        service.mockedModelOutput = """
            {
              "daily_note": {
                "summary": "我今天和小雨聊了天气",
                "emotional_thread": "我感到愉快",
                "unfinished_topics": []
              },
              "patches": [
                {
                  "action": "NOOP",
                  "reason": "闲聊无需建卡"
                }
              ]
            }
        """.trimIndent()

        // 1. 首次运行：执行并落库
        val res1 = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = memoryDate,
            force = false,
        )
        assertTrue(res1 is ConsolidationServiceResult.Success)
        assertFalse((res1 as ConsolidationServiceResult.Success).skippedByFingerprint)
        assertEquals("我今天和小雨聊了天气", res1.dailyNote.summary)

        // 2. 第二次运行（消息内容完全不变）：被 fingerprint 跳过
        val res2 = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = memoryDate,
            force = false,
        )
        assertTrue(res2 is ConsolidationServiceResult.Success)
        assertTrue((res2 as ConsolidationServiceResult.Success).skippedByFingerprint)

        // 3. 第三次运行（即使 fingerprint 相同，传入 force = true）：强制执行重新整理
        val res3 = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = memoryDate,
            force = true,
        )
        assertTrue(res3 is ConsolidationServiceResult.Success)
        assertFalse((res3 as ConsolidationServiceResult.Success).skippedByFingerprint)
    }

    @Test
    fun testConsolidationEndToEndWithPatches() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val assistant = Assistant(
            id = assistantId,
            name = "测试助手",
        )
        val model = Model(id = Uuid.random(), displayName = "TestModel", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "TestProvider",
            models = listOf(model),
        )
        val settings = Settings(
            assistants = listOf(assistant),
            providers = listOf(provider),
            chatModelId = model.id,
        )

        val service = TestableService(fakeDao, settings = settings)
        val memoryDate = "2026-09-08"

        service.mockedMessages = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "小雨喜欢喝无糖乌龙茶", 1000L)
        )
        service.mockedModelOutput = """
            {
              "daily_note": {
                "summary": "我记住了小雨提到的茶饮偏好",
                "emotional_thread": "我感到轻松日常",
                "unfinished_topics": ["我下次推荐乌龙茶品牌"]
              },
              "patches": [
                {
                  "action": "ADD",
                  "category": "preference",
                  "fact_key": "tea.oolong.nosugar",
                  "content": "我记得小雨喜欢喝无糖乌龙茶",
                  "confidence": 0.95,
                  "importance": 0.6,
                  "source_ids": ["m1"],
                  "evidence_snippet": "小雨喜欢喝无糖乌龙茶",
                  "reason": "明确表达的饮品喜好"
                }
              ]
            }
        """.trimIndent()

        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = memoryDate,
        )

        assertTrue(result is ConsolidationServiceResult.Success)
        val success = result as ConsolidationServiceResult.Success
        assertEquals(1, success.appliedPatches)
        assertEquals(0, success.conflictedPatches)

        // 验证 DailyNote 写入
        val savedNote = fakeDao.getDailyNote(assistantId.toString(), memoryDate)
        assertNotNull(savedNote)
        assertEquals("我记住了小雨提到的茶饮偏好", savedNote?.summary)
        assertTrue(savedNote?.unfinishedTopics?.contains("我下次推荐乌龙茶品牌") == true)

        // 验证 MemoryCard 写入
        val savedCards = fakeDao.getAllCards(MemoryConstants.OWNER_TYPE_ASSISTANT, assistantId.toString())
        assertEquals(1, savedCards.size)
        assertEquals("我记得小雨喜欢喝无糖乌龙茶", savedCards.first().content)
        assertEquals("tea.oolong.nosugar", savedCards.first().factKey)
        assertEquals(MemoryConstants.ACTOR_NIGHTLY_MODEL, savedCards.first().createdBy)
    }

    @Test
    fun testEmptyDialogueFingerprintConsistencyAndFastSkip() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val model = Model(id = Uuid.random(), modelId = "test-model")
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "TestProvider",
            models = listOf(model),
        )
        val assistant = Assistant(
            id = assistantId,
            name = "测试助手",
            chatModelId = model.id,
        )
        val settings = Settings(
            assistants = listOf(assistant),
            providers = listOf(provider),
            chatModelId = model.id,
        )

        val service = TestableService(fakeDao, settings = settings)
        val memoryDate = "2026-09-08"
        // 模拟当天无对话
        service.mockedMessages = emptyList()

        // 第一次执行：应当落库并且计算出真实的空列表 SHA-256 fingerprint
        val result1 = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = memoryDate,
            force = false,
        )
        assertTrue(result1 is ConsolidationServiceResult.Skipped)

        val run = fakeDao.getRun(assistantId.toString(), memoryDate)
        assertNotNull(run)
        assertEquals(MemoryConstants.RUN_STATUS_SUCCEEDED, run?.status)
        assertNotNull(run?.sourceFingerprint)
        // 确认不是硬编码的 "EMPTY"，而是真实的 SHA-256
        assertFalse(run?.sourceFingerprint == "EMPTY")
        assertEquals(64, run?.sourceFingerprint?.length)

        // 第二次执行（非 force）：快速幂等检查必须命中 fingerprint 相等直接返回，不应重新申请租约
        val result2 = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = memoryDate,
            force = false,
        )
        assertTrue(result2 is ConsolidationServiceResult.Skipped)
        assertEquals("Fingerprint unchanged and note already processed", (result2 as ConsolidationServiceResult.Skipped).reason)
    }

    @Test
    fun testNoEmbeddingEnqueuedWhenAssistantHasNoEmbeddingModel() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val fakeEmbedding = FakeEmbeddingService(MemoryRepositoryV1(database = null, dao = fakeDao))
        val assistantId = Uuid.random()
        val chatModelId = Uuid.random()
        val model = Model(id = chatModelId, modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "TestProvider",
            models = listOf(model),
        )
        // 助手未配置 memoryEmbeddingModelId
        val assistant = Assistant(
            id = assistantId,
            name = "纯文本助手",
            chatModelId = chatModelId,
            memoryEmbeddingModelId = null,
        )
        val settings = Settings(
            assistants = listOf(assistant),
            providers = listOf(provider),
            chatModelId = chatModelId,
        )

        val service = TestableService(
            fakeDao = fakeDao,
            settings = settings,
            embeddingService = fakeEmbedding,
        )
        val memoryDate = "2026-09-08"
        service.mockedMessages = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "小雨喜欢苹果", 1000L)
        )
        service.mockedModelOutput = """
            {
              "daily_note": { "summary": "我整理了今天的偏好" },
              "patches": [
                {
                  "action": "ADD",
                  "category": "preference",
                  "content": "我记得小雨喜欢苹果",
                  "confidence": 0.9
                }
              ]
            }
        """.trimIndent()

        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = memoryDate,
        )
        assertTrue(result is ConsolidationServiceResult.Success)
        // 验证：严禁使用 chatModelId 触发向量入队，列表必须为空！
        assertTrue(fakeEmbedding.enqueuedCards.isEmpty())

        // 假如有配置向量嵌入模型，则应当正常入队
        val embModelId = Uuid.random()
        val assistantWithEmb = assistant.copy(memoryEmbeddingModelId = embModelId)
        val settingsWithEmb = settings.copy(assistants = listOf(assistantWithEmb))
        val serviceWithEmb = TestableService(
            fakeDao = fakeDao,
            settings = settingsWithEmb,
            embeddingService = fakeEmbedding,
        )
        serviceWithEmb.mockedMessages = listOf(
            CleanedDialogueMessage("c1", "m2", "USER", "小雨喜欢橙子", 2000L)
        )
        serviceWithEmb.mockedModelOutput = """
            {
              "daily_note": { "summary": "我整理了新的偏好" },
              "patches": [
                {
                  "action": "ADD",
                  "category": "preference",
                  "content": "我记得小雨喜欢橙子",
                  "confidence": 0.9,
                  "source_ids": ["m2"],
                  "evidence_snippet": "小雨喜欢橙子"
                }
              ]
            }
        """.trimIndent()

        val resultWithEmb = serviceWithEmb.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = "2026-09-09",
        )
        assertTrue(resultWithEmb is ConsolidationServiceResult.Success)
        assertEquals(1, fakeEmbedding.enqueuedCards.size)
        assertEquals(embModelId, fakeEmbedding.enqueuedCards.first().second)
        assertEquals("我记得小雨喜欢橙子", fakeEmbedding.enqueuedCards.first().first.content)
    }

    @Test
    fun testCancellationReleasesLease() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val chatModelId = Uuid.random()
        val model = Model(id = chatModelId, modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "TestProvider",
            models = listOf(model),
        )
        val assistant = Assistant(
            id = assistantId,
            name = "测试助手",
            chatModelId = chatModelId,
        )
        val settings = Settings(
            assistants = listOf(assistant),
            providers = listOf(provider),
            chatModelId = chatModelId,
        )

        val service = TestableService(fakeDao, settings = settings)
        val memoryDate = "2026-09-08"
        service.mockedMessages = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "一些文本", 1000L)
        )
        // 触发模拟的 CancellationException
        service.mockedModelOutput = "CANCEL"

        var threwCancellation = false
        try {
            service.consolidateDate(
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = assistantId.toString(),
                memoryDate = memoryDate,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            threwCancellation = true
        }

        assertTrue("CancellationException should be rethrown", threwCancellation)
        val run = fakeDao.getRun(assistantId.toString(), memoryDate)
        assertNotNull(run)
        assertEquals(MemoryConstants.RUN_STATUS_FAILED, run?.status)
        assertEquals("CANCELLED", run?.errorCode)
        // 核心断言：租约 leaseUntil 必须被成功清空为 null，不能产生残留死锁！
        assertNull(run?.leaseUntil)
    }

    @Test
    fun testConflictedPatchesLeavesFingerprintNull() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val chatModelId = Uuid.random()
        val model = Model(id = chatModelId, displayName = "gpt-4o", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "TestProvider",
            models = listOf(model),
        )
        val assistant = Assistant(
            id = assistantId,
            name = "测试助手",
            chatModelId = chatModelId,
        )
        val settings = Settings(
            assistants = listOf(assistant),
            providers = listOf(provider),
            chatModelId = chatModelId,
        )

        // 模型输出了一个合规的 ADD（通过了闸门），但并发写入导致在批处理落库时发生 fact_key 事实冲突
        val modelJson = """
            {
              "daily_note": { "summary": "我记录了今日对话，但发生并发事实冲突" },
              "patches": [
                {
                  "action": "ADD",
                  "category": "preference",
                  "content": "我记得小雨最喜欢喝奶茶",
                  "fact_key": "user.tea",
                  "confidence": 0.95,
                  "importance": 0.8,
                  "source_ids": ["m1"],
                  "evidence_snippet": "我最喜欢喝奶茶了"
                }
              ]
            }
        """.trimIndent()

        // 预置旧指纹：模拟该日期此前已有成功整理记录与旧指纹
        val memoryDate = "2026-09-08"
        fakeDao.insertOrReplaceRun(
            MemoryConsolidationRunEntity(
                id = "run_old",
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = assistantId.toString(),
                memoryDate = memoryDate,
                status = MemoryConstants.RUN_STATUS_SUCCEEDED,
                sourceFingerprint = "old-fingerprint-prior-run",
            )
        )

        val service = TestableService(fakeDao, mockedModelOutput = modelJson, settings = settings)
        service.mockedMessages = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "我最喜欢喝奶茶了", 1000L)
        )
        // 模拟模型调用期间发生并发写入：数据库中多出了一张同 fact_key 但内容不同的活跃卡片
        service.onGenerateModelText = {
            kotlinx.coroutines.runBlocking {
                fakeDao.insertCard(
                    MemoryCardEntity(
                        id = "card_tea_old",
                        ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                        ownerId = assistantId.toString(),
                        content = "小雨最喜欢喝乌龙茶",
                        category = MemoryConstants.CATEGORY_PREFERENCE,
                        factKey = "user.tea",
                        status = MemoryConstants.STATUS_ACTIVE,
                        confidence = 0.95f,
                        importance = 0.8f,
                        validFrom = 0L,
                        createdAt = 1000L,
                        updatedAt = 1000L,
                        createdBy = "USER",
                        updatedBy = "USER",
                        revision = 1,
                    )
                )
            }
        }

        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = memoryDate,
            force = true,
        )

        assertTrue(result is ConsolidationServiceResult.Success)
        val successResult = result as ConsolidationServiceResult.Success
        assertEquals(0, successResult.appliedPatches)
        assertEquals(1, successResult.conflictedPatches)

        // 核心断言：由于存在冲突 patch，run 的 sourceFingerprint 必须为 null，保留后续重新整理的机会！
        val run = fakeDao.getRun(assistantId.toString(), memoryDate)
        assertNotNull(run)
        assertEquals(MemoryConstants.RUN_STATUS_SUCCEEDED, run?.status)
        assertNull("Fingerprint must be null when patches have conflicts", run?.sourceFingerprint)
    }

    @Test
    fun testEmptyModelResponseFailsConsolidation() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val chatModelId = Uuid.random()
        val model = Model(id = chatModelId, displayName = "gpt-4o", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "TestProvider",
            models = listOf(model),
        )
        val assistant = Assistant(
            id = assistantId,
            name = "测试助手",
            chatModelId = chatModelId,
        )
        val settings = Settings(
            assistants = listOf(assistant),
            providers = listOf(provider),
            chatModelId = chatModelId,
        )

        val service = TestableService(fakeDao, settings = settings)
        // 模型没有返回日记内容时，整理失败并释放租约。
        service.mockedMessages = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "长".repeat(60_001), 1000L)
        )
        val memoryDate = "2026-09-08"

        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = memoryDate,
        )

        assertTrue("Consolidation should fail when the model returns no daily note", result is ConsolidationServiceResult.Failed)
        val run = fakeDao.getRun(assistantId.toString(), memoryDate)
        assertNotNull(run)
        assertEquals(MemoryConstants.RUN_STATUS_FAILED, run?.status)
        // 核心断言：分段失败绝不落库指纹，且释放租约，保留后续重试补偿机会！
        assertNull("Fingerprint must NOT be stored on model failure", run?.sourceFingerprint)
        assertNull("Lease must be released on model failure", run?.leaseUntil)
    }

    @Test
    fun `conversation is split into original-content batches capped at 150000 characters`() = kotlinx.coroutines.runBlocking {
        val service = TestableService(FakeDao(), settings = Settings())
        val messages = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "甲".repeat(149_999) + "😀尾", 1000L),
            CleanedDialogueMessage("c1", "m2", "TOOL", "工具返回全文", 2000L),
        )

        val batches = service.splitConversationContent(messages)
        val rebuilt = batches.flatten().joinToString("") { it.content }

        assertTrue(batches.all { batch -> batch.sumOf { it.content.length } <= 150_000 })
        assertEquals(messages.sumOf { it.content.length }, rebuilt.length)
        assertTrue(rebuilt.endsWith("😀尾工具返回全文"))
        assertTrue(service.modelCalls.isEmpty())
    }

    @Test
    fun `mixed tool messages retain text and non-text returns in order`() {
        val longResult = "完整工具返回".repeat(1000)
        val content = memoryMessageText(listOf(
            UIMessagePart.Text("调用前发言"),
            UIMessagePart.Tool("call", "read", "{\"id\":1}", listOf(
                UIMessagePart.Text(longResult), UIMessagePart.Image("file:///tool-result.png"),
            )),
            UIMessagePart.Text("调用后发言"),
        ))
        assertTrue(content.contains(longResult))
        assertTrue(content.contains("file:///tool-result.png"))
        assertTrue(content.contains("{\"id\":1}"))
        assertTrue(content.indexOf("调用前发言") < content.indexOf(longResult))
        assertTrue(content.indexOf(longResult) < content.indexOf("调用后发言"))
    }

    @Test
    fun `oversize original content uses two existing calls for each batch`() = runBlocking {
        val dao = FakeDao()
        val assistantId = Uuid.random()
        val model = Model(id = Uuid.random(), displayName = "TestModel", modelId = "test-model")
        val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "TestProvider", models = listOf(model))
        val settings = Settings(assistants = listOf(Assistant(id = assistantId)), providers = listOf(provider), chatModelId = model.id)
        val service = TestableService(dao, settings = settings)
        val content = "长".repeat(150_000) + "末尾原文"
        service.mockedMessages = listOf(CleanedDialogueMessage("c1", "m1", "USER", content, 1000L))
        service.mockedModelOutputs = listOf(
            """{"patches":[],"timelines":[],"relations":[]}""",
            """{"daily_note":{"summary":"我记下这段经历"}}""",
        )
        val result = service.consolidateDate(MemoryConstants.OWNER_TYPE_ASSISTANT, assistantId.toString(), "2026-09-26")
        assertTrue("$result", result is ConsolidationServiceResult.Success)
        assertEquals(4, service.modelCalls.size)
        assertEquals(2, service.modelCalls.count { it.userPrompt.contains("末尾原文") })
        assertTrue(service.modelCalls.none { it.systemPrompt.contains("对话片段摘要助手") })
    }

    @Test
    fun testReconsolidationCleansOldEventChunksAndKeepsStableIds() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val chatModelId = Uuid.random()
        val model = Model(id = chatModelId, displayName = "gpt-4o", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "TestProvider",
            models = listOf(model),
        )
        val assistant = Assistant(
            id = assistantId,
            name = "测试助手",
            chatModelId = chatModelId,
        )
        val settings = Settings(
            assistants = listOf(assistant),
            providers = listOf(provider),
            chatModelId = chatModelId,
        )

        val memoryDate = "2026-09-08"
        val (startMs, _) = MemoryDateUtils.getMemoryDateRange(memoryDate)
        // 预置旧的事件块（例如上次整理残留）
        fakeDao.insertEventChunk(
            MemoryEventChunkEntity(
                id = "old_chunk_1",
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = assistantId.toString(),
                conversationId = "c1",
                startTime = startMs + 1000L,
                endTime = startMs + 5000L,
                summary = "旧摘要",
                fingerprint = "old_fp",
                createdAt = startMs,
            )
        )
        assertEquals(1, fakeDao.eventChunks.size)

        val modelJson = """
            {
              "daily_note": { "summary": "我写下了今日总结" },
              "patches": []
            }
        """.trimIndent()

        val service = TestableService(fakeDao, mockedModelOutput = modelJson, settings = settings)
        val msgTime = startMs + 10000L
        service.mockedMessages = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "今天天气不错", msgTime)
        )

        val result1 = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = memoryDate,
            force = true,
        )
        assertTrue(result1 is ConsolidationServiceResult.Success)
        // 旧块已被清理，只保留新切出来的块
        assertEquals(1, fakeDao.eventChunks.size)
        val chunkIdFirstRun = fakeDao.eventChunks.first().id
        assertTrue(chunkIdFirstRun.startsWith("chunk_"))
        assertFalse(fakeDao.eventChunks.any { it.id == "old_chunk_1" })

        // 第二次重新整理（force = true），事件块数量依然为 1，且 ID 保持稳定一致
        val result2 = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = memoryDate,
            force = true,
        )
        assertTrue(result2 is ConsolidationServiceResult.Success)
        assertEquals(1, fakeDao.eventChunks.size)
        assertEquals(chunkIdFirstRun, fakeDao.eventChunks.first().id)
    }

    @Test
    fun testFailedBatchLeavesPromotedProposalsInReviewStateWithoutOrphanAccepted() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val chatModelId = Uuid.random()
        val model = Model(id = chatModelId, displayName = "gpt-4o", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "TestProvider",
            models = listOf(model),
        )
        val assistant = Assistant(
            id = assistantId,
            name = "测试助手",
            chatModelId = chatModelId,
        )
        val settings = Settings(
            assistants = listOf(assistant),
            providers = listOf(provider),
            chatModelId = chatModelId,
        )

        // 模型输出了一个合规的 ADD（通过了闸门并被晋升），但在批处理事务中发生并发事实冲突
        val modelJson = """
            {
              "daily_note": { "summary": "我记录了今日对话" },
              "patches": [
                {
                  "action": "ADD",
                  "category": "preference",
                  "content": "我记住了小雨的新资料",
                  "fact_key": "user.profile",
                  "confidence": 0.95,
                  "importance": 0.8,
                  "source_ids": ["m1"],
                  "evidence_snippet": "我的新资料是这个"
                }
              ]
            }
        """.trimIndent()

        val memoryDate = "2026-09-08"
        val service = TestableService(fakeDao, mockedModelOutput = modelJson, settings = settings)
        service.mockedMessages = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "我的新资料是这个", 1000L)
        )
        // 模拟模型调用期间发生并发写入：数据库中多出了一张同 fact_key 但内容不同的活跃卡片
        service.onGenerateModelText = {
            kotlinx.coroutines.runBlocking {
                fakeDao.insertCard(
                    MemoryCardEntity(
                        id = "card_1",
                        ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                        ownerId = assistantId.toString(),
                        content = "旧内容",
                        category = MemoryConstants.CATEGORY_PREFERENCE,
                        factKey = "user.profile",
                        status = MemoryConstants.STATUS_ACTIVE,
                        confidence = 0.9f,
                        importance = 0.5f,
                        validFrom = 0L,
                        createdAt = 1000L,
                        updatedAt = 1000L,
                        createdBy = "USER",
                        updatedBy = "USER",
                        revision = 5,
                    )
                )
            }
        }

        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = memoryDate,
            force = true,
        )

        assertTrue(result is ConsolidationServiceResult.Success)
        val successResult = result as ConsolidationServiceResult.Success
        assertEquals(0, successResult.appliedPatches)
        assertEquals(1, successResult.conflictedPatches)

        // 核心断言：由于批处理中版本冲突未能落库为卡片，该提议不得成为 ACCEPTED，必须为 REVIEW 且 cardId 为 null
        val proposals = fakeDao.getAllProposals(MemoryConstants.OWNER_TYPE_ASSISTANT, assistantId.toString())
        assertTrue("Proposals should be saved", proposals.isNotEmpty())
        for (prop in proposals) {
            assertEquals("REVIEW", prop.status)
            assertNull("CardId must be null for conflicted/failed patch", prop.cardId)
        }
    }

    @Test
    fun testAddWithExistingFactKeyDoesNotOverwriteCardAndRoutedToReview() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val chatModelId = Uuid.random()
        val model = Model(id = chatModelId, displayName = "gpt-4o", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "TestProvider",
            models = listOf(model),
        )
        val assistant = Assistant(
            id = assistantId,
            name = "测试助手",
            chatModelId = chatModelId,
        )
        val settings = Settings(
            assistants = listOf(assistant),
            providers = listOf(provider),
            chatModelId = chatModelId,
        )

        // 1. 系统中已存在一张 ACTIVE 卡片，factKey 为 "user.diet"
        val existingCard = MemoryCardEntity(
            id = "card_diet_1",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "小雨喜欢吃拉面",
            category = MemoryConstants.CATEGORY_PREFERENCE,
            factKey = "user.diet",
            status = MemoryConstants.STATUS_ACTIVE,
            confidence = 0.95f,
            importance = 0.8f,
            validFrom = 0L,
            createdAt = 1000L,
            updatedAt = 1000L,
            createdBy = "USER",
            updatedBy = "USER",
            revision = 1,
        )
        fakeDao.insertCard(existingCard)

        // 2. 模型输出 ADD 操作，试图通过相同的 fact_key 覆盖既有事实为“只吃乌龙面”
        val modelJson = """
            {
              "daily_note": { "summary": "我记录了今日偏好" },
              "patches": [
                {
                  "action": "ADD",
                  "category": "preference",
                  "content": "小雨现在只吃乌龙面",
                  "fact_key": "user.diet",
                  "confidence": 0.95,
                  "importance": 0.8,
                  "source_ids": ["m1"],
                  "evidence_snippet": "我改吃乌龙面了"
                }
              ]
            }
        """.trimIndent()

        val memoryDate = "2026-09-08"
        val service = TestableService(fakeDao, mockedModelOutput = modelJson, settings = settings)
        service.mockedMessages = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "我改吃乌龙面了", 1000L)
        )

        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = memoryDate,
            force = true,
        )

        assertTrue(result is ConsolidationServiceResult.Success)
        val successResult = result as ConsolidationServiceResult.Success
        // 核心断言 1：因为有同 factKey 的 ACTIVE 卡片，该 ADD 被闸门判定为 REVIEW，绝不自动晋升落卡
        assertEquals(0, successResult.appliedPatches)

        // 核心断言 2：原卡片内容与版本严禁被篡改！
        val reloadedCard = fakeDao.getCardById(existingCard.id)
        assertNotNull(reloadedCard)
        assertEquals("小雨喜欢吃拉面", reloadedCard?.content)
        assertEquals(1, reloadedCard?.revision)

        // 核心断言 3：提案保存到了审核池，且元数据保留了 action、factKey
        val proposals = fakeDao.getAllProposals(MemoryConstants.OWNER_TYPE_ASSISTANT, assistantId.toString())
        assertEquals(1, proposals.size)
        val prop = proposals.first()
        assertEquals("REVIEW", prop.status)
        assertEquals("ADD", prop.action)
        assertEquals("user.diet", prop.factKey)
        assertNull(prop.cardId)
    }

    @Test
    fun testArchivePatchWithoutContentEntersReviewProposal() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val chatModelId = Uuid.random()
        val model = Model(id = chatModelId, displayName = "gpt-4o", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "TestProvider",
            models = listOf(model),
        )
        val assistant = Assistant(
            id = assistantId,
            name = "测试助手",
            chatModelId = chatModelId,
        )
        val settings = Settings(
            assistants = listOf(assistant),
            providers = listOf(provider),
            chatModelId = chatModelId,
        )

        val targetCard = MemoryCardEntity(
            id = "card_old_habit",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "小雨以前每天晨跑",
            category = MemoryConstants.CATEGORY_PREFERENCE,
            status = MemoryConstants.STATUS_ACTIVE,
            confidence = 0.9f,
            importance = 0.5f,
            validFrom = 0L,
            createdAt = 1000L,
            updatedAt = 1000L,
            createdBy = "USER",
            updatedBy = "USER",
            revision = 2,
        )
        fakeDao.insertCard(targetCard)

        // 模型输出 ARCHIVE 操作，但没有 content 字段
        val modelJson = """
            {
              "daily_note": { "summary": "我完成了今日整理" },
              "patches": [
                {
                  "action": "ARCHIVE",
                  "target_id": "card_old_habit",
                  "expected_revision": 2,
                  "reason": "不再晨跑了",
                  "source_ids": ["m1"],
                  "evidence_snippet": "我膝盖受伤不晨跑了"
                }
              ]
            }
        """.trimIndent()

        val memoryDate = "2026-09-08"
        val service = TestableService(fakeDao, mockedModelOutput = modelJson, settings = settings)
        service.mockedMessages = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "我膝盖受伤不晨跑了", 1000L)
        )

        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = memoryDate,
            force = true,
        )

        assertTrue(result is ConsolidationServiceResult.Success)
        // 核心断言：ARCHIVE 不会被跳过，且作为 REVIEW 提议安全入库！
        val proposals = fakeDao.getAllProposals(MemoryConstants.OWNER_TYPE_ASSISTANT, assistantId.toString())
        assertEquals(1, proposals.size)
        val prop = proposals.first()
        assertEquals("REVIEW", prop.status)
        assertEquals("ARCHIVE", prop.action)
        assertEquals("card_old_habit", prop.targetId)
        assertEquals(2, prop.expectedRevision)
        assertTrue(prop.content.contains("晨跑"))
    }

    /* Removed E-axis experience-signal regression test.
    @Test
    fun `experience signal is immutable and creates maintenance task on significant change`() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val chatModelId = Uuid.random()
        val model = Model(id = chatModelId, displayName = "Test Model", modelId = "test-model")
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "Test Provider",
            models = listOf(model),
        )
        val assistant = Assistant(
            id = assistantId,
            name = "测试助手",
            chatModelId = chatModelId,
        )
        val settings = Settings(
            assistants = listOf(assistant),
            providers = listOf(provider),
            chatModelId = chatModelId,
        )

        val targetCard = MemoryCardEntity(
            id = "card_exp_1",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "小雨最近工作压力很大",
            category = MemoryConstants.CATEGORY_PREFERENCE,
            factKey = "person.stress",
            status = MemoryConstants.STATUS_ACTIVE,
            confidence = 0.9f,
            importance = 0.8f,
            validFrom = 0L,
            createdAt = 1000L,
            updatedAt = 1000L,
            createdBy = "USER",
            updatedBy = "USER",
            revision = 1,
        )
        fakeDao.insertCard(targetCard)

        // 预设由主模型首次写入的体验信号（不可变源头）
        val initialRemovedAxisValue = RemovedAxisValue(
            id = "exp_card_exp_1",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryId = "card_exp_1",
            valence = -0.6f,
            arousal = 0.7f,
            tension = 0.8f,
            risk = "low",
            urgency = "medium",
            responseTendency = "comfort",
            authoredBy = "MAIN_MODEL:gemini-pro",
            initialPriority = 90,
            confidence = 0.95f,
            status = MemoryConstants.STATUS_ACTIVE,
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        fakeDao.insertRemovedAxisValue(initialRemovedAxisValue)

        // 夜间整理模型尝试用完全不同的情绪维度更新该卡片
        val modelJson = """
            {
              "daily_note": { "summary": "我完成了今日整理" },
              "patches": [
                {
                  "action": "ADD",
                  "fact_key": "person.stress",
                  "category": "preference",
                  "content": "小雨最近工作压力很大",
                  "confidence": 0.95,
                  "importance": 0.8,
                  "source_ids": ["m1"],
                  "evidence_snippet": "今天工作压力好大",
                  "reason": "重复事实幂等整理",
                  "experience": {
                    "valence": 0.8,
                    "arousal": 0.2,
                    "tension": 0.0,
                    "removed_axis_value": "give_space",
                    "initial_priority": 30
                  }
                }
              ]
            }
        """.trimIndent()

        val service = TestableService(fakeDao, mockedModelOutput = modelJson, settings = settings)
        service.mockedMessages = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "今天工作压力好大", 1000L)
        )
        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = "2026-09-08",
            force = true,
        )

        assertTrue(result is ConsolidationServiceResult.Success)

        // 1. 验证原始体验信号绝未被覆盖（保持主模型署名与初始优先级）
        val signalAfter = fakeDao.getRemovedAxisValueByMemoryId("card_exp_1")
        assertNotNull(signalAfter)
        assertEquals("MAIN_MODEL:gemini-pro", signalAfter!!.authoredBy)
        assertEquals(90, signalAfter.initialPriority)
        assertEquals("comfort", signalAfter.responseTendency)
        assertEquals(-0.6f, signalAfter.valence ?: 0f, 0.01f)

        // 2. 验证显著差异转为待处理维护任务
        val tasks = fakeDao.getMaintenanceTasksByStatus(MemoryConstants.OWNER_TYPE_ASSISTANT, assistantId.toString(), "PENDING")
        assertEquals(1, tasks.size)
        val task = tasks.first()
        assertEquals("EXPERIENCE_SIGNAL_UPDATE", task.taskType)
        assertEquals("card_exp_1", task.targetId)
        assertTrue(task.reason.contains("夜间整理模型"))
        assertTrue(task.metadata?.contains("give_space") == true)
    }
    */

    @Test
    fun `consolidation skips creating relations across different assistants`() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantA = Uuid.random()
        val assistantB = Uuid.random()
        val chatModelId = Uuid.random()
        val model = Model(id = chatModelId, displayName = "Test Model", modelId = "test-model")
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "Test Provider",
            models = listOf(model),
        )
        val assistant = Assistant(
            id = assistantA,
            name = "助手A",
            chatModelId = chatModelId,
        )
        val settings = Settings(
            assistants = listOf(assistant),
            providers = listOf(provider),
            chatModelId = chatModelId,
        )

        // 助手A的卡片
        val cardA = MemoryCardEntity(
            id = "card_a",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantA.toString(),
            content = "卡片A：属于助手A",
            category = MemoryConstants.CATEGORY_PREFERENCE,
            status = MemoryConstants.STATUS_ACTIVE,
            confidence = 0.9f,
            importance = 0.5f,
            validFrom = 0L,
            createdAt = 1000L,
            updatedAt = 1000L,
            createdBy = "USER",
            updatedBy = "USER",
            revision = 1,
        )
        // 助手B的卡片（私有隔离）
        val cardB = MemoryCardEntity(
            id = "card_b",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantB.toString(),
            content = "卡片B：属于助手B",
            category = MemoryConstants.CATEGORY_PREFERENCE,
            status = MemoryConstants.STATUS_ACTIVE,
            confidence = 0.9f,
            importance = 0.5f,
            validFrom = 0L,
            createdAt = 1000L,
            updatedAt = 1000L,
            createdBy = "USER",
            updatedBy = "USER",
            revision = 1,
        )
        fakeDao.insertCard(cardA)
        fakeDao.insertCard(cardB)

        // 整理模型在助手A的任务中，试图将 card_a 与 card_b 建立关系
        val modelJson = """
            {
              "daily_note": { "summary": "我完成了今日整理" },
              "patches": [
                {
                  "action": "UPDATE",
                  "target_id": "card_a",
                  "expected_revision": 1,
                  "content": "卡片A：更新内容",
                  "confidence": 0.9,
                  "importance": 0.5,
                  "reason": "更新",
                  "related_card_ids": ["card_b"]
                }
              ],
              "relations": [
                {
                  "source_card_id": "card_a",
                  "target_card_id": "card_b",
                  "relation_type": "same_topic",
                  "strength": 0.9,
                  "reason": "跨助手尝试建边"
                }
              ]
            }
        """.trimIndent()

        val service = TestableService(fakeDao, mockedModelOutput = modelJson, settings = settings)
        service.mockedMessages = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "今天聊了卡片", 1000L)
        )
        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantA.toString(),
            memoryDate = "2026-09-08",
            force = true,
        )

        assertTrue(result is ConsolidationServiceResult.Success)

        // 核心断言：由于 card_b 属于助手B，跨助手建边被严格拦截，关系边集合必须为空！
        val allRelations = fakeDao.getAllRelations(MemoryConstants.OWNER_TYPE_ASSISTANT, assistantA.toString())
        assertTrue("No relation should be created across different assistants", allRelations.isEmpty())
    }

    @Test
    fun `consolidation creates timeline and attaches cards for X axis recall`() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val chatModelId = Uuid.random()
        val model = Model(id = chatModelId, displayName = "Test Model", modelId = "test-model")
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "Test Provider",
            models = listOf(model),
        )
        val assistant = Assistant(
            id = assistantId,
            name = "助手",
            chatModelId = chatModelId,
        )
        val settings = Settings(
            assistants = listOf(assistant),
            providers = listOf(provider),
            chatModelId = chatModelId,
        )

        val card1 = MemoryCardEntity(
            id = "c_roadtrip_1",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "小雨自驾游去了川西",
            category = MemoryConstants.CATEGORY_EXPERIENCE,
            status = MemoryConstants.STATUS_ACTIVE,
            confidence = 0.9f,
            importance = 0.8f,
            validFrom = 0L,
            createdAt = 1000L,
            updatedAt = 1000L,
            createdBy = "USER",
            updatedBy = "USER",
            revision = 1,
        )
        fakeDao.insertCard(card1)

        val modelJson = """
            {
              "daily_note": { "summary": "我整理了自驾游记忆" },
              "timelines": [
                {
                  "title": "2024川西自驾之旅",
                  "summary": "关于自驾游的完整行程与回忆",
                  "card_ids": ["c_roadtrip_1"]
                }
              ],
              "patches": [
                {
                  "action": "ADD",
                  "content": "我记得小雨在川西看到了雪山",
                  "category": "preference",
                  "confidence": 0.95,
                  "importance": 0.7,
                  "evidence_snippet": "今天在川西看到了雪山",
                  "source_ids": ["m1"],
                  "timeline_title": "2024川西自驾之旅"
                }
              ]
            }
        """.trimIndent()

        val service = TestableService(fakeDao, mockedModelOutput = modelJson, settings = settings)
        service.mockedMessages = listOf(
            CleanedDialogueMessage("conv-1", "m1", "USER", "今天在川西看到了雪山，自驾游玩得很开心", 1000L)
        )

        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = "2026-09-08",
            force = true,
        )
        assertTrue(result is ConsolidationServiceResult.Success)

        // 1. 验证时间线被成功创建
        val timelines = fakeDao.getAllTimelines(MemoryConstants.OWNER_TYPE_ASSISTANT, assistantId.toString())
        assertEquals(1, timelines.size)
        val timeline = timelines.first()
        assertEquals("2024川西自驾之旅", timeline.title)

        // 2. 验证已有卡片与新卡片均加入了该时间线
        val members = fakeDao.getTimelineMembers(timeline.id)
        assertTrue("Timeline should contain at least card1", members.any { it.cardId == "c_roadtrip_1" })
        assertEquals(2, members.size)

        // 3. 验证 X 轴召回能够由 seedCard 展开获取同一时间线中的其他记忆
        val repo = object : MemoryRepositoryV1(database = null, dao = fakeDao) {}
        val expanded = repo.expandTimelines(
            seedCardIds = listOf("c_roadtrip_1"),
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            includeGlobal = false,
        )
        assertEquals(1, expanded.size)
        assertEquals("我记得小雨在川西看到了雪山", expanded.first().content)
    }

    @Test
    fun `consolidation gates relations with evidence and cleans up deterministically on reconsolidation`() = kotlinx.coroutines.runBlocking {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val chatModelId = Uuid.random()
        val model = Model(id = chatModelId, displayName = "Test Model", modelId = "test-model")
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "Test Provider",
            models = listOf(model),
        )
        val assistant = Assistant(
            id = assistantId,
            name = "助手",
            chatModelId = chatModelId,
        )
        val settings = Settings(
            assistants = listOf(assistant),
            providers = listOf(provider),
            chatModelId = chatModelId,
        )

        val card1 = MemoryCardEntity(
            id = "c_pref_tea",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "小雨喜欢喝无糖茉莉乌龙茶",
            category = MemoryConstants.CATEGORY_PREFERENCE,
            status = MemoryConstants.STATUS_ACTIVE,
            confidence = 0.9f,
            importance = 0.8f,
            validFrom = 0L,
            createdAt = 1000L,
            updatedAt = 1000L,
            createdBy = "USER",
            updatedBy = "USER",
            revision = 1,
        )
        val card2 = MemoryCardEntity(
            id = "c_pref_sweet",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "小雨不吃高糖食品",
            category = MemoryConstants.CATEGORY_PREFERENCE,
            status = MemoryConstants.STATUS_ACTIVE,
            confidence = 0.9f,
            importance = 0.8f,
            validFrom = 0L,
            createdAt = 1000L,
            updatedAt = 1000L,
            createdBy = "USER",
            updatedBy = "USER",
            revision = 1,
        )
        fakeDao.insertCard(card1)
        fakeDao.insertCard(card2)

        // 第一次整理：包含一条有真实原文支撑的关系和一条无证据支撑的关系
        val modelJson1 = """
            {
              "daily_note": { "summary": "我整理了饮食习惯" },
              "relations": [
                {
                  "source_card_id": "c_pref_tea",
                  "target_card_id": "c_pref_sweet",
                  "relation_type": "same_topic",
                  "strength": 0.85,
                  "reason": "健康饮茶与控糖偏好相互补充",
                  "source_ids": ["m1"],
                  "evidence_snippet": "我平时只喝无糖茶，也不吃甜食"
                },
                {
                  "source_card_id": "c_pref_sweet",
                  "target_card_id": "c_pref_tea",
                  "relation_type": "DEPENDS_ON",
                  "strength": 0.5,
                  "reason": "缺乏证据的关系",
                  "source_ids": ["m1"],
                  "evidence_snippet": "凭空虚构的句子"
                }
              ]
            }
        """.trimIndent()

        val service = TestableService(fakeDao, mockedModelOutput = modelJson1, settings = settings)
        service.mockedMessages = listOf(
            CleanedDialogueMessage("conv-1", "m1", "USER", "我平时只喝无糖茶，也不吃甜食，喜欢清爽的口味", 1000L)
        )

        val result1 = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = "2026-09-08",
            force = true,
        )
        assertTrue(result1 is ConsolidationServiceResult.Success)

        // 验证：有效证据的关系直接成为 ACTIVE 并记录证据与来源；无效证据的关系被降级为 REVIEW（未进入 ACTIVE 关系）
        val allRelations = fakeDao.getAllRelations(MemoryConstants.OWNER_TYPE_ASSISTANT, assistantId.toString())
        val activeRels = allRelations.filter { it.status == "ACTIVE" }
        assertEquals(1, activeRels.size)
        val activeRel = activeRels.first()
        assertEquals("same_topic", activeRel.relationType)
        assertEquals("CONSOLIDATION:2026-09-08", activeRel.source)
        assertNotNull(activeRel.evidenceSourceIds)
        assertTrue(activeRel.evidenceSourceIds!!.contains("m1"))

        val reviewRels = allRelations.filter { it.status == "REVIEW" }
        assertEquals(1, reviewRels.size)
        assertEquals("depends_on", reviewRels.first().relationType)

        // 第二次重新整理（force = true）：旧的 CONSOLIDATION:2026-09-08 关系被确定性清理并重建，不重复累加
        val modelJson2 = """
            {
              "daily_note": { "summary": "我重新整理了饮食习惯" },
              "relations": [
                {
                  "source_card_id": "c_pref_tea",
                  "target_card_id": "c_pref_sweet",
                  "relation_type": "same_topic",
                  "strength": 0.95,
                  "reason": "再次确认为互补关系",
                  "source_ids": ["m1"],
                  "evidence_snippet": "我平时只喝无糖茶，也不吃甜食"
                }
              ]
            }
        """.trimIndent()

        val service2 = TestableService(fakeDao, mockedModelOutput = modelJson2, settings = settings)
        service2.mockedMessages = service.mockedMessages

        val result2 = service2.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = "2026-09-08",
            force = true,
        )
        assertTrue(result2 is ConsolidationServiceResult.Success)

        // 重新整理后，旧有的自动关系被清理，ACTIVE 关系仍然只有 1 条且强度更新为 0.95
        val reloadedRelations = fakeDao.getAllRelations(MemoryConstants.OWNER_TYPE_ASSISTANT, assistantId.toString())
        val reloadedActive = reloadedRelations.filter { it.status == "ACTIVE" }
        assertEquals(1, reloadedActive.size)
        assertEquals(0.95f, reloadedActive.first().strength, 0.01f)
    }

    @Test
    fun `consolidation prompts follow companion memory XYZ contract and formatting rules`() {
        val fakeDao = FakeDao()
        val repo = object : MemoryRepositoryV1(database = null, dao = fakeDao) {}
        val service = MemoryConsolidationService(repo)

        val systemPrompt = service.buildStructuredSystemPrompt(userName = "小雨")
        val userPrompt = service.buildStructuredUserPrompt(
            memoryDate = "2026-09-08",
            existingCards = emptyList(),
            conversationContent = "对话内容",
            existingTimelines = emptyList(),
        )

        // 1. 时间线定义覆盖小雨、AI 和双方
        assertTrue("Timeline prompt should cover Person", systemPrompt.contains("小雨的生活变化"))
        assertTrue("Timeline prompt should cover AI", systemPrompt.contains("我的变化"))
        assertTrue("Timeline prompt should cover shared experiences", systemPrompt.contains("共同经历"))
        assertTrue("Timeline prompt should cover relations/promises", systemPrompt.contains("长期约定与关系发展"))

        // 2. 时间线提示词不再使用工作型事项列表作为主要定义
        assertFalse("Timeline prompt should not bias towards job/exam/travel", systemPrompt.contains("旅行规划"))
        assertFalse("Timeline prompt should not bias towards job/exam/travel", systemPrompt.contains("求职晋升"))
        assertFalse("Timeline prompt should not bias towards job/exam/travel", systemPrompt.contains("考试备考"))
        assertFalse("Timeline JSON example should not use Tokyo travel", userPrompt.contains("2026东京旅行规划"))

        // 3. 关系提示词保留小雨不同事情之间的关联并覆盖双方脉络
        assertTrue("Relation prompt should cover Person experiences", systemPrompt.contains("小雨的经历之间"))
        assertTrue("Relation prompt should cover AI memories", systemPrompt.contains("我的记忆之间"))
        assertTrue("Relation prompt should cover mutual memories", systemPrompt.contains("双方记忆之间"))

        // 4. fact_key 示例覆盖三类主体
        assertTrue("fact_key examples should cover Person", userPrompt.contains("person.preference.food_spicy"))
        assertTrue("fact_key examples should cover Person life", userPrompt.contains("person.life.work_status"))
        assertTrue("fact_key examples should cover self", userPrompt.contains("self.preference.communication"))
        assertTrue("fact_key examples should cover relationship naming", userPrompt.contains("relationship.naming"))
        assertTrue("fact_key examples should cover relationship continuity", userPrompt.contains("relationship.promise.continuity"))

        // 5. 提示词不包含禁用句式（如“不是……而是……”）
        assertFalse("Prompt should not contain '不是……而是……'", systemPrompt.contains("不是……而是"))
    }

    @Test
    fun `consolidation with experience creates eProposals instead of directly inserting experiences`() = kotlinx.coroutines.runBlocking<Unit> {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val assistant = Assistant(
            id = assistantId,
            name = "橘瓣助手",
            enableExperienceMemory = true,
            allowNightlyExperienceConsolidation = true,
        )
        val model = Model(id = Uuid.random(), displayName = "TestModel", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "TestProvider", models = listOf(model))
        val settings = Settings(assistants = listOf(assistant), providers = listOf(provider), chatModelId = model.id)
        val service = TestableService(fakeDao, settings = settings)
        service.mockedMessages = listOf(CleanedDialogueMessage("c1", "m1", "USER", "今天我很开心", 1000L))
        service.mockedModelOutputs = listOf(
            """
                {
                  "patches": [
                    {
                      "action": "ADD",
                      "category": "preference",
                      "content": "我感到被信任与支持",
                      "confidence": 0.95,
                      "importance": 0.8,
                      "source_ids": ["m1"],
                      "evidence_snippet": "今天我很开心"
                    }
                  ],
                  "experience_coordinates": [
                    {
                      "valence": 0.8,
                      "arousal": 0.4,
                      "response_tendency": "ENGAGE",
                      "growth_delta": "REINFORCE",
                      "source_episode_key": "conv:c1:msg:m1",
                      "meaning": "我感到被信任与支持",
                      "initial_priority": 70
                    }
                  ],
                  "timelines": [],
                  "relations": []
                }
            """.trimIndent(),
            """
                {
                  "daily_note": {
                    "summary": "今天我和小雨度过了开心的一天。",
                    "emotional_thread": "我感到很温暖",
                    "unfinished_topics": []
                  }
                }
            """.trimIndent(),
        )

        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = "2026-09-08",
        )

        assertTrue(result is ConsolidationServiceResult.Success)
        // 验证：夜间整理绝不直接写 experiences 表（禁止越权直接写入权威 E）
        assertTrue("Consolidation must NEVER directly insert experiences", fakeDao.experiences.isEmpty())
        // 验证：必须写入 eProposals 表等待用户审核或作为提案落库
        assertEquals("Should create exactly 1 eProposal", 1, fakeDao.eProposals.size)
        val proposal = fakeDao.eProposals.values.first()
        assertEquals("consolidation", proposal.proposer)
        assertEquals("我感到被信任与支持", proposal.meaning)
        assertEquals(70, proposal.initialPriority)
        assertEquals("ENGAGE", proposal.responseTendency)
        assertEquals(0.8, proposal.valence ?: 0.0, 0.01)
        assertEquals(0.4, proposal.arousal ?: 0.0, 0.01)
    }

    @Test
    fun `consolidation when experience disabled never proposes or prompts experience`() = kotlinx.coroutines.runBlocking<Unit> {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val assistant = Assistant(
            id = assistantId,
            name = "橘瓣助手",
            enableExperienceMemory = false, // 禁用
            allowNightlyExperienceConsolidation = false,
        )
        val model = Model(id = Uuid.random(), displayName = "TestModel", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "TestProvider", models = listOf(model))
        val settings = Settings(assistants = listOf(assistant), providers = listOf(provider), chatModelId = model.id)
        val service = TestableService(fakeDao, settings = settings)
        service.mockedMessages = listOf(CleanedDialogueMessage("c1", "m1", "USER", "今天我很开心", 1000L))
        service.mockedModelOutputs = listOf(
            """
                {
                  "patches": [
                    {
                      "action": "ADD",
                      "category": "life",
                      "content": "今天天气很好",
                      "fact_key": "life.weather.good"
                    }
                  ],
                  "experience_coordinates": [
                    {
                      "valence": 0.8,
                      "arousal": 0.4,
                      "response_tendency": "ENGAGE",
                      "growth_delta": "REINFORCE",
                      "source_episode_key": "conv:c1:msg:m1",
                      "meaning": "偷跑的E"
                    }
                  ],
                  "timelines": [],
                  "relations": []
                }
            """.trimIndent(),
            """
                {
                  "daily_note": {
                    "summary": "今天我和小雨度过了天气很好的一天。",
                    "emotional_thread": "我感到平静",
                    "unfinished_topics": []
                  }
                }
            """.trimIndent(),
        )

        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = "2026-09-08",
        )

        assertTrue(result is ConsolidationServiceResult.Success)
        // 提示词中绝不能含有 experience_coordinates
        val structuredPrompt = service.modelCalls[0]
        assertFalse("System prompt should not prompt experience when disabled", structuredPrompt.systemPrompt.contains("experience_coordinates"))
        assertFalse("User prompt should not have experience schema when disabled", structuredPrompt.userPrompt.contains("experience_coordinates"))

        // 绝不落库任何 EProposal 或 Experience
        assertTrue("Must not insert any eProposal when disabled", fakeDao.eProposals.isEmpty())
        assertTrue("Must not insert any experience when disabled", fakeDao.experiences.isEmpty())
    }

    @Test
    fun `consolidation skips ambiguous experience coordinates when multiple new cards exist and card_id is null`() = kotlinx.coroutines.runBlocking<Unit> {
        val fakeDao = FakeDao()
        val assistantId = Uuid.random()
        val assistant = Assistant(
            id = assistantId,
            name = "橘瓣助手",
            enableExperienceMemory = true,
            allowNightlyExperienceConsolidation = true,
        )
        val model = Model(id = Uuid.random(), displayName = "TestModel", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "TestProvider", models = listOf(model))
        val settings = Settings(assistants = listOf(assistant), providers = listOf(provider), chatModelId = model.id)
        val service = TestableService(fakeDao, settings = settings)
        service.mockedMessages = listOf(CleanedDialogueMessage("c1", "m1", "USER", "今天学了数学和英语", 1000L))
        service.mockedModelOutputs = listOf(
            """
                {
                  "patches": [
                    {
                      "action": "ADD",
                      "category": "study",
                      "content": "我学了数学",
                      "fact_key": "study.math"
                    },
                    {
                      "action": "ADD",
                      "category": "study",
                      "content": "我学了英语",
                      "fact_key": "study.english"
                    }
                  ],
                  "experience_coordinates": [
                    {
                      "valence": 0.5,
                      "arousal": 0.5,
                      "response_tendency": "ENGAGE",
                      "growth_delta": "REINFORCE",
                      "source_episode_key": "conv:c1:msg:m1",
                      "meaning": "我不确定绑定哪张卡",
                      "card_id": null
                    }
                  ],
                  "timelines": [],
                  "relations": []
                }
            """.trimIndent(),
            """
                {
                  "daily_note": {
                    "summary": "今天我和小雨一起学习了数学和英语。",
                    "emotional_thread": "我感到充实",
                    "unfinished_topics": []
                  }
                }
            """.trimIndent(),
        )

        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = "2026-09-08",
        )

        assertTrue(result is ConsolidationServiceResult.Success)
        // 存在 2 张新增卡且 card_id 为空，存在歧义，绝不能瞎猜绑定第一张卡，必须跳过！
        assertTrue("Ambiguous null card_id must be skipped when multiple cards added", fakeDao.eProposals.isEmpty())
        assertTrue(fakeDao.experiences.isEmpty())
    }
}
