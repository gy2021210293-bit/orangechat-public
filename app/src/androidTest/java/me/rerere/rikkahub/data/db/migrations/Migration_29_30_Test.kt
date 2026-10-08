/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_29_30_Test {
    private val testDb = "migration-29-30-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate29To30_repairsMissingSecurityAuditIndexAndCreatesClaimTable() {
        helper.createDatabase(testDb, 29).apply {
            execSQL("DROP INDEX IF EXISTS `index_security_audit_logs_timestamp`")
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 30, true, Migration_29_30)

        db.query(
            "SELECT name FROM sqlite_master " +
                "WHERE type = 'index' AND name = 'index_security_audit_logs_timestamp'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        db.query("PRAGMA table_info(`workflow_fire_claims`)").use { cursor ->
            assertEquals(10, cursor.count)
        }
        db.close()
    }
}
