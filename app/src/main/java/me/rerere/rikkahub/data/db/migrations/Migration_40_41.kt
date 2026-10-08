/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 *
 * Portions adapted from Living Memory Coordinate-5 (LMC-5),
 * upstream commit fb3e72c9ee7357c8b17311097b0750082a8a1237, GNU AGPL v3.
 * Adapted for Kotlin/Room on 2026-09-13.
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_40_41 : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. memory_experience_coordinates 表与索引
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_experience_coordinates` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `owner_type` TEXT NOT NULL,
                `owner_id` TEXT NOT NULL,
                `memory_card_id` TEXT NOT NULL,
                `meaning` TEXT NOT NULL,
                `e_authored_by` TEXT NOT NULL,
                `e_initial_priority` INTEGER NOT NULL,
                `valence` REAL NOT NULL,
                `arousal` REAL NOT NULL,
                `tension` REAL NOT NULL,
                `emotion_confidence` REAL NOT NULL,
                `response_tendency` TEXT NOT NULL,
                `growth_delta` TEXT NOT NULL,
                `source_episode_key` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `superseded_by` INTEGER,
                `created_at` INTEGER NOT NULL,
                FOREIGN KEY(`memory_card_id`) REFERENCES `memory_cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_experience_coordinates_owner_type_owner_id_status` ON `memory_experience_coordinates` (`owner_type`, `owner_id`, `status`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_experience_coordinates_memory_card_id_status` ON `memory_experience_coordinates` (`memory_card_id`, `status`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_experience_coordinates_source_episode_key` ON `memory_experience_coordinates` (`source_episode_key`)"
        )

        // 2. memory_e_proposals 表与索引
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_e_proposals` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `owner_type` TEXT NOT NULL,
                `owner_id` TEXT NOT NULL,
                `memory_card_id` TEXT NOT NULL,
                `meaning` TEXT,
                `initial_priority` INTEGER NOT NULL DEFAULT 50,
                `valence` REAL,
                `arousal` REAL,
                `tension` REAL,
                `response_tendency` TEXT,
                `growth_delta` TEXT,
                `confidence` REAL,
                `proposer` TEXT NOT NULL,
                `rubric_version` TEXT,
                `reason` TEXT,
                `source_episode_key` TEXT,
                `status` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `reviewed_at` INTEGER,
                FOREIGN KEY(`memory_card_id`) REFERENCES `memory_cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_e_proposals_owner_type_owner_id_status` ON `memory_e_proposals` (`owner_type`, `owner_id`, `status`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_e_proposals_memory_card_id` ON `memory_e_proposals` (`memory_card_id`)"
        )
    }
}
