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
    tableName = "memory_consolidation_runs",
    indices = [
        Index(value = ["owner_id", "memory_date"], unique = true),
    ]
)
data class MemoryConsolidationRunEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("owner_type")
    val ownerType: String,
    @ColumnInfo("owner_id")
    val ownerId: String,
    @ColumnInfo("memory_date")
    val memoryDate: String,
    @ColumnInfo("status")
    val status: String,
    @ColumnInfo("source_fingerprint")
    val sourceFingerprint: String? = null,
    @ColumnInfo("attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo("model_id")
    val modelId: String? = null,
    @ColumnInfo("started_at")
    val startedAt: Long? = null,
    @ColumnInfo("finished_at")
    val finishedAt: Long? = null,
    @ColumnInfo("error_code")
    val errorCode: String? = null,
    @ColumnInfo("lease_until")
    val leaseUntil: Long? = null,
    @ColumnInfo("run_token")
    val runToken: String? = null,
)
