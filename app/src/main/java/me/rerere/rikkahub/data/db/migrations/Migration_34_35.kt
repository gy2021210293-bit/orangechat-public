/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_34_35 : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_relations` (
                `id` TEXT NOT NULL,
                `owner_type` TEXT NOT NULL,
                `owner_id` TEXT NOT NULL,
                `source_card_id` TEXT NOT NULL,
                `target_card_id` TEXT NOT NULL,
                `relation_type` TEXT NOT NULL,
                `weight` REAL NOT NULL,
                `source` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_relations_owner_type_owner_id` ON `memory_relations` (`owner_type`, `owner_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_relations_source_card_id` ON `memory_relations` (`source_card_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_relations_target_card_id` ON `memory_relations` (`target_card_id`)")
    }
}
