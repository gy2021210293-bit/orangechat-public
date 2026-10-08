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
    tableName = "memory_experience_coordinates",
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
        Index(value = ["memory_card_id", "status"]),
        Index(value = ["source_episode_key"]),
    ]
)
data class MemoryExperienceCoordinateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("owner_type")
    val ownerType: String,
    @ColumnInfo("owner_id")
    val ownerId: String,
    @ColumnInfo("memory_card_id")
    val memoryCardId: String,
    @ColumnInfo("meaning")
    val meaning: String,
    @ColumnInfo("e_authored_by")
    val eAuthoredBy: String,
    @ColumnInfo("e_initial_priority")
    val eInitialPriority: Int,
    @ColumnInfo("valence")
    val valence: Double = 0.0,
    @ColumnInfo("arousal")
    val arousal: Double = 0.5,
    @ColumnInfo("tension")
    val tension: Double = 0.0,
    @ColumnInfo("emotion_confidence")
    val emotionConfidence: Double = 1.0,
    @ColumnInfo("response_tendency")
    val responseTendency: String = "ENGAGE",
    @ColumnInfo("growth_delta")
    val growthDelta: String = "STABLE",
    @ColumnInfo("source_episode_key")
    val sourceEpisodeKey: String = "",
    @ColumnInfo("status")
    val status: String = "CURRENT",
    @ColumnInfo("superseded_by")
    val supersededBy: Long? = null,
    @ColumnInfo("created_at")
    val createdAt: Long = 0L,
) {
    companion object {
        const val STATUS_CURRENT = "CURRENT"
        const val STATUS_SUPERSEDED = "SUPERSEDED"
        const val STATUS_REVIEW = "REVIEW"
        const val STATUS_REJECTED = "REJECTED"

        const val GROWTH_GROWTH = "GROWTH"
        const val GROWTH_STABLE = "STABLE"
        const val GROWTH_SETBACK = "SETBACK"

        // 核心与陪伴倾向枚举
        const val TENDENCY_COMFORT = "COMFORT"
        const val TENDENCY_ENGAGE = "ENGAGE"
        const val TENDENCY_WITHDRAW = "WITHDRAW"
        const val TENDENCY_ALERT = "ALERT"
        const val TENDENCY_GIVE_SPACE = "GIVE_SPACE"
        const val TENDENCY_REPAIR = "REPAIR"
        const val TENDENCY_CELEBRATE = "CELEBRATE"
        const val TENDENCY_CLARIFY = "CLARIFY"
        const val TENDENCY_OBSERVE = "OBSERVE"

        val ALLOWED_TENDENCIES: Set<String> = setOf(
            TENDENCY_COMFORT,
            TENDENCY_ENGAGE,
            TENDENCY_WITHDRAW,
            TENDENCY_ALERT,
            TENDENCY_GIVE_SPACE,
            TENDENCY_REPAIR,
            TENDENCY_CELEBRATE,
            TENDENCY_CLARIFY,
            TENDENCY_OBSERVE,
        )

        fun normalizeTendency(raw: String?): String {
            val upper = raw?.trim()?.uppercase().orEmpty()
            if (upper == "NURTURE") return TENDENCY_COMFORT
            return if (upper in ALLOWED_TENDENCIES) upper else TENDENCY_OBSERVE
        }
    }
}
