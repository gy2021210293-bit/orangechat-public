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
    tableName = "memory_relations",
    indices = [
        Index(value = ["owner_type", "owner_id"]),
        Index(value = ["source_card_id"]),
        Index(value = ["target_card_id"]),
        Index(value = ["status"]),
        Index(value = ["relation_type"]),
    ]
)
data class MemoryRelationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("owner_type")
    val ownerType: String,
    @ColumnInfo("owner_id")
    val ownerId: String,
    @ColumnInfo("source_card_id")
    val sourceCardId: String,
    @ColumnInfo("target_card_id")
    val targetCardId: String,
    @ColumnInfo("relation_type")
    val relationType: String,
    @ColumnInfo("weight")
    val weight: Float = 1.0f,
    @ColumnInfo("strength")
    val strength: Float = 1.0f,
    @ColumnInfo("reason")
    val reason: String? = null,
    @ColumnInfo("evidence_source_ids")
    val evidenceSourceIds: String? = null,
    @ColumnInfo("valid_from")
    val validFrom: Long? = null,
    @ColumnInfo("valid_until")
    val validUntil: Long? = null,
    @ColumnInfo("status")
    val status: String = "ACTIVE",
    @ColumnInfo("source")
    val source: String = "AUTO",
    @ColumnInfo("created_by")
    val createdBy: String = "AUTO",
    @ColumnInfo("created_at")
    val createdAt: Long,
)
