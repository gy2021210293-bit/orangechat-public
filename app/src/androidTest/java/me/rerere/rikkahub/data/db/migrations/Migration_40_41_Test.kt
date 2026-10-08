/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
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
class Migration_40_41_Test {
    private val testDb = "migration-40-41-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate40To41_createsExperienceAndProposalTablesAndPreservesCardData() {
        // 1. 创建 v40 数据库并插入测试卡片数据
        helper.createDatabase(testDb, 40).apply {
            val values = ContentValues().apply {
                put("id", "card-test-1")
                put("owner_type", "assistant")
                put("owner_id", "assistant-1")
                put("category", "experience")
                put("content", "旧记忆卡片内容")
                put("status", "ACTIVE")
                put("created_at", 1000L)
                put("updated_at", 1000L)
                put("hit_count", 0)
                put("heat", 1.0f)
                put("is_protected", 0)
                put("always_inject", 0)
            }
            insert("memory_cards", SQLiteDatabase.CONFLICT_FAIL, values)
            close()
        }

        // 2. 执行 Migration_40_41 迁移至 v41
        val db = helper.runMigrationsAndValidate(testDb, 41, true, Migration_40_41)

        // 3. 验证旧数据依然完整
        db.query("SELECT * FROM memory_cards WHERE id = 'card-test-1'").use { cursor ->
            assertTrue("card-test-1 must exist after migration", cursor.moveToNext())
            val contentIndex = cursor.getColumnIndex("content")
            assertEquals("旧记忆卡片内容", cursor.getString(contentIndex))
        }

        // 4. 验证 memory_experience_coordinates 表已创建且字段正确
        var hasCoordMeaning = false
        var hasCoordPriority = false
        var hasResponseTendency = false
        db.query("PRAGMA table_info(`memory_experience_coordinates`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                when (cursor.getString(nameIndex)) {
                    "meaning" -> hasCoordMeaning = true
                    "e_initial_priority" -> hasCoordPriority = true
                    "response_tendency" -> hasResponseTendency = true
                }
            }
        }
        assertTrue("memory_experience_coordinates must contain meaning column", hasCoordMeaning)
        assertTrue("memory_experience_coordinates must contain e_initial_priority column", hasCoordPriority)
        assertTrue("memory_experience_coordinates must contain response_tendency column", hasResponseTendency)

        // 5. 验证 memory_e_proposals 表已创建且包含 meaning 与 initial_priority
        var hasProposalMeaning = false
        var hasProposalPriority = false
        db.query("PRAGMA table_info(`memory_e_proposals`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                when (cursor.getString(nameIndex)) {
                    "meaning" -> hasProposalMeaning = true
                    "initial_priority" -> hasProposalPriority = true
                }
            }
        }
        assertTrue("memory_e_proposals must contain meaning column", hasProposalMeaning)
        assertTrue("memory_e_proposals must contain initial_priority column", hasProposalPriority)

        db.close()
    }
}
