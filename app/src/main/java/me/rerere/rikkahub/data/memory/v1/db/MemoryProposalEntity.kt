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
    tableName = "memory_proposals",
    indices = [
        Index(value = ["owner_type", "owner_id", "memory_date"]),
        Index(value = ["chunk_id"]),
        Index(value = ["status"]),
        Index(value = ["target_id"]),
        Index(value = ["fact_key"]),
    ]
)
data class MemoryProposalEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("owner_type")
    val ownerType: String,
    @ColumnInfo("owner_id")
    val ownerId: String,
    @ColumnInfo("memory_date")
    val memoryDate: String,
    @ColumnInfo("chunk_id")
    val chunkId: String? = null,
    @ColumnInfo("type")
    val type: String, // event, fact, preference, relationship_moment, risk_boundary, decision
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("content")
    val content: String,
    @ColumnInfo("importance")
    val importance: Int = 5, // 1..10
    @ColumnInfo("risk")
    val risk: String = "normal", // normal, review
    @ColumnInfo("evidence")
    val evidence: String,
    @ColumnInfo("source_message_ids")
    val sourceMessageIds: String = "[]",
    @ColumnInfo("timeline_hint")
    val timelineHint: String? = null,
    @ColumnInfo("relation_hints")
    val relationHints: String? = null,
    @ColumnInfo("status")
    val status: String = "REVIEW", // REVIEW, ACCEPTED, REJECTED, EXPIRED
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("resolved_at")
    val resolvedAt: Long? = null,
    @ColumnInfo("resolved_by")
    val resolvedBy: String? = null, // manual, auto_promote
    @ColumnInfo("card_id")
    val cardId: String? = null,
    @ColumnInfo("action")
    val action: String = "ADD", // ADD, UPDATE, MERGE, SUPERSEDE, ARCHIVE, DELETE
    @ColumnInfo("target_id")
    val targetId: String? = null,
    @ColumnInfo("expected_revision")
    val expectedRevision: Int? = null,
    @ColumnInfo("fact_key")
    val factKey: String? = null,
    @ColumnInfo("secondary_card_ids")
    val secondaryCardIds: String? = null,
)

val MemoryProposalEntity.secondaryIdsList: List<String>
    get() {
        val raw = secondaryCardIds
        return if (raw.isNullOrBlank()) emptyList() else runCatching {
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(raw)
        }.getOrDefault(emptyList())
    }

