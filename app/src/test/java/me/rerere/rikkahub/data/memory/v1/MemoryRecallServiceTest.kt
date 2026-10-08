/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemorySourceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryRecallServiceTest {

    private class FakeMemoryRepository : MemoryRepositoryV1() {
        val cardsToReturn = mutableListOf<MemoryCardEntity>()
        val allStoredCards = mutableListOf<MemoryCardEntity>()
        val pendingAuditFactKeys = mutableSetOf<String>()
        val pendingAuditFactKeyOwners = mutableSetOf<Pair<String, String>>()
        val sourcesMap = mutableMapOf<String, List<MemorySourceEntity>>()
        val sourceIds = mutableSetOf<String>()

        override suspend fun search(
            query: String,
            ownerType: String,
            ownerId: String,
            statusList: List<String>?,
            limit: Int,
            includeGlobal: Boolean
        ): List<MemoryCardEntity> {
            return cardsToReturn.take(limit)
        }

        override suspend fun getCardsByIds(ids: List<String>): List<MemoryCardEntity> {
            val all = (cardsToReturn + allStoredCards + graphExpandedCards + timelineExpandedCards).distinctBy { it.id }
            return all.filter { it.id in ids }
        }

        override suspend fun getAuthoritativeFactStates(
            ownerType: String,
            ownerId: String,
            includeGlobal: Boolean,
            factKeys: Collection<String>,
        ): Map<String, FactKeyAuthoritativeState> {
            val nonBlankKeys = factKeys.filter { it.isNotBlank() }.distinct()
            if (nonBlankKeys.isEmpty()) return emptyMap()

            val pool = (cardsToReturn + allStoredCards + graphExpandedCards + timelineExpandedCards).distinctBy { it.id }
            val isPrimaryGlobal = ownerType == MemoryConstants.OWNER_TYPE_GLOBAL && ownerId == MemoryConstants.GLOBAL_OWNER_ID

            return nonBlankKeys.associateWith { key ->
                val privCards = pool.filter { card ->
                    card.factKey == key &&
                        card.status == MemoryConstants.STATUS_ACTIVE &&
                        card.ownerType == ownerType && card.ownerId == ownerId
                }.sortedByDescending { it.updatedAt }

                val globCards = if (includeGlobal && !isPrimaryGlobal) {
                    pool.filter { card ->
                        card.factKey == key &&
                            card.status == MemoryConstants.STATUS_ACTIVE &&
                            card.ownerType == MemoryConstants.OWNER_TYPE_GLOBAL
                    }.sortedByDescending { it.updatedAt }
                } else emptyList()

                val privLatest = privCards.firstOrNull()
                val globLatest = globCards.firstOrNull()

                val privAudit = key in pendingAuditFactKeys || (ownerType to key) in pendingAuditFactKeyOwners
                val globAudit = (MemoryConstants.OWNER_TYPE_GLOBAL to key) in pendingAuditFactKeyOwners

                if (isPrimaryGlobal) {
                    FactKeyAuthoritativeState(
                        globalLatestActiveCardId = privLatest?.id,
                        globalLatestUpdatedAt = privLatest?.updatedAt,
                        globalHasPendingAudit = privAudit,
                    )
                } else {
                    FactKeyAuthoritativeState(
                        privateLatestActiveCardId = privLatest?.id,
                        privateLatestUpdatedAt = privLatest?.updatedAt,
                        privateHasPendingAudit = privAudit,
                        globalLatestActiveCardId = globLatest?.id,
                        globalLatestUpdatedAt = globLatest?.updatedAt,
                        globalHasPendingAudit = globAudit,
                    )
                }
            }
        }

        override suspend fun getMemoryIdsWithSources(memoryIds: List<String>): Set<String> {
            return sourceIds
        }

        override suspend fun getSources(memoryId: String): List<MemorySourceEntity> {
            return sourcesMap[memoryId].orEmpty()
        }

        override suspend fun getSourcesForMemories(memoryIds: List<String>): List<MemorySourceEntity> {
            return memoryIds.flatMap { sourcesMap[it].orEmpty() }
        }

        val graphExpandedCards = mutableListOf<MemoryCardEntity>()
        val timelineExpandedCards = mutableListOf<MemoryCardEntity>()

        override suspend fun expandTimelines(
            seedCardIds: List<String>,
            ownerType: String,
            ownerId: String,
            includeGlobal: Boolean,
            limit: Int,
        ): List<MemoryCardEntity> = timelineExpandedCards.take(limit)

        override suspend fun graphExpand(
            seedCardIds: List<String>,
            maxHops: Int,
            limit: Int,
            minStrength: Float,
            ownerType: String?,
            ownerId: String?,
            includeGlobal: Boolean,
        ): List<MemoryCardEntity> {
            return graphExpandedCards.take(limit)
        }
    }

    private val repository = FakeMemoryRepository()
    private val embeddingService = MemoryEmbeddingService(repository)
    private val service = MemoryRecallService(repository, embeddingService)

    private val testAssistant = Assistant(
        id = Uuid.random(),
        memoryMode = MemoryMode.LOCAL_V1,
        autoRecallMemory = true,
        maxAutoRecallCount = 8,
    )

    private fun createCard(
        id: String,
        content: String,
        factKey: String? = null,
        importance: Float = 0.5f,
        status: String = MemoryConstants.STATUS_ACTIVE,
        updatedAt: Long = System.currentTimeMillis(),
    ): MemoryCardEntity {
        return MemoryCardEntity(
            id = id,
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = testAssistant.id.toString(),
            category = "preference",
            factKey = factKey,
            content = content,
            confidence = 1.0f,
            importance = importance,
            status = status,
            validFrom = null,
            validTo = null,
            supersededBy = null,
            createdAt = 1000L,
            updatedAt = updatedAt,
            revision = 1,
            createdBy = "user",
            updatedBy = "user",
        )
    }

    @Test
    fun `low information query returns null immediately`() = runBlocking {
        val result = service.recall(
            assistant = testAssistant,
            query = "好的",
            settings = Settings(),
        )
        assertNull(result)
    }

    @Test
    fun `recall when both channels empty returns null`() = runBlocking {
        repository.cardsToReturn.clear()

        val result = service.recall(
            assistant = testAssistant,
            query = "小雨喜欢吃什么",
            settings = Settings(),
        )
        assertNull(result)
    }

    @Test
    fun `recall merges dual channels via RRF and dedupes same factKey preserving newest`() = runBlocking {
        val card1New = createCard("card_1", "小雨喜欢喝无糖乌龙奶茶", factKey = "favorite_tea", importance = 0.9f, updatedAt = 2000L)
        val card2Old = createCard("card_2", "小雨喜欢喝全糖奶茶", factKey = "favorite_tea", importance = 0.5f, updatedAt = 1000L)
        val card3 = createCard("card_3", "小雨家里有一只英短蓝猫", factKey = "pet_cat", importance = 0.8f, updatedAt = 1500L)

        // FTS 召回即使旧卡片 card2Old 排在前面，系统也必须按照 updatedAt 最大的 card1New 淘汰旧事实
        repository.cardsToReturn.clear()
        repository.cardsToReturn.addAll(listOf(card2Old, card1New, card3))

        val result = service.recall(
            assistant = testAssistant,
            query = "我喜欢喝什么奶茶来着",
            settings = Settings(),
        )

        assertNotNull(result)
        val recalledCards = result!!.cards
        assertEquals(2, recalledCards.size)
        assertEquals("card_1", recalledCards[0].id)
        assertEquals("card_3", recalledCards[1].id)

        // 验证提示词格式
        val prompt = result.formattedPrompt
        assertTrue(prompt.startsWith("<memory_context"))
        assertTrue(prompt.endsWith("</memory_context>"))
        assertTrue(prompt.contains("小雨喜欢喝无糖乌龙奶茶"))
        assertTrue(prompt.contains("以下是与你当前话题可能相关的长期记忆"))
        // 强约束：面向小雨的提示词不使用“用户”
        assertFalse(prompt.contains("用户"))
    }

    @Test
    fun `recall excludes cards originating from current turn message`() = runBlocking {
        val currentMsgId = "msg_current_123"
        val card1 = createCard("card_1", "刚说的这句话")
        val card2 = createCard("card_2", "历史事实记录")

        repository.cardsToReturn.clear()
        repository.cardsToReturn.addAll(listOf(card1, card2))

        repository.sourceIds.clear()
        repository.sourceIds.add("card_1")

        repository.sourcesMap["card_1"] = listOf(
            MemorySourceEntity(
                id = "src_1",
                memoryId = "card_1",
                conversationId = "conv_1",
                nodeId = null,
                messageId = currentMsgId, // 当轮同源
                role = "user",
                sourceKind = "chat",
                sourceHash = null,
                createdAt = 1000L,
            )
        )
        repository.sourcesMap["card_2"] = emptyList()

        val result = service.recall(
            assistant = testAssistant,
            query = "刚才聊的事",
            settings = Settings(),
            currentMessageId = currentMsgId,
        )

        assertNotNull(result)
        assertEquals(1, result!!.cards.size)
        assertEquals("card_2", result.cards[0].id)
    }

    @Test
    fun `recall honors configured maxAutoRecallCount`() = runBlocking {
        val assistantLimited = testAssistant.copy(maxAutoRecallCount = 2)
        val cards = (1..5).map { createCard("card_$it", "长期记忆 $it") }

        repository.cardsToReturn.clear()
        repository.cardsToReturn.addAll(cards)

        val result = service.recall(
            assistant = assistantLimited,
            query = "列出我的所有记忆",
            settings = Settings(),
        )

        assertNotNull(result)
        assertEquals(2, result!!.cards.size)
    }

    @Test
    fun `recall allows a smaller wake quota without changing assistant setting`() = runBlocking {
        repository.cardsToReturn.clear()
        repository.cardsToReturn.addAll((1..5).map { createCard("card_$it", "长期记忆 $it") })

        val result = service.recall(
            assistant = testAssistant.copy(maxAutoRecallCount = 3),
            query = "列出我的所有记忆",
            settings = Settings(),
            maxCountOverride = 2,
        )

        assertNotNull(result)
        assertEquals(2, result!!.cards.size)
    }

    @Test
    fun `recall preserves content above the former token budget`() = runBlocking {
        val longContent = "记忆内容".repeat(1_000)
        repository.cardsToReturn.clear()
        repository.cardsToReturn += createCard("long_card", longContent)

        val result = service.recall(
            assistant = testAssistant.copy(maxAutoRecallCount = 1),
            query = "请回忆详细内容",
            settings = Settings(),
        )

        assertNotNull(result)
        assertEquals(listOf("long_card"), result!!.cards.map { it.id })
        assertTrue(result.formattedPrompt.contains(longContent))
    }

    @Test
    fun `evidence bridge is not called when allowMemoryEvidenceRead is false`() = runBlocking {
        val fakeSearcher = FakeMemoryEvidenceSearcher()
        val customService = MemoryRecallService(repository, embeddingService, evidenceSearcher = fakeSearcher)
        repository.cardsToReturn.clear()

        val assistantWithoutEvidence = testAssistant.copy(allowMemoryEvidenceRead = false)
        val result = customService.recall(
            assistant = assistantWithoutEvidence,
            query = "你记得《三体》吗",
            settings = Settings(),
        )

        assertNull(result)
        assertNull(fakeSearcher.lastSearchQuery)
    }

    @Test
    fun `evidence bridge retrieves and formats bounded context when allowed`() = runBlocking {
        val fakeSearcher = FakeMemoryEvidenceSearcher()
        val customService = MemoryRecallService(repository, embeddingService, evidenceSearcher = fakeSearcher)
        repository.cardsToReturn.clear()

        val convId = Uuid.random()
        val msg1 = me.rerere.ai.ui.UIMessage.user("你觉得《三体》好看吗")
        val msg2 = me.rerere.ai.ui.UIMessage.assistant("《三体》非常震撼！")
        val msg3 = me.rerere.ai.ui.UIMessage.user("特别是第二部黑暗森林")

        val conv = me.rerere.rikkahub.data.model.Conversation(
            id = convId,
            assistantId = testAssistant.id,
            title = "科幻讨论",
            messageNodes = listOf(
                me.rerere.rikkahub.data.model.MessageNode.of(msg1),
                me.rerere.rikkahub.data.model.MessageNode.of(msg2),
                me.rerere.rikkahub.data.model.MessageNode.of(msg3),
            )
        )
        fakeSearcher.conversations[convId] = conv
        fakeSearcher.searchHits.add(
            me.rerere.rikkahub.data.db.fts.MessageSearchResult(
                nodeId = "node_2",
                messageId = msg2.id.toString(),
                conversationId = convId.toString(),
                title = conv.title,
                updateAt = java.time.Instant.now(),
                snippet = "《三体》非常震撼！",
            )
        )

        val assistantWithEvidence = testAssistant.copy(allowMemoryEvidenceRead = true)
        val result = customService.recall(
            assistant = assistantWithEvidence,
            query = "你记得《三体》吗",
            settings = Settings(),
        )

        assertNotNull(result)
        assertEquals(0, result!!.cards.size)
        assertEquals(1, result.evidences.size)
        assertEquals("科幻讨论", result.evidences[0].conversationTitle)
        assertEquals("《三体》非常震撼！", result.evidences[0].matchedContent)
        assertEquals("你觉得《三体》好看吗", result.evidences[0].beforeContent)
        assertEquals("特别是第二部黑暗森林", result.evidences[0].afterContent)

        val prompt = result.formattedPrompt
        assertTrue(prompt.contains("<evidence_context>"))
        assertTrue(prompt.contains("以下为近 90 天聊天原文证据片段（仅供核对与导航参考，不可覆盖长期记忆事实）："))
        assertTrue(prompt.contains("[对话: \"科幻讨论\"]"))
        assertTrue(prompt.contains("原话 [我]: 《三体》非常震撼！"))
        assertTrue(prompt.contains("上文 [你]: 你觉得《三体》好看吗"))
        assertTrue(prompt.contains("下文 [你]: 特别是第二部黑暗森林"))
        // 确保不包含“用户”
        assertFalse(prompt.contains("用户"))
    }

    @Test
    fun `evidence bridge combines cards and evidence when both match`() = runBlocking {
        val fakeSearcher = FakeMemoryEvidenceSearcher()
        val customService = MemoryRecallService(repository, embeddingService, evidenceSearcher = fakeSearcher)

        val card = createCard("card_sci_fi", "小雨喜欢刘慈欣的科幻作品")
        repository.cardsToReturn.clear()
        repository.cardsToReturn.add(card)

        val convId = Uuid.random()
        val msg1 = me.rerere.ai.ui.UIMessage.user("你记得《三体》吗")
        val conv = me.rerere.rikkahub.data.model.Conversation(
            id = convId,
            assistantId = testAssistant.id,
            title = "阅读记录",
            messageNodes = listOf(me.rerere.rikkahub.data.model.MessageNode.of(msg1))
        )
        fakeSearcher.conversations[convId] = conv
        fakeSearcher.searchHits.add(
            me.rerere.rikkahub.data.db.fts.MessageSearchResult(
                nodeId = "node_1",
                messageId = msg1.id.toString(),
                conversationId = convId.toString(),
                title = conv.title,
                updateAt = java.time.Instant.now(),
                snippet = "你记得《三体》吗",
            )
        )

        val assistantWithEvidence = testAssistant.copy(allowMemoryEvidenceRead = true)
        val result = customService.recall(
            assistant = assistantWithEvidence,
            query = "你记得《三体》吗",
            settings = Settings(),
        )

        assertNotNull(result)
        assertEquals(1, result!!.cards.size)
        assertEquals(1, result.evidences.size)
        val prompt = result.formattedPrompt
        assertTrue(prompt.contains("小雨喜欢刘慈欣的科幻作品"))
        assertTrue(prompt.contains("<evidence_context>"))
        assertTrue(prompt.indexOf("小雨喜欢刘慈欣的科幻作品") < prompt.indexOf("<evidence_context>"))
    }

    @Test
    fun `test graph expansion merges into recall result and formats prompt`() = runBlocking {
        val seedCard = createCard("card_tea", "小雨喜欢喝乌龙茶")
        val expandedCard = createCard("card_cup", "小雨有一只紫砂茶杯")

        repository.cardsToReturn.clear()
        repository.cardsToReturn.add(seedCard)

        repository.graphExpandedCards.clear()
        repository.graphExpandedCards.add(expandedCard)

        val result = service.recall(
            assistant = testAssistant,
            query = "来杯茶吧",
            settings = Settings(),
        )

        assertNotNull(result)
        assertEquals(2, result!!.cards.size)
        assertEquals(1, result.graphExpandedCards.size)
        assertEquals("card_cup", result.graphExpandedCards[0].id)

        val prompt = result.formattedPrompt
        assertTrue(prompt.contains("[memory_id=card_tea] 小雨喜欢喝乌龙茶"))
        assertTrue(prompt.contains("[memory_id=card_cup] [关联记忆: 来自关系拓展] 小雨有一只紫砂茶杯"))
    }

    @Test
    fun `timeline expansion supplements an active same-timeline card`() = runBlocking {
        val seedCard = createCard("card_plan", "小雨正在准备答辩")
        val timelineCard = createCard("card_progress", "小雨已经完成答辩幻灯片")
        repository.cardsToReturn.clear()
        repository.cardsToReturn.add(seedCard)
        repository.timelineExpandedCards.clear()
        repository.timelineExpandedCards.add(timelineCard)

        val result = service.recall(testAssistant, "答辩准备得怎么样", Settings())

        assertNotNull(result)
        assertEquals(listOf("card_plan", "card_progress"), result!!.cards.map { it.id })
        assertEquals(listOf("card_progress"), result.timelineExpandedCards.map { it.id })
        assertTrue(result.formattedPrompt.contains("[同一事项的进展] 小雨已经完成答辩幻灯片"))
    }

    @Test
    fun `timeline expansion excludes current-turn sources and duplicate content`() = runBlocking {
        val seedCard = createCard("card_seed", "小雨正在准备答辩")
        val currentTurnCard = createCard("card_current", "小雨刚刚完成答辩海报")
        val duplicateCard = createCard("card_duplicate", "小雨刚刚完成答辩海报")
        val duplicateTimelineCard = createCard("card_duplicate_2", "小雨刚刚完成答辩海报")
        val validCard = createCard("card_valid", "小雨已经预约答辩场地")
        repository.cardsToReturn.clear()
        repository.cardsToReturn.add(seedCard)
        repository.timelineExpandedCards.clear()
        repository.timelineExpandedCards.addAll(listOf(currentTurnCard, duplicateCard, duplicateTimelineCard, validCard))
        repository.sourcesMap[currentTurnCard.id] = listOf(
            MemorySourceEntity(
                id = "source_current",
                memoryId = currentTurnCard.id,
                conversationId = "conversation",
                messageId = "message_current",
                role = "user",
                sourceKind = "chat",
                createdAt = 1L,
            )
        )

        val result = service.recall(
            assistant = testAssistant,
            query = "答辩准备得怎么样",
            settings = Settings(),
            currentMessageId = "message_current",
        )

        assertNotNull(result)
        assertEquals(listOf("card_seed", "card_duplicate", "card_valid"), result!!.cards.map { it.id })
        assertEquals(listOf("card_duplicate", "card_valid"), result.timelineExpandedCards.map { it.id })
    }

    @Test
    fun `recall never exceeds a three-card limit when both expansion channels have results`() = runBlocking {
        val limitedAssistant = testAssistant.copy(maxAutoRecallCount = 3, enableGraphExpansion = true)
        val seedCard = createCard("card_seed_limited", "小雨正在准备答辩")
        val timelineCard1 = createCard("card_timeline_1", "小雨完成答辩幻灯片")
        val timelineCard2 = createCard("card_timeline_2", "小雨预约答辩场地")
        val graphCard = createCard("card_graph", "小雨的导师关注答辩进展")
        repository.cardsToReturn.clear()
        repository.cardsToReturn.add(seedCard)
        repository.timelineExpandedCards.clear()
        repository.timelineExpandedCards.addAll(listOf(timelineCard1, timelineCard2))
        repository.graphExpandedCards.clear()
        repository.graphExpandedCards.add(graphCard)

        val result = service.recall(limitedAssistant, "答辩准备得怎么样", Settings())

        assertNotNull(result)
        assertEquals(3, result!!.cards.size)
        assertEquals(listOf("card_timeline_1", "card_timeline_2"), result.timelineExpandedCards.map { it.id })
        assertTrue(result.graphExpandedCards.isEmpty())
    }

    @Test
    fun `timeline expansion stays ahead of direct-result backfill`() = runBlocking {
        val directCards = (1..6).map { createCard("card_direct_$it", "直接命中 $it") }
        val timelineCard = createCard("card_timeline_priority", "同一事项的进展")
        repository.cardsToReturn.clear()
        repository.cardsToReturn.addAll(directCards)
        repository.timelineExpandedCards.clear()
        repository.timelineExpandedCards.add(timelineCard)
        repository.graphExpandedCards.clear()

        val result = service.recall(testAssistant, "继续这个事项", Settings())

        assertNotNull(result)
        assertEquals(
            listOf(
                "card_direct_1", "card_direct_2", "card_direct_3", "card_direct_4", "card_direct_5",
                "card_timeline_priority", "card_direct_6",
            ),
            result!!.cards.map { it.id },
        )
    }

    @Test
    fun `recall drops outdated fact when search only hits old card but newer active card exists in database`() = runBlocking {
        val oldCard = createCard("card_old", "小雨住在北京海淀", factKey = "living_city", updatedAt = 1000L)
        val newCard = createCard("card_new", "小雨搬到了上海浦东", factKey = "living_city", updatedAt = 2000L)

        // 搜索单边只命中了旧卡片
        repository.cardsToReturn.clear()
        repository.cardsToReturn.add(oldCard)
        // 数据库中其实存在更新的事实卡片
        repository.allStoredCards.clear()
        repository.allStoredCards.add(newCard)

        val result = service.recall(
            assistant = testAssistant,
            query = "小雨住在哪里",
            settings = Settings(),
        )

        // 旧卡片已被权威状态识别为过时，不得召回
        assertNull(result)
    }

    @Test
    fun `recall drops fact card when there is a pending fact conflict audit`() = runBlocking {
        val conflictCard = createCard("card_conflict", "小雨住在北京海淀", factKey = "living_city", updatedAt = 1000L)

        repository.cardsToReturn.clear()
        repository.cardsToReturn.add(conflictCard)
        repository.allStoredCards.clear()
        repository.pendingAuditFactKeys.clear()
        repository.pendingAuditFactKeys.add("living_city")

        val result = service.recall(
            assistant = testAssistant,
            query = "小雨住在哪里",
            settings = Settings(),
        )

        // 存在未决冲突审核的事实卡片不得召回注入
        assertNull(result)
    }

    @Test
    fun `graph expansion filters out cards with duplicate factKey or current turn source message`() = runBlocking {
        val customAssistant = testAssistant.copy(maxAutoRecallCount = 5, enableGraphExpansion = true)
        val seedCard = createCard("card_seed", "小雨正在准备毕业设计", factKey = "thesis_project", updatedAt = 2000L)
        val graphCardOldFact = createCard("card_graph_old_fact", "小雨最初选的题目是老题目", factKey = "thesis_project", updatedAt = 1000L)
        val graphCardCurrentSource = createCard("card_graph_cur_source", "导师刚才发了邮件", factKey = "supervisor_email", updatedAt = 2500L)
        val graphCardValid = createCard("card_graph_valid", "导师的研究方向是分布式系统", factKey = "supervisor_field", updatedAt = 2500L)

        repository.cardsToReturn.clear()
        repository.cardsToReturn.add(seedCard)
        repository.graphExpandedCards.clear()
        repository.graphExpandedCards.addAll(listOf(graphCardOldFact, graphCardCurrentSource, graphCardValid))
        repository.timelineExpandedCards.clear()

        repository.sourcesMap[graphCardCurrentSource.id] = listOf(
            MemorySourceEntity(
                id = "source_curr",
                memoryId = graphCardCurrentSource.id,
                conversationId = "conv",
                messageId = "msg_current_turn",
                role = "user",
                sourceKind = "chat",
                createdAt = 1000L,
            )
        )

        val result = service.recall(
            assistant = customAssistant,
            query = "毕设进行得怎么样",
            settings = Settings(),
            currentMessageId = "msg_current_turn",
        )

        assertNotNull(result)
        val graphCards = result!!.graphExpandedCards
        // graphCardOldFact (同 factKey) 和 graphCardCurrentSource (当轮消息) 均被过滤，仅保留 graphCardValid
        assertEquals(listOf("card_graph_valid"), graphCards.map { it.id })
    }

    @Test
    fun `timeline expansion filters out cards when there is a pending fact conflict audit`() = runBlocking {
        val customAssistant = testAssistant.copy(maxAutoRecallCount = 5)
        val seedCard = createCard("card_seed", "小雨正在准备毕业设计", factKey = "thesis_project", updatedAt = 2000L)
        val timelineCardConflict = createCard("card_timeline_conflict", "小雨住在北京海淀", factKey = "living_city", updatedAt = 1500L)
        val timelineCardValid = createCard("card_timeline_valid", "中期答辩已顺利通过", factKey = "thesis_stage", updatedAt = 2500L)

        repository.cardsToReturn.clear()
        repository.cardsToReturn.add(seedCard)
        repository.graphExpandedCards.clear()
        repository.timelineExpandedCards.clear()
        repository.timelineExpandedCards.addAll(listOf(timelineCardConflict, timelineCardValid))

        repository.pendingAuditFactKeys.clear()
        repository.pendingAuditFactKeys.add("living_city")

        val result = service.recall(
            assistant = customAssistant,
            query = "毕设进展如何",
            settings = Settings(),
        )

        assertNotNull(result)
        val timelineCards = result!!.timelineExpandedCards
        // 存在未决审计的 timelineCardConflict 必须被权威事实状态过滤拦截，仅保留 timelineCardValid
        assertEquals(listOf("card_timeline_valid"), timelineCards.map { it.id })
    }

    @Test
    fun `timeline expansion filters out superseded fact cards based on authoritative state`() = runBlocking {
        val customAssistant = testAssistant.copy(maxAutoRecallCount = 5)
        val seedCard = createCard("card_seed", "小雨正在准备毕业设计", factKey = "thesis_project", updatedAt = 2000L)
        val newerCardInDb = createCard("card_newer_active", "小雨搬到了上海浦东", factKey = "living_city", updatedAt = 3000L)
        val timelineCardSuperseded = createCard("card_timeline_old", "小雨住在北京海淀", factKey = "living_city", updatedAt = 1500L)
        val timelineCardValid = createCard("card_timeline_valid", "中期答辩已顺利通过", factKey = "thesis_stage", updatedAt = 2500L)

        repository.cardsToReturn.clear()
        repository.cardsToReturn.add(seedCard)
        repository.allStoredCards.clear()
        repository.allStoredCards.add(newerCardInDb)
        repository.graphExpandedCards.clear()
        repository.timelineExpandedCards.clear()
        repository.timelineExpandedCards.addAll(listOf(timelineCardSuperseded, timelineCardValid))
        repository.pendingAuditFactKeys.clear()

        val result = service.recall(
            assistant = customAssistant,
            query = "毕设进展如何",
            settings = Settings(),
        )

        assertNotNull(result)
        val timelineCards = result!!.timelineExpandedCards
        // 已被 newerCardInDb 淘汰的旧事实卡片 card_timeline_old 必须被权威事实状态过滤，仅保留 timelineCardValid
        assertEquals(listOf("card_timeline_valid"), timelineCards.map { it.id })
    }

    @Test
    fun `recall prioritizes assistant private fact over newer global fact and isolates global pending audit`() = runBlocking {
        val assistantWithGlobal = testAssistant.copy(useGlobalMemory = true)

        // 私有事实：用户住在上海，更新时间较早 (1000L)
        val privateCard = createCard(
            id = "card_private_shanghai",
            content = "小雨住在上海浦东",
            factKey = "living_city",
            updatedAt = 1000L,
        )

        // 全局事实：用户住在北京，更新时间较新 (2000L)
        val globalCard = MemoryCardEntity(
            id = "card_global_beijing",
            ownerType = MemoryConstants.OWNER_TYPE_GLOBAL,
            ownerId = MemoryConstants.GLOBAL_OWNER_ID,
            category = "preference",
            factKey = "living_city",
            content = "小雨住在北京朝阳",
            confidence = 1.0f,
            importance = 0.8f,
            status = MemoryConstants.STATUS_ACTIVE,
            createdAt = 1000L,
            updatedAt = 2000L,
            revision = 1,
            createdBy = "user",
            updatedBy = "user",
        )

        // 全局记忆中还存在另一条独立事实：宠物 (私有空间无此事实)
        val globalPetCard = MemoryCardEntity(
            id = "card_global_pet",
            ownerType = MemoryConstants.OWNER_TYPE_GLOBAL,
            ownerId = MemoryConstants.GLOBAL_OWNER_ID,
            category = "preference",
            factKey = "pet_kind",
            content = "小雨养了一只金渐层猫猫",
            confidence = 1.0f,
            importance = 0.8f,
            status = MemoryConstants.STATUS_ACTIVE,
            createdAt = 1000L,
            updatedAt = 1500L,
            revision = 1,
            createdBy = "user",
            updatedBy = "user",
        )

        // 设置搜索命中这三张卡
        repository.cardsToReturn.clear()
        repository.cardsToReturn.addAll(listOf(privateCard, globalCard, globalPetCard))

        // 模拟 GLOBAL 空间的 living_city 存在未决冲突审核
        repository.pendingAuditFactKeyOwners.clear()
        repository.pendingAuditFactKeyOwners.add(MemoryConstants.OWNER_TYPE_GLOBAL to "living_city")

        val result = service.recall(
            assistant = assistantWithGlobal,
            query = "小雨住哪里，养什么宠物",
            settings = Settings(),
        )

        assertNotNull(result)
        val recalledIds = result!!.cards.map { it.id }

        // 1. 私有事实 card_private_shanghai 必须成功召回（不能被 GLOBAL 的未决审核阻断，也不能被较新的 globalCard 淘汰）
        assertTrue("Private fact must be recalled", recalledIds.contains("card_private_shanghai"))

        // 2. 全局卡片 card_global_beijing 必须被屏蔽（私有事实优先，同 factKey 的全局事实不得进入）
        assertFalse("Global fact must be shadowed by private fact", recalledIds.contains("card_global_beijing"))

        // 3. 私有空间不存在 pet_kind 事实，因此无私有时成功回退并采纳全局卡 card_global_pet
        assertTrue("Global pet fact must be recalled when no private fact exists", recalledIds.contains("card_global_pet"))
    }

    private class FakeMemoryEvidenceSearcher : MemoryEvidenceSearcher {
        val searchHits = mutableListOf<me.rerere.rikkahub.data.db.fts.MessageSearchResult>()
        val conversations = mutableMapOf<Uuid, me.rerere.rikkahub.data.model.Conversation>()
        var lastSearchQuery: String? = null
        var lastExcludeMessageId: String? = null

        override suspend fun searchEvidence(
            query: String,
            assistantId: String,
            sinceEpochMilli: Long,
            excludeMessageId: String?,
            limit: Int,
        ): List<me.rerere.rikkahub.data.db.fts.MessageSearchResult> {
            lastSearchQuery = query
            lastExcludeMessageId = excludeMessageId
            return searchHits.filter { it.messageId != excludeMessageId }.take(limit)
        }

        override suspend fun getConversation(uuid: Uuid): me.rerere.rikkahub.data.model.Conversation? {
            return conversations[uuid]
        }
    }
}
