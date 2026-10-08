/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import me.rerere.rikkahub.workflow.model.ClaimResult

@Dao
interface WorkflowFireClaimDao {

    @Query("SELECT * FROM workflow_fire_claims WHERE workflowId = :workflowId AND scheduledAt = :scheduledAt")
    suspend fun getClaim(workflowId: String, scheduledAt: Long): WorkflowFireClaimEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: WorkflowFireClaimEntity): Long

    @Update
    suspend fun update(entity: WorkflowFireClaimEntity)

    @Query("""
        UPDATE workflow_fire_claims
        SET status = :status,
            completedAt = :completedAt,
            error = :error
        WHERE workflowId = :workflowId
          AND scheduledAt = :scheduledAt
          AND status = 'RUNNING'
          AND claimToken = :claimToken
    """)
    suspend fun updateStatus(
        workflowId: String,
        scheduledAt: Long,
        claimToken: String,
        status: String,
        completedAt: Long?,
        error: String?,
    ): Int

    @Query("DELETE FROM workflow_fire_claims WHERE workflowId = :workflowId")
    suspend fun deleteAllFor(workflowId: String): Int

    @Query("DELETE FROM workflow_fire_claims WHERE claimedAt < :cutoffMs")
    suspend fun cleanupOldClaims(cutoffMs: Long): Int

    /**
     * 在数据库事务内原子领取 occurrence。
     * 规则：
     * 1. 记录不存在：插入 RUNNING，生成唯一 claimToken，领取成功。
     * 2. 已经是 SUCCEEDED 或 SKIPPED：拒绝执行。
     * 3. 是 RUNNING 且 lease 未过期：拒绝执行。
     * 4. 是 RUNNING 但 lease 已过期：原子接管，生成新 claimToken。
     * 5. 是 FAILED_RETRYABLE 且未超过重试次数：原子接管，生成新 claimToken。
     * 6. 是 FAILED_FINAL：拒绝执行。
     */
    @Transaction
    suspend fun tryClaim(
        workflowId: String,
        scheduledAt: Long,
        source: String,
        nowMs: Long,
        leaseDurationMs: Long = 10 * 60 * 1000L,
        maxAttempts: Int = 2,
    ): ClaimResult {
        val existing = getClaim(workflowId, scheduledAt)
        if (existing == null) {
            val token = java.util.UUID.randomUUID().toString()
            val newEntity = WorkflowFireClaimEntity(
                workflowId = workflowId,
                scheduledAt = scheduledAt,
                status = WorkflowFireClaimStatus.RUNNING,
                source = source,
                claimedAt = nowMs,
                leaseUntil = nowMs + leaseDurationMs,
                claimToken = token,
                attemptCount = 1,
            )
            val rowId = insertIgnore(newEntity)
            return if (rowId > 0) {
                ClaimResult.Acquired(token)
            } else {
                val raced = getClaim(workflowId, scheduledAt)
                if (raced != null && (raced.status == WorkflowFireClaimStatus.SUCCEEDED || raced.status == WorkflowFireClaimStatus.SKIPPED)) {
                    ClaimResult.AlreadyCompleted(raced.status)
                } else if (raced != null && raced.status == WorkflowFireClaimStatus.RUNNING && raced.leaseUntil > nowMs) {
                    ClaimResult.AlreadyRunning(raced.leaseUntil)
                } else {
                    ClaimResult.AlreadyRunning(nowMs + leaseDurationMs)
                }
            }
        }

        return when (existing.status) {
            WorkflowFireClaimStatus.SUCCEEDED,
            WorkflowFireClaimStatus.SKIPPED -> {
                ClaimResult.AlreadyCompleted(existing.status)
            }
            WorkflowFireClaimStatus.RUNNING -> {
                if (existing.leaseUntil > nowMs) {
                    ClaimResult.AlreadyRunning(existing.leaseUntil)
                } else {
                    // Lease 已过期，允许原子接管
                    if (existing.attemptCount >= maxAttempts) {
                        ClaimResult.RetryLimitReached
                    } else {
                        val token = java.util.UUID.randomUUID().toString()
                        val updated = existing.copy(
                            status = WorkflowFireClaimStatus.RUNNING,
                            source = source,
                            claimedAt = nowMs,
                            leaseUntil = nowMs + leaseDurationMs,
                            claimToken = token,
                            attemptCount = existing.attemptCount + 1,
                        )
                        update(updated)
                        ClaimResult.Acquired(token)
                    }
                }
            }
            WorkflowFireClaimStatus.FAILED_RETRYABLE -> {
                if (existing.attemptCount >= maxAttempts) {
                    ClaimResult.RetryLimitReached
                } else {
                    val token = java.util.UUID.randomUUID().toString()
                    val updated = existing.copy(
                        status = WorkflowFireClaimStatus.RUNNING,
                        source = source,
                        claimedAt = nowMs,
                        leaseUntil = nowMs + leaseDurationMs,
                        claimToken = token,
                        attemptCount = existing.attemptCount + 1,
                        error = null,
                    )
                    update(updated)
                    ClaimResult.Acquired(token)
                }
            }
            WorkflowFireClaimStatus.FAILED_FINAL -> {
                ClaimResult.RetryLimitReached
            }
            else -> {
                ClaimResult.AlreadyCompleted(existing.status)
            }
        }
    }
}
