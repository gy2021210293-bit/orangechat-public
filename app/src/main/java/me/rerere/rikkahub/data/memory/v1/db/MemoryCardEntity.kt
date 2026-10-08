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
    tableName = "memory_cards",
    indices = [
        Index(value = ["owner_type", "owner_id"]),
        Index(value = ["fact_key"]),
        Index(value = ["status"]),
        Index(value = ["updated_at"]),
    ]
)
data class MemoryCardEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("owner_type")
    val ownerType: String,
    @ColumnInfo("owner_id")
    val ownerId: String,
    @ColumnInfo("content")
    val content: String,
    @ColumnInfo("category")
    val category: String,
    @ColumnInfo("fact_key")
    val factKey: String? = null,
    @ColumnInfo("status")
    val status: String,
    @ColumnInfo("confidence")
    val confidence: Float = 1.0f,
    @ColumnInfo("importance")
    val importance: Float = 0.5f,
    @ColumnInfo("valid_from")
    val validFrom: Long? = null,
    @ColumnInfo("valid_to")
    val validTo: Long? = null,
    @ColumnInfo("superseded_by")
    val supersededBy: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("revision")
    val revision: Int = 1,
    @ColumnInfo("created_by")
    val createdBy: String,
    @ColumnInfo("updated_by")
    val updatedBy: String,
    @ColumnInfo("hit_count")
    val hitCount: Int = 0,
    @ColumnInfo("last_recalled_at")
    val lastRecalledAt: Long? = null,
    @ColumnInfo("heat")
    val heat: Float = 0f,
    @ColumnInfo("last_reviewed_at")
    val lastReviewedAt: Long? = null,
    @ColumnInfo("is_protected")
    val isProtected: Boolean = false,
    @ColumnInfo("always_inject")
    val alwaysInject: Boolean = false,
)
