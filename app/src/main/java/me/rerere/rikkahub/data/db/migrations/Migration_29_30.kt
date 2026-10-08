/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_29_30 : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `workflow_fire_claims` (
                `workflowId` TEXT NOT NULL,
                `scheduledAt` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `claimedAt` INTEGER NOT NULL,
                `leaseUntil` INTEGER NOT NULL,
                `claimToken` TEXT NOT NULL DEFAULT '',
                `completedAt` INTEGER,
                `attemptCount` INTEGER NOT NULL DEFAULT 1,
                `error` TEXT,
                PRIMARY KEY(`workflowId`, `scheduledAt`)
            )
            """.trimIndent(),
        )

        // Some version-29 installs were created without this index even though the
        // exported Room schema already declared it. Repair the physical database so
        // Room's version-30 schema validation can open the app without deleting data.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_security_audit_logs_timestamp` " +
                "ON `security_audit_logs` (`timestamp`)",
        )
    }
}
