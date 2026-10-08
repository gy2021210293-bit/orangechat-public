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

class Migration_41_42_SchemaValidationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadSchema(version: Int): File {
        val file = File("schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json")
            .takeIf { it.exists() }
            ?: File("app/schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json")
        assertTrue("schema $version.json must exist", file.exists())
        return file
    }

    @Test
    fun migration41To42_preservesExistingTablesAndAddsMusicTables() {
        val schema41File = loadSchema(41)
        val schema42File = loadSchema(42)

        val root41 = json.parseToJsonElement(schema41File.readText()).jsonObject
        val root42 = json.parseToJsonElement(schema42File.readText()).jsonObject

        val entities41 = root41["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }
        val entities42 = root42["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }

        // 1. Ensure all tables from v41 exist in v42 with identical fields
        for ((tableName, entity41) in entities41) {
            val entity42 = entities42[tableName]
            assertNotNull("Table $tableName from v41 must exist in v42", entity42)

            val fields41 = entity41.jsonObject["fields"]!!.jsonArray.map {
                it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject["affinity"]!!.jsonPrimitive.content
            }
            val fields42 = entity42!!.jsonObject["fields"]!!.jsonArray.map {
                it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject["affinity"]!!.jsonPrimitive.content
            }
            val fields42Map = fields42.toMap()
            for ((colName, affinity) in fields41) {
                assertEquals(
                    "Column $colName in $tableName must have identical affinity",
                    affinity,
                    fields42Map[colName]
                )
            }
        }

        // 2. Ensure new music tables exist in v42
        val expectedNewTables = listOf(
            "music_playlists",
            "music_session_states",
            "music_events",
            "music_daily_discoveries",
            "music_feedbacks",
            "music_summaries",
        )
        for (table in expectedNewTables) {
            assertNotNull("New table $table must exist in v42", entities42[table])
        }
    }
}
