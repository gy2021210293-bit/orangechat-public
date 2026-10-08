package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_43_44 : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE autonomous_wake_runs ADD COLUMN next_wake_delay_minutes INTEGER")
        db.execSQL("ALTER TABLE autonomous_wake_runs ADD COLUMN next_wake_reason TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE autonomous_wake_runs ADD COLUMN chosen_wake_delay_minutes INTEGER")
        db.execSQL("ALTER TABLE autonomous_wake_runs ADD COLUMN wake_adjustment_reason TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE autonomous_wake_runs ADD COLUMN delivery_feedback TEXT NOT NULL DEFAULT ''")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS autonomous_wake_items (
                id TEXT NOT NULL PRIMARY KEY,
                assistant_id TEXT NOT NULL,
                content TEXT NOT NULL,
                progress TEXT NOT NULL,
                status TEXT NOT NULL,
                resume_at TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_autonomous_wake_items_assistant_id_status_resume_at ON autonomous_wake_items(assistant_id, status, resume_at)")
        db.execSQL("""
            INSERT OR IGNORE INTO autonomous_wake_items
                (id, assistant_id, content, progress, status, resume_at, created_at, updated_at)
            SELECT 'legacy:' || assistant_id, assistant_id, current_item, '', 'active', NULL, updated_at, updated_at
            FROM autonomous_wake_state
            WHERE trim(current_item) != ''
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS autonomous_wake_plans (
                assistant_id TEXT NOT NULL PRIMARY KEY,
                autonomous_candidate_at INTEGER NOT NULL,
                item_candidate_at INTEGER,
                scheduled_at INTEGER NOT NULL,
                source TEXT NOT NULL,
                reason TEXT NOT NULL,
                version INTEGER NOT NULL,
                status TEXT NOT NULL,
                running_version INTEGER,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS autonomous_wake_shares (
                id TEXT NOT NULL PRIMARY KEY,
                assistant_id TEXT NOT NULL,
                dedupe_key TEXT NOT NULL,
                item_id TEXT,
                result_id TEXT,
                request_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                message_id TEXT NOT NULL,
                content TEXT NOT NULL,
                sent_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_autonomous_wake_shares_assistant_id_dedupe_key ON autonomous_wake_shares(assistant_id, dedupe_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_autonomous_wake_shares_assistant_id_sent_at ON autonomous_wake_shares(assistant_id, sent_at)")
    }
}
