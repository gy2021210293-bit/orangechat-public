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
    tableName = "memory_sources",
    indices = [
        Index(value = ["memory_id"]),
        Index(value = ["source_hash"]),
    ]
)
data class MemorySourceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("memory_id")
    val memoryId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("node_id")
    val nodeId: String? = null,
    @ColumnInfo("message_id")
    val messageId: String? = null,
    @ColumnInfo("role")
    val role: String? = null,
    @ColumnInfo("message_created_at")
    val messageCreatedAt: Long? = null,
    @ColumnInfo("source_kind")
    val sourceKind: String,
    @ColumnInfo("source_hash")
    val sourceHash: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
