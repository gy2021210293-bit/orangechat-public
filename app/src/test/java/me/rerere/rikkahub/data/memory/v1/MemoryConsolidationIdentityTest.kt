/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryConsolidationRunEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryDailyNoteEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEventChunkEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryConsolidationIdentityTest {

    private class TestIdentityDao : MemoryV1DaoStub() {
        val cards = mutableListOf<MemoryCardEntity>()
        val notes = mutableMapOf<String, MemoryDailyNoteEntity>()
        val runs = mutableMapOf<String, MemoryConsolidationRunEntity>()

        override suspend fun getAlwaysInjectSelfCards(ownerType: String, ownerId: String): List<MemoryCardEntity> {
            return cards.filter {
                it.ownerType == ownerType &&
                    it.ownerId == ownerId &&
                    it.category == MemoryConstants.CATEGORY_SELF &&
                    it.status == MemoryConstants.STATUS_ACTIVE &&
                    it.alwaysInject
            }
        }

        override suspend fun getCardsByStatus(ownerType: String, ownerId: String, status: String): List<MemoryCardEntity> {
            return cards.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.status == status }
        }

        override suspend fun insertOrUpdateDailyNote(note: MemoryDailyNoteEntity) {
            notes["${note.ownerId}_${note.memoryDate}"] = note
        }

        override suspend fun getDailyNote(ownerId: String, memoryDate: String): MemoryDailyNoteEntity? {
            return notes["${ownerId}_${memoryDate}"]
        }

        override suspend fun insertOrReplaceRun(run: MemoryConsolidationRunEntity) {
            runs["${run.ownerId}_${run.memoryDate}"] = run
        }

        override suspend fun getRun(ownerId: String, memoryDate: String): MemoryConsolidationRunEntity? {
            return runs["${ownerId}_${memoryDate}"]
        }

        override suspend fun deleteEventChunksByDateRange(ownerType: String, ownerId: String, startMs: Long, endMs: Long) {}
        override suspend fun insertEventChunks(chunks: List<MemoryEventChunkEntity>) {}
    }

    private class TestableConsolidationService(
        val dao: TestIdentityDao,
        settings: Settings,
        var mockedDialogue: List<CleanedDialogueMessage> = emptyList(),
    ) : MemoryConsolidationService(
        repository = MemoryRepositoryV1(database = null, dao = dao),
        testSettingsProvider = { settings },
    ) {
        data class CapturedCall(
            val callType: String,
            val systemPrompt: String,
            val userPrompt: String,
            val temperature: Float,
        )

        val capturedCalls = mutableListOf<CapturedCall>()

        override suspend fun loadAndCleanMessages(
            assistantId: Uuid,
            startMs: Long,
            endMs: Long,
        ): List<CleanedDialogueMessage> = mockedDialogue

        override suspend fun generateModelText(
            providerSetting: ProviderSetting,
            model: Model,
            systemPrompt: String,
            userPrompt: String,
            temperature: Float,
            reasoningLevel: ReasoningLevel,
        ): String {
            val isDailyNote = userPrompt.contains("\"daily_note\"")
            capturedCalls.add(
                CapturedCall(
                    callType = if (isDailyNote) "DAILY_NOTE" else "STRUCTURED",
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    temperature = temperature,
                )
            )

            return if (isDailyNote) {
                """
                {
                  "daily_note": {
                    "summary": "今天我和小雨聊了许多关于未来的想法，感到十分踏实。",
                    "emotional_thread": "我感到平静与温暖。"
                  }
                }
                """.trimIndent()
            } else {
                """
                {
                  "timelines": [],
                  "patches": [
                    { "action": "NOOP", "reason": "保持现状" }
                  ],
                  "relations": []
                }
                """.trimIndent()
            }
        }
    }

    @Test
    fun `current assistant system prompt and stable self enter both nightly calls with post positioned task constraints`() = runBlocking {
        val dao = TestIdentityDao()
        val assistantId = Uuid.random()
        val persona = "你叫小橘，说话习惯带喵喵，性格温柔害羞。"
        val assistant = Assistant(
            id = assistantId,
            name = "小橘",
            systemPrompt = persona,
            memoryMode = MemoryMode.LOCAL_V1,
        )

        // 注入属于该助手的 ACTIVE 稳定自我卡片
        val selfCard = MemoryCardEntity(
            id = "self_card_1",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            category = MemoryConstants.CATEGORY_SELF,
            status = MemoryConstants.STATUS_ACTIVE,
            alwaysInject = true,
            content = "我习惯在小雨工作疲倦时，安静地陪在身边不打扰她。",
            factKey = "self.comfort.quiet_presence",
            revision = 1,
            createdAt = 1000L,
            updatedAt = 1000L,
            createdBy = MemoryConstants.ACTOR_MANUAL,
            updatedBy = MemoryConstants.ACTOR_MANUAL,
        )
        dao.cards.add(selfCard)

        val model = Model(id = Uuid.random(), displayName = "TestModel", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "TestProvider", models = listOf(model))
        val settings = Settings(assistants = listOf(assistant), providers = listOf(provider), chatModelId = model.id)

        val service = TestableConsolidationService(dao, settings)
        service.mockedDialogue = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "今天工作好累啊小橘", 1000L),
            CleanedDialogueMessage("c1", "m2", "ASSISTANT", "辛苦啦喵~给你倒杯水", 2000L),
        )

        val result = service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = "2026-09-13",
            force = true,
        )

        assertTrue("Consolidation should succeed, result: $result", result is ConsolidationServiceResult.Success)
        assertEquals(2, service.capturedCalls.size)

        // 1. 验证写日记调用 (DAILY_NOTE)
        val noteCall = service.capturedCalls[0]
        assertEquals("DAILY_NOTE", noteCall.callType)
        assertTrue("Daily note system prompt must contain assistant persona", noteCall.systemPrompt.contains(persona))
        assertTrue("Daily note system prompt must contain <stable_self>", noteCall.systemPrompt.contains("<stable_self>"))
        assertTrue("Daily note system prompt must contain self card content", noteCall.systemPrompt.contains(selfCard.content))

        // 验证任务约束后置且包含高优先级真实性要求
        val notePersonaIdx = noteCall.systemPrompt.indexOf("【当前助手人设与身份设定】")
        val noteConstraintIdx = noteCall.systemPrompt.indexOf("【日记任务定义与硬性约束")
        assertTrue("Task constraints must be placed after persona", noteConstraintIdx > notePersonaIdx)
        assertTrue("Daily note must require grounded in real dialogue", noteCall.systemPrompt.contains("日记必须基于今日真实对话"))
        assertTrue("Daily note must require first-person", noteCall.systemPrompt.contains("第一人称“我”"))

        // 2. 验证结构化整理调用 (STRUCTURED)
        val structCall = service.capturedCalls[1]
        assertEquals("STRUCTURED", structCall.callType)
        assertTrue("Structured system prompt must contain assistant persona", structCall.systemPrompt.contains(persona))
        assertTrue("Structured system prompt must contain <stable_self>", structCall.systemPrompt.contains("<stable_self>"))
        assertTrue("Structured system prompt must contain self card content", structCall.systemPrompt.contains(selfCard.content))

        // 验证任务约束后置且明确“日记不能当作证据”
        val structPersonaIdx = structCall.systemPrompt.indexOf("【当前助手人设与身份设定】")
        val structConstraintIdx = structCall.systemPrompt.indexOf("【整理任务约束 (最高优先级)】")
        assertTrue("Structured task constraints must be placed after persona", structConstraintIdx > structPersonaIdx)
        assertTrue("Structured prompt must explicitly specify diary cannot be evidence", structCall.systemPrompt.contains("日记不能当作证据"))
        assertTrue("Structured prompt must require grounded in real dialogue", structCall.systemPrompt.contains("日记必须基于真实对话"))
        assertTrue("Structured user prompt must also enforce diary is not evidence", structCall.userPrompt.contains("不得将日记中的措辞、推断或情绪当作证据"))
    }

    @Test
    fun `nightly consolidation never includes chat ephemeral context`() = runBlocking {
        val dao = TestIdentityDao()
        val assistantId = Uuid.random()
        val assistant = Assistant(
            id = assistantId,
            name = "小橘",
            systemPrompt = "人设设定",
            memoryMode = MemoryMode.LOCAL_V1,
        )

        val model = Model(id = Uuid.random(), displayName = "TestModel", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "TestProvider", models = listOf(model))
        val settings = Settings(assistants = listOf(assistant), providers = listOf(provider), chatModelId = model.id)

        val service = TestableConsolidationService(dao, settings)
        service.mockedDialogue = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "你好", 1000L),
            CleanedDialogueMessage("c1", "m2", "ASSISTANT", "你好呀", 2000L),
        )

        service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            memoryDate = "2026-09-13",
            force = true,
        )

        for (call in service.capturedCalls) {
            val allPrompts = "${call.systemPrompt}\n${call.userPrompt}"
            // 严禁包含临时聊天专属内容
            assertFalse("Must not include code block prompt", allPrompts.contains("Code Block Rules"))
            assertFalse("Must not include write_files tool prompt", allPrompts.contains("write_files"))
            assertFalse("Must not include dynamic context tag", allPrompts.contains("<dynamic_context>"))
            assertFalse("Must not include plugin context tag", allPrompts.contains("<plugin-context>"))
            assertFalse("Must not include [JUMP] tag policy", allPrompts.contains("[JUMP]"))
            assertFalse("Must not include [SKIP] tag policy", allPrompts.contains("[SKIP]"))
            assertFalse("Must not include Message Bubbles split policy", allPrompts.contains("## Message Bubbles"))
        }
    }

    @Test
    fun `different assistants use only their own identity prompts`() = runBlocking {
        val dao = TestIdentityDao()
        val assistantAId = Uuid.random()
        val personaA = "我是助手A，严肃认真一丝不苟。"
        val assistantA = Assistant(
            id = assistantAId,
            name = "助手A",
            systemPrompt = personaA,
            memoryMode = MemoryMode.LOCAL_V1,
        )

        val assistantBId = Uuid.random()
        val personaB = "我是助手B，活泼可爱最爱吃蛋糕。"
        val assistantB = Assistant(
            id = assistantBId,
            name = "助手B",
            systemPrompt = personaB,
            memoryMode = MemoryMode.LOCAL_V1,
        )

        val selfCardA = MemoryCardEntity(
            id = "self_A",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantAId.toString(),
            category = MemoryConstants.CATEGORY_SELF,
            status = MemoryConstants.STATUS_ACTIVE,
            alwaysInject = true,
            content = "我对逻辑严密性有极高要求。",
            factKey = "self.style.rigorous",
            revision = 1,
            createdAt = 1000L,
            updatedAt = 1000L,
            createdBy = MemoryConstants.ACTOR_MANUAL,
            updatedBy = MemoryConstants.ACTOR_MANUAL,
        )
        val selfCardB = MemoryCardEntity(
            id = "self_B",
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantBId.toString(),
            category = MemoryConstants.CATEGORY_SELF,
            status = MemoryConstants.STATUS_ACTIVE,
            alwaysInject = true,
            content = "我喜欢在阳光下吃草莓蛋糕。",
            factKey = "self.preference.strawberry_cake",
            revision = 1,
            createdAt = 1000L,
            updatedAt = 1000L,
            createdBy = MemoryConstants.ACTOR_MANUAL,
            updatedBy = MemoryConstants.ACTOR_MANUAL,
        )
        dao.cards.add(selfCardA)
        dao.cards.add(selfCardB)

        val model = Model(id = Uuid.random(), displayName = "TestModel", modelId = "gpt-4o")
        val provider = ProviderSetting.OpenAI(id = Uuid.random(), name = "TestProvider", models = listOf(model))
        val settings = Settings(assistants = listOf(assistantA, assistantB), providers = listOf(provider), chatModelId = model.id)

        val service = TestableConsolidationService(dao, settings)
        service.mockedDialogue = listOf(
            CleanedDialogueMessage("c1", "m1", "USER", "今天天气真好", 1000L),
            CleanedDialogueMessage("c1", "m2", "ASSISTANT", "是啊", 2000L),
        )

        // 1. 对 Assistant A 运行整理
        service.capturedCalls.clear()
        service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantAId.toString(),
            memoryDate = "2026-09-13",
            force = true,
        )

        assertEquals(2, service.capturedCalls.size)
        for (call in service.capturedCalls) {
            assertTrue("Assistant A prompt must contain persona A", call.systemPrompt.contains(personaA))
            assertTrue("Assistant A prompt must contain self card A", call.systemPrompt.contains(selfCardA.content))
            assertFalse("Assistant A prompt must NOT contain persona B", call.systemPrompt.contains(personaB))
            assertFalse("Assistant A prompt must NOT contain self card B", call.systemPrompt.contains(selfCardB.content))
        }

        // 2. 对 Assistant B 运行整理
        service.capturedCalls.clear()
        service.consolidateDate(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantBId.toString(),
            memoryDate = "2026-09-13",
            force = true,
        )

        assertEquals(2, service.capturedCalls.size)
        for (call in service.capturedCalls) {
            assertTrue("Assistant B prompt must contain persona B", call.systemPrompt.contains(personaB))
            assertTrue("Assistant B prompt must contain self card B", call.systemPrompt.contains(selfCardB.content))
            assertFalse("Assistant B prompt must NOT contain persona A", call.systemPrompt.contains(personaA))
            assertFalse("Assistant B prompt must NOT contain self card A", call.systemPrompt.contains(selfCardA.content))
        }
    }
}
