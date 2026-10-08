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
    tableName = "memory_event_chunks",
    indices = [
        Index(value = ["owner_type", "owner_id"]),
        Index(value = ["conversation_id"]),
        Index(value = ["fingerprint"]),
        Index(value = ["start_time"]),
    ]
)
data class MemoryEventChunkEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("owner_type")
    val ownerType: String,
    @ColumnInfo("owner_id")
    val ownerId: String,
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("summary")
    val summary: String,
    @ColumnInfo("start_time")
    val startTime: Long,
    @ColumnInfo("end_time")
    val endTime: Long,
    @ColumnInfo("message_ids")
    val messageIds: String = "[]",
    @ColumnInfo("status")
    val status: String = "PROCESSED",
    @ColumnInfo("fingerprint")
    val fingerprint: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
