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
    tableName = "memory_maintenance_tasks",
    indices = [
        Index(value = ["owner_type", "owner_id", "status"]),
        Index(value = ["target_type", "target_id"]),
        Index(value = ["created_at"]),
    ]
)
data class MemoryMaintenanceTaskEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("owner_type")
    val ownerType: String,
    @ColumnInfo("owner_id")
    val ownerId: String,
    @ColumnInfo("task_type")
    val taskType: String,
    @ColumnInfo("target_type")
    val targetType: String,
    @ColumnInfo("target_id")
    val targetId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("suggestion")
    val suggestion: String,
    @ColumnInfo("reason")
    val reason: String,
    @ColumnInfo("severity")
    val severity: String = "INFO",
    @ColumnInfo("status")
    val status: String = "PENDING",
    @ColumnInfo("metadata")
    val metadata: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("resolved_at")
    val resolvedAt: Long? = null,
    @ColumnInfo("resolved_by")
    val resolvedBy: String? = null,
)
