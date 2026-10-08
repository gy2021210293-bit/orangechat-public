/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_35_36 : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. memory_cards 添加 M 轴代谢元数据字段
        db.execSQL("ALTER TABLE `memory_cards` ADD COLUMN `hit_count` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memory_cards` ADD COLUMN `last_recalled_at` INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE `memory_cards` ADD COLUMN `heat` REAL NOT NULL DEFAULT 0.0")
        db.execSQL("ALTER TABLE `memory_cards` ADD COLUMN `last_reviewed_at` INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE `memory_cards` ADD COLUMN `is_protected` INTEGER NOT NULL DEFAULT 0")

        // 2. 创建 memory_maintenance_tasks 巡检建议表
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_maintenance_tasks` (
                `id` TEXT NOT NULL,
                `owner_type` TEXT NOT NULL,
                `owner_id` TEXT NOT NULL,
                `task_type` TEXT NOT NULL,
                `target_type` TEXT NOT NULL,
                `target_id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `suggestion` TEXT NOT NULL,
                `reason` TEXT NOT NULL,
                `severity` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `metadata` TEXT,
                `created_at` INTEGER NOT NULL,
                `resolved_at` INTEGER,
                `resolved_by` TEXT,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_maintenance_tasks_owner_type_owner_id_status` ON `memory_maintenance_tasks` (`owner_type`, `owner_id`, `status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_maintenance_tasks_target_type_target_id` ON `memory_maintenance_tasks` (`target_type`, `target_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_maintenance_tasks_created_at` ON `memory_maintenance_tasks` (`created_at`)")
    }
}
