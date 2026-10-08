/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_39_40 : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. desire_states 表
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `desire_states` (
                `assistant_id` TEXT NOT NULL,
                `drives_json` TEXT NOT NULL,
                `thoughts_json` TEXT NOT NULL,
                `last_tick_at` INTEGER NOT NULL,
                `tick_count` INTEGER NOT NULL,
                `next_tick_at` INTEGER NOT NULL,
                `schema_version` INTEGER NOT NULL DEFAULT 1,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`assistant_id`)
            )
            """.trimIndent()
        )

        // 2. desire_tick_logs 表及索引
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `desire_tick_logs` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `tick_number` INTEGER NOT NULL,
                `event_name` TEXT,
                `drive_changes_json` TEXT NOT NULL,
                `thought_changes_json` TEXT NOT NULL,
                `behavior_hints_json` TEXT NOT NULL,
                `monologue` TEXT NOT NULL,
                `warnings_json` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_desire_tick_logs_assistant_id_created_at` ON `desire_tick_logs` (`assistant_id`, `created_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_desire_tick_logs_created_at` ON `desire_tick_logs` (`created_at`)")

        // 3. desire_active_sends 表及索引
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `desire_active_sends` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `sent_at` INTEGER NOT NULL,
                `reason` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `drives_snapshot_json` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `message_id` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_desire_active_sends_assistant_id_sent_at` ON `desire_active_sends` (`assistant_id`, `sent_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_desire_active_sends_sent_at` ON `desire_active_sends` (`sent_at`)")

        // 4. desire_event_receipts 表及索引
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `desire_event_receipts` (
                `event_id` TEXT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `event_name` TEXT NOT NULL,
                `source_type` TEXT NOT NULL,
                `source_id` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `processed_at` INTEGER NOT NULL,
                PRIMARY KEY(`event_id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_desire_event_receipts_assistant_id` ON `desire_event_receipts` (`assistant_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_desire_event_receipts_created_at` ON `desire_event_receipts` (`created_at`)")
    }
}
