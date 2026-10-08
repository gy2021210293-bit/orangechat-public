/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesireActiveSendPolicyTest {

    private val baseDrives = createDefaultDrives().mapValues { it.value.value }

    @Test
    fun testPresentSuppressesSend() {
        val highAttachment = baseDrives + ("attachment" to 99.0)
        val decision = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = highAttachment,
            absentHours = 0.2, // 用户半小时前刚说过话
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = 1_000_000_000L
        )
        assertFalse("用户在场时不得主动发消息", decision.shouldSend)
    }

    @Test
    fun testCooldownSuppressesSend() {
        val highAttachment = baseDrives + ("attachment" to 95.0)
        val now = 1_000_000_000_000L
        val recentSent = now - (30 * 60 * 1000L) // 30分钟前刚发过（冷却需1小时）
        val decision = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = highAttachment,
            absentHours = 5.0,
            lastSentEpochMs = recentSent,
            countToday = 1,
            nowEpochMs = now
        )
        assertFalse("1小时冷却期内不得重复主动发", decision.shouldSend)
    }

    @Test
    fun testDailyLimitNotSuppressed() {
        val highAttachment = baseDrives + ("attachment" to 95.0)
        val now = 1_000_000_000_000L
        val decision = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = highAttachment,
            absentHours = 5.0,
            lastSentEpochMs = null,
            countToday = 10, // 每日上限已移除，今日多次发送不阻断
            nowEpochMs = now
        )
        assertTrue("移除每日上限后应正常允许发送", decision.shouldSend)
    }

    @Test
    fun testMinAbsentHoursSuppressesSend() {
        val highAttachment = baseDrives + ("attachment" to 95.0)
        val now = 1_000_000_000_000L
        val decision = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = highAttachment,
            absentHours = 2.0, // 小于3小时最小离线时间，且小于15小时
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now
        )
        assertFalse("未达到3小时最小离线间隔时不触发情绪主动发送", decision.shouldSend)
    }

    @Test
    fun testLongAbsentHardTrigger() {
        val now = 1_000_000_000_000L
        val decision = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = baseDrives, // 即使驱动力均为基线值
            absentHours = 7.0, // 超过6小时太久没联系
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now
        )
        assertTrue(decision.shouldSend)
        assertEquals("long_absent", decision.reason)
    }

    @Test
    fun testAttachmentTrigger() {
        val drives = baseDrives + ("attachment" to 89.0)
        val now = 1_000_000_000_000L
        val decision = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = drives,
            absentHours = 4.0,
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now
        )
        assertTrue(decision.shouldSend)
        assertEquals("attachment", decision.reason)
    }

    @Test
    fun testJoyTriggerJoy() {
        val drives = baseDrives + ("joy" to 92.0)
        val now = 1_000_000_000_000L
        val decision = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = drives,
            absentHours = 4.0,
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now
        )
        assertTrue(decision.shouldSend)
        assertEquals("joy", decision.reason)
    }

    @Test
    fun testStressOrFatigueTriggerIndependent() {
        val drivesStress = baseDrives + ("stress" to 86.0)
        val now = 1_000_000_000_000L
        val decisionStress = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = drivesStress,
            absentHours = 4.0,
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now
        )
        assertTrue(decisionStress.shouldSend)
        assertEquals("stress", decisionStress.reason)

        val drivesFatigue = baseDrives + ("fatigue" to 89.0)
        val decisionFatigue = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = drivesFatigue,
            absentHours = 4.0,
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now
        )
        assertTrue(decisionFatigue.shouldSend)
        assertEquals("fatigue", decisionFatigue.reason)

        // 验证两者自定义提示词完全独立生效，绝不互相覆盖
        val testAssistant = me.rerere.rikkahub.data.model.Assistant(
            desireActiveSendPrompts = mapOf(
                "stress" to "工作压力好大，想抱抱小雨缓一下",
                "fatigue" to "走了一天好累好困，想靠在小雨肩膀上休息"
            )
        )
        assertEquals("工作压力好大，想抱抱小雨缓一下", DesireActiveSendPolicy.getActiveSendPrompt(testAssistant, "stress"))
        assertEquals("走了一天好累好困，想靠在小雨肩膀上休息", DesireActiveSendPolicy.getActiveSendPrompt(testAssistant, "fatigue"))
    }

    @Test
    fun testAllRemainingDimensionsTrigger() {
        val now = 1_000_000_000_000L

        // curiosity (>= 85.0)
        val dCuriosity = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = baseDrives + ("curiosity" to 86.0),
            absentHours = 4.0,
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now,
        )
        assertTrue(dCuriosity.shouldSend)
        assertEquals("curiosity", dCuriosity.reason)

        // duty (>= 80.0)
        val dDuty = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = baseDrives + ("duty" to 82.0),
            absentHours = 4.0,
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now,
        )
        assertTrue(dDuty.shouldSend)
        assertEquals("duty", dDuty.reason)

        // social (>= 85.0)
        val dSocial = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = baseDrives + ("social" to 86.0),
            absentHours = 4.0,
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now,
        )
        assertTrue(dSocial.shouldSend)
        assertEquals("social", dSocial.reason)

        // intimacy (>= 88.0)
        val dIntimacy = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = baseDrives + ("intimacy" to 89.0),
            absentHours = 4.0,
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now,
        )
        assertTrue(dIntimacy.shouldSend)
        assertEquals("intimacy", dIntimacy.reason)

        // reflection (>= 85.0)
        val dReflection = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = baseDrives + ("reflection" to 86.0),
            absentHours = 4.0,
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now,
        )
        assertTrue(dReflection.shouldSend)
        assertEquals("reflection", dReflection.reason)
    }

    @Test
    fun testCustomThresholdAndDisable() {
        val now = 1_000_000_000_000L
        val dummyAssistant = me.rerere.rikkahub.data.model.Assistant(
            desireActiveSendDriveEnabled = mapOf("curiosity" to false),
            desireActiveSendDriveThresholds = mapOf("duty" to 95.0),
            desireActiveSendPrompts = mapOf("duty" to "去看看小雨今天的待办和打卡")
        )

        // curiosity 被显式禁用
        val dCuriosity = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = baseDrives + ("curiosity" to 90.0),
            absentHours = 4.0,
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now,
            assistant = dummyAssistant,
        )
        assertFalse("禁用维度不应触发", dCuriosity.shouldSend)

        // duty 阈值调高到 95.0，85.0 不应触发
        val dDuty85 = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = baseDrives + ("duty" to 85.0),
            absentHours = 4.0,
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now,
            assistant = dummyAssistant,
        )
        assertFalse("未达到自定义高阈值不应触发", dDuty85.shouldSend)

        // duty 达到 96.0，应触发并读取自定义提示词
        val dDuty96 = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = baseDrives + ("duty" to 96.0),
            absentHours = 4.0,
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now,
            assistant = dummyAssistant,
        )
        assertTrue(dDuty96.shouldSend)
        assertEquals("duty", dDuty96.reason)
        assertEquals("去看看小雨今天的待办和打卡", DesireActiveSendPolicy.getActiveSendPrompt(dummyAssistant, "duty"))
    }

    @Test
    fun testEmptyOpenerDetection() {
        assertTrue(DesireActiveSendPolicy.isEmptyOpener("跟你说个事"))
        assertTrue(DesireActiveSendPolicy.isEmptyOpener("我问你个事呢"))
        assertTrue(DesireActiveSendPolicy.isEmptyOpener("你知道吗？"))
        assertTrue(DesireActiveSendPolicy.isEmptyOpener("她，想问你件事~"))
        assertTrue(DesireActiveSendPolicy.isEmptyOpener("你猜怎么着 嗯啊"))

        // 真实有内容的开头不应当被视为空话头
        assertFalse(
            DesireActiveSendPolicy.isEmptyOpener(
                "你知道吗？今天公司楼下开了一家超好吃的甜品店，我给你带了一份抹茶蛋糕！"
            )
        )
        assertFalse(DesireActiveSendPolicy.isEmptyOpener("想你了，忙完了记得理我一下哦"))
        assertFalse(DesireActiveSendPolicy.isEmptyOpener("今天散步看到一只好可爱的小柯基"))
    }

    @Test
    fun testNormalizedUrgencyRanking() {
        val now = 1_000_000_000_000L
        // reflection: threshold = 45, value = 50 -> excess = 5, urgency = 5 / 55 ≈ 0.0909
        // duty: threshold = 55, value = 65 -> excess = 10, urgency = 10 / 45 ≈ 0.2222
        // 虽然 duty 阈值更高，但标准化紧迫度更高，应选 duty
        val drives = baseDrives + mapOf(
            "reflection" to 50.0,
            "duty" to 65.0,
        )
        val decision = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = drives,
            absentHours = 4.0,
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now,
        )
        assertTrue(decision.shouldSend)
        assertEquals("duty", decision.reason)
    }

    @Test
    fun testThresholdBoundaryAndTieBreaking() {
        val now = 1_000_000_000_000L
        val assistantThreshold100 = me.rerere.rikkahub.data.model.Assistant(
            desireActiveSendDriveThresholds = mapOf("joy" to 100.0)
        )
        val decision99 = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = baseDrives + ("joy" to 99.9),
            absentHours = 4.0,
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now,
            assistant = assistantThreshold100,
        )
        assertFalse("100 阈值下 99.9 不触发", decision99.shouldSend)

        val decision100 = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = baseDrives + ("joy" to 100.0),
            absentHours = 4.0,
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now,
            assistant = assistantThreshold100,
        )
        assertTrue("100 阈值下 100.0 触发", decision100.shouldSend)
        assertEquals("joy", decision100.reason)
    }

    @Test
    fun testLongAbsentSuppressedWhenAlreadySentInCurrentAbsence() {
        val now = 1_000_000_000_000L
        val decision = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = baseDrives,
            absentHours = 7.0, // 超过 6 小时
            lastSentEpochMs = now - (2 * 3600 * 1000L), // 2小时前发过
            countToday = 1,
            nowEpochMs = now,
            hasSentInCurrentAbsence = true, // 本失联阶段已发过
        )
        assertFalse("同一失联阶段已有主动发送，long_absent 不得重复触发", decision.shouldSend)
    }

    @Test
    fun testDutySuppressedWhenAlreadySentInCurrentAbsence() {
        val now = 1_000_000_000_000L
        val drives = baseDrives + ("duty" to 80.0)
        val decision = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = drives,
            absentHours = 4.0,
            lastSentEpochMs = now - (2 * 3600 * 1000L),
            countToday = 1,
            nowEpochMs = now,
            hasSentInCurrentAbsence = true, // 本失联阶段已发过
        )
        assertFalse("同一失联阶段已有主动发送，duty 不得重复触发", decision.shouldSend)
    }

    @Test
    fun testPassCooldownSuppressesSend() {
        val now = 1_000_000_000_000L
        val drives = baseDrives + ("attachment" to 95.0)
        val decision = DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = drives,
            absentHours = 4.0,
            lastSentEpochMs = null,
            countToday = 0,
            nowEpochMs = now,
            passCooldownEndEpochMs = now + 10 * 60 * 1000L, // 处于 PASS 冷却期
        )
        assertFalse("处于 PASS 冷却期内不得触发主动发送", decision.shouldSend)
    }
}
