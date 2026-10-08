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

class Migration_30_38_SchemaValidationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadSchema(version: Int): File {
        val file = File("schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json")
            .takeIf { it.exists() }
            ?: File("app/schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json")
        assertTrue("schema $version.json must exist", file.exists())
        return file
    }

    @Test
    fun migration30To38_allOriginalTablesPreservedAndMemoryTablesPresent() {
        val schema30File = loadSchema(30)
        val schema38File = loadSchema(38)

        val root30 = json.parseToJsonElement(schema30File.readText()).jsonObject
        val root38 = json.parseToJsonElement(schema38File.readText()).jsonObject

        val entities30 = root30["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }
        val entities38 = root38["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }

        // 1. Ensure all original non-memory tables from version 30 exist in version 38
        for ((tableName, entity30) in entities30) {
            val entity38 = entities38[tableName]
            assertNotNull("Table $tableName from v30 must exist in v38", entity38)

            val fields30 = entity30.jsonObject["fields"]!!.jsonArray.map {
                it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject["affinity"]!!.jsonPrimitive.content
            }
            val fields38 = entity38!!.jsonObject["fields"]!!.jsonArray.map {
                it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject["affinity"]!!.jsonPrimitive.content
            }
            // All original fields must be preserved
            val fields38Map = fields38.toMap()
            for ((colName, affinity) in fields30) {
                assertEquals(
                    "Column $colName in $tableName must have identical affinity",
                    affinity,
                    fields38Map[colName]
                )
            }
        }

        // 2. Ensure all Memory V1 tables exist in version 38
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
            assertTrue("Table $table must exist in v38", entities38.containsKey(table))
        }

        // 3. Verify key columns in memory_relations and memory_proposals
        val relFields = entities38["memory_relations"]!!.jsonObject["fields"]!!.jsonArray.map {
            it.jsonObject["columnName"]!!.jsonPrimitive.content
        }.toSet()
        assertTrue("memory_relations must have evidence_source_ids", relFields.contains("evidence_source_ids"))
        assertTrue("memory_relations must have strength", relFields.contains("strength"))
        assertTrue("memory_relations must have status", relFields.contains("status"))

        val propFields = entities38["memory_proposals"]!!.jsonObject["fields"]!!.jsonArray.map {
            it.jsonObject["columnName"]!!.jsonPrimitive.content
        }.toSet()
        assertTrue("memory_proposals must have action", propFields.contains("action"))
        assertTrue("memory_proposals must have target_id", propFields.contains("target_id"))
        assertTrue("memory_proposals must have expected_revision", propFields.contains("expected_revision"))
        assertTrue("memory_proposals must have fact_key", propFields.contains("fact_key"))
    }

    @Test
    fun migration32To38_incrementalEvolutionVerified() {
        val schema32File = loadSchema(32)
        val schema38File = loadSchema(38)

        val root32 = json.parseToJsonElement(schema32File.readText()).jsonObject
        val root38 = json.parseToJsonElement(schema38File.readText()).jsonObject

        val entities32 = root32["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }
        val entities38 = root38["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }

        // Tables added after v32
        val tablesAddedAfter32 = setOf(
            "memory_proposals",
            "memory_fact_audits",
            "memory_maintenance_tasks",
            "memory_relations",
            "memory_timelines",
            "memory_timeline_members"
        )

        for (table in tablesAddedAfter32) {
            assertTrue("Table $table must NOT exist in v32", !entities32.containsKey(table))
            assertTrue("Table $table must exist in v38", entities38.containsKey(table))
        }
    }
}
