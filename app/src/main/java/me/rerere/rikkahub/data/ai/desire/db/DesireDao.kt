/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface DesireDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAutonomousRun(run: AutonomousWakeRunEntity)

    @Query("SELECT * FROM autonomous_wake_runs WHERE assistant_id = :assistantId ORDER BY scheduled_at DESC LIMIT :limit")
    suspend fun listAutonomousRuns(assistantId: String, limit: Int): List<AutonomousWakeRunEntity>

    @Query("SELECT * FROM autonomous_wake_runs WHERE assistant_id = :assistantId AND COALESCE(started_at, scheduled_at) >= :startMs AND COALESCE(started_at, scheduled_at) < :endMs ORDER BY COALESCE(started_at, scheduled_at) ASC")
    suspend fun listAutonomousRunsInRange(assistantId: String, startMs: Long, endMs: Long): List<AutonomousWakeRunEntity>

    @Query("SELECT * FROM autonomous_wake_runs WHERE id = :runId LIMIT 1")
    suspend fun getAutonomousRun(runId: String): AutonomousWakeRunEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAutonomousState(state: AutonomousWakeStateEntity)

    @Query("SELECT * FROM autonomous_wake_state WHERE assistant_id = :assistantId LIMIT 1")
    suspend fun getAutonomousState(assistantId: String): AutonomousWakeStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAutonomousItem(item: AutonomousItemEntity)

    @Query("SELECT * FROM autonomous_wake_items WHERE assistant_id = :assistantId AND status = 'active' ORDER BY resume_at")
    suspend fun listActiveAutonomousItems(assistantId: String): List<AutonomousItemEntity>

    @Query("SELECT * FROM autonomous_wake_items WHERE assistant_id = :assistantId ORDER BY updated_at DESC LIMIT :limit")
    suspend fun listRecentAutonomousItems(assistantId: String, limit: Int): List<AutonomousItemEntity>

    @Query("SELECT * FROM autonomous_wake_items WHERE assistant_id = :assistantId AND (created_at >= :startMs AND created_at < :endMs OR updated_at >= :startMs AND updated_at < :endMs) ORDER BY updated_at DESC")
    suspend fun listItemsChangedOnDay(assistantId: String, startMs: Long, endMs: Long): List<AutonomousItemEntity>

    @Query("SELECT * FROM autonomous_wake_items WHERE assistant_id = :assistantId AND status = 'completed' AND updated_at >= :startMs AND updated_at < :endMs ORDER BY updated_at DESC")
    suspend fun listCompletedItemsOnDay(assistantId: String, startMs: Long, endMs: Long): List<AutonomousItemEntity>

    @Query("SELECT * FROM autonomous_wake_items WHERE id = :itemId AND assistant_id = :assistantId LIMIT 1")
    suspend fun getAutonomousItem(assistantId: String, itemId: String): AutonomousItemEntity?

    @Query("UPDATE autonomous_wake_items SET content = :content, progress = :progress, resume_at = :resumeAt, updated_at = :updatedAt WHERE assistant_id = :assistantId AND id = :itemId AND status = 'active'")
    suspend fun updateAutonomousItem(assistantId: String, itemId: String, content: String, progress: String, resumeAt: String?, updatedAt: Long): Int

    @Query("UPDATE autonomous_wake_items SET status = :status, updated_at = :updatedAt WHERE assistant_id = :assistantId AND id = :itemId AND status = 'active'")
    suspend fun setAutonomousItemStatus(assistantId: String, itemId: String, status: String, updatedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWakePlan(plan: AutonomousWakePlanEntity)

    @Query("SELECT * FROM autonomous_wake_plans WHERE assistant_id = :assistantId LIMIT 1")
    suspend fun getWakePlan(assistantId: String): AutonomousWakePlanEntity?

    @Transaction
    suspend fun replaceScheduledWakePlan(
        assistantId: String,
        autonomousAt: Long,
        itemAt: Long?,
        source: String,
        reason: String,
        nowMs: Long,
    ): AutonomousWakePlanEntity {
        val previous = getWakePlan(assistantId)
        val version = (previous?.version ?: 0L) + 1L
        val runningVersion = previous?.runningVersion ?: previous?.takeIf { it.status == "running" }?.version
        val finalSource = if (itemAt != null && itemAt < autonomousAt) "item" else source
        val finalReason = if (itemAt != null && itemAt < autonomousAt) "事项到期提前唤醒；$reason" else reason
        return AutonomousWakePlanEntity(
            assistantId = assistantId,
            autonomousCandidateAt = autonomousAt,
            itemCandidateAt = itemAt,
            scheduledAt = minOf(autonomousAt, itemAt ?: Long.MAX_VALUE),
            source = finalSource,
            reason = finalReason,
            version = version,
            status = "scheduled",
            runningVersion = runningVersion,
            updatedAt = nowMs,
        ).also { upsertWakePlan(it) }
    }

    @Transaction
    suspend fun finishAutonomousWakePlan(
        assistantId: String,
        expectedVersion: Long,
        proposedAutonomousAt: Long,
        source: String,
        reason: String,
        retryAt: Long?,
        nowMs: Long,
    ): AutonomousWakePlanEntity? {
        val current = getWakePlan(assistantId) ?: return null
        val ownsRunningPlan = current.runningVersion == expectedVersion ||
            (current.runningVersion == null && current.version == expectedVersion && current.status == "running")
        if (!ownsRunningPlan) return null
        if (current.status == "cancelled") {
            upsertWakePlan(current.copy(runningVersion = null, updatedAt = nowMs))
            return null
        }
        val samePlan = current.version == expectedVersion
        val autonomousAt = if (samePlan) {
            retryAt ?: proposedAutonomousAt
        } else if (retryAt != null) {
            minOf(current.autonomousCandidateAt, retryAt)
        } else {
            current.autonomousCandidateAt
        }
        val items = listActiveAutonomousItems(assistantId)
        val itemAt = items.mapNotNull { item ->
            item.resumeAt?.let { value -> runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull() }
        }.filter { it > nowMs }.minOrNull()
        val autonomousReason = when {
            retryAt != null && retryAt <= autonomousAt -> reason
            samePlan -> reason
            else -> current.reason
        }
        val finalSource = if (itemAt != null && itemAt < autonomousAt) "item" else when {
            retryAt != null && retryAt <= autonomousAt -> source
            samePlan -> source
            else -> current.source
        }
        val finalReason = if (itemAt != null && itemAt < autonomousAt) "事项到期提前唤醒；$autonomousReason" else autonomousReason
        val next = current.copy(
            autonomousCandidateAt = autonomousAt,
            itemCandidateAt = itemAt,
            scheduledAt = minOf(autonomousAt, itemAt ?: Long.MAX_VALUE),
            source = finalSource,
            reason = finalReason,
            version = current.version + 1,
            status = "scheduled",
            runningVersion = null,
            updatedAt = nowMs,
        )
        upsertWakePlan(next)
        return next
    }

    @Transaction
    suspend fun cancelWakePlan(assistantId: String, nowMs: Long): AutonomousWakePlanEntity? {
        val current = getWakePlan(assistantId) ?: return null
        val cancelled = current.copy(version = current.version + 1, status = "cancelled", updatedAt = nowMs)
        upsertWakePlan(cancelled)
        return cancelled
    }

    @Query("UPDATE autonomous_wake_plans SET status = 'running', running_version = :version, updated_at = :nowMs WHERE assistant_id = :assistantId AND version = :version AND status = 'scheduled' AND running_version IS NULL AND scheduled_at <= :nowMs")
    suspend fun claimWakePlan(assistantId: String, version: Long, nowMs: Long): Int

    @Query("UPDATE autonomous_wake_plans SET status = 'scheduled', autonomous_candidate_at = :autonomousAt, item_candidate_at = :itemAt, scheduled_at = :scheduledAt, source = :source, reason = :reason, version = :newVersion, updated_at = :nowMs WHERE assistant_id = :assistantId AND version = :expectedVersion AND status = 'running'")
    suspend fun finishWakePlan(assistantId: String, expectedVersion: Long, newVersion: Long, autonomousAt: Long, itemAt: Long?, scheduledAt: Long, source: String, reason: String, nowMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAutonomousShare(share: AutonomousShareEntity): Long

    @Query("SELECT * FROM autonomous_wake_shares WHERE assistant_id = :assistantId AND dedupe_key = :dedupeKey LIMIT 1")
    suspend fun getAutonomousShare(assistantId: String, dedupeKey: String): AutonomousShareEntity?

    @Query("SELECT * FROM autonomous_wake_shares WHERE assistant_id = :assistantId ORDER BY sent_at DESC LIMIT :limit")
    suspend fun listAutonomousShares(assistantId: String, limit: Int): List<AutonomousShareEntity>

    @Query("SELECT * FROM autonomous_wake_shares WHERE assistant_id = :assistantId ORDER BY sent_at DESC LIMIT 1")
    suspend fun getLastAutonomousShare(assistantId: String): AutonomousShareEntity?

    @Query("SELECT * FROM desire_states WHERE assistant_id = :assistantId LIMIT 1")
    suspend fun getState(assistantId: String): DesireStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: DesireStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReceipt(receipt: DesireEventReceiptEntity): Long

    @Query("SELECT COUNT(*) FROM desire_event_receipts WHERE event_id = :eventId")
    suspend fun countReceipt(eventId: String): Int

    suspend fun hasReceipt(eventId: String): Boolean = countReceipt(eventId) > 0

    @Query("""
        SELECT * FROM desire_event_receipts
        WHERE assistant_id = :assistantId
          AND event_name = 'master_message'
          AND source_type = 'user_message'
        ORDER BY created_at DESC
        LIMIT 1
    """)
    suspend fun getLastUserMasterMessageReceipt(assistantId: String): DesireEventReceiptEntity?

    @Insert
    suspend fun insertLog(log: DesireTickLogEntity): Long

    @Query("SELECT * FROM desire_tick_logs WHERE assistant_id = :assistantId ORDER BY created_at DESC LIMIT :limit")
    suspend fun listRecentLogs(assistantId: String, limit: Int = 500): List<DesireTickLogEntity>

    @Query("""
        DELETE FROM desire_tick_logs
        WHERE assistant_id = :assistantId
          AND id NOT IN (
              SELECT id FROM desire_tick_logs
              WHERE assistant_id = :assistantId
              ORDER BY created_at DESC
              LIMIT :keepCount
          )
    """)
    suspend fun pruneLogs(assistantId: String, keepCount: Int = 500)

    @Insert
    suspend fun insertActiveSend(send: DesireActiveSendEntity): Long

    @Query("SELECT * FROM desire_active_sends WHERE assistant_id = :assistantId ORDER BY sent_at DESC LIMIT 1")
    suspend fun getLastActiveSend(assistantId: String): DesireActiveSendEntity?

    @Query("SELECT COUNT(*) FROM desire_active_sends WHERE assistant_id = :assistantId AND sent_at >= :startEpochMs AND sent_at < :endEpochMs")
    suspend fun countActiveSendsBetween(assistantId: String, startEpochMs: Long, endEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM desire_active_sends WHERE assistant_id = :assistantId AND sent_at > :sinceEpochMs")
    suspend fun countActiveSendsSince(assistantId: String, sinceEpochMs: Long): Int

    suspend fun hasActiveSendSince(assistantId: String, sinceEpochMs: Long): Boolean =
        countActiveSendsSince(assistantId, sinceEpochMs) > 0

    @Transaction
    suspend fun saveEventWithReceipt(
        receipt: DesireEventReceiptEntity,
        state: DesireStateEntity,
        log: DesireTickLogEntity,
        keepLogs: Int = 500,
    ): Boolean {
        val rowId = insertReceipt(receipt)
        if (rowId == -1L) {
            return false
        }
        upsertState(state)
        insertLog(log)
        pruneLogs(receipt.assistantId, keepLogs)
        return true
    }

    @Transaction
    suspend fun saveTickWithReceipt(
        receipt: DesireEventReceiptEntity,
        state: DesireStateEntity,
        log: DesireTickLogEntity,
        keepLogs: Int = 500,
    ): Boolean {
        val rowId = insertReceipt(receipt)
        if (rowId == -1L) {
            return false
        }
        upsertState(state)
        insertLog(log)
        pruneLogs(receipt.assistantId, keepLogs)
        return true
    }

    @Transaction
    suspend fun deleteAllForAssistant(assistantId: String) {
        deleteStateForAssistant(assistantId)
        deleteLogsForAssistant(assistantId)
        deleteActiveSendsForAssistant(assistantId)
        deleteReceiptsForAssistant(assistantId)
    }

    @Query("DELETE FROM desire_states WHERE assistant_id = :assistantId")
    suspend fun deleteStateForAssistant(assistantId: String)

    @Query("DELETE FROM desire_tick_logs WHERE assistant_id = :assistantId")
    suspend fun deleteLogsForAssistant(assistantId: String)

    @Query("DELETE FROM desire_active_sends WHERE assistant_id = :assistantId")
    suspend fun deleteActiveSendsForAssistant(assistantId: String)

    @Query("DELETE FROM desire_event_receipts WHERE assistant_id = :assistantId")
    suspend fun deleteReceiptsForAssistant(assistantId: String)
}
