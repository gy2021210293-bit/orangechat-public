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
    tableName = "memory_fact_audits",
    indices = [
        Index(value = ["owner_type", "owner_id", "status"]),
        Index(value = ["fact_key"]),
        Index(value = ["existing_card_id"]),
        Index(value = ["created_at"]),
    ]
)
data class MemoryFactAuditEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("owner_type")
    val ownerType: String,
    @ColumnInfo("owner_id")
    val ownerId: String,
    @ColumnInfo("fact_key")
    val factKey: String,
    @ColumnInfo("existing_card_id")
    val existingCardId: String,
    @ColumnInfo("candidate_card_id")
    val candidateCardId: String? = null,
    @ColumnInfo("candidate_content")
    val candidateContent: String,
    @ColumnInfo("model_suggestion")
    val modelSuggestion: String, // SUPERSEDE, BOTH_VALID, UNCERTAIN
    @ColumnInfo("reason")
    val reason: String,
    @ColumnInfo("evidence_snippet")
    val evidenceSnippet: String? = null,
    @ColumnInfo("status")
    val status: String = "PENDING", // PENDING, RESOLVED_SUPERSEDE, RESOLVED_KEEP_BOTH, REJECTED
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("resolved_at")
    val resolvedAt: Long? = null,
    @ColumnInfo("resolved_by")
    val resolvedBy: String? = null,
)
