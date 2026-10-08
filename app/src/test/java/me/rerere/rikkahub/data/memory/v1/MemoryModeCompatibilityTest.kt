/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryMode
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryModeCompatibilityTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun defaultAssistant_isOff() {
        val assistant = Assistant()
        assertEquals(null, assistant.memoryMode)
        assertEquals(MemoryMode.OFF, assistant.effectiveMemoryMode)
    }

    @Test
    fun legacyEnableMemory_mapsToLegacySimple() {
        val assistant = Assistant(enableMemory = true)
        assertEquals(null, assistant.memoryMode)
        assertEquals(MemoryMode.LEGACY_SIMPLE, assistant.effectiveMemoryMode)
    }

    @Test
    fun legacyExternalMemory_mapsToExternal() {
        val assistant = Assistant(externalMemoryIds = setOf(Uuid.random()))
        assertEquals(null, assistant.memoryMode)
        assertEquals(MemoryMode.EXTERNAL, assistant.effectiveMemoryMode)
    }

    @Test
    fun explicitOff_withEnableMemoryTrue_isOff() {
        val assistant = Assistant(
            enableMemory = true,
            memoryMode = MemoryMode.OFF,
        )
        assertEquals(MemoryMode.OFF, assistant.memoryMode)
        assertEquals(MemoryMode.OFF, assistant.effectiveMemoryMode)
    }

    @Test
    fun explicitLocalV1_takesPrecedence() {
        val assistant = Assistant(
            enableMemory = true,
            externalMemoryIds = setOf(Uuid.random()),
            memoryMode = MemoryMode.LOCAL_V1,
        )
        assertEquals(MemoryMode.LOCAL_V1, assistant.memoryMode)
        assertEquals(MemoryMode.LOCAL_V1, assistant.effectiveMemoryMode)
    }

    @Test
    fun legacyJsonDeserialization_withoutMemoryMode_preservesLegacyBehavior() {
        val legacyJson = """
            {
                "id": "00000000-0000-0000-0000-000000000001",
                "name": "旧助手",
                "enableMemory": true
            }
        """.trimIndent()

        val decoded = json.decodeFromString<Assistant>(legacyJson)
        assertEquals(null, decoded.memoryMode)
        assertEquals(MemoryMode.LEGACY_SIMPLE, decoded.effectiveMemoryMode)
    }

    @Test
    fun newJsonDeserialization_withLocalV1_decodesCorrectly() {
        val v1Json = """
            {
                "id": "00000000-0000-0000-0000-000000000002",
                "name": "V1助手",
                "memoryMode": "LOCAL_V1"
            }
        """.trimIndent()

        val decoded = json.decodeFromString<Assistant>(v1Json)
        assertEquals(MemoryMode.LOCAL_V1, decoded.memoryMode)
        assertEquals(MemoryMode.LOCAL_V1, decoded.effectiveMemoryMode)
    }

    @Test
    fun systemPromptBuilder_isolatesLegacyMemories_underOffOrLocalV1() {
        val testMemories = listOf(me.rerere.rikkahub.data.model.AssistantMemory(id = 1, content = "这是一条绝密旧记忆"))
        val dummyModel = me.rerere.ai.provider.Model(
            id = Uuid.random(),
            displayName = "Test",
            modelId = "test",
        )
        val dummySettings = me.rerere.rikkahub.data.datastore.Settings()

        val offAssistant = Assistant(
            enableMemory = true,
            memoryMode = MemoryMode.OFF,
        )
        val promptOff = me.rerere.rikkahub.data.ai.SystemPromptBuilder.build(
            assistant = offAssistant,
            conversationSystemPrompt = null,
            memories = testMemories,
            tools = emptyList(),
            model = dummyModel,
            settings = dummySettings,
            promptMessages = emptyList(),
        )
        org.junit.Assert.assertFalse("OFF 模式下绝不能注入旧记忆", promptOff.contains("这是一条绝密旧记忆"))

        val v1Assistant = Assistant(
            enableMemory = true,
            memoryMode = MemoryMode.LOCAL_V1,
        )
        val promptV1 = me.rerere.rikkahub.data.ai.SystemPromptBuilder.build(
            assistant = v1Assistant,
            conversationSystemPrompt = null,
            memories = testMemories,
            tools = emptyList(),
            model = dummyModel,
            settings = dummySettings,
            promptMessages = emptyList(),
        )
        org.junit.Assert.assertFalse("LOCAL_V1 模式下绝不能注入旧记忆", promptV1.contains("这是一条绝密旧记忆"))

        val legacyAssistant = Assistant(
            enableMemory = true,
            memoryMode = MemoryMode.LEGACY_SIMPLE,
        )
        val promptLegacy = me.rerere.rikkahub.data.ai.SystemPromptBuilder.build(
            assistant = legacyAssistant,
            conversationSystemPrompt = null,
            memories = testMemories,
            tools = emptyList(),
            model = dummyModel,
            settings = dummySettings,
            promptMessages = emptyList(),
        )
        org.junit.Assert.assertTrue("LEGACY_SIMPLE 模式下应正常注入旧记忆", promptLegacy.contains("这是一条绝密旧记忆"))
    }

    @Test
    fun legacyDualMode_mapsToLegacyBoth_preservingBothBehaviors() {
        val extId = Uuid.random()
        val assistant = Assistant(
            enableMemory = true,
            externalMemoryIds = setOf(extId)
        )
        assertEquals(null, assistant.memoryMode)
        assertEquals(MemoryMode.LEGACY_BOTH, assistant.effectiveMemoryMode)
        org.junit.Assert.assertTrue("旧版双开启应保留简单记忆", assistant.isLegacySimpleMemoryEnabled)
        org.junit.Assert.assertTrue("旧版双开启应保留外置记忆", assistant.isExternalMemoryEnabled)
    }

    @Test
    fun legacyDualMode_jsonDeserialization_preservesBothBehaviors() {
        val legacyDualJson = """
            {
                "id": "00000000-0000-0000-0000-000000000003",
                "name": "旧双模式助手",
                "enableMemory": true,
                "externalMemoryIds": ["11111111-1111-1111-1111-111111111111"]
            }
        """.trimIndent()

        val decoded = json.decodeFromString<Assistant>(legacyDualJson)
        assertEquals(null, decoded.memoryMode)
        assertEquals(MemoryMode.LEGACY_BOTH, decoded.effectiveMemoryMode)
        org.junit.Assert.assertTrue(decoded.isLegacySimpleMemoryEnabled)
        org.junit.Assert.assertTrue(decoded.isExternalMemoryEnabled)
    }

    @Test
    fun localV1_completelyShutsDownBothOldSimpleAndSupabase() {
        val extId = Uuid.random()
        val assistant = Assistant(
            enableMemory = true,
            externalMemoryIds = setOf(extId),
            memoryMode = MemoryMode.LOCAL_V1
        )
        assertEquals(MemoryMode.LOCAL_V1, assistant.effectiveMemoryMode)
        org.junit.Assert.assertFalse("LOCAL_V1 绝不能开启旧简单记忆", assistant.isLegacySimpleMemoryEnabled)
        org.junit.Assert.assertFalse("LOCAL_V1 绝不能开启 Supabase 外置记忆", assistant.isExternalMemoryEnabled)
    }
}

