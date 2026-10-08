/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_embeddings",
    indices = [
        Index(value = ["memory_id"]),
        Index(value = ["owner_id", "embedding_model"]),
        Index(value = ["content_hash"]),
    ]
)
data class MemoryEmbeddingEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("memory_id")
    val memoryId: String,
    @ColumnInfo("owner_type")
    val ownerType: String,
    @ColumnInfo("owner_id")
    val ownerId: String,
    @ColumnInfo("embedding_provider")
    val embeddingProvider: String,
    @ColumnInfo("embedding_model")
    val embeddingModel: String,
    @ColumnInfo("dimension")
    val dimension: Int,
    @ColumnInfo("input_type")
    val inputType: String,
    @ColumnInfo("content_hash")
    val contentHash: String,
    @ColumnInfo(name = "vector_blob", typeAffinity = ColumnInfo.BLOB)
    val vectorBlob: ByteArray,
    @ColumnInfo("status")
    val status: String,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MemoryEmbeddingEntity

        if (id != other.id) return false
        if (memoryId != other.memoryId) return false
        if (ownerType != other.ownerType) return false
        if (ownerId != other.ownerId) return false
        if (embeddingProvider != other.embeddingProvider) return false
        if (embeddingModel != other.embeddingModel) return false
        if (dimension != other.dimension) return false
        if (inputType != other.inputType) return false
        if (contentHash != other.contentHash) return false
        if (!vectorBlob.contentEquals(other.vectorBlob)) return false
        if (status != other.status) return false
        if (updatedAt != other.updatedAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + memoryId.hashCode()
        result = 31 * result + ownerType.hashCode()
        result = 31 * result + ownerId.hashCode()
        result = 31 * result + embeddingProvider.hashCode()
        result = 31 * result + embeddingModel.hashCode()
        result = 31 * result + dimension
        result = 31 * result + inputType.hashCode()
        result = 31 * result + contentHash.hashCode()
        result = 31 * result + vectorBlob.contentHashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
