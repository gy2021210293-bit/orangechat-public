/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEmbeddingEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEmbeddingProjection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class MemoryEmbeddingServiceTest {

    private class FakeMemoryRepository : MemoryRepositoryV1() {
        val projections = mutableListOf<MemoryEmbeddingProjection>()
        val cards = mutableMapOf<String, MemoryCardEntity>()
        val embeddings = mutableMapOf<String, MemoryEmbeddingEntity>()
        val pagedQueries = mutableListOf<Pair<Int, Int>>()

        private fun embKey(memoryId: String, provider: String, model: String) = "${memoryId}_${provider}_${model}"

        override suspend fun getActiveEmbeddingProjections(
            ownerType: String,
            ownerId: String,
            embeddingProvider: String,
            embeddingModel: String,
            includeGlobal: Boolean,
            limit: Int,
            offset: Int
        ): List<MemoryEmbeddingProjection> {
            if (offset >= projections.size) return emptyList()
            return projections.drop(offset).take(limit)
        }

        override suspend fun getCardById(id: String): MemoryCardEntity? {
            return cards[id]
        }

        override suspend fun getEmbedding(
            memoryId: String,
            embeddingProvider: String,
            embeddingModel: String
        ): MemoryEmbeddingEntity? {
            return embeddings[embKey(memoryId, embeddingProvider, embeddingModel)]
        }

        override suspend fun getEmbeddingByMemoryId(memoryId: String): MemoryEmbeddingEntity? {
            return embeddings.values.firstOrNull { it.memoryId == memoryId }
        }

        override suspend fun conditionalInsertEmbedding(
            memoryId: String,
            expectedRevision: Int,
            expectedStatus: String,
            expectedContentHash: String,
            embedding: MemoryEmbeddingEntity
        ): Boolean {
            val card = cards[memoryId] ?: return false
            if (card.revision != expectedRevision ||
                card.status != expectedStatus ||
                MemoryEmbeddingService.computeContentHash(card.content) != expectedContentHash
            ) {
                return false
            }
            embeddings[embKey(memoryId, embedding.embeddingProvider, embedding.embeddingModel)] = embedding
            return true
        }

        override suspend fun insertOrReplaceEmbedding(embedding: MemoryEmbeddingEntity) {
            embeddings[embKey(embedding.memoryId, embedding.embeddingProvider, embedding.embeddingModel)] = embedding
        }

        override suspend fun deleteEmbedding(
            memoryId: String,
            embeddingProvider: String,
            embeddingModel: String
        ) {
            embeddings.remove(embKey(memoryId, embeddingProvider, embeddingModel))
        }

        override suspend fun deleteEmbeddingByMemoryId(memoryId: String) {
            embeddings.entries.removeAll { it.value.memoryId == memoryId }
        }

        override suspend fun countCardsByStatus(ownerType: String, ownerId: String, status: String): Int {
            return cards.values.count { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }
        }

        override suspend fun getCardsByStatusPaged(
            ownerType: String,
            ownerId: String,
            status: String,
            limit: Int,
            offset: Int
        ): List<MemoryCardEntity> {
            pagedQueries.add(limit to offset)
            val filtered = cards.values.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }
            return filtered.drop(offset).take(limit)
        }
    }

    private class TestMemoryEmbeddingService(
        val repo: FakeMemoryRepository,
    ) : MemoryEmbeddingService(repository = repo) {
        var generateCallCount = 0
        var generateDelayMs: Long = 0L
        var embeddingToReturn: FloatArray? = floatArrayOf(1.0f, 0.0f, 0.0f)

        override suspend fun generateEmbedding(
            text: String,
            model: me.rerere.ai.provider.Model,
            providerSetting: me.rerere.ai.provider.ProviderSetting,
        ): FloatArray? {
            generateCallCount++
            if (generateDelayMs > 0L) {
                kotlinx.coroutines.delay(generateDelayMs)
            }
            return embeddingToReturn
        }
    }

    private val repository = FakeMemoryRepository()
    private val service = MemoryEmbeddingService(repository = repository)

    @Test
    fun `float array and blob serialization roundtrip`() {
        val original = floatArrayOf(0.123f, -0.456f, 1.0f, 0.0f, -99.99f)
        val blob = MemoryEmbeddingService.floatArrayToVectorBlob(original)
        val decoded = MemoryEmbeddingService.vectorBlobToFloatArray(blob)

        assertEquals(original.size * 4, blob.size)
        assertArrayEquals(original, decoded, 1e-6f)
    }

    @Test
    fun `calculateCosineSimilarity with math precision`() {
        // 同向向量: similarity = 1.0
        val v1 = floatArrayOf(1.0f, 2.0f, 3.0f)
        val norm1 = sqrt(1.0f * 1.0f + 2.0f * 2.0f + 3.0f * 3.0f)
        val blob1 = MemoryEmbeddingService.floatArrayToVectorBlob(v1)
        val simSame = MemoryEmbeddingService.calculateCosineSimilarity(v1, norm1, blob1)
        assertEquals(1.0f, simSame, 1e-5f)

        // 正交向量: similarity = 0.0
        val v2 = floatArrayOf(0.0f, 1.0f, 0.0f)
        val v3 = floatArrayOf(1.0f, 0.0f, 0.0f)
        val norm2 = 1.0f
        val blob3 = MemoryEmbeddingService.floatArrayToVectorBlob(v3)
        val simOrthogonal = MemoryEmbeddingService.calculateCosineSimilarity(v2, norm2, blob3)
        assertEquals(0.0f, simOrthogonal, 1e-5f)

        // 反向向量: similarity = -1.0
        val v4 = floatArrayOf(-1.0f, -2.0f, -3.0f)
        val blob4 = MemoryEmbeddingService.floatArrayToVectorBlob(v4)
        val simOpposite = MemoryEmbeddingService.calculateCosineSimilarity(v1, norm1, blob4)
        assertEquals(-1.0f, simOpposite, 1e-5f)

        // 维度不匹配: 返回 0.0
        val vMismatch = floatArrayOf(1.0f, 2.0f)
        val blobMismatch = MemoryEmbeddingService.floatArrayToVectorBlob(vMismatch)
        val simMismatch = MemoryEmbeddingService.calculateCosineSimilarity(v1, norm1, blobMismatch)
        assertEquals(0.0f, simMismatch, 1e-5f)
    }

    @Test
    fun `searchSimilarMemories filters mismatched dimensions and maintains Top-K order`() = runBlocking {
        val queryVector = floatArrayOf(1.0f, 0.0f, 0.0f)

        // 构造模拟候选列表
        repository.projections.addAll(
            listOf(
                MemoryEmbeddingProjection("card_1", 3, MemoryEmbeddingService.floatArrayToVectorBlob(floatArrayOf(0.9f, 0.1f, 0.0f))),
                MemoryEmbeddingProjection("card_2", 3, MemoryEmbeddingService.floatArrayToVectorBlob(floatArrayOf(0.5f, 0.5f, 0.0f))),
                MemoryEmbeddingProjection("card_3", 3, MemoryEmbeddingService.floatArrayToVectorBlob(floatArrayOf(0.1f, 0.9f, 0.0f))),
                MemoryEmbeddingProjection("card_4", 2, MemoryEmbeddingService.floatArrayToVectorBlob(floatArrayOf(1.0f, 0.0f))),
                MemoryEmbeddingProjection("card_5", 3, MemoryEmbeddingService.floatArrayToVectorBlob(floatArrayOf(0.8f, 0.2f, 0.0f))),
            )
        )

        val results = service.searchSimilarMemories(
            assistantId = "asst_1",
            includeGlobal = false,
            queryVector = queryVector,
            embeddingProvider = "provider_1",
            embeddingModel = "text-embedding-3",
            limit = 3,
        )

        assertEquals(3, results.size)
        assertEquals("card_1", results[0].first)
        assertEquals("card_5", results[1].first)
        assertEquals("card_2", results[2].first)
        assertTrue(results[0].second > results[1].second)
        assertTrue(results[1].second > results[2].second)
    }

    @Test
    fun `searchSimilarMemories filters low similarity below MIN_VECTOR_SIMILARITY threshold`() = runBlocking {
        val queryVector = floatArrayOf(1.0f, 0.0f, 0.0f)

        repository.projections.clear()
        repository.projections.addAll(
            listOf(
                MemoryEmbeddingProjection("card_low", 3, MemoryEmbeddingService.floatArrayToVectorBlob(floatArrayOf(0.49f, 0.8717f, 0.0f))),
                MemoryEmbeddingProjection("card_high", 3, MemoryEmbeddingService.floatArrayToVectorBlob(floatArrayOf(0.8f, 0.6f, 0.0f))),
            )
        )

        val results = service.searchSimilarMemories(
            assistantId = "asst_1",
            includeGlobal = false,
            queryVector = queryVector,
            embeddingProvider = "provider_1",
            embeddingModel = "text-embedding-3",
            limit = 10,
        )

        assertEquals(1, results.size)
        assertEquals("card_high", results[0].first)
    }

    @Test
    fun `syncCardEmbedding creates deterministic ID and stores vector with provider`() = runBlocking {
        val testRepo = FakeMemoryRepository()
        val testService = TestMemoryEmbeddingService(testRepo)

        val modelUuid = kotlin.uuid.Uuid.random()
        val providerUuid = kotlin.uuid.Uuid.random()
        val model = me.rerere.ai.provider.Model(
            id = modelUuid,
            modelId = "text-embedding-3-small",
            displayName = "Embedding 3 Small",
        )
        val provider = me.rerere.ai.provider.ProviderSetting.OpenAI(
            id = providerUuid,
            name = "Test OpenAI",
            models = listOf(model),
        )
        val settings = me.rerere.rikkahub.data.datastore.Settings(
            providers = listOf(provider),
        )

        val card = me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity(
            id = "card_test_1",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = "asst_1",
            content = "小雨喜欢喝拿铁",
            category = MemoryConstants.CATEGORY_PREFERENCE,
            status = MemoryConstants.STATUS_ACTIVE,
            revision = 1,
            confidence = 1.0f,
            importance = 0.5f,
            validFrom = 0L,
            createdAt = 0L,
            updatedAt = 0L,
            createdBy = "USER",
            updatedBy = "USER",
        )
        testRepo.cards[card.id] = card

        val success = testService.syncCardEmbedding(card, modelUuid, settings)
        assertTrue(success)
        assertEquals(1, testService.generateCallCount)

        val saved = testRepo.getEmbedding(card.id, providerUuid.toString(), model.modelId)
        org.junit.Assert.assertNotNull(saved)
        assertEquals("emb_${card.id}_${providerUuid}_${model.modelId}", saved!!.id)
        assertEquals(providerUuid.toString(), saved.embeddingProvider)
        assertEquals(model.modelId, saved.embeddingModel)
        assertEquals(MemoryEmbeddingService.computeContentHash(card.content), saved.contentHash)
    }

    @Test
    fun `syncCardEmbedding reuses existing vector only when content model and provider match`() = runBlocking {
        val testRepo = FakeMemoryRepository()
        val testService = TestMemoryEmbeddingService(testRepo)

        val modelUuid = kotlin.uuid.Uuid.random()
        val providerUuid1 = kotlin.uuid.Uuid.random()
        val providerUuid2 = kotlin.uuid.Uuid.random()

        val model1 = me.rerere.ai.provider.Model(id = modelUuid, modelId = "text-embedding-3")
        val provider1 = me.rerere.ai.provider.ProviderSetting.OpenAI(id = providerUuid1, name = "P1", models = listOf(model1))
        val settings1 = me.rerere.rikkahub.data.datastore.Settings(providers = listOf(provider1))

        val card = me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity(
            id = "card_reuse_test",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = "asst_1",
            content = "小雨的编程喜好",
            category = MemoryConstants.CATEGORY_PREFERENCE,
            status = MemoryConstants.STATUS_ACTIVE,
            revision = 1,
            confidence = 1.0f,
            importance = 0.5f,
            validFrom = 0L,
            createdAt = 0L,
            updatedAt = 0L,
            createdBy = "USER",
            updatedBy = "USER",
        )
        testRepo.cards[card.id] = card

        // 首次同步生成
        testService.syncCardEmbedding(card, modelUuid, settings1)
        assertEquals(1, testService.generateCallCount)

        // 相同内容 + 相同模型 + 相同 Provider: 复用，不调用生成
        testService.syncCardEmbedding(card, modelUuid, settings1)
        assertEquals(1, testService.generateCallCount)

        // 切换 Provider (Provider ID 变动): 即使 modelId 与 contentHash 相同，也禁止复用，必须重新生成
        val model2 = me.rerere.ai.provider.Model(id = modelUuid, modelId = "text-embedding-3")
        val provider2 = me.rerere.ai.provider.ProviderSetting.OpenAI(id = providerUuid2, name = "P2", models = listOf(model2))
        val settings2 = me.rerere.rikkahub.data.datastore.Settings(providers = listOf(provider2))

        testService.syncCardEmbedding(card, modelUuid, settings2)
        assertEquals(2, testService.generateCallCount)
        // 关键验证：两个不同 Provider 的向量共存，互不覆盖和删除
        val saved1 = testRepo.getEmbedding(card.id, providerUuid1.toString(), model1.modelId)
        val saved2 = testRepo.getEmbedding(card.id, providerUuid2.toString(), model2.modelId)
        org.junit.Assert.assertNotNull(saved1)
        org.junit.Assert.assertNotNull(saved2)
        assertEquals(providerUuid1.toString(), saved1?.embeddingProvider)
        assertEquals(providerUuid2.toString(), saved2?.embeddingProvider)
    }

    @Test
    fun `multiple assistants using different models can co-exist on the same global card`() = runBlocking {
        val testRepo = FakeMemoryRepository()
        val testService = TestMemoryEmbeddingService(testRepo)

        val modelUuid1 = kotlin.uuid.Uuid.random()
        val modelUuid2 = kotlin.uuid.Uuid.random()
        val providerUuid = kotlin.uuid.Uuid.random()

        val model1 = me.rerere.ai.provider.Model(id = modelUuid1, modelId = "text-embedding-3-small")
        val model2 = me.rerere.ai.provider.Model(id = modelUuid2, modelId = "text-embedding-3-large")
        val provider = me.rerere.ai.provider.ProviderSetting.OpenAI(
            id = providerUuid,
            name = "Test OpenAI",
            models = listOf(model1, model2),
        )
        val settings = me.rerere.rikkahub.data.datastore.Settings(
            providers = listOf(provider),
        )

        val globalCard = me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity(
            id = "global_card_1",
            ownerType = MemoryConstants.OWNER_TYPE_GLOBAL,
            ownerId = MemoryConstants.GLOBAL_OWNER_ID,
            content = "小雨喜欢安静的环境",
            category = MemoryConstants.CATEGORY_PREFERENCE,
            status = MemoryConstants.STATUS_ACTIVE,
            revision = 1,
            confidence = 1.0f,
            importance = 0.5f,
            validFrom = 0L,
            createdAt = 0L,
            updatedAt = 0L,
            createdBy = "USER",
            updatedBy = "USER",
        )
        testRepo.cards[globalCard.id] = globalCard

        // Assistant 1 同步 model 1
        val ok1 = testService.syncCardEmbedding(globalCard, modelUuid1, settings)
        assertTrue(ok1)
        // Assistant 2 同步 model 2
        val ok2 = testService.syncCardEmbedding(globalCard, modelUuid2, settings)
        assertTrue(ok2)

        // 验证两个模型的向量均独立存在，且属于同一张卡片
        val emb1 = testRepo.getEmbedding(globalCard.id, providerUuid.toString(), model1.modelId)
        val emb2 = testRepo.getEmbedding(globalCard.id, providerUuid.toString(), model2.modelId)
        org.junit.Assert.assertNotNull(emb1)
        org.junit.Assert.assertNotNull(emb2)
        assertEquals(model1.modelId, emb1?.embeddingModel)
        assertEquals(model2.modelId, emb2?.embeddingModel)
    }

    @Test
    fun `syncCardEmbedding aborted by CAS check when repository card is modified or inactive`() = runBlocking {
        val testRepo = FakeMemoryRepository()
        val testService = TestMemoryEmbeddingService(testRepo)

        val modelUuid = kotlin.uuid.Uuid.random()
        val providerUuid = kotlin.uuid.Uuid.random()
        val model = me.rerere.ai.provider.Model(id = modelUuid, modelId = "emb-model")
        val provider = me.rerere.ai.provider.ProviderSetting.OpenAI(id = providerUuid, name = "P", models = listOf(model))
        val settings = me.rerere.rikkahub.data.datastore.Settings(providers = listOf(provider))

        // 数据库中卡片已经被更新到 revision 2
        val currentCardInRepo = me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity(
            id = "card_cas_test",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = "asst_1",
            content = "小雨喜欢喝拿铁（新版本）",
            category = MemoryConstants.CATEGORY_PREFERENCE,
            status = MemoryConstants.STATUS_ACTIVE,
            revision = 2,
            confidence = 1.0f,
            importance = 0.5f,
            validFrom = 0L,
            createdAt = 0L,
            updatedAt = 0L,
            createdBy = "USER",
            updatedBy = "USER",
        )
        testRepo.cards[currentCardInRepo.id] = currentCardInRepo

        // 慢任务尝试写回旧的 revision 1
        val staleCard = currentCardInRepo.copy(
            revision = 1,
            content = "小雨喜欢喝拿铁（旧版本）",
        )

        val ok = testService.syncCardEmbedding(staleCard, modelUuid, settings)
        // CAS 校验必须阻止写入并返回 false
        org.junit.Assert.assertFalse(ok)
        org.junit.Assert.assertNull(testRepo.getEmbedding(staleCard.id, providerUuid.toString(), model.modelId))

        // 若卡片在 repo 中已变为 ARCHIVED，同样阻止写入
        val archivedCard = currentCardInRepo.copy(status = MemoryConstants.STATUS_ARCHIVED)
        testRepo.cards[archivedCard.id] = archivedCard
        val ok2 = testService.syncCardEmbedding(archivedCard, modelUuid, settings)
        org.junit.Assert.assertFalse(ok2)
    }

    @Test
    fun `rebuildEmbeddingsForAssistant uses batch pagination with pageSize 50`() = runBlocking {
        val testRepo = FakeMemoryRepository()
        val testService = TestMemoryEmbeddingService(testRepo)

        val modelUuid = kotlin.uuid.Uuid.random()
        val providerUuid = kotlin.uuid.Uuid.random()
        val model = me.rerere.ai.provider.Model(id = modelUuid, modelId = "emb-model")
        val provider = me.rerere.ai.provider.ProviderSetting.OpenAI(id = providerUuid, name = "P", models = listOf(model))
        val settings = me.rerere.rikkahub.data.datastore.Settings(providers = listOf(provider))

        val asstId = kotlin.uuid.Uuid.random()
        val assistant = me.rerere.rikkahub.data.model.Assistant(
            id = asstId,
            memoryEmbeddingModelId = modelUuid,
            useGlobalMemory = false,
        )

        // 放入 120 张活跃卡片
        for (i in 1..120) {
            val card = me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity(
                id = "card_$i",
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = asstId.toString(),
                content = "卡片内容 $i",
                category = MemoryConstants.CATEGORY_OTHER,
                status = MemoryConstants.STATUS_ACTIVE,
                revision = 1,
                confidence = 1.0f,
                importance = 0.5f,
                validFrom = 0L,
                createdAt = 0L,
                updatedAt = 0L,
                createdBy = "TEST",
                updatedBy = "TEST",
            )
            testRepo.cards[card.id] = card
        }

        var progressCalls = 0
        val count = testService.rebuildEmbeddingsForAssistant(assistant, settings) { cur, total ->
            progressCalls++
            assertEquals(120, total)
        }

        assertEquals(120, count)
        assertEquals(120, progressCalls)
        // 验证分页查询：limit = 50, offset = 0, 50, 100
        assertEquals(3, testRepo.pagedQueries.size)
        assertEquals(50 to 0, testRepo.pagedQueries[0])
        assertEquals(50 to 50, testRepo.pagedQueries[1])
        assertEquals(50 to 100, testRepo.pagedQueries[2])
    }

    @Test
    fun `enqueueSyncCard cancels in-flight job for same card`() = runBlocking {
        val testRepo = FakeMemoryRepository()
        val testService = TestMemoryEmbeddingService(testRepo)
        testService.generateDelayMs = 2000L

        val modelUuid = kotlin.uuid.Uuid.random()
        val providerUuid = kotlin.uuid.Uuid.random()
        val model = me.rerere.ai.provider.Model(id = modelUuid, modelId = "emb-model")
        val provider = me.rerere.ai.provider.ProviderSetting.OpenAI(id = providerUuid, name = "P", models = listOf(model))
        val settings = me.rerere.rikkahub.data.datastore.Settings(providers = listOf(provider))

        val card = me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity(
            id = "card_queue_test",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = "asst_1",
            content = "并发测试内容",
            category = MemoryConstants.CATEGORY_OTHER,
            status = MemoryConstants.STATUS_ACTIVE,
            revision = 1,
            confidence = 1.0f,
            importance = 0.5f,
            validFrom = 0L,
            createdAt = 0L,
            updatedAt = 0L,
            createdBy = "TEST",
            updatedBy = "TEST",
        )
        testRepo.cards[card.id] = card

        val job1 = testService.enqueueSyncCard(card, modelUuid, settings)
        val job2 = testService.enqueueSyncCard(card, modelUuid, settings)

        // 验证旧 job 被取消
        assertTrue(job1.isCancelled)
        job2.join()
    }
}
