/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Migration_30_31_SchemaValidationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun migration30To31_schemaIntegrityAndZeroDestruction() {
        val schema30File = File("schemas/me.rerere.rikkahub.data.db.AppDatabase/30.json")
            .takeIf { it.exists() } ?: File("app/schemas/me.rerere.rikkahub.data.db.AppDatabase/30.json")
        val schema31File = File("schemas/me.rerere.rikkahub.data.db.AppDatabase/31.json")
            .takeIf { it.exists() } ?: File("app/schemas/me.rerere.rikkahub.data.db.AppDatabase/31.json")

        assertTrue("schema 30.json must exist", schema30File.exists())
        assertTrue("schema 31.json must exist", schema31File.exists())

        val root30 = json.parseToJsonElement(schema30File.readText()).jsonObject
        val root31 = json.parseToJsonElement(schema31File.readText()).jsonObject

        val entities30 = root30["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }
        val entities31 = root31["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }

        // 1. Verify all existing tables from version 30 exist in version 31 with identical fields
        for ((tableName, entity30) in entities30) {
            val entity31 = entities31[tableName]
            assertTrue("Table $tableName from v30 must exist in v31", entity31 != null)

            val fields30 = entity30.jsonObject["fields"]!!.jsonArray.map {
                it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject["affinity"]!!.jsonPrimitive.content
            }
            val fields31 = entity31!!.jsonObject["fields"]!!.jsonArray.map {
                it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject["affinity"]!!.jsonPrimitive.content
            }
            assertEquals("Table $tableName fields must not be altered", fields30, fields31)
        }

        // 2. Verify all 6 memory v1 tables are newly added in version 31
        val expectedNewTables = setOf(
            "memory_cards",
            "memory_sources",
            "memory_revisions",
            "memory_daily_notes",
            "memory_consolidation_runs",
            "memory_embeddings"
        )
        for (table in expectedNewTables) {
            assertTrue("Table $table must exist in v31", entities31.containsKey(table))
            assertTrue("Table $table must NOT exist in v30", !entities30.containsKey(table))
        }

        // 3. Verify Migration_30_31 code contains creation for all 6 tables and their indices
        val migrationCode = File("src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_30_31.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_30_31.kt")
        assertTrue("Migration_30_31.kt must exist", migrationCode.exists())
        val codeText = migrationCode.readText()

        for (table in expectedNewTables) {
            assertTrue("Migration_30_31 must create table $table", codeText.contains("`$table`"))
            val entity = entities31[table]!!.jsonObject
            val indices = entity["indices"]?.jsonArray.orEmpty()
            for (idx in indices) {
                val idxName = idx.jsonObject["name"]!!.jsonPrimitive.content
                assertTrue("Migration_30_31 must create index $idxName", codeText.contains(idxName))
            }
        }
    }
}
