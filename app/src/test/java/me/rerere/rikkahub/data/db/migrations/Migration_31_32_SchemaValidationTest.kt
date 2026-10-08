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

class Migration_31_32_SchemaValidationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun migration31To32_schemaIntegrityAndRunTokenAdded() {
        val schema31File = File("schemas/me.rerere.rikkahub.data.db.AppDatabase/31.json")
            .takeIf { it.exists() } ?: File("app/schemas/me.rerere.rikkahub.data.db.AppDatabase/31.json")
        val schema32File = File("schemas/me.rerere.rikkahub.data.db.AppDatabase/32.json")
            .takeIf { it.exists() } ?: File("app/schemas/me.rerere.rikkahub.data.db.AppDatabase/32.json")

        assertTrue("schema 31.json must exist", schema31File.exists())
        assertTrue("schema 32.json must exist", schema32File.exists())

        val root31 = json.parseToJsonElement(schema31File.readText()).jsonObject
        val root32 = json.parseToJsonElement(schema32File.readText()).jsonObject

        val entities31 = root31["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }
        val entities32 = root32["database"]!!.jsonObject["entities"]!!.jsonArray.associateBy {
            it.jsonObject["tableName"]!!.jsonPrimitive.content
        }

        // 1. 除了 memory_consolidation_runs 增加 run_token，所有其他表必须结构完全相同
        for ((tableName, entity31) in entities31) {
            val entity32 = entities32[tableName]
            assertTrue("Table $tableName from v31 must exist in v32", entity32 != null)

            val fields31 = entity31.jsonObject["fields"]!!.jsonArray.map {
                it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject["affinity"]!!.jsonPrimitive.content
            }
            val fields32 = entity32!!.jsonObject["fields"]!!.jsonArray.map {
                it.jsonObject["columnName"]!!.jsonPrimitive.content to it.jsonObject["affinity"]!!.jsonPrimitive.content
            }

            if (tableName == "memory_consolidation_runs") {
                // v32 应该包含 v31 的全部列 + run_token
                val diff = fields32.toSet() - fields31.toSet()
                assertEquals("Only run_token should be added to memory_consolidation_runs", setOf("run_token" to "TEXT"), diff)
            } else {
                assertEquals("Table $tableName fields must not be altered between v31 and v32", fields31, fields32)
            }
        }

        // 2. 校验 Migration_31_32.kt 存在且包含 ALTER TABLE 语句
        val migrationCode = File("src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_31_32.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_31_32.kt")
        assertTrue("Migration_31_32.kt must exist", migrationCode.exists())
        val codeText = migrationCode.readText()
        assertTrue("Migration_31_32 must contain ALTER TABLE", codeText.contains("ALTER TABLE"))
        assertTrue("Migration_31_32 must add run_token column", codeText.contains("`run_token`"))
    }
}
