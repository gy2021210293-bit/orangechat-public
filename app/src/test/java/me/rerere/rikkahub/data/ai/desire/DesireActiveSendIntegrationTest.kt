/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.service.resolveProactiveAllowAllTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 欲望系统主动发送端到端集成测试：
 * 覆盖【自定义规则 -> 调度初筛 -> 服务防竞态二次校验 -> 独立提示词注入 -> 全局单开关权限 -> 成功发送记录】完整链路。
 */
class DesireActiveSendIntegrationTest {

    private class FakeDesireDao : DesireDao {
        val items = mutableMapOf<String, AutonomousItemEntity>()
        val plans = mutableMapOf<String, AutonomousWakePlanEntity>()
        val shares = mutableMapOf<Pair<String, String>, AutonomousShareEntity>()
        val stateMap = mutableMapOf<String, DesireStateEntity>()
        val tickLogs = mutableListOf<DesireTickLogEntity>()
        val receipts = mutableListOf<DesireEventReceiptEntity>()
        val activeSends = mutableListOf<DesireActiveSendEntity>()

        override suspend fun getState(assistantId: String): DesireStateEntity? = stateMap[assistantId]

        override suspend fun upsertState(state: DesireStateEntity) {
            stateMap[state.assistantId] = state
        }

        override suspend fun insertReceipt(receipt: DesireEventReceiptEntity): Long {
            receipts.add(receipt)
            return receipts.size.toLong()
        }

        override suspend fun countReceipt(eventId: String): Int =
            receipts.count { it.eventId == eventId }

        override suspend fun getLastUserMasterMessageReceipt(assistantId: String): DesireEventReceiptEntity? =
            receipts.filter { it.assistantId == assistantId && it.eventName == "master_message" && it.sourceType == "user_message" }
                .maxByOrNull { it.createdAt }

        override suspend fun insertLog(log: DesireTickLogEntity): Long {
            tickLogs.add(log)
            return tickLogs.size.toLong()
        }

        override suspend fun listRecentLogs(assistantId: String, limit: Int): List<DesireTickLogEntity> =
            tickLogs.filter { it.assistantId == assistantId }.takeLast(limit)

        override suspend fun pruneLogs(assistantId: String, keepCount: Int) {
            // no-op
        }

        override suspend fun insertActiveSend(send: DesireActiveSendEntity): Long {
            activeSends.add(send)
            return activeSends.size.toLong()
        }

        override suspend fun getLastActiveSend(assistantId: String): DesireActiveSendEntity? =
            activeSends.filter { it.assistantId == assistantId }.maxByOrNull { it.sentAt }

        override suspend fun countActiveSendsBetween(assistantId: String, startEpochMs: Long, endEpochMs: Long): Int =
            activeSends.count { it.assistantId == assistantId && it.sentAt in startEpochMs..endEpochMs }

        override suspend fun countActiveSendsSince(assistantId: String, sinceEpochMs: Long): Int =
            activeSends.count { it.assistantId == assistantId && it.sentAt >= sinceEpochMs }

        override suspend fun deleteStateForAssistant(assistantId: String) {
            stateMap.remove(assistantId)
        }

        override suspend fun deleteLogsForAssistant(assistantId: String) {
            tickLogs.removeAll { it.assistantId == assistantId }
        }

        override suspend fun deleteActiveSendsForAssistant(assistantId: String) {
            activeSends.removeAll { it.assistantId == assistantId }
        }

