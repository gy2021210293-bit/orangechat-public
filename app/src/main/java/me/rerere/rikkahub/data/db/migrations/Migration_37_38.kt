/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_37_38 : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `memory_proposals` ADD COLUMN `action` TEXT NOT NULL DEFAULT 'ADD'")
        db.execSQL("ALTER TABLE `memory_proposals` ADD COLUMN `target_id` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `memory_proposals` ADD COLUMN `expected_revision` INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE `memory_proposals` ADD COLUMN `fact_key` TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE `memory_proposals` ADD COLUMN `secondary_card_ids` TEXT DEFAULT NULL")

        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_proposals_target_id` ON `memory_proposals` (`target_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_proposals_fact_key` ON `memory_proposals` (`fact_key`)")
    }
}
