/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesireSchedulerTest {

    @Test
    fun testSlotEventIdDeduplication() {
        val assistantId = "assistant-test-1"
        val slotEpochMs = 1700000000000L

        // Both Alarm and Worker firing for the same slot should produce the identical event ID
        val alarmEventId = "${assistantId}_tick_$slotEpochMs"
        val workerEventId = "${assistantId}_tick_$slotEpochMs"

        assertEquals(alarmEventId, workerEventId)
        assertEquals("assistant-test-1_tick_1700000000000", alarmEventId)
    }

    @Test
    fun testCatchupStepBounding() {
        val intervalMs = 1800000L // 30 minutes

        // Case 1: Elapsed 2 hours -> 4 intervals, steps = 4, catchup = 3
        val elapsed2h = 2 * 3600 * 1000L
        val steps2h = (elapsed2h / intervalMs).toInt().coerceIn(1, 96)
        assertEquals(4, steps2h)

        // Case 2: Elapsed 10 days -> must be capped at 96
        val elapsed10d = 10 * 24 * 3600 * 1000L
        val steps10d = (elapsed10d / intervalMs).toInt().coerceIn(1, 96)
        assertEquals(96, steps10d)
    }

    @Test
    fun testDesireActiveContextPromptStructure() {
        val prompt = DesireScheduler.buildDesireActiveContext(
            reason = "miss",
            assistant = null,
        )

        // 包含对应契机提示词与表达要求
        assertTrue("Must include Chinese trigger reason", prompt.contains("触发原因：很想你，突然特别想联系你"))
        assertFalse("Must NOT contain [PASS] fallback", prompt.contains("[PASS]"))
        assertTrue("Must forbid empty opener like '在干嘛' or '吃了吗'", prompt.contains("严禁无营养的查户口式空洞发问"))
        assertTrue("Must require concrete details or feelings", prompt.contains("必须带上具体的事情、真实的感受或有趣的日常细节"))

        // 不包含任何未解决念头、执念或独白文本
        assertFalse("Must NOT include monologue header or content", prompt.contains("内心感受"))
        assertFalse("Must NOT include thought prefix or content", prompt.contains("念头"))
        assertFalse("Must NOT include obsession prefix or content", prompt.contains("执念"))

        // 验证自定义契机提示词生效
        val customAssistant = me.rerere.rikkahub.data.model.Assistant(
            desireActiveSendPrompts = mapOf("miss" to "好久没见你了，想找你聊聊天")
        )
        val customPrompt = DesireScheduler.buildDesireActiveContext(
            reason = "miss",
            assistant = customAssistant,
        )
        assertTrue("Must use custom active send prompt", customPrompt.contains("触发原因：好久没见你了，想找你聊聊天"))
        assertFalse("Must NOT include monologue header in custom prompt", customPrompt.contains("内心感受"))
        assertFalse("Must NOT include thoughts in custom prompt", customPrompt.contains("念头"))
    }
}
