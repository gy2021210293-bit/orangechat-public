/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.insertLocalMemoryContextBeforeCurrentUser
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryPayloadVerificationTest {

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

        override suspend fun getCurrentExperiencesForCards(cardIds: List<String>): List<MemoryExperienceCoordinateEntity> =
            experiences.filter { it.memoryCardId in cardIds && it.status == MemoryExperienceCoordinateEntity.STATUS_CURRENT }

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

    @Test
    fun eAxisPayload_injectedIntoProviderMessagesBeforeCurrentUser_whenEnabled() = runBlocking {
        val fakeRepo = FakeRepo()
        val assistantId = Uuid.random()
        val cardId = "card-exp-1"

        // 1. 准备卡片及权威 E 坐标
        val card = MemoryCardEntity(
            id = cardId,
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            category = "experience",
            content = "今天和小雨一起散步，感到非常温暖与被信任",
            factKey = "self.experience.walk_together",
            status = "ACTIVE",
            createdBy = "user",
            updatedBy = "user",
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        fakeRepo.cards.add(card)

        val coord = MemoryExperienceCoordinateEntity(
            id = 1L,
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryCardId = cardId,
            meaning = "我和小雨彼此陪伴让我感到温暖与安心",
            eAuthoredBy = "primary-agent",
            eInitialPriority = 85,
            valence = 0.85,
            arousal = 0.35,
            tension = 0.1,
            emotionConfidence = 0.9,
            responseTendency = MemoryExperienceCoordinateEntity.TENDENCY_ENGAGE,
            growthDelta = MemoryExperienceCoordinateEntity.GROWTH_GROWTH,
            sourceEpisodeKey = "conv:c1:msg:m1",
            status = MemoryExperienceCoordinateEntity.STATUS_CURRENT,
            createdAt = 1000L,
        )
        fakeRepo.experiences.add(coord)

        val embeddingService = MemoryEmbeddingService(fakeRepo)
        val recallService = MemoryRecallService(fakeRepo, embeddingService)

        // 2. 启用 E 轴配置，必须设置 memoryMode = LOCAL_V1
        val assistant = Assistant(
            id = assistantId,
            name = "橘瓣",
            memoryMode = MemoryMode.LOCAL_V1,
            autoRecallMemory = true,
            enableExperienceMemory = true,
            maxAutoRecallCount = 3,
            allowEmotionResonanceRecall = true,
        )
        val settings = Settings()

        // 3. 执行召回
        val recallResult = recallService.recall(
            assistant = assistant,
            query = "散步",
            settings = settings,
        )
        assertNotNull(recallResult)
        val prompt = recallResult!!.formattedPrompt

        // 断言召回的 prompt 中包含 <recalled_experience> 标签与体验核心内容
        assertTrue("Prompt should contain <recalled_experience> block", prompt.contains("<recalled_experience>"))
        assertTrue("Prompt should contain meaning", prompt.contains("我和小雨彼此陪伴让我感到温暖与安心"))
        assertTrue("Prompt should contain response_tendency tag", prompt.contains("<response_tendency>ENGAGE</response_tendency>"))
        assertTrue("Prompt should contain tension tag", prompt.contains("<tension>0.10</tension>"))

        // 4. 组装发往 Provider 的上下文消息链
        val initialMessages = listOf(
            UIMessage.system("你是一个温暖的伴侣AI助手"),
            UIMessage.user("之前我们有一起散步过吗？"),
            UIMessage.assistant("有的，那次真的很开心！"),
            UIMessage.user("今天天气真好，我们出去散步吧"),
        )

        val finalPayload = insertLocalMemoryContextBeforeCurrentUser(
            messages = initialMessages,
            localMemoryContext = prompt,
        )

        // 5. 验证最终载荷：消息数量应为 5，插在最后一条 USER 提问之前
        assertEquals(5, finalPayload.size)
        val injectedMemoryMsg = finalPayload[3] // 倒数第二条
        val currentUserMsg = finalPayload[4]   // 最后一条

        assertEquals(MessageRole.USER, injectedMemoryMsg.role)
        assertEquals(MessageRole.USER, currentUserMsg.role)
        assertEquals("今天天气真好，我们出去散步吧", currentUserMsg.toText())

        // 验证 injected 消息确实包含 E 轴内容且标记了 memory_context
        val injectedText = injectedMemoryMsg.toText()
        assertTrue("Injected memory message must contain <recalled_experience>", injectedText.contains("<recalled_experience>"))
        assertTrue("Injected memory message must contain meaning", injectedText.contains("我和小雨彼此陪伴让我感到温暖与安心"))
        assertTrue(
            "Injected message must have memory_context metadata",
            injectedMemoryMsg.metadata?.get("memory_context")?.toString() == "true"
        )
    }

    @Test
    fun eAxisPayload_excludedFromProviderMessages_whenDisabled() = runBlocking {
        val fakeRepo = FakeRepo()
        val assistantId = Uuid.random()
        val cardId = "card-exp-2"

        val card = MemoryCardEntity(
            id = cardId,
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            category = "experience",
            content = "今天和小雨一起散步",
            factKey = "self.experience.walk",
            status = "ACTIVE",
            createdBy = "user",
            updatedBy = "user",
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        fakeRepo.cards.add(card)

        val coord = MemoryExperienceCoordinateEntity(
            id = 2L,
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryCardId = cardId,
            meaning = "散步体验",
            eAuthoredBy = "primary-agent",
            eInitialPriority = 80,
            valence = 0.8,
            arousal = 0.3,
            tension = 0.1,
            emotionConfidence = 0.9,
            responseTendency = MemoryExperienceCoordinateEntity.TENDENCY_ENGAGE,
            growthDelta = MemoryExperienceCoordinateEntity.GROWTH_STABLE,
            sourceEpisodeKey = "conv:c1:msg:m1",
            status = MemoryExperienceCoordinateEntity.STATUS_CURRENT,
            createdAt = 1000L,
        )
        fakeRepo.experiences.add(coord)

        val embeddingService = MemoryEmbeddingService(fakeRepo)
        val recallService = MemoryRecallService(fakeRepo, embeddingService)

        // 关闭 E 轴配置，必须设置 memoryMode = LOCAL_V1
        val assistant = Assistant(
            id = assistantId,
            name = "橘瓣",
            memoryMode = MemoryMode.LOCAL_V1,
            autoRecallMemory = true,
            enableExperienceMemory = false, // 关闭！
            maxAutoRecallCount = 3,
        )
        val settings = Settings()

        val recallResult = recallService.recall(
            assistant = assistant,
            query = "散步",
            settings = settings,
        )
        assertNotNull(recallResult)
        val prompt = recallResult!!.formattedPrompt

        // 断言即使卡片有 E 坐标，关闭后 formattedPrompt 绝不出现 <recalled_experience>
        assertFalse("Prompt must NOT contain <recalled_experience> when disabled", prompt.contains("<recalled_experience>"))
        assertFalse("Prompt must NOT contain experience meaning when disabled", prompt.contains("散步体验"))

        val initialMessages = listOf(
            UIMessage.user("今天我们去散步吗？"),
        )
        val finalPayload = insertLocalMemoryContextBeforeCurrentUser(
            messages = initialMessages,
            localMemoryContext = prompt,
        )

        val combinedPayloadText = finalPayload.joinToString("\n") { it.toText() }
        assertFalse("Final payload must NOT contain <recalled_experience>", combinedPayloadText.contains("<recalled_experience>"))
        assertFalse("Final payload must NOT contain experience meaning", combinedPayloadText.contains("散步体验"))
    }
}
