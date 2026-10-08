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
class Migration_30_38_Test {
    private val testDb = "migration-30-38-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val migrations30To38 = arrayOf(
        Migration_30_31,
        Migration_31_32,
        Migration_32_33,
        Migration_33_34,
        Migration_34_35,
        Migration_35_36,
        Migration_36_37,
        Migration_37_38
    )

    private val migrations32To38 = arrayOf(
        Migration_32_33,
        Migration_33_34,
        Migration_34_35,
        Migration_35_36,
        Migration_36_37,
        Migration_37_38
    )

    @Test
    fun migrate30To38_fullMigrationChainValidatesSuccessfully() {
        helper.createDatabase(testDb, 30).apply {
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 38, true, *migrations30To38)

        // Verify key tables exist
        db.query("PRAGMA table_info(`memory_cards`)").use { cursor ->
            assertTrue("memory_cards should exist", cursor.count > 0)
        }
        db.query("PRAGMA table_info(`memory_relations`)").use { cursor ->
            assertTrue("memory_relations should exist", cursor.count > 0)
            var hasEvidenceSourceIds = false
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIdx) == "evidence_source_ids") {
                    hasEvidenceSourceIds = true
                    break
                }
            }
            assertTrue("evidence_source_ids column should exist in memory_relations", hasEvidenceSourceIds)
        }
        db.query("PRAGMA table_info(`memory_timelines`)").use { cursor ->
            assertTrue("memory_timelines should exist", cursor.count > 0)
        }
        db.query("PRAGMA table_info(`memory_timeline_members`)").use { cursor ->
            assertTrue("memory_timeline_members should exist", cursor.count > 0)
        }
        db.query("PRAGMA table_info(`memory_proposals`)").use { cursor ->
            assertTrue("memory_proposals should exist", cursor.count > 0)
            var hasAction = false
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIdx) == "action") {
                    hasAction = true
                    break
                }
            }
            assertTrue("action column should exist in memory_proposals", hasAction)
        }
        db.close()
    }

    @Test
    fun migrate32To38_incrementalMigrationChainValidatesSuccessfully() {
        helper.createDatabase(testDb + "_32", 32).apply {
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb + "_32", 38, true, *migrations32To38)

        db.query("PRAGMA table_info(`memory_relations`)").use { cursor ->
            assertTrue("memory_relations should exist", cursor.count > 0)
        }
        db.query("PRAGMA table_info(`memory_timelines`)").use { cursor ->
            assertTrue("memory_timelines should exist", cursor.count > 0)
        }
        db.close()
    }
}
