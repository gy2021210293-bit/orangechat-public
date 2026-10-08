/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_33_34 : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_timelines` (
                `id` TEXT NOT NULL,
                `owner_type` TEXT NOT NULL,
                `owner_id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `start_time` INTEGER,
                `end_time` INTEGER,
                `status` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_timelines_owner_type_owner_id` ON `memory_timelines` (`owner_type`, `owner_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_timelines_status` ON `memory_timelines` (`status`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_timeline_members` (
                `id` TEXT NOT NULL,
                `timeline_id` TEXT NOT NULL,
                `card_id` TEXT NOT NULL,
                `start_time` INTEGER,
                `end_time` INTEGER,
                `confidence` REAL NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_timeline_members_timeline_id` ON `memory_timeline_members` (`timeline_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_timeline_members_card_id` ON `memory_timeline_members` (`card_id`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_event_chunks` (
                `id` TEXT NOT NULL,
                `owner_type` TEXT NOT NULL,
                `owner_id` TEXT NOT NULL,
                `conversation_id` TEXT,
                `summary` TEXT NOT NULL,
                `start_time` INTEGER NOT NULL,
                `end_time` INTEGER NOT NULL,
                `message_ids` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `fingerprint` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_event_chunks_owner_type_owner_id` ON `memory_event_chunks` (`owner_type`, `owner_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_event_chunks_conversation_id` ON `memory_event_chunks` (`conversation_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_event_chunks_fingerprint` ON `memory_event_chunks` (`fingerprint`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_event_chunks_start_time` ON `memory_event_chunks` (`start_time`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_proposals` (
                `id` TEXT NOT NULL,
                `owner_type` TEXT NOT NULL,
                `owner_id` TEXT NOT NULL,
                `memory_date` TEXT NOT NULL,
                `chunk_id` TEXT,
                `type` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `importance` INTEGER NOT NULL,
                `risk` TEXT NOT NULL,
                `evidence` TEXT NOT NULL,
                `source_message_ids` TEXT NOT NULL,
                `timeline_hint` TEXT,
                `relation_hints` TEXT,
                `status` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `resolved_at` INTEGER,
                `resolved_by` TEXT,
                `card_id` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_proposals_owner_type_owner_id_memory_date` ON `memory_proposals` (`owner_type`, `owner_id`, `memory_date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_proposals_chunk_id` ON `memory_proposals` (`chunk_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_proposals_status` ON `memory_proposals` (`status`)")
    }
}