        override suspend fun deleteReceiptsForAssistant(assistantId: String) {
            receipts.removeAll { it.assistantId == assistantId }
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

    private lateinit var fakeDao: FakeDesireDao
    private lateinit var desireRepository: DesireRepository
    private val json = Json { ignoreUnknownKeys = true }

    private fun persistState(assistantId: String, state: DesireState) = kotlinx.coroutines.runBlocking {
        fakeDao.upsertState(
            DesireStateEntity(
                assistantId = assistantId,
                drivesJson = json.encodeToString(state.drives),
                thoughtsJson = json.encodeToString(state.thoughts),
                lastTickAt = System.currentTimeMillis(),
                tickCount = 1,
                nextTickAt = System.currentTimeMillis() + 1800000L,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    @Before
    fun setUp() {
        fakeDao = FakeDesireDao()
        desireRepository = DesireRepository(fakeDao)
    }

    @Test
    fun testCustomRules_endToEndScheduleAndSecondaryCheck() = kotlinx.coroutines.runBlocking {
        val assistantId = Uuid.random().toString()
        val customAssistant = Assistant(
            id = Uuid.parse(assistantId),
            desireSystemEnabled = true,
            desireActiveSendEnabled = true,
            desireActiveSendMinAbsentHours = 1.0, // 自定义：只要离线满 1 小时即可开始评估（默认需 3 小时）
            desireActiveSendLongAbsentHours = 2.5, // 自定义：离线 2.5 小时即硬触发（默认需 6 小时）
            desireActiveSendDriveThresholds = mapOf(
                "duty" to 75.0, // 自定义：责任感 75 即可触发（默认 80）
            ),
            desireActiveSendPrompts = mapOf(
                "duty" to "今天小雨吃晚饭了吗？提醒她要按时吃饭和喝水",
                "long_absent" to "小雨离开两个多小时了，静静趴在桌前等她"
            ),
        )

        // 1. 初始化欲望状态：设置 duty 为 76.0 (超标)，absentHours 为 1.5h (介于 1.0h 和 2.5h 之间)
        val state = desireRepository.getOrCreateState(assistantId)
        state.drives["duty"]?.value = 76.0
        persistState(assistantId, state)

        val nowMs = 1_700_000_000_000L
        val absentHours = 1.5

        // 2. 调度器初筛判断 (Scheduler First Check)
        val schedulerDecision = desireRepository.checkShouldSendActiveMessage(
            assistantId = assistantId,
            absentHours = absentHours,
            nowMs = nowMs,
            assistant = customAssistant,
        )
        assertTrue("在自定义 1.0h 门槛和 75.0 阈值下，初筛必须通过", schedulerDecision.shouldSend)
        assertEquals("duty", schedulerDecision.reason)

        // 3. 服务防竞态二次校验 (Service Secondary Check)
        // 关键验证点：二次校验传入了 assistant，绝不被默认的 3.0h 门槛错误拦截！
        val secondaryDecision = desireRepository.checkShouldSendActiveMessage(
            assistantId = assistantId,
            absentHours = absentHours,
            nowMs = nowMs,
            assistant = customAssistant,
        )
        assertTrue("二次防竞态校验必须使用传入的助手自定义规则，确保不被默认规则拦截", secondaryDecision.shouldSend)
        assertEquals("duty", secondaryDecision.reason)

        // 4. 契机提示词注入校验
        val prompt = DesireActiveSendPolicy.getActiveSendPrompt(customAssistant, secondaryDecision.reason!!)
        assertEquals("今天小雨吃晚饭了吗？提醒她要按时吃饭和喝水", prompt)

        // 5. 模拟服务发送成功后记录
        desireRepository.recordSuccessfulActiveSend(
            assistantId = assistantId,
            reason = secondaryDecision.reason ?: "",
            content = "小雨，你今天按时吃饭了吗？要多喝温水哦",
            conversationId = Uuid.random().toString(),
            messageId = Uuid.random().toString(),
            nowMs = nowMs,
        )

        // 验证数据库已成功记录
        val lastSend = fakeDao.getLastActiveSend(assistantId)
        assertNotNull(lastSend)
        assertEquals("duty", lastSend?.reason)
        assertEquals(nowMs, lastSend?.sentAt)

        // 6. 验证发送后的 1 小时冷却期生效
        val inCooldownDecision = desireRepository.checkShouldSendActiveMessage(
            assistantId = assistantId,
            absentHours = absentHours + 0.1,
            nowMs = nowMs + 1800_000L, // 30 分钟后
            assistant = customAssistant,
        )
        assertFalse("成功发送后处于 1 小时冷却期内，不得重复发送", inCooldownDecision.shouldSend)
    }

    @Test
    fun testStressAndFatigue_independentPromptsNoClobber() = kotlinx.coroutines.runBlocking {
        val assistantId = Uuid.random().toString()
        val assistant = Assistant(
            id = Uuid.parse(assistantId),
            desireActiveSendPrompts = mapOf(
                "stress" to "【专属压力提示词】有些焦虑紧绷，想找小雨倾诉被抱抱",
                "fatigue" to "【专属疲劳提示词】身体好累好困，想在小雨身边睡一会儿",
            )
        )

        // Case A: 只有压力值超标 (stress = 88.0, fatigue = 20.0)
        val stateStress = desireRepository.getOrCreateState(assistantId)
        stateStress.drives["stress"]?.value = 88.0
        stateStress.drives["fatigue"]?.value = 20.0
        persistState(assistantId, stateStress)

        val decisionStress = desireRepository.checkShouldSendActiveMessage(
            assistantId = assistantId,
            absentHours = 4.0,
            assistant = assistant,
        )
        assertTrue(decisionStress.shouldSend)
        assertEquals("stress", decisionStress.reason)
        val promptStress = DesireActiveSendPolicy.getActiveSendPrompt(assistant, decisionStress.reason!!)
        assertEquals("【专属压力提示词】有些焦虑紧绷，想找小雨倾诉被抱抱", promptStress)

        // Case B: 只有疲劳值超标 (fatigue = 92.0, stress = 20.0)
        val stateFatigue = desireRepository.getOrCreateState(assistantId)
        stateFatigue.drives["stress"]?.value = 20.0
        stateFatigue.drives["fatigue"]?.value = 92.0
        persistState(assistantId, stateFatigue)

        val decisionFatigue = desireRepository.checkShouldSendActiveMessage(
            assistantId = assistantId,
            absentHours = 4.0,
            assistant = assistant,
        )
        assertTrue(decisionFatigue.shouldSend)
        assertEquals("fatigue", decisionFatigue.reason)
        val promptFatigue = DesireActiveSendPolicy.getActiveSendPrompt(assistant, decisionFatigue.reason!!)
        assertEquals("【专属疲劳提示词】身体好累好困，想在小雨身边睡一会儿", promptFatigue)
    }

    @Test
    fun testToolAuthorizationUnifiedGlobalSwitch() {
        val defaultProactive = ProactiveMessageSetting(allowAllToolsAndPlugins = false)
        val permissiveProactive = ProactiveMessageSetting(allowAllToolsAndPlugins = true)

        // 1. 全局开关关闭时，主动联络（含情感与定时）统一不获取全部工具权限
        val resolvedDisabled = resolveProactiveAllowAllTools(
            isWorkflowWake = false,
            workflowExtra = false,
            proactiveSetting = defaultProactive,
        )
        assertFalse("全局开关关闭时，主动联络统一不具备工具与插件权限", resolvedDisabled)

        // 2. 全局开关开启时，主动联络统一获得工具与插件权限
        val resolvedEnabled = resolveProactiveAllowAllTools(
            isWorkflowWake = false,
            workflowExtra = false,
            proactiveSetting = permissiveProactive,
        )
        assertTrue("全局开关开启时，主动联络统一获得工具与插件权限", resolvedEnabled)

        // 3. 工作流唤醒独立遵从自身配置
        val resolvedWorkflow = resolveProactiveAllowAllTools(
            isWorkflowWake = true,
            workflowExtra = true,
            proactiveSetting = defaultProactive,
        )
        assertTrue("工作流唤醒保留自身独立显式配置", resolvedWorkflow)
    }
}
