/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEmbeddingEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEmbeddingProjection
import me.rerere.rikkahub.data.model.Assistant
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val TAG = "MemoryEmbeddingService"

private inline fun safeLogW(tag: String, message: String, throwable: Throwable? = null) {
    runCatching { Log.w(tag, message, throwable) }
}

open class MemoryEmbeddingService(
    private val repository: MemoryRepositoryV1,
    private val providerManager: ProviderManager? = null,
) {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cardSyncJobs = ConcurrentHashMap<String, Job>()

    /**
     * 生成文本的 FloatArray 向量表示。
     * 带 5 秒超时保护，出现任何异常时记录警告并平滑返回 null，绝不阻断调用方。
     */
    open suspend fun generateEmbedding(
        text: String,
        model: Model,
        providerSetting: ProviderSetting,
    ): FloatArray? = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext null
        val pm = providerManager ?: return@withContext null

        withTimeoutOrNull(5.seconds) {
            runCatching {
                val providerImpl = pm.getProviderByType(providerSetting)
                val result = providerImpl.generateEmbedding(
                    providerSetting = providerSetting,
                    params = EmbeddingGenerationParams(
                        model = model,
                        input = listOf(trimmed),
                    )
                )
                result.embeddings.firstOrNull()?.toFloatArray()
            }.onFailure {
                safeLogW(TAG, "generateEmbedding failed for model ${model.modelId}", it)
            }.getOrNull()
        }
    }

    /**
     * 在全库已激活记忆中进行基于 Float32 BLOB 的内存安全分页线性扫描，维护 Top-K 小根堆。
     *
     * 关键约束（根据 docs/local-memory-v1-design.md 10.4 与 16 节）：
     * - 禁止在内存中一次性加载全量记忆实体并反序列化为 List<Float>；
     * - 按页读取轻量投影 (memory_id, dimension, vector_blob)；
     * - 直接在 ByteArray 字节流上计算余弦相似度，堆内存占用极低；
     * - 严格模型与维度隔离：跳过维度不符的记录，不混用不同模型的向量。
     *
     * @return 按余弦相似度降序排列的 Pair(cardId, similarity)
     */
    suspend fun searchSimilarMemories(
        assistantId: String,
        includeGlobal: Boolean,
        queryVector: FloatArray,
        embeddingProvider: String,
        embeddingModel: String,
        limit: Int = 40,
    ): List<Pair<String, Float>> = withContext(Dispatchers.IO) {
        if (queryVector.isEmpty()) return@withContext emptyList()

        var sumSq = 0.0
        for (f in queryVector) {
            sumSq += (f * f).toDouble()
        }
        val queryNorm = sqrt(sumSq).toFloat()
        if (queryNorm <= 1e-8f) return@withContext emptyList()

        // 容量为 limit 的小根堆：按相似度升序排列，堆顶是堆内最小元素
        val minHeap = PriorityQueue<Pair<String, Float>>(compareBy { it.second })

        val pageSize = 100
        var offset = 0

        while (true) {
            val projections = repository.getActiveEmbeddingProjections(
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = assistantId,
                embeddingProvider = embeddingProvider,
                embeddingModel = embeddingModel,
                includeGlobal = includeGlobal,
                limit = pageSize,
                offset = offset,
            )
            if (projections.isEmpty()) break

            for (proj in projections) {
                // 模型/维度隔离检查
                if (proj.dimension != queryVector.size) continue

                val similarity = calculateCosineSimilarity(
                    queryVector = queryVector,
                    queryNorm = queryNorm,
                    vectorBlob = proj.vectorBlob,
                )

                // 只有大于等于最低相关度阈值的有效正相似度才参与 Top-K 竞争
                if (similarity >= MIN_VECTOR_SIMILARITY) {
                    if (minHeap.size < limit) {
                        minHeap.offer(proj.memoryId to similarity)
                    } else if (similarity > minHeap.peek()!!.second) {
                        minHeap.poll()
                        minHeap.offer(proj.memoryId to similarity)
                    }
                }
            }

            offset += projections.size
            if (projections.size < pageSize) break
        }

        // 导出结果并按相似度从高到低降序排序
        buildList(minHeap.size) {
            while (minHeap.isNotEmpty()) {
                add(minHeap.poll()!!)
            }
        }.asReversed()
    }

    /**
     * 将单张卡片的向量同步加入后台非阻塞队列。
     * 同一 cardId + embeddingModelId 的前序在飞任务会被取消，保证最新版本优先且避免乱序写回。
     */
    open fun enqueueSyncCard(
        card: MemoryCardEntity,
        embeddingModelId: Uuid,
        settings: Settings,
    ): Job {
        val syncKey = "${card.id}_${embeddingModelId}"
        var job: Job? = null
        job = serviceScope.launch {
            runCatching {
                syncCardEmbedding(card, embeddingModelId, settings)
            }.onFailure {
                safeLogW(TAG, "enqueueSyncCard failed for card ${card.id} model $embeddingModelId", it)
            }
        }
        val prev = cardSyncJobs.put(syncKey, job)
        prev?.cancel()
        job.invokeOnCompletion {
            cardSyncJobs.remove(syncKey, job)
        }
        return job
    }

    open fun cancelSyncJob(memoryId: String) {
        val keysToRemove = cardSyncJobs.keys().toList().filter { it.startsWith("${memoryId}_") || it == memoryId }
        for (key in keysToRemove) {
            cardSyncJobs.remove(key)?.cancel()
        }
    }

    /**
     * 为单张记忆卡片同步或更新向量表示。
     *
     * 关键约束：
     * - 复用校验必须同时满足 contentHash、embeddingModel 和 embeddingProvider 一致；
     * - 写入前执行仓储单事务条件写入 CAS：原子校验 revision、status 和 contentHash；
     * - 确定性主键格式：emb_${card.id}_${providerSetting.id}_${model.modelId}。
     */
    suspend fun syncCardEmbedding(
        card: MemoryCardEntity,
        embeddingModelId: Uuid,
        settings: Settings,
    ): Boolean = withContext(Dispatchers.IO) {
        val model = settings.findModelById(embeddingModelId) ?: return@withContext false
        val providerSetting = model.findProvider(settings.providers) ?: return@withContext false

        val contentHash = computeContentHash(card.content)
        val existing = repository.getEmbedding(card.id, providerSetting.id.toString(), model.modelId)
        if (existing != null &&
            existing.contentHash == contentHash &&
            existing.embeddingModel == model.modelId &&
            existing.embeddingProvider == providerSetting.id.toString()
        ) {
            // 内容、模型与 Provider 均未改变，无需重新生成
            return@withContext true
        }

        val vector = generateEmbedding(card.content, model, providerSetting) ?: return@withContext false
        val blob = floatArrayToVectorBlob(vector)

        val entity = MemoryEmbeddingEntity(
            id = "emb_${card.id}_${providerSetting.id}_${model.modelId}",
            memoryId = card.id,
            ownerType = card.ownerType,
            ownerId = card.ownerId,
            embeddingProvider = providerSetting.id.toString(),
            embeddingModel = model.modelId,
            dimension = vector.size,
            inputType = "DOCUMENT",
            contentHash = contentHash,
            vectorBlob = blob,
            status = card.status,
            updatedAt = System.currentTimeMillis(),
        )

        // 仓储单事务 CAS 条件写入：在单事务内读取卡片并原子校验版本、状态与内容哈希
        val success = repository.conditionalInsertEmbedding(
            memoryId = card.id,
            expectedRevision = card.revision,
            expectedStatus = MemoryConstants.STATUS_ACTIVE,
            expectedContentHash = contentHash,
            embedding = entity,
        )
        if (!success) {
            safeLogW(TAG, "syncCardEmbedding aborted by CAS in transaction: card ${card.id} changed or inactive")
            return@withContext false
        }

        true
    }

    /**
     * 为指定助理的所有有效记忆全量/增量补建向量（分页加载，避免全量加载到内存引发 OOM）。
     *
     * @param assistant 目标助理
     * @param settings 当前设置
     * @param onProgress 进度回调 (current, total)
     * @return 成功同步向量的卡片数量
     */
    suspend fun rebuildEmbeddingsForAssistant(
        assistant: Assistant,
        settings: Settings,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        val embeddingModelId = assistant.memoryEmbeddingModelId ?: return@withContext 0
        val model = settings.findModelById(embeddingModelId) ?: return@withContext 0
        model.findProvider(settings.providers) ?: return@withContext 0

        val assistantId = assistant.id.toString()
        val assistantTotal = repository.countCardsByStatus(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId,
            status = MemoryConstants.STATUS_ACTIVE,
        )
        val globalTotal = if (assistant.useGlobalMemory) {
            repository.countCardsByStatus(
                ownerType = MemoryConstants.OWNER_TYPE_GLOBAL,
                ownerId = MemoryConstants.GLOBAL_OWNER_ID,
                status = MemoryConstants.STATUS_ACTIVE,
            )
        } else 0

        val total = assistantTotal + globalTotal
        var processed = 0
        var successCount = 0
        val pageSize = 50

        // 1. 分批扫描助理专属卡片
        var offset = 0
        while (true) {
            val page = repository.getCardsByStatusPaged(
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = assistantId,
                status = MemoryConstants.STATUS_ACTIVE,
                limit = pageSize,
                offset = offset,
            )
            if (page.isEmpty()) break
            for (card in page) {
                val ok = syncCardEmbedding(card, embeddingModelId, settings)
                if (ok) successCount++
                processed++
                onProgress?.invoke(processed, total)
            }
            offset += page.size
            if (page.size < pageSize) break
        }

        // 2. 分批扫描全局共享卡片（若启用）
        if (assistant.useGlobalMemory) {
            offset = 0
            while (true) {
                val page = repository.getCardsByStatusPaged(
                    ownerType = MemoryConstants.OWNER_TYPE_GLOBAL,
                    ownerId = MemoryConstants.GLOBAL_OWNER_ID,
                    status = MemoryConstants.STATUS_ACTIVE,
                    limit = pageSize,
                    offset = offset,
                )
                if (page.isEmpty()) break
                for (card in page) {
                    val ok = syncCardEmbedding(card, embeddingModelId, settings)
                    if (ok) successCount++
                    processed++
                    onProgress?.invoke(processed, total)
                }
                offset += page.size
                if (page.size < pageSize) break
            }
        }

        successCount
    }

    open suspend fun deleteCardEmbedding(memoryId: String) = withContext(Dispatchers.IO) {
        cancelSyncJob(memoryId)
        repository.deleteEmbeddingByMemoryId(memoryId)
    }

    companion object {
        const val MIN_VECTOR_SIMILARITY = 0.5f

        /**
         * 将 FloatArray 序列化为 Little-Endian 字节数组 (4 * size 字节).
         */
        fun floatArrayToVectorBlob(floats: FloatArray): ByteArray {
            val bytes = ByteArray(floats.size * 4)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (f in floats) {
                buffer.putFloat(f)
            }
            return bytes
        }

        /**
         * 将 Little-Endian 字节数组反序列化为 FloatArray (主要供单元测试断言).
         */
        fun vectorBlobToFloatArray(blob: ByteArray): FloatArray {
            val count = blob.size / 4
            val floats = FloatArray(count)
            val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                floats[i] = buffer.getFloat()
            }
            return floats
        }

        /**
         * 直接在 Little-Endian BLOB 字节流上计算与 queryVector 的余弦相似度。
         * 零临时对象分配，保证超低堆内存占用。
         */
        fun calculateCosineSimilarity(
            queryVector: FloatArray,
            queryNorm: Float,
            vectorBlob: ByteArray,
        ): Float {
            if (vectorBlob.size != queryVector.size * 4 || queryNorm <= 1e-8f) {
                return 0f
            }

            val buffer = ByteBuffer.wrap(vectorBlob).order(ByteOrder.LITTLE_ENDIAN)
            var dot = 0.0
            var targetSumSq = 0.0

            for (i in queryVector.indices) {
                val v = buffer.getFloat().toDouble()
                dot += queryVector[i].toDouble() * v
                targetSumSq += v * v
            }

            val targetNorm = sqrt(targetSumSq).toFloat()
            if (targetNorm <= 1e-8f) return 0f

            val sim = (dot / (queryNorm.toDouble() * targetNorm.toDouble())).toFloat()
            return sim.coerceIn(-1.0f, 1.0f)
        }

        /**
         * 计算字符串的 SHA-256 哈希值作为 contentHash.
         */
        fun computeContentHash(text: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(text.trim().toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
