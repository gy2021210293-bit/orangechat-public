/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.db

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 工作流单次执行 occurrence 原子抢占持久化表。
 * 复合主键：(workflowId, scheduledAt)，保证同一计划触发点全局唯一。
 */
@Entity(
    tableName = "workflow_fire_claims",
    primaryKeys = ["workflowId", "scheduledAt"]
)
data class WorkflowFireClaimEntity(
    val workflowId: String,
    val scheduledAt: Long,
    val status: String,          // RUNNING, SUCCEEDED, SKIPPED, FAILED_RETRYABLE, FAILED_FINAL
    val source: String,          // ALARM, WORKER, MANUAL
    val claimedAt: Long,
    val leaseUntil: Long,
    @ColumnInfo(defaultValue = "''")
    val claimToken: String = "",
    val completedAt: Long? = null,
    @ColumnInfo(defaultValue = "1")
    val attemptCount: Int = 1,
    val error: String? = null,
)

object WorkflowFireClaimStatus {
    const val RUNNING = "RUNNING"
    const val SUCCEEDED = "SUCCEEDED"
    const val SKIPPED = "SKIPPED"
    const val FAILED_RETRYABLE = "FAILED_RETRYABLE"
    const val FAILED_FINAL = "FAILED_FINAL"
}
