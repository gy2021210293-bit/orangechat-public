/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 *
 * Portions adapted from Living Memory Coordinate-5 (LMC-5),
 * upstream commit fb3e72c9ee7357c8b17311097b0750082a8a1237, GNU AGPL v3.
 * Adapted for Kotlin/Room on 2026-09-13.
 */

package me.rerere.rikkahub.data.memory.v1.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_e_proposals",
    foreignKeys = [
        ForeignKey(
            entity = MemoryCardEntity::class,
            parentColumns = ["id"],
            childColumns = ["memory_card_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["owner_type", "owner_id", "status"]),
        Index(value = ["memory_card_id"]),
    ]
)
data class MemoryEProposalEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("owner_type")
    val ownerType: String,
    @ColumnInfo("owner_id")
    val ownerId: String,
    @ColumnInfo("memory_card_id")
    val memoryCardId: String,
    @ColumnInfo("meaning")
    val meaning: String? = null,
    @ColumnInfo("initial_priority")
    val initialPriority: Int = 50,
    @ColumnInfo("valence")
    val valence: Double? = null,
    @ColumnInfo("arousal")
    val arousal: Double? = null,
    @ColumnInfo("tension")
    val tension: Double? = null,
    @ColumnInfo("response_tendency")
    val responseTendency: String? = null,
    @ColumnInfo("growth_delta")
    val growthDelta: String? = null,
    @ColumnInfo("confidence")
    val confidence: Double? = null,
    @ColumnInfo("proposer")
    val proposer: String,
    @ColumnInfo("rubric_version")
    val rubricVersion: String? = null,
    @ColumnInfo("reason")
    val reason: String? = null,
    @ColumnInfo("source_episode_key")
    val sourceEpisodeKey: String? = null,
    @ColumnInfo("status")
    val status: String = STATUS_PENDING,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("reviewed_at")
    val reviewedAt: Long? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_REVIEW = "review"
    }
}
