/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesireCustomTemplatesTest {

    private val deterministicRandom = object : DesireRandomSource {
        override fun nextDouble(): Double = 0.0
        override fun <T> choice(list: List<T>): T = list.first()
        override fun <T> weightedChoice(list: List<T>, weights: List<Double>): T = list.first()
    }

    @Test
    fun testThoughtEngineCustomTemplates() {
        // 1. 默认回退
        val defaultAttachment = DesireThoughtEngine.getThoughtTemplates("attachment", null)
        assertEquals(DesireThoughtEngine.THOUGHT_TEMPLATES["attachment"], defaultAttachment)

        // 2. 自定义优先
        val customMap = mapOf(
            "attachment" to listOf("我的专属念头A", "我的专属念头B")
        )
        val customAttachment = DesireThoughtEngine.getThoughtTemplates("attachment", customMap)
        assertEquals(listOf("我的专属念头A", "我的专属念头B"), customAttachment)

        // 3. 已自定义的空列表会禁用该类别；只有未配置键才回退默认
        val blankMap = mapOf(
            "attachment" to listOf("", "   ")
        )
        val blankAttachment = DesireThoughtEngine.getThoughtTemplates("attachment", blankMap)
        assertEquals(emptyList<String>(), blankAttachment)

        val emptyListMap = mapOf(
            "attachment" to emptyList<String>()
        )
        val emptyAttachment = DesireThoughtEngine.getThoughtTemplates("attachment", emptyListMap)
        assertEquals(emptyList<String>(), emptyAttachment)

        // 未配置 key 回退系统内置
        val unconfiguredAttachment = DesireThoughtEngine.getThoughtTemplates("attachment", emptyMap())
        assertEquals(DesireThoughtEngine.THOUGHT_TEMPLATES["attachment"], unconfiguredAttachment)

        // 4. maybeSpawnThought 使用自定义
        val state = DesireState(
            drives = createDefaultDrives().mapValues { (k, v) ->
                if (k == "attachment") v.copy(value = 95.0) else v.copy(value = 20.0)
            }.toMutableMap()
        )
        val thought = DesireThoughtEngine.maybeSpawnThought(
            state = state,
            randomSource = deterministicRandom,
            customTemplates = customMap,
        )
        assertNotNull(thought)
        assertEquals("我的专属念头A", thought?.content)

        val disabledThought = DesireThoughtEngine.maybeSpawnThought(
            state = state,
            randomSource = deterministicRandom,
            customTemplates = mapOf("attachment" to emptyList()),
        )
        assertEquals(null, disabledThought)

        // 5. 移除自定义类别（模拟重置）后回退系统默认
        val resetMap = customMap.toMutableMap().also { it.remove("attachment") }
        val resetAttachment = DesireThoughtEngine.getThoughtTemplates("attachment", resetMap)
        assertEquals(DesireThoughtEngine.THOUGHT_TEMPLATES["attachment"], resetAttachment)
    }

    @Test
    fun testAssistantSerializationCompatibility() {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        // 旧版 JSON（包含已弃用的独白字段与念头字段）
        val legacyJsonWithMonologue = """
            {
                "id": "11111111-1111-1111-1111-111111111111",
                "name": "测试助手",
                "desireSystemEnabled": true,
                "desireCustomThoughtTemplates": {
                    "attachment": ["想念她"]
                },
                "desireCustomMonologueTemplates": {
                    "stress_high": ["好累"]
                }
            }
        """.trimIndent()

        // 1. 旧 JSON 中的独白字段可被成功反序列化并忽略，念头字段正常读取
        val assistant = json.decodeFromString<Assistant>(legacyJsonWithMonologue)
        assertEquals(listOf("想念她"), assistant.desireCustomThoughtTemplates["attachment"])

        // 2. 重新编码后，JSON 字符串中不再包含独白字段与对应内容
        val reEncoded = json.encodeToString(assistant)
        assertTrue("Re-encoded JSON must retain thought templates", reEncoded.contains("desireCustomThoughtTemplates"))
        assertTrue("Re-encoded JSON must retain thought content", reEncoded.contains("想念她"))
        assertFalse("Re-encoded JSON must NOT contain desireCustomMonologueTemplates", reEncoded.contains("desireCustomMonologueTemplates"))
        assertFalse("Re-encoded JSON must NOT contain legacy monologue content", reEncoded.contains("好累"))

        // 3. 再次反序列化确认念头配置完好
        val restored = json.decodeFromString<Assistant>(reEncoded)
        assertEquals(listOf("想念她"), restored.desireCustomThoughtTemplates["attachment"])
    }
}
