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
    tableName = "memory_timeline_members",
    indices = [
        Index(value = ["timeline_id"]),
        Index(value = ["card_id"]),
    ]
)
data class MemoryTimelineMemberEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("timeline_id")
    val timelineId: String,
    @ColumnInfo("card_id")
    val cardId: String,
    @ColumnInfo("start_time")
    val startTime: Long? = null,
    @ColumnInfo("end_time")
    val endTime: Long? = null,
    @ColumnInfo("confidence")
    val confidence: Float = 1.0f,
    @ColumnInfo("created_at")
    val createdAt: Long,
)
