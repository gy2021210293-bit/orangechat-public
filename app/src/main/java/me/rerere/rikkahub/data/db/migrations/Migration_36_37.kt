/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_36_37 : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. 创建 memory_fact_audits 表
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_fact_audits` (
                `id` TEXT NOT NULL,
                `owner_type` TEXT NOT NULL,
                `owner_id` TEXT NOT NULL,
                `fact_key` TEXT NOT NULL,
                `existing_card_id` TEXT NOT NULL,
                `candidate_card_id` TEXT,
                `candidate_content` TEXT NOT NULL,
                `model_suggestion` TEXT NOT NULL,
                `reason` TEXT NOT NULL,
                `evidence_snippet` TEXT,
                `status` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `resolved_at` INTEGER,
                `resolved_by` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_fact_audits_owner_type_owner_id_status` ON `memory_fact_audits` (`owner_type`, `owner_id`, `status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_fact_audits_fact_key` ON `memory_fact_audits` (`fact_key`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_fact_audits_existing_card_id` ON `memory_fact_audits` (`existing_card_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_fact_audits_created_at` ON `memory_fact_audits` (`created_at`)")

        // 2. 扩充 memory_relations 字段
        db.execSQL("ALTER TABLE `memory_relations` ADD COLUMN `strength` REAL NOT NULL DEFAULT 1.0")
        db.execSQL("ALTER TABLE `memory_relations` ADD COLUMN `reason` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `memory_relations` ADD COLUMN `evidence_source_ids` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `memory_relations` ADD COLUMN `valid_from` INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE `memory_relations` ADD COLUMN `valid_until` INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE `memory_relations` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'ACTIVE'")
        db.execSQL("ALTER TABLE `memory_relations` ADD COLUMN `created_by` TEXT NOT NULL DEFAULT 'AUTO'")

        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_relations_status` ON `memory_relations` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_relations_relation_type` ON `memory_relations` (`relation_type`)")
    }
}
