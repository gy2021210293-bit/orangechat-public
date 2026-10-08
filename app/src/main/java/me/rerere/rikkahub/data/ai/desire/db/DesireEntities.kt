/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "autonomous_wake_runs",
    indices = [Index(value = ["assistant_id", "scheduled_at"]), Index(value = ["assistant_id", "started_at"])],
)
data class AutonomousWakeRunEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "assistant_id") val assistantId: String,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long? = null,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    val status: String,
    val summary: String = "",
    val outcome: String = "",
    @ColumnInfo(name = "next_step") val nextStep: String = "",
    @ColumnInfo(name = "should_contact") val shouldContact: Boolean = false,
    @ColumnInfo(name = "message_text") val messageText: String = "",
    @ColumnInfo(name = "transcript_json", defaultValue = "[]") val transcriptJson: String = "[]",
    @ColumnInfo(name = "tool_calls_json") val toolCallsJson: String = "[]",
    @ColumnInfo(name = "error_text") val errorText: String = "",
    @ColumnInfo(name = "next_wake_delay_minutes") val nextWakeDelayMinutes: Int? = null,
    @ColumnInfo(name = "next_wake_reason", defaultValue = "") val nextWakeReason: String = "",
    @ColumnInfo(name = "chosen_wake_delay_minutes") val chosenWakeDelayMinutes: Int? = null,
    @ColumnInfo(name = "wake_adjustment_reason", defaultValue = "") val wakeAdjustmentReason: String = "",
    @ColumnInfo(name = "delivery_feedback", defaultValue = "") val deliveryFeedback: String = "",
)

@Entity(tableName = "autonomous_wake_state")
data class AutonomousWakeStateEntity(
    @PrimaryKey @ColumnInfo(name = "assistant_id") val assistantId: String,
    @ColumnInfo(name = "current_item") val currentItem: String = "",
    @ColumnInfo(name = "latest_run_id") val latestRunId: String = "",
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "autonomous_wake_items",
    indices = [Index(value = ["assistant_id", "status", "resume_at"])],
)
data class AutonomousItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "assistant_id") val assistantId: String,
    val content: String,
    val progress: String = "",
    val status: String = "active",
    @ColumnInfo(name = "resume_at") val resumeAt: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "autonomous_wake_plans")
data class AutonomousWakePlanEntity(
    @PrimaryKey @ColumnInfo(name = "assistant_id") val assistantId: String,
    @ColumnInfo(name = "autonomous_candidate_at") val autonomousCandidateAt: Long,
    @ColumnInfo(name = "item_candidate_at") val itemCandidateAt: Long? = null,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Long,
    val source: String,
    val reason: String,
    val version: Long,
    val status: String = "scheduled",
    @ColumnInfo(name = "running_version") val runningVersion: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "autonomous_wake_shares",
    indices = [
        Index(value = ["assistant_id", "dedupe_key"], unique = true),
        Index(value = ["assistant_id", "sent_at"]),
    ],
)
data class AutonomousShareEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "assistant_id") val assistantId: String,
    @ColumnInfo(name = "dedupe_key") val dedupeKey: String,
    @ColumnInfo(name = "item_id") val itemId: String? = null,
    @ColumnInfo(name = "result_id") val resultId: String? = null,
    @ColumnInfo(name = "request_id") val requestId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "message_id") val messageId: String,
    val content: String,
    @ColumnInfo(name = "sent_at") val sentAt: Long,
)

@Entity(
    tableName = "desire_states",
)
data class DesireStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,

    @ColumnInfo(name = "drives_json")
    val drivesJson: String,

    @ColumnInfo(name = "thoughts_json")
    val thoughtsJson: String,

    @ColumnInfo(name = "last_tick_at")
    val lastTickAt: Long,

    @ColumnInfo(name = "tick_count")
    val tickCount: Int,

    @ColumnInfo(name = "next_tick_at")
    val nextTickAt: Long,

    @ColumnInfo(name = "schema_version", defaultValue = "1")
    val schemaVersion: Int = 1,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "desire_tick_logs",
    indices = [
        Index(value = ["assistant_id", "created_at"]),
        Index(value = ["created_at"]),
    ],
)
data class DesireTickLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "assistant_id")
    val assistantId: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "tick_number")
    val tickNumber: Int,

    @ColumnInfo(name = "event_name")
    val eventName: String? = null,

    @ColumnInfo(name = "drive_changes_json")
    val driveChangesJson: String,

    @ColumnInfo(name = "thought_changes_json")
    val thoughtChangesJson: String,

    @ColumnInfo(name = "behavior_hints_json")
    val behaviorHintsJson: String,

    @ColumnInfo(name = "monologue")
    val monologue: String = "",

    @ColumnInfo(name = "warnings_json")
    val warningsJson: String,
)

@Entity(
    tableName = "desire_active_sends",
    indices = [
        Index(value = ["assistant_id", "sent_at"]),
        Index(value = ["sent_at"]),
    ],
)
data class DesireActiveSendEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "assistant_id")
    val assistantId: String,

    @ColumnInfo(name = "sent_at")
    val sentAt: Long,

    @ColumnInfo(name = "reason")
    val reason: String,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "drives_snapshot_json")
    val drivesSnapshotJson: String,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "message_id")
    val messageId: String,
)

@Entity(
    tableName = "desire_event_receipts",
    indices = [
        Index(value = ["assistant_id"]),
        Index(value = ["created_at"]),
    ],
)
data class DesireEventReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,

    @ColumnInfo(name = "assistant_id")
    val assistantId: String,

    @ColumnInfo(name = "event_name")
    val eventName: String,

    @ColumnInfo(name = "source_type")
    val sourceType: String,

    @ColumnInfo(name = "source_id")
    val sourceId: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "processed_at")
    val processedAt: Long,
)
