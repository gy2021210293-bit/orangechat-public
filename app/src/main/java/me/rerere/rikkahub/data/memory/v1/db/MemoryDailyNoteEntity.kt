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
    tableName = "memory_daily_notes",
    indices = [
        Index(value = ["owner_id", "memory_date"], unique = true),
    ]
)
data class MemoryDailyNoteEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("owner_type")
    val ownerType: String,
    @ColumnInfo("owner_id")
    val ownerId: String,
    @ColumnInfo("memory_date")
    val memoryDate: String,
    @ColumnInfo("summary")
    val summary: String,
    @ColumnInfo("emotional_thread")
    val emotionalThread: String? = null,
    @ColumnInfo("unfinished_topics")
    val unfinishedTopics: String = "[]",
    @ColumnInfo("source_fingerprint")
    val sourceFingerprint: String? = null,
    @ColumnInfo("status")
    val status: String = "COMPLETED",
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("revision")
    val revision: Int = 1,
)
