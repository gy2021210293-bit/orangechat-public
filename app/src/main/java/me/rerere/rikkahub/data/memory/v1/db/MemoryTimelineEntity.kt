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
    tableName = "memory_timelines",
    indices = [
        Index(value = ["owner_type", "owner_id"]),
        Index(value = ["status"]),
    ]
)
data class MemoryTimelineEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("owner_type")
    val ownerType: String,
    @ColumnInfo("owner_id")
    val ownerId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("summary")
    val summary: String = "",
    @ColumnInfo("start_time")
    val startTime: Long? = null,
    @ColumnInfo("end_time")
    val endTime: Long? = null,
    @ColumnInfo("status")
    val status: String = "ACTIVE",
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
