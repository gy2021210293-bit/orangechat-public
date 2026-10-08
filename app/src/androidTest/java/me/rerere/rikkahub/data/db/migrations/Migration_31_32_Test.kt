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
class Migration_31_32_Test {
    private val testDb = "migration-31-32-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate31To32_addsRunTokenColumn() {
        // 创建旧版 31 数据库
        helper.createDatabase(testDb, 31).apply {
            close()
        }

        // 运行 31 -> 32 迁移
        val db = helper.runMigrationsAndValidate(testDb, 32, true, Migration_31_32)

        var hasRunToken = false
        db.query("PRAGMA table_info(`memory_consolidation_runs`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "run_token") {
                    hasRunToken = true
                    break
                }
            }
        }
        assertTrue("run_token column should be added to memory_consolidation_runs", hasRunToken)
        db.close()
    }
}
