/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_30_31 : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. memory_cards
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_cards` (
                `id` TEXT NOT NULL,
                `owner_type` TEXT NOT NULL,
                `owner_id` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `fact_key` TEXT,
                `status` TEXT NOT NULL,
                `confidence` REAL NOT NULL,
                `importance` REAL NOT NULL,
                `valid_from` INTEGER,
                `valid_to` INTEGER,
                `superseded_by` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL,
                `created_by` TEXT NOT NULL,
                `updated_by` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_cards_owner_type_owner_id` ON `memory_cards` (`owner_type`, `owner_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_cards_fact_key` ON `memory_cards` (`fact_key`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_cards_status` ON `memory_cards` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_cards_updated_at` ON `memory_cards` (`updated_at`)")

        // 2. memory_sources
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_sources` (
                `id` TEXT NOT NULL,
                `memory_id` TEXT NOT NULL,
                `conversation_id` TEXT,
                `node_id` TEXT,
                `message_id` TEXT,
                `role` TEXT,
                `source_kind` TEXT NOT NULL,
                `source_hash` TEXT,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_sources_memory_id` ON `memory_sources` (`memory_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_sources_source_hash` ON `memory_sources` (`source_hash`)")

        // 3. memory_revisions
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_revisions` (
                `id` TEXT NOT NULL,
                `memory_id` TEXT NOT NULL,
                `revision` INTEGER NOT NULL,
                `operation` TEXT NOT NULL,
                `before_content` TEXT,
                `after_content` TEXT,
                `before_status` TEXT,
                `after_status` TEXT,
                `reason` TEXT,
                `actor` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `run_id` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_revisions_memory_id` ON `memory_revisions` (`memory_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_revisions_created_at` ON `memory_revisions` (`created_at`)")

        // 4. memory_daily_notes
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_daily_notes` (
                `id` TEXT NOT NULL,
                `owner_type` TEXT NOT NULL,
                `owner_id` TEXT NOT NULL,
                `memory_date` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `emotional_thread` TEXT,
                `unfinished_topics` TEXT NOT NULL,
                `source_fingerprint` TEXT,
                `status` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `revision` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_daily_notes_owner_id_memory_date` ON `memory_daily_notes` (`owner_id`, `memory_date`)")

        // 5. memory_consolidation_runs
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_consolidation_runs` (
                `id` TEXT NOT NULL,
                `owner_type` TEXT NOT NULL,
                `owner_id` TEXT NOT NULL,
                `memory_date` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `source_fingerprint` TEXT,
                `attempt_count` INTEGER NOT NULL,
                `model_id` TEXT,
                `started_at` INTEGER,
                `finished_at` INTEGER,
                `error_code` TEXT,
                `lease_until` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_consolidation_runs_owner_id_memory_date` ON `memory_consolidation_runs` (`owner_id`, `memory_date`)")

        // 6. memory_embeddings
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_embeddings` (
                `id` TEXT NOT NULL,
                `memory_id` TEXT NOT NULL,
                `owner_type` TEXT NOT NULL,
                `owner_id` TEXT NOT NULL,
                `embedding_provider` TEXT NOT NULL,
                `embedding_model` TEXT NOT NULL,
                `dimension` INTEGER NOT NULL,
                `input_type` TEXT NOT NULL,
                `content_hash` TEXT NOT NULL,
                `vector_blob` BLOB NOT NULL,
                `status` TEXT NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_embeddings_memory_id` ON `memory_embeddings` (`memory_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_embeddings_owner_id_embedding_model` ON `memory_embeddings` (`owner_id`, `embedding_model`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_embeddings_content_hash` ON `memory_embeddings` (`content_hash`)")
    }
}
