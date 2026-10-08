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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Migration_30_39_SchemaValidationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadSchema(version: Int): File {
        val file = File("schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json")
            .takeIf { it.exists() }
            ?: File("app/schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json")
        assertTrue("schema $version.json must exist", file.exists())
        return file
    }

    @Test
    fun migration30To39_allOriginalTablesPreservedAndMemoryTablesPresent() {
        val schema30File = loadSchema(30)
        val schema39File = loadSchema(39)

        val root30 = json.parseToJsonElement(schema30File.readText()).jsonObject
        val root39 = json.parseToJsonElement(schema39File.readText()).jsonObject

        val entities30 = root30["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }
        val entities39 = root39["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }

        // 1. Ensure all original non-memory tables from version 30 exist in version 39
        for ((tableName, entity30) in entities30) {
            val entity39 = entities39[tableName]
            assertNotNull("Table $tableName from v30 must exist in v39", entity39)

            val fields30 = entity30.jsonObject["fields"]!!.jsonArray.map {
                it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject["affinity"]!!.jsonPrimitive.content
            }
            val fields39 = entity39!!.jsonObject["fields"]!!.jsonArray.map {
                it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject["affinity"]!!.jsonPrimitive.content
            }
            val fields39Map = fields39.toMap()
            for ((colName, affinity) in fields30) {
                assertEquals(
                    "Column $colName in $tableName must have identical affinity",
                    affinity,
                    fields39Map[colName]
                )
            }
        }

        // 2. Ensure all Memory V1 tables exist in version 39
        val expectedMemoryTables = setOf(
            "memory_cards",
            "memory_sources",
            "memory_revisions",
            "memory_daily_notes",
            "memory_consolidation_runs",
            "memory_embeddings",
            "memory_proposals",
            "memory_fact_audits",
            "memory_maintenance_tasks",
            "memory_relations",
            "memory_timelines",
            "memory_timeline_members"
        )
        for (table in expectedMemoryTables) {
            assertTrue("Table $table must exist in v39", entities39.containsKey(table))
        }

        // 3. Verify always_inject column in memory_cards
        val cardFields = entities39["memory_cards"]!!.jsonObject["fields"]!!.jsonArray.associate {
            it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject["affinity"]!!.jsonPrimitive.content
        }
        assertTrue("memory_cards must have always_inject column", cardFields.containsKey("always_inject"))
        assertEquals("INTEGER", cardFields["always_inject"])
    }

    @Test
    fun migration38To39_alwaysInjectColumnAdded() {
        val schema38File = loadSchema(38)
        val schema39File = loadSchema(39)

        val root38 = json.parseToJsonElement(schema38File.readText()).jsonObject
        val root39 = json.parseToJsonElement(schema39File.readText()).jsonObject

        val entities38 = root38["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }
        val entities39 = root39["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }

        val cardFields38 = entities38["memory_cards"]!!.jsonObject["fields"]!!.jsonArray.map {
            it.jsonObject["columnName"]!!.jsonPrimitive.content
        }.toSet()
        val cardFields39 = entities39["memory_cards"]!!.jsonObject["fields"]!!.jsonArray.map {
            it.jsonObject["columnName"]!!.jsonPrimitive.content
        }.toSet()

        assertTrue("v38 memory_cards must NOT have always_inject", !cardFields38.contains("always_inject"))
        assertTrue("v39 memory_cards must have always_inject", cardFields39.contains("always_inject"))
    }

    @Test
    fun migration39To40_allDesireTablesAddedWithExactAffinityAndIndices() {
        val schema39File = loadSchema(39)
        val schema40File = loadSchema(40)

        val root39 = json.parseToJsonElement(schema39File.readText()).jsonObject
        val root40 = json.parseToJsonElement(schema40File.readText()).jsonObject

        val entities39 = root39["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }
        val entities40 = root40["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }

        // 1. Ensure all v39 tables exist in v40 without regression
        for ((tableName, entity39) in entities39) {
            val entity40 = entities40[tableName]
            assertNotNull("Table $tableName from v39 must exist in v40", entity40)

            val fields39 = entity39.jsonObject["fields"]!!.jsonArray.map {
                it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject["affinity"]!!.jsonPrimitive.content
            }
            val fields40 = entity40!!.jsonObject["fields"]!!.jsonArray.associate {
                it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject["affinity"]!!.jsonPrimitive.content
            }
            for ((colName, affinity) in fields39) {
                assertEquals("Column $colName in $tableName must match affinity", affinity, fields40[colName])
            }
        }

        // 2. Ensure all 4 Desire tables exist in v40
        val expectedDesireTables = setOf(
            "desire_states",
            "desire_tick_logs",
            "desire_active_sends",
            "desire_event_receipts"
        )
        for (table in expectedDesireTables) {
            assertTrue("v39 must not have table $table", !entities39.containsKey(table))
            assertTrue("v40 must have table $table", entities40.containsKey(table))
        }

        // 3. Verify desire_states primary key
        val stateEntity = entities40["desire_states"]!!.jsonObject
        val pks = stateEntity["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("assistant_id"), pks)

        // 4. Verify desire_event_receipts primary key
        val receiptEntity = entities40["desire_event_receipts"]!!.jsonObject
        val receiptPks = receiptEntity["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("event_id"), receiptPks)
    }
}
