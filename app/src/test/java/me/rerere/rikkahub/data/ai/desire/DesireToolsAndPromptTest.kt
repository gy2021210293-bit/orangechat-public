/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.desire.db.AutonomousWakeRunEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousWakeStateEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousItemEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousShareEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousWakePlanEntity
import me.rerere.rikkahub.data.ai.desire.db.DesireActiveSendEntity
import me.rerere.rikkahub.data.ai.desire.db.DesireDao
import me.rerere.rikkahub.data.ai.desire.db.DesireEventReceiptEntity
import me.rerere.rikkahub.data.ai.desire.db.DesireStateEntity
import me.rerere.rikkahub.data.ai.desire.db.DesireTickLogEntity
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class DesireToolsAndPromptTest {

    @Test
    fun todayUiItemsAndThreeDayCompletedContextHaveSeparateWindows() = runBlocking {
        val dao = FakeDesireDao()
        val repository = DesireRepository(dao)
        val zone = java.time.ZoneId.systemDefault()
        val start = java.time.LocalDate.of(2026, 10, 3).atStartOfDay(zone).toInstant().toEpochMilli()
        val noon = start + 12 * 60 * 60 * 1000L
        dao.items["done"] = AutonomousItemEntity("done", "a", "今天完成", status = "completed", createdAt = start - 86_400_000L, updatedAt = noon)
        dao.items["due"] = AutonomousItemEntity("due", "a", "今天到期", resumeAt = java.time.Instant.ofEpochMilli(noon).toString(), createdAt = start - 86_400_000L, updatedAt = start - 86_400_000L)
        dao.items["old"] = AutonomousItemEntity("old", "a", "旧完成", status = "completed", createdAt = start - 86_400_000L, updatedAt = start - 86_400_000L)
        dao.items["older"] = AutonomousItemEntity("older", "a", "更早完成", status = "completed", createdAt = start - 4 * 86_400_000L, updatedAt = start - 4 * 86_400_000L)

        assertEquals(setOf("done", "due"), repository.listTodayAutonomousItems("a", noon).map { it.id }.toSet())
        assertEquals(listOf("done", "old"), repository.listCompletedAutonomousItemsRecentDays("a", noon).map { it.id }.sorted())
    }

    @Test
    fun chatItemCreateReusesAnActiveItemButKeepsDifferentItems() = runBlocking {
        val dao = FakeDesireDao()
        val tool = buildChatAutonomousItemTool("assistant-a", DesireRepository(dao))
        suspend fun create(content: String) = Json.parseToJsonElement(
            (tool.execute(buildJsonObject { put("action", "create"); put("content", content) })
                .single() as UIMessagePart.Text).text
        ).jsonObject

        val first = create("  阅读论文  ")
        val repeated = create("阅读论文")
        val second = create("整理实验数据")
        assertEquals(first["item"]!!.jsonObject["item_id"], repeated["item"]!!.jsonObject["item_id"])
        assertEquals("true", repeated["already_exists"]!!.jsonPrimitive.content)
        assertEquals("false", second["already_exists"]!!.jsonPrimitive.content)
        assertEquals(2, dao.items.size)
    }

    private class FakeDesireDao : DesireDao {
        val items = mutableMapOf<String, AutonomousItemEntity>()
        val plans = mutableMapOf<String, AutonomousWakePlanEntity>()
        val shares = mutableMapOf<Pair<String, String>, AutonomousShareEntity>()
        val states = mutableMapOf<String, DesireStateEntity>()
        val receipts = mutableSetOf<String>()
        val receiptEntities = mutableListOf<DesireEventReceiptEntity>()
        var failOnUpsert: Boolean = false
        var simulatedDelayMs: Long = 0L
        val activeCallers = java.util.concurrent.atomic.AtomicInteger(0)
        val maxConcurrentCallers = java.util.concurrent.atomic.AtomicInteger(0)

        override suspend fun getState(assistantId: String): DesireStateEntity? {
            if (simulatedDelayMs > 0) {
                val cur = activeCallers.incrementAndGet()
                maxConcurrentCallers.updateAndGet { maxOf(it, cur) }
                kotlinx.coroutines.delay(simulatedDelayMs)
                activeCallers.decrementAndGet()
            }
            return states[assistantId]
        }

        override suspend fun upsertState(state: DesireStateEntity) {
            if (failOnUpsert) {
                throw IllegalStateException("Simulated database failure on upsertState")
            }
            if (simulatedDelayMs > 0) {
                val cur = activeCallers.incrementAndGet()
                maxConcurrentCallers.updateAndGet { maxOf(it, cur) }
                kotlinx.coroutines.delay(simulatedDelayMs)
                activeCallers.decrementAndGet()
            }
            states[state.assistantId] = state
        }

        override suspend fun insertReceipt(receipt: DesireEventReceiptEntity): Long {
            return if (receipts.add(receipt.eventId)) {
                receiptEntities.add(receipt)
                1L
            } else {
                -1L
            }
        }

        override suspend fun countReceipt(eventId: String): Int = if (receipts.contains(eventId)) 1 else 0

        override suspend fun getLastUserMasterMessageReceipt(assistantId: String): DesireEventReceiptEntity? {
            return receiptEntities
                .filter { it.assistantId == assistantId && it.eventName == "master_message" && it.sourceType == "user_message" }
                .maxByOrNull { it.createdAt }
        }

        override suspend fun saveEventWithReceipt(
            receipt: DesireEventReceiptEntity,
            state: DesireStateEntity,
            log: DesireTickLogEntity,
            keepLogs: Int,
        ): Boolean {
            val rowId = insertReceipt(receipt)
            if (rowId == -1L) return false
            try {
                upsertState(state)
                insertLog(log)
                pruneLogs(receipt.assistantId, keepLogs)
                return true
            } catch (e: Exception) {
                // 模拟 Room @Transaction 自动回滚
                receipts.remove(receipt.eventId)
                receiptEntities.removeAll { it.eventId == receipt.eventId }
                throw e
            }
        }

        override suspend fun saveTickWithReceipt(
            receipt: DesireEventReceiptEntity,
            state: DesireStateEntity,
            log: DesireTickLogEntity,
            keepLogs: Int,
        ): Boolean {
            val rowId = insertReceipt(receipt)
            if (rowId == -1L) return false
            try {
                upsertState(state)
                insertLog(log)
                pruneLogs(receipt.assistantId, keepLogs)
                return true
            } catch (e: Exception) {
                // 模拟 Room @Transaction 自动回滚
                receipts.remove(receipt.eventId)
                receiptEntities.removeAll { it.eventId == receipt.eventId }
                throw e
            }
        }

        val savedLogs = mutableListOf<DesireTickLogEntity>()

        override suspend fun insertLog(log: DesireTickLogEntity): Long {
            savedLogs.add(log)
            return 1L
        }

        override suspend fun listRecentLogs(assistantId: String, limit: Int): List<DesireTickLogEntity> =
            savedLogs.filter { it.assistantId == assistantId }.takeLast(limit).reversed()

        override suspend fun pruneLogs(assistantId: String, keepCount: Int) {}

        override suspend fun insertActiveSend(send: DesireActiveSendEntity): Long = 1L

        override suspend fun getLastActiveSend(assistantId: String): DesireActiveSendEntity? = null

        override suspend fun countActiveSendsBetween(assistantId: String, startEpochMs: Long, endEpochMs: Long): Int = 0

        override suspend fun countActiveSendsSince(assistantId: String, sinceEpochMs: Long): Int = 0

        override suspend fun deleteStateForAssistant(assistantId: String) {
            states.remove(assistantId)
        }

        override suspend fun deleteLogsForAssistant(assistantId: String) {
            savedLogs.removeAll { it.assistantId == assistantId }
        }

        override suspend fun deleteActiveSendsForAssistant(assistantId: String) {}

        override suspend fun deleteReceiptsForAssistant(assistantId: String) {
            receiptEntities.removeAll { it.assistantId == assistantId }
        }

        override suspend fun upsertAutonomousRun(run: AutonomousWakeRunEntity) {}
        override suspend fun listAutonomousRuns(assistantId: String, limit: Int): List<AutonomousWakeRunEntity> = emptyList()
        override suspend fun listAutonomousRunsInRange(assistantId: String, startMs: Long, endMs: Long): List<AutonomousWakeRunEntity> = emptyList()
        override suspend fun getAutonomousRun(runId: String): AutonomousWakeRunEntity? = null
        override suspend fun upsertAutonomousState(state: AutonomousWakeStateEntity) {}
        override suspend fun getAutonomousState(assistantId: String): AutonomousWakeStateEntity? = null
        override suspend fun upsertAutonomousItem(item: AutonomousItemEntity) { items[item.id] = item }
        override suspend fun listActiveAutonomousItems(assistantId: String) = items.values.filter { it.assistantId == assistantId && it.status == "active" }
        override suspend fun listRecentAutonomousItems(assistantId: String, limit: Int) = items.values.filter { it.assistantId == assistantId }.take(limit)
        override suspend fun listItemsChangedOnDay(assistantId: String, startMs: Long, endMs: Long) =
            items.values.filter { it.assistantId == assistantId && (it.createdAt in startMs until endMs || it.updatedAt in startMs until endMs) }
        override suspend fun listCompletedItemsOnDay(assistantId: String, startMs: Long, endMs: Long) =
            items.values.filter { it.assistantId == assistantId && it.status == "completed" && it.updatedAt in startMs until endMs }
        override suspend fun getAutonomousItem(assistantId: String, itemId: String) = items[itemId]?.takeIf { it.assistantId == assistantId }
        override suspend fun updateAutonomousItem(assistantId: String, itemId: String, content: String, progress: String, resumeAt: String?, updatedAt: Long): Int = 0
        override suspend fun setAutonomousItemStatus(assistantId: String, itemId: String, status: String, updatedAt: Long): Int = 0
        override suspend fun upsertWakePlan(plan: AutonomousWakePlanEntity) { plans[plan.assistantId] = plan }
        override suspend fun getWakePlan(assistantId: String) = plans[assistantId]
        override suspend fun claimWakePlan(assistantId: String, version: Long, nowMs: Long): Int = 0
        override suspend fun finishWakePlan(
            assistantId: String, expectedVersion: Long, newVersion: Long, autonomousAt: Long,
            itemAt: Long?, scheduledAt: Long, source: String, reason: String, nowMs: Long,
        ): Int = 0
        override suspend fun insertAutonomousShare(share: AutonomousShareEntity): Long {
            val key = share.assistantId to share.dedupeKey
            if (key in shares) return -1L
            shares[key] = share
            return 1L
        }
        override suspend fun getAutonomousShare(assistantId: String, dedupeKey: String) = shares[assistantId to dedupeKey]
        override suspend fun listAutonomousShares(assistantId: String, limit: Int) = shares.values.filter { it.assistantId == assistantId }.take(limit)
        override suspend fun getLastAutonomousShare(assistantId: String) = shares.values.filter { it.assistantId == assistantId }.maxByOrNull { it.sentAt }
    }

    @Test
    fun testNineDrivesPromptInjections() = runBlocking {
        val fakeDao = FakeDesireDao()
        val repo = DesireRepository(fakeDao)
        val assistantId = "assistant-test-9drives"

        // 初始化状态
        val state = repo.getOrCreateState(assistantId)
        // 令 reflection, duty, social, intimacy 偏离基线
        state.drives["reflection"]?.let { it.value = it.baseline + 15.0 } // 45.0 vs 30.0 -> 正偏离
        state.drives["duty"]?.let { it.value = it.baseline - 12.0 }       // 28.0 vs 40.0 -> 负偏离
        state.drives["social"]?.let { it.value = it.baseline + 20.0 }     // 50.0 vs 30.0 -> 正偏离
        state.drives["intimacy"]?.let { it.value = it.baseline + 18.0 }   // 58.0 vs 40.0 -> 正偏离

        // 保存回去
        val nowMs = System.currentTimeMillis()
        val json = Json { ignoreUnknownKeys = true }
        fakeDao.upsertState(
            DesireStateEntity(
                assistantId = assistantId,
                drivesJson = json.encodeToString(state.drives),
                thoughtsJson = json.encodeToString(state.thoughts),
                lastTickAt = nowMs,
                tickCount = state.tickCount,
                nextTickAt = nowMs + 1800 * 1000L,
                schemaVersion = 1,
                updatedAt = nowMs,
            )
        )

        val prompt = repo.buildDesireStatePrompt(assistantId)
        assertTrue("Prompt should not be empty", prompt.isNotBlank())
        assertTrue("Prompt must contain Desire State header", prompt.contains("## 心理与情感倾向 (Desire State)"))
        // 前 3 个最大偏离是 social(+20), intimacy(+18), reflection(+15)
        assertTrue("Prompt should include social drive prompt", prompt.contains("社交欲望高涨，渴望与外界交流互动"))
        assertTrue("Prompt should include intimacy drive prompt", prompt.contains("亲密渴望强烈，寻求更深层次的心灵或身体亲近"))
        assertTrue("Prompt should include reflection drive prompt", prompt.contains("陷入深思与自我反省，渴望梳理思绪"))
    }

    @Test
    fun testDesireToolsBoundaryAndAliases() = runBlocking {
        val fakeDao = FakeDesireDao()
        val repo = DesireRepository(fakeDao)
        val assistant = Assistant(
            id = Uuid.random(),
            name = "TestAssistant",
            desireSystemEnabled = true,
        )
        val assistantId = assistant.id.toString()

        val tools = buildDesireTools(repo, assistant)
        // 3 个原生核心工具 + 2 个纯只读/化解别名
        assertEquals("Should register 5 tools (3 core + 2 safe aliases)", 5, tools.size)

        val toolMap = tools.associateBy { it.name }
        assertTrue(toolMap.containsKey("desire_status"))
        assertTrue(toolMap.containsKey("desire_event"))
        assertFalse("Heartbeat must not be exposed as an AI tool", toolMap.containsKey("desire_tick"))
        assertTrue(toolMap.containsKey("desire_resolve_thought"))
        assertTrue(toolMap.containsKey("introspect_desires"))
        assertTrue(toolMap.containsKey("resolve_thought"))
        assertFalse("Must NOT allow arbitrary drive modification tools", toolMap.containsKey("suppress_desire"))
        assertFalse("Must NOT allow arbitrary drive modification tools", toolMap.containsKey("indulge_desire"))

        // 1. 测试 introspect_desires 别名
        val introspectTool = toolMap["introspect_desires"]!!
        val statusRes = introspectTool.execute(buildJsonObject {})
        val statusJson = (statusRes.first() as UIMessagePart.Text).text
        val statusObj = Json.parseToJsonElement(statusJson).jsonObject
        assertNotNull(statusObj["drives"])
        assertNotNull(statusObj["drives"]!!.jsonObject["attachment"])

        // 2. 测试 resolve_thought 别名
        val resolveTool = toolMap["resolve_thought"]!!
        val state = repo.getOrCreateState(assistantId)
        state.thoughts.add(
            Thought(
                id = "thought-test-1",
                content = "今天想买一朵向日葵",
                sourceDrive = "joy",
            )
        )
        fakeDao.upsertState(
            DesireStateEntity(
                assistantId = assistantId,
                drivesJson = Json.encodeToString(state.drives),
                thoughtsJson = Json.encodeToString(state.thoughts),
                lastTickAt = System.currentTimeMillis(),
                tickCount = 1,
                nextTickAt = System.currentTimeMillis() + 1800 * 1000L,
                schemaVersion = 1,
                updatedAt = System.currentTimeMillis(),
            )
        )

        val resolveRes = resolveTool.execute(
            buildJsonObject {
                put("thought_text", "向日葵")
            }
        )
        val resolveJson = (resolveRes.first() as UIMessagePart.Text).text
        val resolveObj = Json.parseToJsonElement(resolveJson).jsonObject
        assertEquals("true", resolveObj["success"]!!.jsonPrimitive.content)
        assertEquals("thought-test-1", resolveObj["resolved_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun testDesireTickIsNotExposedButHeartbeatLogicRemains() = runBlocking {
        val fakeDao = FakeDesireDao()
        val repo = DesireRepository(fakeDao)
        val assistant = Assistant(
            id = Uuid.random(),
            name = "TestAssistant",
            desireSystemEnabled = true,
        )
        val tools = buildDesireTools(repo, assistant).associateBy { it.name }

        assertFalse("Heartbeat must not be exposed as an AI tool", tools.containsKey("desire_tick"))

        val tickResult = repo.runTickOnce(
            assistantId = assistant.id.toString(),
            tickEventId = "scheduled_tick",
            isMasterPresent = false,
        )
        assertNotNull("Heartbeat logic must remain available to the scheduler", tickResult)
        assertNotNull(tickResult?.tick)
        assertNotNull(tickResult?.changes)

        val savedLog = fakeDao.savedLogs.lastOrNull()
        assertNotNull("Heartbeat must still save a log entity", savedLog)
        assertEquals("", savedLog?.monologue)
    }

    @Test
    fun testToolCallIdIdempotence() = runBlocking {
        val fakeDao = FakeDesireDao()
        val repo = DesireRepository(fakeDao)
        val assistant = Assistant(
            id = Uuid.random(),
            name = "TestAssistant",
            desireSystemEnabled = true,
        )
        val tools = buildDesireTools(repo, assistant).associateBy { it.name }
        val eventTool = tools["desire_event"]!!

        val callId = "call_abc123"
        // 第一次调用：应用公开白名单中的 task_done 事件
        val res1 = eventTool.execute(
            buildJsonObject {
                put("event_type", "task_done")
                put("_tool_call_id", callId)
            }
        )
        val json1 = (res1.first() as UIMessagePart.Text).text
        val obj1 = Json.parseToJsonElement(json1).jsonObject
        val changes1 = obj1["changes"]
        assertNotNull(changes1)
        assertTrue(changes1.toString().contains("duty"))

        val stateAfterFirst = repo.getOrCreateState(assistant.id.toString())
        val dutyAfterFirst = stateAfterFirst.drives["duty"]!!.value

        // 第二次调用（相同 toolCallId，模拟重试或重放）：应幂等忽略，不再重复生效
        val res2 = eventTool.execute(
            buildJsonObject {
                put("event_type", "task_done")
                put("_tool_call_id", callId)
            }
        )
        val json2 = (res2.first() as UIMessagePart.Text).text
        val obj2 = Json.parseToJsonElement(json2).jsonObject
        assertEquals("[]", obj2["changes"].toString())

        val stateAfterSecond = repo.getOrCreateState(assistant.id.toString())
        assertEquals(dutyAfterFirst, stateAfterSecond.drives["duty"]!!.value, 0.001)
    }

    @Test
    fun testAtomicTransactionRollbackOnError() = runBlocking {
        val fakeDao = FakeDesireDao()
        val repo = DesireRepository(fakeDao)
        val assistantId = "assistant-fail-test"

        // 模拟数据库在更新状态时发生异常
        fakeDao.failOnUpsert = true

        var failed = false
        try {
            repo.applyEventOnce(
                assistantId = assistantId,
                eventId = "event-crash-test",
                eventName = "master_message",
            )
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("Execution should throw due to simulated failure", failed)

        // 关键断言：事务回滚后，收据必须不存在！绝不能留下收据导致后续永久跳过！
        assertFalse("Receipt must NOT remain in DB after rollback", fakeDao.hasReceipt("event-crash-test"))

        // 恢复正常后，使用相同 eventId 再次重试必须能够成功执行
        fakeDao.failOnUpsert = false
        val changes = repo.applyEventOnce(
            assistantId = assistantId,
            eventId = "event-crash-test",
            eventName = "master_message",
        )
        assertTrue("Retry with same eventId must succeed after rollback", changes.isNotEmpty())
        assertTrue(fakeDao.hasReceipt("event-crash-test"))
    }

    @Test
    fun testAllStateModificationsSerializedWithoutDeadlock() = runBlocking {
        val fakeDao = FakeDesireDao().apply {
            simulatedDelayMs = 15L
        }
        val repo = DesireRepository(fakeDao)
        val assistantId = "assistant-concurrency-test"

        // 初始化
        repo.getOrCreateState(assistantId)
        fakeDao.maxConcurrentCallers.set(0)

        // 并发执行 5 种不同类型的写操作
        val jobs = listOf(
            async {
                repo.applyEventOnce(assistantId, "evt_1", "master_message")
            },
            async {
                repo.runTickOnce(assistantId, "tick_1", isMasterPresent = true)
            },
            async {
                repo.resolveThought(assistantId, "keyword_test")
            },
            async {
                repo.batchBaselineRegression(assistantId, 24 * 3600 * 1000L)
            },
            async {
                repo.resetState(assistantId)
            }
        )

        // 全部等待完成：绝不应出现非可重入 Mutex 死锁
        jobs.forEach { it.await() }

        // 验证并发互斥性：在存在模拟挂起延迟的情况下，DAO 的并发状态写/读进入数绝对不能 > 1
        assertEquals("Repository lock must enforce max 1 concurrent caller to state in DAO", 1, fakeDao.maxConcurrentCallers.get())

        // 最终状态必须存在且合法
        val finalState = repo.getOrCreateState(assistantId)
        assertNotNull(finalState)
        assertTrue(finalState.drives.containsKey("attachment"))
    }

    @Test
    fun testPublicGetOrCreateStateRaceWithApplyEvent() = runBlocking {
        val fakeDao = FakeDesireDao().apply {
            simulatedDelayMs = 30L
        }
        val repo = DesireRepository(fakeDao)
        val assistantId = "assistant-init-race-test"

        // 同时启动：公开 getOrCreateState（模拟 UI/工具并发初始化）与 applyEventOnce（模拟用户消息事件）
        val job1 = async {
            repo.getOrCreateState(assistantId)
        }
        val job2 = async {
            repo.applyEventOnce(
                assistantId = assistantId,
                eventId = "first_msg_race",
                eventName = "master_message"
            )
        }

        job1.await()
        val changes = job2.await()

        // 验证：最终状态中的 attachment 驱动绝对不能被未持锁的初始默认值 (40.0) 覆盖！
        val finalState = repo.getOrCreateState(assistantId)
        assertTrue("master_message event must be applied", changes.isNotEmpty())
        assertTrue(
            "Attachment should be reduced below baseline 40.0, but was ${finalState.drives["attachment"]?.value}",
            finalState.drives["attachment"]!!.value < 40.0
        )
        assertEquals("Repository lock must enforce max 1 concurrent caller to state in DAO during init", 1, fakeDao.maxConcurrentCallers.get())
    }

    @Test
    fun testMasterMessageAndSilentEventEffects() {
        val state = DesireState()
        val initAttachment = state.drives["attachment"]!!.value
        val initIntimacy = state.drives["intimacy"]!!.value
        val initStress = state.drives["stress"]!!.value

        // master_message: 满足想念与亲密，attachment 减少, intimacy 增加 0.5
        val changes1 = DesireEventEngine.applyEvent(state, "master_message")
        assertTrue(changes1.isNotEmpty())
        assertTrue(state.drives["attachment"]!!.value < initAttachment)
        assertTrue(state.drives["intimacy"]!!.value > initIntimacy)

        // master_silent: stress 增加
        val changes2 = DesireEventEngine.applyEvent(state, "master_silent")
        assertTrue(changes2.isNotEmpty())
        assertTrue(state.drives["stress"]!!.value > initStress)

        // 兼容别名 wife_message 与 master_message 产生完全相同的数值变化
        val stateMaster = DesireState()
        val stateWife = DesireState()
        DesireEventEngine.applyEvent(stateMaster, "master_message")
        DesireEventEngine.applyEvent(stateWife, "wife_message")
        assertEquals(stateMaster.drives["attachment"]!!.value, stateWife.drives["attachment"]!!.value, 0.0001)
        assertEquals(stateMaster.drives["intimacy"]!!.value, stateWife.drives["intimacy"]!!.value, 0.0001)

        DesireEventEngine.applyEvent(stateMaster, "master_silent")
        DesireEventEngine.applyEvent(stateWife, "wife_silent")
        assertEquals(stateMaster.drives["stress"]!!.value, stateWife.drives["stress"]!!.value, 0.0001)

        // 验证调整后的 master_message 效果 (attachment 20% 比例扣减, intimacy +0.5, stress -1.0，经过惊喜乘数)
        val stFresh = DesireState()
        // baseline: attachment 40 -> 20% 扣除 8.0 -> 32.0; intimacy 1.3 -> delta +0.65 -> 40.65
        DesireEventEngine.applyEvent(stFresh, "master_message")
        assertEquals(32.0, stFresh.drives["attachment"]!!.value, 0.0001)
        assertEquals(40.65, stFresh.drives["intimacy"]!!.value, 0.0001)
    }

    @Test
    fun testAiToolWhitelistConstraint() = runBlocking {
        val fakeDao = FakeDesireDao()
        val repo = DesireRepository(fakeDao)
        val assistant = Assistant(
            id = Uuid.random(),
            name = "WhiteListAssistant",
            desireSystemEnabled = true,
        )
        val tools = buildDesireTools(repo, assistant).associateBy { it.name }
        val eventTool = tools["desire_event"]!!

        // 1. 白名单大小正好是 14
        assertEquals(14, DesireEventEngine.AI_CALLABLE_EVENTS.size)

        // 2. 内部事件 master_message 不得由 AI 工具调用
        val resMaster = eventTool.execute(
            buildJsonObject {
                put("event_type", "master_message")
                put("_tool_call_id", "call_master_illegal")
            }
        )
        val jsonMaster = (resMaster.first() as UIMessagePart.Text).text
        val objMaster = Json.parseToJsonElement(jsonMaster).jsonObject
        assertEquals("UNKNOWN_EVENT_TYPE", objMaster["error"]?.jsonPrimitive?.content)
        assertFalse("Illegal tool call must NOT insert receipt", fakeDao.hasReceipt("call_master_illegal"))

        // 3. 未知事件返回 UNKNOWN_EVENT_TYPE，不得写库
        val resUnknown = eventTool.execute(
            buildJsonObject {
                put("event_type", "reading_book")
                put("_tool_call_id", "call_unknown")
            }
        )
        val jsonUnknown = (resUnknown.first() as UIMessagePart.Text).text
        val objUnknown = Json.parseToJsonElement(jsonUnknown).jsonObject
        assertEquals("UNKNOWN_EVENT_TYPE", objUnknown["error"]?.jsonPrimitive?.content)
        assertFalse(fakeDao.hasReceipt("call_unknown"))

        // 4. 白名单中的新增事件 task_added 允许调用并持久化
        val resAdded = eventTool.execute(
            buildJsonObject {
                put("event_type", "task_added")
                put("_tool_call_id", "call_task_added")
            }
        )
        val jsonAdded = (resAdded.first() as UIMessagePart.Text).text
        val objAdded = Json.parseToJsonElement(jsonAdded).jsonObject
        val changes = objAdded["changes"]
        assertNotNull(changes)
        assertTrue(changes.toString().contains("duty"))
    }

    @Test
    fun testMasterMessageCooldown() = runBlocking {
        val fakeDao = FakeDesireDao()
        val repo = DesireRepository(fakeDao)
        val assistantId = "assistant-cooldown-test"
        val baseMs = 1_000_000_000L

        // 1. 首条真实用户消息结算一次
        val res1 = repo.applyMasterMessageOnce(
            assistantId = assistantId,
            eventId = "evt_msg_1",
            sourceId = "msg_1",
            nowMs = baseMs,
        )
        assertTrue("First user message must settle", res1.isNotEmpty())
        val state1 = repo.getOrCreateState(assistantId, nowMs = baseMs)
        assertEquals(32.0, state1.drives["attachment"]!!.value, 0.001)

        // 2. 29分59秒内的新消息不改变状态，但留下收据
        val res2 = repo.applyMasterMessageOnce(
            assistantId = assistantId,
            eventId = "evt_msg_2",
            sourceId = "msg_2",
            nowMs = baseMs + 29 * 60 * 1000L + 59 * 1000L,
        )
        assertTrue("Message within cooldown must NOT change drives", res2.isEmpty())
        assertTrue("Message within cooldown must still leave receipt", fakeDao.hasReceipt("evt_msg_2"))
        val state2 = repo.getOrCreateState(assistantId, nowMs = baseMs)
        assertEquals(32.0, state2.drives["attachment"]!!.value, 0.001)

        // 3. 连续消息刷新了最近活跃时间：距 msg_2 仅 10 分钟的消息，仍在冷却内
        val res3 = repo.applyMasterMessageOnce(
            assistantId = assistantId,
            eventId = "evt_msg_3",
            sourceId = "msg_3",
            nowMs = baseMs + 40 * 60 * 1000L,
        )
        assertTrue("Recent message resets boundary; must NOT settle", res3.isEmpty())
        assertTrue(fakeDao.hasReceipt("evt_msg_3"))

        // 4. 真正间隔 30 分钟后（距 msg_3 恰好 30 分钟），成功结算一次新会话
        val res4 = repo.applyMasterMessageOnce(
            assistantId = assistantId,
            eventId = "evt_msg_4",
            sourceId = "msg_4",
            nowMs = baseMs + 70 * 60 * 1000L,
        )
        assertTrue("Message after exactly 30 minutes must settle", res4.isNotEmpty())
        val state4 = repo.getOrCreateState(assistantId, nowMs = baseMs + 70 * 60 * 1000L)
        assertNotNull(state4)
        assertTrue("Attachment after 70min evolution and master_message must be valid", state4.drives["attachment"]!!.value in 10.0..50.0)

        // 5. 重试相同 eventId 不得重复结算
        val res4Retry = repo.applyMasterMessageOnce(
            assistantId = assistantId,
            eventId = "evt_msg_4",
            sourceId = "msg_4",
            nowMs = baseMs + 120 * 60 * 1000L,
        )
        assertTrue("Retrying same eventId must be idempotent", res4Retry.isEmpty())

        // 6. 设备时间回拨不结算
        val resClockBack = repo.applyMasterMessageOnce(
            assistantId = assistantId,
            eventId = "evt_msg_clock_back",
            sourceId = "msg_clock_back",
            nowMs = baseMs + 65 * 60 * 1000L, // 比 msg_4 的 70m 更早
        )
        assertTrue("Clock skew must not settle", resClockBack.isEmpty())

        // 7. 不同 Assistant 互不影响
        val resOther = repo.applyMasterMessageOnce(
            assistantId = "other-assistant",
            eventId = "evt_other_1",
            sourceId = "msg_other_1",
            nowMs = baseMs + 2 * 60 * 1000L,
        )
        assertTrue("Different assistant must settle independently", resOther.isNotEmpty())
    }

    @Test
    fun testNewGeneralEventsAndValues() {
        val state = DesireState()
        // 初始值：duty 40, curiosity 40, reflection 30, stress 20, joy 50

        // 1. task_added: duty +10, stress +2
        val ch1 = DesireEventEngine.applyEvent(state, "task_added")
        assertTrue(ch1.isNotEmpty())
        // duty surprise: 1.0 + (60/100)*0.5 = 1.3 -> delta = 13.0 -> 40 + 13 = 53.0
        // stress surprise: 1.0 + (80/100)*0.5 = 1.4 -> delta = 2.8 -> 20 + 2.8 = 22.8
        assertEquals(53.0, state.drives["duty"]!!.value, 0.001)
        assertEquals(22.8, state.drives["stress"]!!.value, 0.001)

        // 2. interest_sparked: curiosity +10, reflection +2
        val ch2 = DesireEventEngine.applyEvent(state, "interest_sparked")
        assertTrue(ch2.isNotEmpty())
        // curiosity surprise: 1.0 + (60/100)*0.5 = 1.3 -> delta = 13.0 -> 40 + 13 = 53.0
        // reflection surprise: 1.0 + (70/100)*0.5 = 1.35 -> delta = 2.7 -> 30 + 2.7 = 32.7
        assertEquals(53.0, state.drives["curiosity"]!!.value, 0.001)
        assertEquals(32.7, state.drives["reflection"]!!.value, 0.001)

        // 3. setback: stress +10, joy -8, reflection +4
        val ch3 = DesireEventEngine.applyEvent(state, "setback")
        assertTrue(ch3.isNotEmpty())
        assertTrue(state.drives["stress"]!!.value > 22.8)
        assertTrue(state.drives["joy"]!!.value < 50.0)
        assertTrue(state.drives["reflection"]!!.value > 32.7)
    }
}

