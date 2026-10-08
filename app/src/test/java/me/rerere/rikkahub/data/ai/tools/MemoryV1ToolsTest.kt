/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.memory.v1.MemoryConstants
import me.rerere.rikkahub.data.memory.v1.MemoryEmbeddingService
import me.rerere.rikkahub.data.memory.v1.MemoryRepositoryV1
import me.rerere.rikkahub.data.memory.v1.RevisionConflictException
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryRevisionEntity
import me.rerere.rikkahub.data.memory.v1.db.MemorySourceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryMode
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryV1ToolsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var repository: InMemoryTestMemoryRepository
    private val assistantId = Uuid.random()
    private lateinit var assistant: Assistant

    @Before
    fun setUp() {
        repository = InMemoryTestMemoryRepository()
        assistant = Assistant(
            id = assistantId,
            name = "测试助手",
            memoryMode = MemoryMode.LOCAL_V1,
        )
    }

    private fun getTools(targetAssistant: Assistant = assistant) = buildMemoryV1Tools(
        repository = repository,
        assistant = targetAssistant,
        callerConversationId = "conv-123",
        json = json,
    )

    private fun parseResult(parts: List<UIMessagePart>): JsonObject {
        val text = (parts.first() as UIMessagePart.Text).text
        return json.parseToJsonElement(text).jsonObject
    }

    // ==========================================
    // 1. memory_search 测试
    // ==========================================

    @Test
    fun search_emptyQuery_returnsError() = runBlocking {
        val tools = getTools()
        val searchTool = tools.first { it.name == "memory_search" }

        val res = parseResult(searchTool.execute(json.parseToJsonElement("""{"query": ""}""")))
        assertEquals(false, res["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("MISSING_QUERY", res["error"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun selfCategory_canBeCreatedAndSearched() = runBlocking {
        val tools = getTools()
        val manageTool = tools.first { it.name == "memory_manage" }
        val searchTool = tools.first { it.name == "memory_search" }

        val createResult = parseResult(manageTool.execute(json.parseToJsonElement("""
            {
              "action": "create",
              "content": "我想记住这次和小雨认真讨论未来计划时的期待。",
              "category": "self"
            }
        """)))
        assertEquals(true, createResult["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(MemoryConstants.CATEGORY_SELF, createResult["card"]?.jsonObject?.get("category")?.jsonPrimitive?.contentOrNull)

        val searchResult = parseResult(searchTool.execute(json.parseToJsonElement("""{"query": "未来计划"}""")))
        assertEquals(1, searchResult["count"]?.jsonPrimitive?.intOrNull)
        assertEquals(MemoryConstants.CATEGORY_SELF, searchResult["memories"]?.jsonArray?.first()?.jsonObject?.get("category")?.jsonPrimitive?.contentOrNull)
        assertTrue(searchTool.description.contains("我的感受"))
        assertTrue(manageTool.description.contains("第一人称“我”"))
    }

    @Test
    fun search_findsMatchingCard() = runBlocking {
        repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "小雨喜欢喝无糖乌龙茶",
            category = MemoryConstants.CATEGORY_PREFERENCE,
            factKey = "person.tea",
        )

        val tools = getTools()
        val searchTool = tools.first { it.name == "memory_search" }

        val res = parseResult(searchTool.execute(json.parseToJsonElement("""{"query": "乌龙茶"}""")))
        assertEquals(true, res["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(1, res["count"]?.jsonPrimitive?.intOrNull)
        val card = res["memories"]!!.jsonArray[0].jsonObject
        assertEquals("小雨喜欢喝无糖乌龙茶", card["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals(MemoryConstants.STATUS_ACTIVE, card["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, card["revision"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun search_respectsLimit() = runBlocking {
        for (i in 1..10) {
            repository.createCard(
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = assistantId.toString(),
                content = "小雨的兴趣爱好编号 $i",
            )
        }

        val tools = getTools()
        val searchTool = tools.first { it.name == "memory_search" }

        val res = parseResult(searchTool.execute(json.parseToJsonElement("""{"query": "爱好", "limit": 3}""")))
        assertEquals(true, res["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(3, res["count"]?.jsonPrimitive?.intOrNull)
        assertEquals(3, res["memories"]!!.jsonArray.size)
    }

    @Test
    fun search_filtersArchivedByDefault_andIncludesWhenRequested() = runBlocking {
        val card = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "小雨曾经住在旧地址",
        )
        repository.archiveCard(card.id, expectedRevision = 1)

        val tools = getTools()
        val searchTool = tools.first { it.name == "memory_search" }

        // 默认 include_archived = false
        val resDefault = parseResult(searchTool.execute(json.parseToJsonElement("""{"query": "旧地址"}""")))
        assertEquals(0, resDefault["count"]?.jsonPrimitive?.intOrNull)

        // 开启 include_archived = true
        val resIncluded = parseResult(
            searchTool.execute(json.parseToJsonElement("""{"query": "旧地址", "include_archived": true}"""))
        )
        assertEquals(1, resIncluded["count"]?.jsonPrimitive?.intOrNull)
        val found = resIncluded["memories"]!!.jsonArray[0].jsonObject
        assertEquals(MemoryConstants.STATUS_ARCHIVED, found["status"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun search_marksHasSourceWhenSourceExists() = runBlocking {
        repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "带证据来源的记忆",
            sourceConversationId = "conv-abc",
            sourceMessageId = "msg-123",
        )

        val tools = getTools()
        val searchTool = tools.first { it.name == "memory_search" }

        val res = parseResult(searchTool.execute(json.parseToJsonElement("""{"query": "证据"}""")))
        assertEquals(1, res["count"]?.jsonPrimitive?.intOrNull)
        val card = res["memories"]!!.jsonArray[0].jsonObject
        assertEquals(true, card["has_source"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun search_recordsCardRecallsForHitCards_includingArchived() = runBlocking {
        val activeCard = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "小雨喜欢苹果",
        )
        val archivedCard = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "小雨以前也喜欢苹果",
        )
        repository.archiveCard(archivedCard.id, expectedRevision = 1)

        val tools = getTools()
        val searchTool = tools.first { it.name == "memory_search" }

        val res = parseResult(
            searchTool.execute(json.parseToJsonElement("""{"query": "苹果", "include_archived": true}"""))
        )
        assertEquals(2, res["count"]?.jsonPrimitive?.intOrNull)

        assertTrue(repository.recalledCardIds.contains(activeCard.id))
        assertTrue(repository.recalledCardIds.contains(archivedCard.id))

        val updatedArchived = repository.getCardById(archivedCard.id)
        assertNotNull(updatedArchived?.lastRecalledAt)
        assertEquals(1, updatedArchived?.hitCount)
    }

    // ==========================================
    // 2. 搜索后更新（Search then update）
    // ==========================================

    @Test
    fun searchThenUpdate_succeedsAndBumpsRevision() = runBlocking {
        val card = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "小雨周六通常早起跑步",
            category = MemoryConstants.CATEGORY_PREFERENCE,
        )

        val tools = getTools()
        val searchTool = tools.first { it.name == "memory_search" }
        val manageTool = tools.first { it.name == "memory_manage" }

        // 1. 搜索
        val searchRes = parseResult(searchTool.execute(json.parseToJsonElement("""{"query": "跑步"}""")))
        val cardFound = searchRes["memories"]!!.jsonArray[0].jsonObject
        val cardId = cardFound["id"]!!.jsonPrimitive.content
        val currentRev = cardFound["revision"]!!.jsonPrimitive.intOrNull!!
        assertEquals(1, currentRev)

        // 2. 更新
        val updatePayload = """
            {
                "action": "update",
                "id": "$cardId",
                "expected_revision": $currentRev,
                "content": "小雨周六通常在奥森公园跑步10公里",
                "reason": "小雨补充了具体地点和距离"
            }
        """.trimIndent()
        val updateRes = parseResult(manageTool.execute(json.parseToJsonElement(updatePayload)))
        assertEquals(true, updateRes["success"]?.jsonPrimitive?.booleanOrNull)
        val updatedCard = updateRes["card"]!!.jsonObject
        assertEquals(2, updatedCard["revision"]?.jsonPrimitive?.intOrNull)
        assertEquals("小雨周六通常在奥森公园跑步10公里", updatedCard["content"]?.jsonPrimitive?.contentOrNull)

        // 3. 再次搜索验证最新状态
        val verifyRes = parseResult(searchTool.execute(json.parseToJsonElement("""{"query": "奥森公园"}""")))
        assertEquals(1, verifyRes["count"]?.jsonPrimitive?.intOrNull)
        assertEquals(2, verifyRes["memories"]!!.jsonArray[0].jsonObject["revision"]?.jsonPrimitive?.intOrNull)
    }

    // ==========================================
    // 3. 乐观锁冲突（Revision conflict）
    // ==========================================

    @Test
    fun update_withStaleRevision_returnsRevisionConflict() = runBlocking {
        val card = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "原始记忆",
        )
        // 先手动更新一次使版本号变为 2
        repository.updateCard(card.id, expectedRevision = 1, content = "第一次更新")

        val tools = getTools()
        val manageTool = tools.first { it.name == "memory_manage" }

        // 模型仍持有陈旧版本 1 尝试更新
        val payload = """
            {
                "action": "update",
                "id": "${card.id}",
                "expected_revision": 1,
                "content": "冲突更新"
            }
        """.trimIndent()
        val res = parseResult(manageTool.execute(json.parseToJsonElement(payload)))
        assertEquals(false, res["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("REVISION_CONFLICT", res["error"]?.jsonPrimitive?.contentOrNull)
        assertTrue(res["message"]!!.jsonPrimitive.content.contains("Revision conflict"))
    }

    @Test
    fun supersede_withStaleRevision_returnsRevisionConflict() = runBlocking {
        val card = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "旧事实",
        )

        val tools = getTools()
        val manageTool = tools.first { it.name == "memory_manage" }

        val payload = """
            {
                "action": "supersede",
                "id": "${card.id}",
                "expected_revision": 999,
                "content": "新事实"
            }
        """.trimIndent()
        val res = parseResult(manageTool.execute(json.parseToJsonElement(payload)))
        assertEquals(false, res["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("REVISION_CONFLICT", res["error"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun archive_withStaleRevision_returnsRevisionConflict() = runBlocking {
        val card = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "要归档的记忆",
        )

        val tools = getTools()
        val manageTool = tools.first { it.name == "memory_manage" }

        val payload = """
            {
                "action": "archive",
                "id": "${card.id}",
                "expected_revision": 5
            }
        """.trimIndent()
        val res = parseResult(manageTool.execute(json.parseToJsonElement(payload)))
        assertEquals(false, res["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("REVISION_CONFLICT", res["error"]?.jsonPrimitive?.contentOrNull)
    }

    // ==========================================
    // 4. 软归档与恢复（Archive & Restore）
    // ==========================================

    @Test
    fun archiveAndRestore_lifecycle() = runBlocking {
        val card = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "准备软归档的事实",
        )

        val tools = getTools()
        val manageTool = tools.first { it.name == "memory_manage" }

        // 1. 归档
        val archivePayload = """
            {
                "action": "archive",
                "id": "${card.id}",
                "expected_revision": 1,
                "reason": "不再需要"
            }
        """.trimIndent()
        val archiveRes = parseResult(manageTool.execute(json.parseToJsonElement(archivePayload)))
        assertEquals(true, archiveRes["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(MemoryConstants.STATUS_ARCHIVED, archiveRes["card"]!!.jsonObject["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, archiveRes["card"]!!.jsonObject["revision"]?.jsonPrimitive?.intOrNull)

        // 2. 重复归档应幂等成功
        val repeatArchiveRes = parseResult(manageTool.execute(json.parseToJsonElement("""
            {
                "action": "archive",
                "id": "${card.id}",
                "expected_revision": 2
            }
        """.trimIndent())))
        assertEquals(true, repeatArchiveRes["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(MemoryConstants.STATUS_ARCHIVED, repeatArchiveRes["card"]!!.jsonObject["status"]?.jsonPrimitive?.contentOrNull)

        // 3. 恢复
        val restorePayload = """
            {
                "action": "restore",
                "id": "${card.id}",
                "expected_revision": 2,
                "reason": "重新启用"
            }
        """.trimIndent()
        val restoreRes = parseResult(manageTool.execute(json.parseToJsonElement(restorePayload)))
        assertEquals(true, restoreRes["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(MemoryConstants.STATUS_ACTIVE, restoreRes["card"]!!.jsonObject["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(3, restoreRes["card"]!!.jsonObject["revision"]?.jsonPrimitive?.intOrNull)
    }

    // ==========================================
    // 5. 事实替代（Supersede）
    // ==========================================

    @Test
    fun supersede_createsNewActiveCard_andMarksOldCardSuperseded() = runBlocking {
        val oldCard = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "小雨目前住在杭州",
            category = MemoryConstants.CATEGORY_PROFILE,
            factKey = "person.residence.city",
        )

        val tools = getTools()
        val manageTool = tools.first { it.name == "memory_manage" }

        val supersedePayload = """
            {
                "action": "supersede",
                "id": "${oldCard.id}",
                "expected_revision": 1,
                "content": "小雨已搬家至上海",
                "reason": "小雨告知已在上海定居"
            }
        """.trimIndent()
        val res = parseResult(manageTool.execute(json.parseToJsonElement(supersedePayload)))
        assertEquals(true, res["success"]?.jsonPrimitive?.booleanOrNull)

        val newCardObj = res["new_card"]!!.jsonObject
        val oldCardObj = res["superseded_card"]!!.jsonObject

        assertEquals(MemoryConstants.STATUS_ACTIVE, newCardObj["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("小雨已搬家至上海", newCardObj["content"]?.jsonPrimitive?.contentOrNull)
        assertEquals("person.residence.city", newCardObj["fact_key"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, newCardObj["revision"]?.jsonPrimitive?.intOrNull)

        assertEquals(MemoryConstants.STATUS_SUPERSEDED, oldCardObj["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, oldCardObj["revision"]?.jsonPrimitive?.intOrNull)

        // 校验老卡片在数据库中的真实状态与指向
        val dbOldCard = repository.getCardById(oldCard.id)
        assertNotNull(dbOldCard)
        assertEquals(MemoryConstants.STATUS_SUPERSEDED, dbOldCard!!.status)
        assertEquals(newCardObj["id"]!!.jsonPrimitive.content, dbOldCard.supersededBy)
        assertNotNull(dbOldCard.validTo)
    }

    // ==========================================
    // 6. 租户与越权 ID 保护（Unauthorized ID）
    // ==========================================

    @Test
    fun unauthorizedAccess_rejectedWhenOperatingOnAnotherAssistantMemory() = runBlocking {
        val otherAssistantId = Uuid.random()
        val otherCard = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = otherAssistantId.toString(),
            content = "另一个助手的私有记忆",
        )

        val tools = getTools() // 使用当前 assistant
        val manageTool = tools.first { it.name == "memory_manage" }

        // 尝试 update
        val updateRes = parseResult(manageTool.execute(json.parseToJsonElement("""
            {
                "action": "update",
                "id": "${otherCard.id}",
                "expected_revision": 1,
                "content": "试图越权篡改"
            }
        """.trimIndent())))
        assertEquals(false, updateRes["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("UNAUTHORIZED", updateRes["error"]?.jsonPrimitive?.contentOrNull)

        // 尝试 supersede
        val supersedeRes = parseResult(manageTool.execute(json.parseToJsonElement("""
            {
                "action": "supersede",
                "id": "${otherCard.id}",
                "expected_revision": 1,
                "content": "试图越权替代"
            }
        """.trimIndent())))
        assertEquals(false, supersedeRes["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("UNAUTHORIZED", supersedeRes["error"]?.jsonPrimitive?.contentOrNull)

        // 尝试 archive
        val archiveRes = parseResult(manageTool.execute(json.parseToJsonElement("""
            {
                "action": "archive",
                "id": "${otherCard.id}",
                "expected_revision": 1
            }
        """.trimIndent())))
        assertEquals(false, archiveRes["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("UNAUTHORIZED", archiveRes["error"]?.jsonPrimitive?.contentOrNull)
    }

    // ==========================================
    // 7. 非法输入与禁止操作
    // ==========================================

    @Test
    fun permanentDelete_isStrictlyForbidden() = runBlocking {
        val tools = getTools()
        val manageTool = tools.first { it.name == "memory_manage" }

        listOf("delete", "drop", "remove", "DELETE").forEach { forbidAction ->
            val payload = """
                {
                    "action": "$forbidAction",
                    "id": "uuid-123"
                }
            """.trimIndent()
            val res = parseResult(manageTool.execute(json.parseToJsonElement(payload)))
            assertEquals(false, res["success"]?.jsonPrimitive?.booleanOrNull)
            assertEquals("PERMANENT_DELETE_FORBIDDEN", res["error"]?.jsonPrimitive?.contentOrNull)
        }
    }

    @Test
    fun unknownAction_returnsError() = runBlocking {
        val tools = getTools()
        val manageTool = tools.first { it.name == "memory_manage" }

        val res = parseResult(manageTool.execute(json.parseToJsonElement("""{"action": "merge_cards"}""")))
        assertEquals(false, res["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("UNKNOWN_ACTION", res["error"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun missingRequiredFields_returnsStructuredError() = runBlocking {
        val tools = getTools()
        val manageTool = tools.first { it.name == "memory_manage" }

        // 缺少 action
        val noActionRes = parseResult(manageTool.execute(json.parseToJsonElement("""{"content": "abc"}""")))
        assertEquals("MISSING_ACTION", noActionRes["error"]?.jsonPrimitive?.contentOrNull)

        // create 缺少 content
        val createNoContentRes = parseResult(manageTool.execute(json.parseToJsonElement("""{"action": "create"}""")))
        assertEquals("MISSING_CONTENT", createNoContentRes["error"]?.jsonPrimitive?.contentOrNull)

        // update 缺少 id
        val updateNoIdRes = parseResult(manageTool.execute(json.parseToJsonElement("""{"action": "update", "content": "abc"}""")))
        assertEquals("MISSING_ID", updateNoIdRes["error"]?.jsonPrimitive?.contentOrNull)

        // update 缺少 revision
        val validCard = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "测试",
        )
        val updateNoRevRes = parseResult(manageTool.execute(json.parseToJsonElement("""
            {
                "action": "update",
                "id": "${validCard.id}",
                "content": "abc"
            }
        """.trimIndent())))
        assertEquals("MISSING_EXPECTED_REVISION", updateNoRevRes["error"]?.jsonPrimitive?.contentOrNull)
    }

    // ==========================================
    // 8. ToolSurfaceBuilder 互斥隔离测试
    // ==========================================

    // ==========================================
    // 8. ToolSurfaceBuilder 互斥隔离测试（真实实例化）
    // ==========================================

    @Test
    fun toolSurfaceBuilder_memoryToolsAreStrictlyMutuallyExclusive() = runBlocking {
        val fakeLegacyDao = object : MemoryDAO {
            override fun getMemoriesOfAssistantFlow(assistantId: String) = kotlinx.coroutines.flow.emptyFlow<List<MemoryEntity>>()
            override suspend fun getMemoriesOfAssistant(assistantId: String) = emptyList<MemoryEntity>()
            override fun getAllMemoriesFlow() = kotlinx.coroutines.flow.emptyFlow<List<MemoryEntity>>()
            override suspend fun getAllMemories() = emptyList<MemoryEntity>()
            override suspend fun getMemoryById(id: Int) = null
            override suspend fun insertMemory(memory: MemoryEntity) = 1L
            override suspend fun updateMemory(memory: MemoryEntity) {}
            override suspend fun deleteMemory(id: Int) {}
            override suspend fun deleteMemoriesOfAssistant(assistantId: String) {}
        }
        val legacyRepo = MemoryRepository(fakeLegacyDao)
        val surfaceBuilder = ToolSurfaceBuilder(
            memoryRepository = legacyRepo,
            memoryRepositoryV1 = repository,
            json = json,
        )

        // 1. LOCAL_V1 模式
        val v1Assistant = Assistant(
            id = Uuid.random(),
            name = "V1",
            memoryMode = MemoryMode.LOCAL_V1,
        )
        val v1Tools = surfaceBuilder.build(
            assistant = v1Assistant,
            settings = Settings(),
            invocationContext = ToolInvocationContext(callerConversationId = "conv-1"),
        )
        val v1ToolNames = v1Tools.map { it.name }
        assertTrue("LOCAL_V1 下必须暴露 memory_search", v1ToolNames.contains("memory_search"))
        assertTrue("LOCAL_V1 下必须暴露 memory_manage", v1ToolNames.contains("memory_manage"))
        assertFalse("LOCAL_V1 下绝不能暴露旧版 memory_tool", v1ToolNames.contains("memory_tool"))

        // 2. LEGACY_SIMPLE 模式
        val legacyAssistant = Assistant(
            id = Uuid.random(),
            name = "Legacy",
            enableMemory = true,
            memoryMode = MemoryMode.LEGACY_SIMPLE,
        )
        val legacyTools = surfaceBuilder.build(
            assistant = legacyAssistant,
            settings = Settings(),
            invocationContext = ToolInvocationContext(callerConversationId = "conv-1"),
        )
        val legacyToolNames = legacyTools.map { it.name }
        assertTrue("LEGACY_SIMPLE 下必须暴露 memory_tool", legacyToolNames.contains("memory_tool"))
        assertFalse("LEGACY_SIMPLE 下绝不能暴露 memory_search", legacyToolNames.contains("memory_search"))
        assertFalse("LEGACY_SIMPLE 下绝不能暴露 memory_manage", legacyToolNames.contains("memory_manage"))

        // 3. OFF 模式
        val offAssistant = Assistant(
            id = Uuid.random(),
            name = "Off",
            memoryMode = MemoryMode.OFF,
        )
        val offTools = surfaceBuilder.build(
            assistant = offAssistant,
            settings = Settings(),
            invocationContext = ToolInvocationContext(callerConversationId = "conv-1"),
        )
        val offToolNames = offTools.map { it.name }
        assertFalse("OFF 模式下绝不能暴露 memory_tool", offToolNames.contains("memory_tool"))
        assertFalse("OFF 模式下绝不能暴露 memory_search", offToolNames.contains("memory_search"))
        assertFalse("OFF 模式下绝不能暴露 memory_manage", offToolNames.contains("memory_manage"))
    }

    @Test
    fun toolSurfaceBuilder_passesEmbeddingServiceAndSettingsToMemoryV1Tools() = runBlocking {
        val modelUuid = Uuid.random()
        val testAssistant = Assistant(
            id = Uuid.random(),
            name = "Test Assistant",
            memoryMode = MemoryMode.LOCAL_V1,
            memoryEmbeddingModelId = modelUuid,
        )
        val settings = Settings(
            assistants = listOf(testAssistant),
        )
        val testEmbService = object : MemoryEmbeddingService(repository) {
            val enqueued = mutableListOf<Pair<String, Uuid>>()
            override fun enqueueSyncCard(
                card: MemoryCardEntity,
                embeddingModelId: Uuid,
                settings: Settings
            ): kotlinx.coroutines.Job {
                enqueued.add(card.id to embeddingModelId)
                return kotlinx.coroutines.Job().apply { complete() }
            }
        }
        val surfaceBuilder = ToolSurfaceBuilder(
            memoryRepositoryV1 = repository,
            memoryEmbeddingService = testEmbService,
            json = json,
        )
        val builtTools = surfaceBuilder.build(
            assistant = testAssistant,
            settings = settings,
            invocationContext = ToolInvocationContext(callerConversationId = "conv-surface-test"),
        )
        val manageTool = builtTools.first { it.name == "memory_manage" }
        val res = parseResult(manageTool.execute(json.parseToJsonElement("""
            {
                "action": "create",
                "content": "通过 ToolSurfaceBuilder 创建的记忆"
            }
        """.trimIndent())))
        assertEquals(true, res["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(1, testEmbService.enqueued.size)
        assertEquals(modelUuid, testEmbService.enqueued[0].second)
    }

    // ==========================================
    // 9. 回归测试：属性保留、权限与真实并发冲突
    // ==========================================

    @Test
    fun update_omittedFieldsPreserveOriginalValues() = runBlocking {
        val original = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "原始偏好内容",
            category = MemoryConstants.CATEGORY_PREFERENCE,
            confidence = 0.85f,
            importance = 0.9f,
            factKey = "user.diet",
        )

        val tools = getTools()
        val manageTool = tools.first { it.name == "memory_manage" }

        // update 仅提供 content，缺省 category/confidence/importance
        val res = parseResult(manageTool.execute(json.parseToJsonElement("""
            {
                "action": "update",
                "id": "${original.id}",
                "expected_revision": 1,
                "content": "修改后的偏好内容"
            }
        """.trimIndent())))

        assertEquals(true, res["success"]?.jsonPrimitive?.booleanOrNull)
        val updatedInRepo = repository.getCardById(original.id)
        assertNotNull(updatedInRepo)
        assertEquals("修改后的偏好内容", updatedInRepo!!.content)
        assertEquals(MemoryConstants.CATEGORY_PREFERENCE, updatedInRepo.category)
        assertEquals(0.85f, updatedInRepo.confidence, 0.001f)
        assertEquals(0.9f, updatedInRepo.importance, 0.001f)
        assertEquals("user.diet", updatedInRepo.factKey)
        assertEquals(2, updatedInRepo.revision)
    }

    @Test
    fun supersede_omittedFieldsInheritFromOldCard() = runBlocking {
        val oldCard = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "小雨喜欢每晚11点睡觉",
            category = MemoryConstants.CATEGORY_PROFILE,
            confidence = 0.75f,
            importance = 0.65f,
            factKey = "person.sleep",
        )

        val tools = getTools()
        val manageTool = tools.first { it.name == "memory_manage" }

        // supersede 仅提供 newContent，缺省 category/confidence/importance/fact_key
        val res = parseResult(manageTool.execute(json.parseToJsonElement("""
            {
                "action": "supersede",
                "id": "${oldCard.id}",
                "expected_revision": 1,
                "content": "小雨改为每晚10点睡觉"
            }
        """.trimIndent())))

        assertEquals(true, res["success"]?.jsonPrimitive?.booleanOrNull)
        val newCardJson = res["new_card"]?.jsonObject
        assertNotNull(newCardJson)
        val newCardId = newCardJson!!["id"]?.jsonPrimitive?.contentOrNull
        assertNotNull(newCardId)

        val newCardInRepo = repository.getCardById(newCardId!!)
        assertNotNull(newCardInRepo)
        assertEquals("小雨改为每晚10点睡觉", newCardInRepo!!.content)
        assertEquals(MemoryConstants.CATEGORY_PROFILE, newCardInRepo.category)
        assertEquals(0.75f, newCardInRepo.confidence, 0.001f)
        assertEquals(0.65f, newCardInRepo.importance, 0.001f)
        assertEquals("person.sleep", newCardInRepo.factKey)
        assertEquals(1, newCardInRepo.revision)

        val oldCardInRepo = repository.getCardById(oldCard.id)
        assertEquals(MemoryConstants.STATUS_SUPERSEDED, oldCardInRepo?.status)
    }

    @Test
    fun search_unauthorizedGlobalScope_isRejectedWhenGlobalDisabled() = runBlocking {
        val localOnlyAssistant = Assistant(
            id = assistantId,
            name = "私有助手",
            memoryMode = MemoryMode.LOCAL_V1,
            useGlobalMemory = false,
        )
        val tools = getTools(localOnlyAssistant)
        val searchTool = tools.first { it.name == "memory_search" }

        val res = parseResult(searchTool.execute(json.parseToJsonElement("""
            {
                "query": "乌龙茶",
                "scope": "global"
            }
        """.trimIndent())))

        assertEquals(false, res["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("UNAUTHORIZED_SCOPE", res["error"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun update_repositoryConcurrentConflict_returnsStructuredConflict() = runBlocking {
        val card = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "并发测试内容",
        )

        val conflictingRepo = object : InMemoryTestMemoryRepository() {
            override suspend fun getCardById(id: String): MemoryCardEntity? {
                return card.copy(revision = 1)
            }

            override suspend fun updateCard(
                id: String,
                expectedRevision: Int,
                content: String,
                category: String?,
                factKey: String?,
                confidence: Float?,
                importance: Float?,
                validFrom: Long?,
                validTo: Long?,
                actor: String,
                reason: String?,
                sourceConversationId: String?,
                sourceNodeId: String?,
                sourceMessageId: String?,
                sourceRole: String?,
                sourceKind: String,
                sourceHash: String?
            ): MemoryCardEntity {
                throw RevisionConflictException(id, expectedRevision = expectedRevision, actualRevision = 3)
            }
        }

        val tools = buildMemoryV1Tools(
            repository = conflictingRepo,
            assistant = assistant,
            callerConversationId = "conv-1",
            json = json,
        )
        val manageTool = tools.first { it.name == "memory_manage" }

        val res = parseResult(manageTool.execute(json.parseToJsonElement("""
            {
                "action": "update",
                "id": "${card.id}",
                "expected_revision": 1,
                "content": "试图更新"
            }
        """.trimIndent())))

        assertEquals(false, res["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("REVISION_CONFLICT", res["error"]?.jsonPrimitive?.contentOrNull)
        assertEquals(card.id, res["card_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, res["expected_revision"]?.jsonPrimitive?.intOrNull)
        assertEquals(3, res["current_revision"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun search_statusFilteringOccursBeforeLimit() = runBlocking {
        for (i in 1..5) {
            val archivedCard = repository.createCard(
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = assistantId.toString(),
                content = "橘瓣记忆归档测试项目 $i",
            )
            repository.archiveCard(archivedCard.id, expectedRevision = 1)
        }

        val active1 = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "橘瓣记忆有效卡片 A",
        )
        val active2 = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "橘瓣记忆有效卡片 B",
        )

        val tools = getTools()
        val searchTool = tools.first { it.name == "memory_search" }

        val res = parseResult(searchTool.execute(json.parseToJsonElement("""
            {
                "query": "橘瓣记忆",
                "limit": 3
            }
        """.trimIndent())))

        assertEquals(true, res["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(2, res["count"]?.jsonPrimitive?.intOrNull)
        val memories = res["memories"]!!.jsonArray
        val returnedIds = memories.map { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
        assertTrue("有效卡片 A 必须被返回，不能被已归档卡片挤占", returnedIds.contains(active1.id))
        assertTrue("有效卡片 B 必须被返回，不能被已归档卡片挤占", returnedIds.contains(active2.id))
    }

    @Test
    fun archive_conflict_returnsRevisionConflictResponse() = runBlocking {
        val card = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "小雨的旧习惯",
        )
        // Card is at revision 1. Try to archive with expected_revision = 99
        val tools = getTools()
        val manageTool = tools.first { it.name == "memory_manage" }

        val res = parseResult(manageTool.execute(json.parseToJsonElement("""
            {
                "action": "archive",
                "id": "${card.id}",
                "expected_revision": 99
            }
        """.trimIndent())))

        assertEquals(false, res["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("REVISION_CONFLICT", res["error"]?.jsonPrimitive?.contentOrNull)
        assertEquals(card.id, res["card_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals(99, res["expected_revision"]?.jsonPrimitive?.intOrNull)
        assertEquals(1, res["current_revision"]?.jsonPrimitive?.intOrNull)

        // 验证卡片依然是 ACTIVE 状态，数据未被破坏
        val currentCard = repository.getCardById(card.id)
        assertEquals(MemoryConstants.STATUS_ACTIVE, currentCard?.status)
    }

    @Test
    fun tools_withEmbeddingService_syncsGlobalCardsForMultipleAssistants() = runBlocking {
        val modelUuid1 = Uuid.random()
        val modelUuid2 = Uuid.random()
        val asstId2 = Uuid.random()

        val assistant1 = assistant.copy(
            useGlobalMemory = true,
            memoryEmbeddingModelId = modelUuid1,
        )
        val assistant2 = Assistant(
            id = asstId2,
            name = "助手2",
            memoryMode = MemoryMode.LOCAL_V1,
            memoryEmbeddingModelId = modelUuid2,
            useGlobalMemory = true,
        )

        val settings = Settings(
            assistants = listOf(assistant1, assistant2),
        )

        val testEmbService = object : MemoryEmbeddingService(repository) {
            val enqueued = mutableListOf<Pair<String, Uuid>>()
            val canceled = mutableListOf<String>()

            override fun enqueueSyncCard(
                card: MemoryCardEntity,
                embeddingModelId: Uuid,
                settings: Settings
            ): kotlinx.coroutines.Job {
                enqueued.add(card.id to embeddingModelId)
                return kotlinx.coroutines.Job().apply { complete() }
            }

            override fun cancelSyncJob(memoryId: String) {
                canceled.add(memoryId)
            }
        }

        val tools = buildMemoryV1Tools(
            repository = repository,
            assistant = assistant1,
            callerConversationId = "conv-123",
            embeddingService = testEmbService,
            settings = settings,
            json = json,
        )
        val manageTool = tools.first { it.name == "memory_manage" }

        // 1. 创建全局卡片：必须同时为两个助手的模型触发入队
        val createRes = parseResult(manageTool.execute(json.parseToJsonElement("""
            {
                "action": "create",
                "content": "全局事实：小雨喜欢喝热可可"
            }
        """.trimIndent())))
        assertEquals(true, createRes["success"]?.jsonPrimitive?.booleanOrNull)
        val newCardId = createRes["card"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull!!

        assertEquals(2, testEmbService.enqueued.size)
        val modelIdsEnqueued = testEmbService.enqueued.map { it.second }.toSet()
        assertEquals(setOf(modelUuid1, modelUuid2), modelIdsEnqueued)

        // 2. 归档卡片：必须调用 cancelSyncJob
        val archiveRes = parseResult(manageTool.execute(json.parseToJsonElement("""
            {
                "action": "archive",
                "id": "$newCardId",
                "expected_revision": 1
            }
        """.trimIndent())))
        assertEquals(true, archiveRes["success"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(testEmbService.canceled.contains(newCardId))
    }

    // ==========================================
    // memory_read_evidence 安全鉴权与限制测试
    // ==========================================

    @Test
    fun evidence_toolNotAvailableWhenDisabled() = runBlocking {
        val disabledAssistant = assistant.copy(allowMemoryEvidenceRead = false)
        val tools = buildMemoryV1Tools(
            repository = repository,
            assistant = disabledAssistant,
            callerConversationId = "conv-123",
            conversationProvider = { null },
            json = json,
        )
        assertFalse(tools.any { it.name == "memory_read_evidence" })
    }

    @Test
    fun evidence_deniesAccessToOtherAssistantCard() = runBlocking {
        val otherAssistantId = Uuid.random().toString()
        val otherCard = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = otherAssistantId,
            content = "其他助手的私密记忆",
        )

        val enabledAssistant = assistant.copy(allowMemoryEvidenceRead = true)
        val tools = buildMemoryV1Tools(
            repository = repository,
            assistant = enabledAssistant,
            callerConversationId = "conv-123",
            conversationProvider = { null },
            json = json,
        )
        val evidenceTool = tools.first { it.name == "memory_read_evidence" }

        val res = parseResult(evidenceTool.execute(json.parseToJsonElement("""{"memory_id": "${otherCard.id}"}""")))
        assertEquals(false, res["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("UNAUTHORIZED", res["error"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun evidence_returnsNotFoundForMissingCard() = runBlocking {
        val enabledAssistant = assistant.copy(allowMemoryEvidenceRead = true)
        val tools = buildMemoryV1Tools(
            repository = repository,
            assistant = enabledAssistant,
            callerConversationId = "conv-123",
            conversationProvider = { null },
            json = json,
        )
        val evidenceTool = tools.first { it.name == "memory_read_evidence" }

        val res = parseResult(evidenceTool.execute(json.parseToJsonElement("""{"memory_id": "non-existent"}""")))
        assertEquals(false, res["success"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("CARD_NOT_FOUND", res["error"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun evidence_findsEvidenceInAlternativeBranchMessageNodes() = runBlocking {
        val convId = Uuid.random()
        val branchMsgId = Uuid.random()
        val mainMsgId = Uuid.random()

        val branchMsg = me.rerere.ai.ui.UIMessage(
            id = branchMsgId,
            role = me.rerere.ai.core.MessageRole.USER,
            parts = listOf(UIMessagePart.Text("我在非主分支里说：我其实最喜欢喝红茶")),
        )
        val mainMsg = me.rerere.ai.ui.UIMessage(
            id = mainMsgId,
            role = me.rerere.ai.core.MessageRole.USER,
            parts = listOf(UIMessagePart.Text("我在主分支里说：我喜欢绿茶")),
        )

        val node = me.rerere.rikkahub.data.model.MessageNode(
            messages = listOf(mainMsg, branchMsg),
            selectIndex = 0, // 主分支选择 mainMsg，branchMsg 位于非主分支
        )

        val fakeConversation = me.rerere.rikkahub.data.model.Conversation(
            id = convId,
            assistantId = assistantId,
            title = "测试会话",
            messageNodes = listOf(node),
        )

        val card = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "小雨喜欢红茶",
            category = MemoryConstants.CATEGORY_PREFERENCE,
            sourceConversationId = convId.toString(),
            sourceMessageId = branchMsgId.toString(),
            sourceRole = "user",
        )

        val enabledAssistant = assistant.copy(allowMemoryEvidenceRead = true)
        val tools = buildMemoryV1Tools(
            repository = repository,
            assistant = enabledAssistant,
            callerConversationId = convId.toString(),
            conversationProvider = { id -> if (id == convId) fakeConversation else null },
            json = json,
        )

        val evidenceTool = tools.first { it.name == "memory_read_evidence" }
        val res = parseResult(evidenceTool.execute(json.parseToJsonElement("""{"memory_id": "${card.id}"}""")))

        assertEquals(true, res["success"]?.jsonPrimitive?.booleanOrNull)
        val evidenceList = res["evidence"]?.jsonArray
        assertNotNull(evidenceList)
        assertEquals(1, evidenceList!!.size)
        val item = evidenceList[0].jsonObject
        assertEquals(branchMsgId.toString(), item["message_id"]?.jsonPrimitive?.contentOrNull)
        assertTrue(item["matched"]?.jsonPrimitive?.contentOrNull?.contains("最喜欢喝红茶") == true)
    }

    @Test
    fun evidence_findsEvidenceInCurrentBranchMessages() = runBlocking {
        val convId = Uuid.random()
        val msgId = Uuid.random()

        val mainMsg = me.rerere.ai.ui.UIMessage(
            id = msgId,
            role = me.rerere.ai.core.MessageRole.USER,
            parts = listOf(UIMessagePart.Text("主分支原话：每天喝一杯黑咖啡")),
        )

        val node = me.rerere.rikkahub.data.model.MessageNode(
            messages = listOf(mainMsg),
            selectIndex = 0,
        )

        val fakeConversation = me.rerere.rikkahub.data.model.Conversation(
            id = convId,
            assistantId = assistantId,
            title = "测试会话",
            messageNodes = listOf(node),
        )

        val card = repository.createCard(
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = assistantId.toString(),
            content = "每天一杯黑咖啡",
            category = MemoryConstants.CATEGORY_PREFERENCE,
            sourceConversationId = convId.toString(),
            sourceMessageId = msgId.toString(),
            sourceRole = "user",
        )

        val enabledAssistant = assistant.copy(allowMemoryEvidenceRead = true)
        val tools = buildMemoryV1Tools(
            repository = repository,
            assistant = enabledAssistant,
            callerConversationId = convId.toString(),
            conversationProvider = { id -> if (id == convId) fakeConversation else null },
            json = json,
        )

        val evidenceTool = tools.first { it.name == "memory_read_evidence" }
        val res = parseResult(evidenceTool.execute(json.parseToJsonElement("""{"memory_id": "${card.id}"}""")))

        assertEquals(true, res["success"]?.jsonPrimitive?.booleanOrNull)
        val evidenceList = res["evidence"]?.jsonArray
        assertNotNull(evidenceList)
        assertEquals(1, evidenceList!!.size)
        val item = evidenceList[0].jsonObject
        assertEquals(msgId.toString(), item["message_id"]?.jsonPrimitive?.contentOrNull)
        assertTrue(item["matched"]?.jsonPrimitive?.contentOrNull?.contains("每天喝一杯黑咖啡") == true)
    }
}

/**
 * 纯 JVM 内存测试仓储，用于严格验证 MemoryV1Tools 业务规则、乐观锁与数据协议
 */
open class InMemoryTestMemoryRepository : MemoryRepositoryV1() {
    private val cards = mutableMapOf<String, MemoryCardEntity>()
    private val revisions = mutableListOf<MemoryRevisionEntity>()
    private val sources = mutableListOf<MemorySourceEntity>()

    override suspend fun createCard(
        ownerType: String,
        ownerId: String,
        content: String,
        category: String,
        factKey: String?,
        confidence: Float,
        importance: Float,
        validFrom: Long?,
        validTo: Long?,
        actor: String,
        reason: String?,
        sourceConversationId: String?,
        sourceNodeId: String?,
        sourceMessageId: String?,
        sourceRole: String?,
        sourceKind: String,
        sourceHash: String?,
        status: String,
        alwaysInject: Boolean,
    ): MemoryCardEntity {
        val now = System.currentTimeMillis()
        val cardId = Uuid.random().toString()
        val card = MemoryCardEntity(
            id = cardId,
            ownerType = ownerType,
            ownerId = ownerId,
            content = content.trim(),
            category = category,
            factKey = factKey,
            status = status,
            confidence = confidence,
            importance = importance,
            validFrom = validFrom ?: now,
            validTo = validTo,
            supersededBy = null,
            createdAt = now,
            updatedAt = now,
            revision = 1,
            createdBy = actor,
            updatedBy = actor,
            alwaysInject = alwaysInject,
        )
        cards[cardId] = card
        revisions.add(
            MemoryRevisionEntity(
                id = Uuid.random().toString(),
                memoryId = cardId,
                revision = 1,
                operation = MemoryConstants.OP_ADD,
                beforeContent = null,
                afterContent = content.trim(),
                beforeStatus = null,
                afterStatus = status,
                reason = reason,
                actor = actor,
                createdAt = now,
            )
        )
        if (sourceConversationId != null || sourceMessageId != null) {
            sources.add(
                MemorySourceEntity(
                    id = Uuid.random().toString(),
                    memoryId = cardId,
                    conversationId = sourceConversationId,
                    nodeId = sourceNodeId,
                    messageId = sourceMessageId,
                    role = sourceRole,
                    sourceKind = sourceKind,
                    sourceHash = sourceHash,
                    createdAt = now,
                )
            )
        }
        return card
    }

    override suspend fun updateCard(
        id: String,
        expectedRevision: Int,
        content: String,
        category: String?,
        factKey: String?,
        confidence: Float?,
        importance: Float?,
        validFrom: Long?,
        validTo: Long?,
        actor: String,
        reason: String?,
        sourceConversationId: String?,
        sourceNodeId: String?,
        sourceMessageId: String?,
        sourceRole: String?,
        sourceKind: String,
        sourceHash: String?
    ): MemoryCardEntity {
        val existing = cards[id] ?: throw IllegalArgumentException("Card $id not found")
        if (existing.revision != expectedRevision) {
            throw RevisionConflictException(id, expectedRevision, existing.revision)
        }
        val now = System.currentTimeMillis()
        val nextRev = existing.revision + 1
        val updated = existing.copy(
            content = content.trim(),
            category = category ?: existing.category,
            factKey = factKey ?: existing.factKey,
            confidence = confidence ?: existing.confidence,
            importance = importance ?: existing.importance,
            validFrom = validFrom ?: existing.validFrom,
            validTo = validTo ?: existing.validTo,
            updatedAt = now,
            revision = nextRev,
            updatedBy = actor,
        )
        cards[id] = updated
        revisions.add(
            MemoryRevisionEntity(
                id = Uuid.random().toString(),
                memoryId = id,
                revision = nextRev,
                operation = MemoryConstants.OP_UPDATE,
                beforeContent = existing.content,
                afterContent = content.trim(),
                beforeStatus = existing.status,
                afterStatus = updated.status,
                reason = reason,
                actor = actor,
                createdAt = now,
            )
        )
        return updated
    }

    override suspend fun archiveCard(
        id: String,
        expectedRevision: Int,
        actor: String,
        reason: String?
    ): MemoryCardEntity {
        val existing = cards[id] ?: throw IllegalArgumentException("Card $id not found")
        if (existing.revision != expectedRevision) {
            throw RevisionConflictException(id, expectedRevision, existing.revision)
        }
        if (existing.status == MemoryConstants.STATUS_ARCHIVED) {
            return existing
        }
        val now = System.currentTimeMillis()
        val nextRev = existing.revision + 1
        val updated = existing.copy(
            status = MemoryConstants.STATUS_ARCHIVED,
            updatedAt = now,
            revision = nextRev,
            updatedBy = actor,
        )
        cards[id] = updated
        revisions.add(
            MemoryRevisionEntity(
                id = Uuid.random().toString(),
                memoryId = id,
                revision = nextRev,
                operation = MemoryConstants.OP_ARCHIVE,
                beforeContent = existing.content,
                afterContent = existing.content,
                beforeStatus = existing.status,
                afterStatus = MemoryConstants.STATUS_ARCHIVED,
                reason = reason,
                actor = actor,
                createdAt = now,
            )
        )
        return updated
    }

    override suspend fun restoreCard(
        id: String,
        expectedRevision: Int,
        actor: String,
        reason: String?
    ): MemoryCardEntity {
        val existing = cards[id] ?: throw IllegalArgumentException("Card $id not found")
        if (existing.revision != expectedRevision) {
            throw RevisionConflictException(id, expectedRevision, existing.revision)
        }
        if (existing.status == MemoryConstants.STATUS_ACTIVE) {
            return existing
        }
        val now = System.currentTimeMillis()
        val nextRev = existing.revision + 1
        val updated = existing.copy(
            status = MemoryConstants.STATUS_ACTIVE,
            updatedAt = now,
            revision = nextRev,
            updatedBy = actor,
        )
        cards[id] = updated
        revisions.add(
            MemoryRevisionEntity(
                id = Uuid.random().toString(),
                memoryId = id,
                revision = nextRev,
                operation = MemoryConstants.OP_RESTORE,
                beforeContent = existing.content,
                afterContent = existing.content,
                beforeStatus = existing.status,
                afterStatus = MemoryConstants.STATUS_ACTIVE,
                reason = reason,
                actor = actor,
                createdAt = now,
            )
        )
        return updated
    }

    override suspend fun supersedeCard(
        oldCardId: String,
        expectedRevision: Int,
        newContent: String,
        category: String?,
        factKey: String?,
        confidence: Float?,
        importance: Float?,
        actor: String,
        reason: String?,
        sourceConversationId: String?,
        sourceNodeId: String?,
        sourceMessageId: String?,
        sourceRole: String?,
        sourceKind: String,
        sourceHash: String?
    ): Pair<MemoryCardEntity, MemoryCardEntity> {
        val oldCard = cards[oldCardId] ?: throw IllegalArgumentException("Card $oldCardId not found")
        if (oldCard.revision != expectedRevision) {
            throw RevisionConflictException(oldCardId, expectedRevision, oldCard.revision)
        }
        val now = System.currentTimeMillis()
        val newCardId = Uuid.random().toString()
        val newCard = MemoryCardEntity(
            id = newCardId,
            ownerType = oldCard.ownerType,
            ownerId = oldCard.ownerId,
            content = newContent.trim(),
            category = category ?: oldCard.category,
            factKey = factKey ?: oldCard.factKey,
            status = MemoryConstants.STATUS_ACTIVE,
            confidence = confidence ?: oldCard.confidence,
            importance = importance ?: oldCard.importance,
            validFrom = now,
            validTo = null,
            supersededBy = null,
            createdAt = now,
            updatedAt = now,
            revision = 1,
            createdBy = actor,
            updatedBy = actor,
        )
        val updatedOldCard = oldCard.copy(
            status = MemoryConstants.STATUS_SUPERSEDED,
            supersededBy = newCardId,
            validTo = now,
            updatedAt = now,
            revision = oldCard.revision + 1,
            updatedBy = actor,
        )
        cards[newCardId] = newCard
        cards[oldCardId] = updatedOldCard
        return Pair(newCard, updatedOldCard)
    }

    override suspend fun deletePermanently(id: String) {
        cards.remove(id)
        sources.removeAll { it.memoryId == id }
        revisions.removeAll { it.memoryId == id }
    }

    override suspend fun getCardById(id: String): MemoryCardEntity? = cards[id]

    override suspend fun getRevisions(memoryId: String): List<MemoryRevisionEntity> =
        revisions.filter { it.memoryId == memoryId }

    override suspend fun getSources(memoryId: String): List<MemorySourceEntity> =
        sources.filter { it.memoryId == memoryId }

    override suspend fun getMemoryIdsWithSources(memoryIds: List<String>): Set<String> =
        sources.filter { it.memoryId in memoryIds }.map { it.memoryId }.toSet()

    override suspend fun getSourcesForMemories(memoryIds: List<String>): List<MemorySourceEntity> =
        sources.filter { it.memoryId in memoryIds }

    val recalledCardIds = mutableListOf<String>()

    override suspend fun recordCardRecalls(cardIds: List<String>, recallTimeMs: Long) {
        recalledCardIds.addAll(cardIds)
        for (id in cardIds) {
            val card = cards[id] ?: continue
            cards[id] = card.copy(
                hitCount = card.hitCount + 1,
                lastRecalledAt = recallTimeMs,
            )
        }
    }

    override suspend fun search(
        query: String,
        ownerType: String,
        ownerId: String,
        statusList: List<String>?,
        limit: Int,
        includeGlobal: Boolean
    ): List<MemoryCardEntity> {
        return cards.values
            .filter { (it.ownerType == ownerType && it.ownerId == ownerId) || (includeGlobal && it.ownerType == "GLOBAL") }
            .filter { statusList == null || it.status in statusList }
            .filter { it.content.contains(query, ignoreCase = true) }
            .take(limit)
    }
}
