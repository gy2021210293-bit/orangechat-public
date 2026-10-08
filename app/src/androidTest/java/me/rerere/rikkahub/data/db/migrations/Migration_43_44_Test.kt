package me.rerere.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_43_44_Test {
    private val testDb = "migration-43-44-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migratesOnlyNonEmptyLegacyItemAndKeepsRunHistory() {
        val oldDb = helper.createDatabase(testDb, 43)
        try {
            val db = oldDb
            db.insert("autonomous_wake_state", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
                put("assistant_id", "assistant-with-item")
                put("current_item", "整理周报")
                put("latest_run_id", "legacy-run")
                put("updated_at", 100L)
            })
            db.insert("autonomous_wake_state", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
                put("assistant_id", "assistant-without-item")
                put("current_item", "   ")
                put("latest_run_id", "")
                put("updated_at", 200L)
            })
            db.insert("autonomous_wake_runs", SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
                put("id", "legacy-run")
                put("assistant_id", "assistant-with-item")
                put("scheduled_at", 50L)
                put("status", "completed")
                put("summary", "历史摘要")
                put("outcome", "历史结果")
                put("next_step", "")
                put("should_contact", 0)
                put("message_text", "")
                put("tool_calls_json", "[]")
                put("error_text", "")
            })
        } finally {
            oldDb.close()
        }

        val migratedDb = helper.runMigrationsAndValidate(testDb, 44, true, Migration_43_44)
        try {
            val db = migratedDb
            db.query("SELECT id, assistant_id, content, status, resume_at, created_at FROM autonomous_wake_items").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy:assistant-with-item", cursor.getString(0))
                assertEquals("assistant-with-item", cursor.getString(1))
                assertEquals("整理周报", cursor.getString(2))
                assertEquals("active", cursor.getString(3))
                assertTrue(cursor.isNull(4))
                assertEquals(100L, cursor.getLong(5))
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT id, summary FROM autonomous_wake_runs WHERE id = 'legacy-run'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy-run", cursor.getString(0))
                assertEquals("历史摘要", cursor.getString(1))
            }
            db.query("SELECT next_wake_reason, wake_adjustment_reason, delivery_feedback FROM autonomous_wake_runs WHERE id = 'legacy-run'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
                assertEquals("", cursor.getString(1))
                assertEquals("", cursor.getString(2))
            }
        } finally {
            migratedDb.close()
        }
    }
}
