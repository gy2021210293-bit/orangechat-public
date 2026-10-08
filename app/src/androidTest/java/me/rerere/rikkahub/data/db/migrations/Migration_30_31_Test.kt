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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_30_31_Test {
    private val testDb = "migration-30-31-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate30To31_createsMemoryV1TablesAndIndices() {
        helper.createDatabase(testDb, 30).apply {
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 31, true, Migration_30_31)

        db.query("PRAGMA table_info(`memory_cards`)").use { cursor ->
            assertTrue("memory_cards should exist", cursor.count > 0)
        }
        db.query("PRAGMA table_info(`memory_sources`)").use { cursor ->
            assertTrue("memory_sources should exist", cursor.count > 0)
        }
        db.query("PRAGMA table_info(`memory_revisions`)").use { cursor ->
            assertTrue("memory_revisions should exist", cursor.count > 0)
        }
        db.query("PRAGMA table_info(`memory_daily_notes`)").use { cursor ->
            assertTrue("memory_daily_notes should exist", cursor.count > 0)
        }
        db.query("PRAGMA table_info(`memory_consolidation_runs`)").use { cursor ->
            assertTrue("memory_consolidation_runs should exist", cursor.count > 0)
        }
        db.query("PRAGMA table_info(`memory_embeddings`)").use { cursor ->
            assertTrue("memory_embeddings should exist", cursor.count > 0)
        }
        db.close()
    }
}
