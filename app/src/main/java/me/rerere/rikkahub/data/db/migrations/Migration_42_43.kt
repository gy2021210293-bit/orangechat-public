package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_42_43 : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `autonomous_wake_runs` (
                `id` TEXT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `scheduled_at` INTEGER NOT NULL,
                `started_at` INTEGER,
                `finished_at` INTEGER,
                `status` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `outcome` TEXT NOT NULL,
                `next_step` TEXT NOT NULL,
                `should_contact` INTEGER NOT NULL,
                `message_text` TEXT NOT NULL,
                `tool_calls_json` TEXT NOT NULL,
                `error_text` TEXT NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_autonomous_wake_runs_assistant_id_scheduled_at` ON `autonomous_wake_runs` (`assistant_id`, `scheduled_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_autonomous_wake_runs_assistant_id_started_at` ON `autonomous_wake_runs` (`assistant_id`, `started_at`)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `autonomous_wake_state` (
                `assistant_id` TEXT NOT NULL,
                `current_item` TEXT NOT NULL,
                `latest_run_id` TEXT NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`assistant_id`)
            )
        """.trimIndent())
    }
}
