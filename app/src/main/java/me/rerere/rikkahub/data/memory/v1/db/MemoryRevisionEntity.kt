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
    tableName = "memory_revisions",
    indices = [
        Index(value = ["memory_id"]),
        Index(value = ["created_at"]),
    ]
)
data class MemoryRevisionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("memory_id")
    val memoryId: String,
    @ColumnInfo("revision")
    val revision: Int,
    @ColumnInfo("operation")
    val operation: String,
    @ColumnInfo("before_content")
    val beforeContent: String? = null,
    @ColumnInfo("after_content")
    val afterContent: String? = null,
    @ColumnInfo("before_status")
    val beforeStatus: String? = null,
    @ColumnInfo("after_status")
    val afterStatus: String? = null,
    @ColumnInfo("reason")
    val reason: String? = null,
    @ColumnInfo("actor")
    val actor: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("run_id")
    val runId: String? = null,
)
