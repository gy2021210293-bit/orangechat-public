/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryRecallServiceETest {

    private class FakeRepo : MemoryRepositoryV1() {
        val cards = mutableListOf<MemoryCardEntity>()
        val experiences = mutableListOf<MemoryExperienceCoordinateEntity>()

        override suspend fun search(
            query: String,
            ownerType: String,
            ownerId: String,
            statusList: List<String>?,
            limit: Int,
            includeGlobal: Boolean,
        ): List<MemoryCardEntity> {
            return cards.filter { card ->
                (statusList == null || card.status in statusList) &&
                    (query.isBlank() || card.content.contains(query, ignoreCase = true))
            }.take(limit)
        }

        override suspend fun getCardsByIds(ids: List<String>): List<MemoryCardEntity> {
            return cards.filter { it.id in ids }
        }

        override suspend fun getCurrentExperiencesForCards(cardIds: List<String>): List<MemoryExperienceCoordinateEntity> {
            return experiences.filter { it.memoryCardId in cardIds && it.status == MemoryExperienceCoordinateEntity.STATUS_CURRENT }
        }

        override suspend fun getCurrentExperiencesForOwner(ownerType: String, ownerId: String): List<MemoryExperienceCoordinateEntity> {
            return experiences.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == MemoryExperienceCoordinateEntity.STATUS_CURRENT }
        }

        override suspend fun recordCardRecalls(cardIds: List<String>, recallTimeMs: Long) {
            // no-op
        }

        override suspend fun getAuthoritativeFactStates(
            ownerType: String,
            ownerId: String,
            includeGlobal: Boolean,
            factKeys: Collection<String>,
        ): Map<String, FactKeyAuthoritativeState> {
            return factKeys.associateWith { fk ->
                val matching = cards.filter { it.factKey == fk && it.status == MemoryConstants.STATUS_ACTIVE }
                val latest = matching.maxByOrNull { it.updatedAt }
                FactKeyAuthoritativeState(
                    privateLatestActiveCardId = latest?.id,
                    privateLatestUpdatedAt = latest?.updatedAt,
                    privateHasPendingAudit = false,
                )
            }
        }
    }

    private val repo = FakeRepo()
    private val embeddingService = MemoryEmbeddingService(repo)
    private val service = MemoryRecallService(
        repository = repo,
        embeddingService = embeddingService,
    )

    private val assistant = Assistant(
        id = Uuid.random(),
        memoryMode = MemoryMode.LOCAL_V1,
        autoRecallMemory = true,
        maxAutoRecallCount = 5,
        enableExperienceMemory = true,
        allowEmotionResonanceRecall = true,
    )

    private fun addCardWithE(
        id: String,
        content: String,
        meaning: String,
        valence: Double,
        arousal: Double,
        priority: Int = 80,
    ) {
        val card = MemoryCardEntity(
            id = id,
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistant.id.toString(),
            category = "experience",
            content = content,
            status = MemoryConstants.STATUS_ACTIVE,
            createdBy = MemoryConstants.ACTOR_TOOL,
            updatedBy = MemoryConstants.ACTOR_TOOL,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        val exp = MemoryExperienceCoordinateEntity(
            id = repo.experiences.size + 1L,
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistant.id.toString(),
            memoryCardId = id,
            meaning = meaning,
            eAuthoredBy = "MAIN_AI",
            eInitialPriority = priority,
            valence = valence,
            arousal = arousal,
            tension = 0.5,
            emotionConfidence = 0.9,
            responseTendency = MemoryExperienceCoordinateEntity.TENDENCY_COMFORT,
            growthDelta = MemoryExperienceCoordinateEntity.GROWTH_STABLE,
            sourceEpisodeKey = "conv_1:msg_1",
            status = MemoryExperienceCoordinateEntity.STATUS_CURRENT,
            createdAt = System.currentTimeMillis(),
        )
        repo.cards.add(card)
        repo.experiences.add(exp)
    }

    @Test
    fun emotionResonanceRecall_whenEmotionalQuery_triggersResonanceChannelAndInjectsExperience() = runBlocking {
        addCardWithE(
            id = "exp_card_1",
            content = "关于雨天喝热可可的温馨时刻",
            meaning = "这是我和小雨在疲惫落泪时共同取暖的重要记忆",
            valence = -0.6,
            arousal = 0.7,
            priority = 85,
        )

        val result = service.recall(
            assistant = assistant,
            query = "我好难过，今天被导师批评得一塌糊涂",
            settings = Settings(),
        )

        assertNotNull("情绪共振通道应触发召回", result)
        assertEquals(1, result!!.cards.size)
        assertEquals("exp_card_1", result.cards.first().id)
        assertEquals(1, result.emotionResonantCards.size)
        assertEquals("exp_card_1", result.emotionResonantCards.first().id)
        assertEquals(1, result.recalledExperiences.size)

        assertTrue("提示词中应包含情绪共振标签", result.formattedPrompt.contains("[情绪共振]"))
        assertTrue("提示词中应注入 <recalled_experience>", result.formattedPrompt.contains("<recalled_experience"))
        assertTrue("应包含我方经验意义", result.formattedPrompt.contains("这是我和小雨在疲惫落泪时共同取暖的重要记忆"))
        assertTrue("应包含回应倾向", result.formattedPrompt.contains("<response_tendency>COMFORT</response_tendency>"))
    }

    @Test
    fun emotionResonanceRecall_whenResonanceRecallDisabled_doesNotTrigger() = runBlocking {
        addCardWithE(
            id = "exp_card_2",
            content = "关于雨天喝热可可的温馨时刻",
            meaning = "这是我和小雨在疲惫落泪时共同取暖的重要记忆",
            valence = -0.6,
            arousal = 0.7,
        )

        val disabledAssistant = assistant.copy(allowEmotionResonanceRecall = false)

        val result = service.recall(
            assistant = disabledAssistant,
            query = "我好难过，今天被导师批评得一塌糊涂",
            settings = Settings(),
        )

        assertNull("情绪共振召回关闭时不应触发独立召回", result)
    }

    @Test
    fun emotionResonanceRecall_whenNeutralQuery_doesNotTriggerResonance() = runBlocking {
        addCardWithE(
            id = "exp_card_3",
            content = "关于雨天喝热可可的温馨时刻",
            meaning = "这是我和小雨在疲惫落泪时共同取暖的重要记忆",
            valence = -0.6,
            arousal = 0.7,
        )

        val result = service.recall(
            assistant = assistant,
            query = "今天天气如何，气温大概多少度？",
            settings = Settings(),
        )

        assertNull("中性 query 不应触发情绪共振独立召回", result)
    }

    @Test
    fun emotionResonanceRecall_whenExperienceMemoryDisabled_doesNotInjectExperience() = runBlocking {
        addCardWithE(
            id = "exp_card_4",
            content = "关于雨天喝热可可的温馨时刻",
            meaning = "这是我和小雨在疲惫落泪时共同取暖的重要记忆",
            valence = -0.6,
            arousal = 0.7,
        )

        val disabledExpAssistant = assistant.copy(enableExperienceMemory = false)

        val result = service.recall(
            assistant = disabledExpAssistant,
            query = "关于雨天喝热可可的温馨时刻",
            settings = Settings(),
        )

        assertNotNull(result)
        assertEquals(1, result!!.cards.size)
        assertFalse(result.formattedPrompt.contains("<recalled_experience"))
        assertTrue(result.recalledExperiences.isEmpty())
    }

    @Test
    fun maxAutoRecallCount_zero_neverRecallsEvenWithEmotion() = runBlocking {
        addCardWithE(
            id = "exp_card_zero",
            content = "关于雨天喝热可可的温馨时刻",
            meaning = "这是我和小雨的重要记忆",
            valence = -0.75,
            arousal = 0.25,
        )

        val zeroAssistant = assistant.copy(maxAutoRecallCount = 0)

        // 哪怕查询词包含极其强烈悲伤的情绪，零配额也绝对不返回任何召回
        val result = service.recall(
            assistant = zeroAssistant,
            query = "好难过，今天真的太委屈太累了 😭",
            settings = Settings(),
        )

        assertNull("maxAutoRecallCount=0 时必须严格不召回任何记忆", result)
    }

    @Test
    fun supersededFactKeyCard_filteredByAuthoritativeState_evenWithHighResonance() = runBlocking {
        // 旧卡片具有很强的情绪共振，但 factKey 'user.status' 已经被新卡片更新
        val oldCard = MemoryCardEntity(
            id = "card_old",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistant.id.toString(),
            category = "experience",
            content = "旧状态记忆",
            factKey = "user.work_status",
            status = MemoryConstants.STATUS_ACTIVE,
            createdBy = MemoryConstants.ACTOR_TOOL,
            updatedBy = MemoryConstants.ACTOR_TOOL,
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        val newCard = MemoryCardEntity(
            id = "card_new",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistant.id.toString(),
            category = "profile",
            content = "新工作状态已确定",
            factKey = "user.work_status",
            status = MemoryConstants.STATUS_ACTIVE,
            createdBy = MemoryConstants.ACTOR_TOOL,
            updatedBy = MemoryConstants.ACTOR_TOOL,
            createdAt = 2000L,
            updatedAt = 2000L,
        )
        val expOld = MemoryExperienceCoordinateEntity(
            id = 999L,
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistant.id.toString(),
            memoryCardId = oldCard.id,
            meaning = "我和小雨都很悲伤难过",
            eAuthoredBy = "primary-agent",
            eInitialPriority = 99,
            valence = -0.75,
            arousal = 0.25,
            sourceEpisodeKey = "conv:1:msg:1",
            status = MemoryExperienceCoordinateEntity.STATUS_CURRENT,
            createdAt = 1000L,
        )

        repo.cards.add(oldCard)
        repo.cards.add(newCard)
        repo.experiences.add(expOld)

        // 查询一个无 FTS 匹配但情绪强烈悲伤的 query，触发情绪共振独立通道
        val result = service.recall(
            assistant = assistant,
            query = "真的太难过太伤心了想哭 😭",
            settings = Settings(),
        )

        // 旧卡片由于不是 user.work_status 的权威卡片 (card_new 才是)，情绪共振通道绝不能复活已失效旧卡片
        val recalledIds = result?.cards?.map { it.id }.orEmpty()
        assertFalse("失效的旧事实绝不能借情绪共振重新进入召回", "card_old" in recalledIds)
    }
}
