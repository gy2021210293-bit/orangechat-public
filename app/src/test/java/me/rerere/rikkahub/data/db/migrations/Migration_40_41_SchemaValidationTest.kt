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

class Migration_40_41_SchemaValidationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadSchema(version: Int): File {
        val file = File("schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json")
            .takeIf { it.exists() }
            ?: File("app/schemas/me.rerere.rikkahub.data.db.AppDatabase/$version.json")
        assertTrue("schema $version.json must exist", file.exists())
        return file
    }

    @Test
    fun migration40To41_preservesExistingTablesAndAddsExperienceTables() {
        val schema40File = loadSchema(40)
        val schema41File = loadSchema(41)

        val root40 = json.parseToJsonElement(schema40File.readText()).jsonObject
        val root41 = json.parseToJsonElement(schema41File.readText()).jsonObject

        val entities40 = root40["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }
        val entities41 = root41["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }

        // 1. Ensure all tables from v40 exist in v41 with identical fields
        for ((tableName, entity40) in entities40) {
            val entity41 = entities41[tableName]
            assertNotNull("Table $tableName from v40 must exist in v41", entity41)

            val fields40 = entity40.jsonObject["fields"]!!.jsonArray.map {
                it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject["affinity"]!!.jsonPrimitive.content
            }
            val fields41 = entity41!!.jsonObject["fields"]!!.jsonArray.map {
                it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject["affinity"]!!.jsonPrimitive.content
            }
            val fields41Map = fields41.toMap()
            for ((colName, affinity) in fields40) {
                assertEquals(
                    "Column $colName in $tableName must have identical affinity",
                    affinity,
                    fields41Map[colName]
                )
            }
        }

        // 2. Ensure new E-axis tables exist in v41
        assertTrue(
            "memory_experience_coordinates must exist in v41",
            entities41.containsKey("memory_experience_coordinates")
        )
        assertTrue(
            "memory_e_proposals must exist in v41",
            entities41.containsKey("memory_e_proposals")
        )

        // 3. Verify fields in memory_experience_coordinates
        val expFields = entities41["memory_experience_coordinates"]!!.jsonObject["fields"]!!.jsonArray.associate {
            it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject["affinity"]!!.jsonPrimitive.content
        }
        assertEquals("INTEGER", expFields["id"])
        assertEquals("TEXT", expFields["owner_type"])
        assertEquals("TEXT", expFields["owner_id"])
        assertEquals("TEXT", expFields["memory_card_id"])
        assertEquals("TEXT", expFields["meaning"])
        assertEquals("TEXT", expFields["e_authored_by"])
        assertEquals("INTEGER", expFields["e_initial_priority"])
        assertEquals("REAL", expFields["valence"])
        assertEquals("REAL", expFields["arousal"])
        assertEquals("REAL", expFields["tension"])
        assertEquals("REAL", expFields["emotion_confidence"])
        assertEquals("TEXT", expFields["response_tendency"])
        assertEquals("TEXT", expFields["growth_delta"])
        assertEquals("TEXT", expFields["source_episode_key"])
        assertEquals("TEXT", expFields["status"])
        assertEquals("INTEGER", expFields["superseded_by"])
        assertEquals("INTEGER", expFields["created_at"])

        // 4. Verify fields in memory_e_proposals
        val proposalFields = entities41["memory_e_proposals"]!!.jsonObject["fields"]!!.jsonArray.associate {
            it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject["affinity"]!!.jsonPrimitive.content
        }
        assertEquals("INTEGER", proposalFields["id"])
        assertEquals("TEXT", proposalFields["owner_type"])
        assertEquals("TEXT", proposalFields["owner_id"])
        assertEquals("TEXT", proposalFields["memory_card_id"])
        assertEquals("TEXT", proposalFields["meaning"])
        assertEquals("INTEGER", proposalFields["initial_priority"])
        assertEquals("REAL", proposalFields["valence"])
        assertEquals("REAL", proposalFields["arousal"])
        assertEquals("REAL", proposalFields["tension"])
        assertEquals("TEXT", proposalFields["response_tendency"])
        assertEquals("TEXT", proposalFields["growth_delta"])
        assertEquals("REAL", proposalFields["confidence"])
        assertEquals("TEXT", proposalFields["proposer"])
        assertEquals("TEXT", proposalFields["rubric_version"])
        assertEquals("TEXT", proposalFields["reason"])
        assertEquals("TEXT", proposalFields["source_episode_key"])
        assertEquals("TEXT", proposalFields["status"])
        assertEquals("INTEGER", proposalFields["created_at"])
        assertEquals("INTEGER", proposalFields["reviewed_at"])
    }
}
