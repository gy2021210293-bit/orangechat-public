/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveMessageStructureTest {

    @Test
    fun scheduledTriggerStructure_dynamicSnapshotBetweenHistoryAndFinalInstruction() {
        val dynamicContext = "<dynamic_context generated_at=\"2026-08-20 10:00\">Location: Beijing</dynamic_context>"
        val messages = mergeAdjacentSameRoleMessages(
            buildInitialProactiveMessages(
                systemPrompt = "system prefix",
                historyMessages = listOf(
                    UIMessage.user("old user msg"),
                    UIMessage.assistant("old assistant msg"),
                ),
                dynamicContext = dynamicContext,
                processedUserMessages = listOf(
                    UIMessage.user("定时触发指令，距离用户上次回复已过去 30 分钟。"),
                ),
            )
        )

        // Expected: SYSTEM, USER(old), ASSISTANT(old), USER(dynamic), USER(instruction)
        assertEquals(5, messages.size)
        assertEquals(MessageRole.SYSTEM, messages[0].role)
        assertEquals(MessageRole.USER, messages[1].role)
        assertEquals(MessageRole.ASSISTANT, messages[2].role)
        assertEquals(MessageRole.USER, messages[3].role)
        assertEquals(MessageRole.USER, messages[4].role)

        // Dynamic snapshot is message[3], with metadata
        val dynamicSnapshot = messages[3]
        val dynamicText = dynamicSnapshot.parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertEquals(dynamicContext, dynamicText)
        assertNotNull("Dynamic snapshot should have metadata", dynamicSnapshot.metadata)
        assertEquals(
            "true",
            dynamicSnapshot.metadata?.get("dynamic_environment")?.toString()
        )

        // Final instruction is message[4], without dynamic metadata
        val finalInstruction = messages[4]
        val finalText = finalInstruction.parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue("Final instruction should contain idle time", finalText.contains("30 分钟"))
        assertNull("Final instruction should not have dynamic metadata", finalInstruction.metadata)
    }

    @Test
    fun deviceEventTriggerStructure_timelineNotLost() {
        val deviceEventContext = "## 设备事件时间线\n- 10:00 亮屏\n- 10:01 打开微信\n- 10:02 回到桌面"
        val proactiveContextBody = "[主动消息上下文]\n当前时间: 2026-08-20 10:03:00"

        // Call the production pure function directly — no manual re-assembly
        val contextStr = buildProactiveMessageContextText(
            proactiveContextBody = proactiveContextBody,
            isFromDeviceEvent = true,
            idleMinutes = 5,
            deviceEventContext = deviceEventContext,
        )

        assertTrue("Context should contain device event trigger header", contextStr.contains("设备事件触发"))
        assertTrue("Context should contain device event timeline", contextStr.contains("设备事件时间线"))
        assertTrue("Context should contain screen on event", contextStr.contains("亮屏"))
        assertTrue("Context should contain app switch event", contextStr.contains("打开微信"))
        assertTrue("Context should contain return to desktop", contextStr.contains("回到桌面"))
        assertTrue("Context should contain proactive body", contextStr.contains("[主动消息上下文]"))
        assertTrue("Context should contain idle time", contextStr.contains("5 分钟"))
    }

    @Test
    fun scheduledTrigger_noDeviceEventSection() {
        val proactiveContextBody = "[主动消息上下文]\n当前时间: 2026-08-20 10:03:00"

        val contextStr = buildProactiveMessageContextText(
            proactiveContextBody = proactiveContextBody,
            isFromDeviceEvent = false,
            idleMinutes = 30,
            deviceEventContext = null,
        )

        assertTrue("Should use scheduled trigger header", contextStr.contains("## 主动消息触发（定时触发）"))
        assertFalse("Should not contain device event header", contextStr.contains("## ⚠️ 当前触发原因"))
        assertFalse("Should not contain device event timeline", contextStr.contains("设备事件时间线"))
        assertTrue("Should contain proactive body", contextStr.contains("[主动消息上下文]"))
        assertTrue("Should contain idle time", contextStr.contains("30 分钟"))
    }

    @Test
    fun deviceEventContext_nullAndEmpty_noExtraBlankLines() {
        val proactiveContextBody = "[主动消息上下文]\n当前时间: 2026-08-20 10:03:00"

        val withNull = buildProactiveMessageContextText(
            proactiveContextBody = proactiveContextBody,
            isFromDeviceEvent = true,
            idleMinutes = 5,
            deviceEventContext = null,
        )
        val withEmpty = buildProactiveMessageContextText(
            proactiveContextBody = proactiveContextBody,
            isFromDeviceEvent = true,
            idleMinutes = 5,
            deviceEventContext = "",
        )

        // Neither should contain a device event section
        assertFalse("null deviceEventContext should not add timeline", withNull.contains("设备事件时间线"))
        assertFalse("empty deviceEventContext should not add timeline", withEmpty.contains("设备事件时间线"))

        // Both should still contain the trigger header and proactive body
        assertTrue(withNull.contains("设备事件触发"))
        assertTrue(withEmpty.contains("设备事件触发"))
        assertTrue(withNull.contains("[主动消息上下文]"))
        assertTrue(withEmpty.contains("[主动消息上下文]"))
    }

    @Test
    fun proactiveBodyComesAfterTriggerAndDeviceEvent() {
        val deviceEventContext = "## 设备事件时间线\n- 10:00 亮屏"
        val proactiveContextBody = "[主动消息上下文]\n当前时间: 2026-08-20 10:03:00"

        val contextStr = buildProactiveMessageContextText(
            proactiveContextBody = proactiveContextBody,
            isFromDeviceEvent = true,
            idleMinutes = 5,
            deviceEventContext = deviceEventContext,
        )

        val triggerPos = contextStr.indexOf("设备事件触发")
        val deviceEventPos = contextStr.indexOf("设备事件时间线")
        val proactiveBodyPos = contextStr.indexOf("[主动消息上下文]")
        val idlePos = contextStr.indexOf("5 分钟")

        assertTrue("Trigger header should come first", triggerPos < deviceEventPos)
        assertTrue("Device event should come before proactive body", deviceEventPos < proactiveBodyPos)
        assertTrue("Proactive body should come before idle time", proactiveBodyPos < idlePos)
    }

    @Test
    fun workflowWakeStructure_customRequirementPreserved() {
        val instruction = buildWorkflowWakeUserInstruction(
            "自定义处理要求：调用通知工具提醒用户明天有会议"
        )

        assertTrue("Should contain workflow wake header", instruction.contains("## 工作流唤醒"))
        assertTrue("Should contain custom requirement", instruction.contains("调用通知工具提醒用户明天有会议"))
        assertTrue("Should explain user approved", instruction.contains("这是用户已批准执行的工作流任务。"))
        assertTrue("Should not allow skip", instruction.contains("不要用 SKIP、忽略任务或泛化的主动消息策略替代自定义处理要求。"))
    }

    @Test
    fun dynamicMetadataAndMessagePosition_correctPlacement() {
        val dynamicContext = "<dynamic_context generated_at=\"2026-08-20 14:30\">Battery: 80%</dynamic_context>"
        val messages = mergeAdjacentSameRoleMessages(
            buildInitialProactiveMessages(
                systemPrompt = "system",
                historyMessages = listOf(UIMessage.assistant("history reply")),
                dynamicContext = dynamicContext,
                processedUserMessages = listOf(UIMessage.user("final instruction")),
            )
        )

        // SYSTEM, ASSISTANT(history), USER(dynamic), USER(instruction)
        assertEquals(4, messages.size)

        // Dynamic snapshot: position 2, role USER, has metadata
        assertEquals(MessageRole.USER, messages[2].role)
        assertEquals(dynamicContext, messages[2].parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals("true", messages[2].metadata?.get("dynamic_environment")?.toString())

        // Final instruction: position 3, role USER, no dynamic metadata
        assertEquals(MessageRole.USER, messages[3].role)
        assertEquals("final instruction", messages[3].parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertNull(messages[3].metadata)
    }

    @Test
    fun sameRoleNormalization_doesNotMergeDynamicSnapshot() {
        // Both dynamic snapshot and final instruction are USER role.
        // mergeAdjacentSameRoleMessages must NOT merge them because the snapshot has dynamic_environment metadata.
        val dynamicContext = "<dynamic_context generated_at=\"2026-08-20 15:00\">Location: Shanghai</dynamic_context>"
        val rawMessages = buildInitialProactiveMessages(
            systemPrompt = "system",
            historyMessages = listOf(UIMessage.assistant("history")),
            dynamicContext = dynamicContext,
            processedUserMessages = listOf(UIMessage.user("执行工作流任务")),
        )

        // Before merge: should have 4 messages (SYSTEM, ASSISTANT, USER(dynamic), USER(instruction))
        assertEquals(4, rawMessages.size)

        val merged = mergeAdjacentSameRoleMessages(rawMessages)

        // After merge: still 4 messages — dynamic snapshot must not be merged with final instruction
        assertEquals(
            "Dynamic snapshot should not be merged with final USER instruction",
            4,
            merged.size,
        )
        assertEquals(MessageRole.SYSTEM, merged[0].role)
        assertEquals(MessageRole.ASSISTANT, merged[1].role)
        assertEquals(MessageRole.USER, merged[2].role)
        assertEquals(MessageRole.USER, merged[3].role)

        // Verify the two USER messages are distinct
        val dynamicText = merged[2].parts.filterIsInstance<UIMessagePart.Text>().single().text
        val instructionText = merged[3].parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertEquals(dynamicContext, dynamicText)
        assertEquals("执行工作流任务", instructionText)

        // Verify metadata preservation
        assertEquals("true", merged[2].metadata?.get("dynamic_environment")?.toString())
        assertNull(merged[3].metadata)
    }

    @Test
    fun noDynamicContext_noExtraMessageInserted() {
        val messages = mergeAdjacentSameRoleMessages(
            buildInitialProactiveMessages(
                systemPrompt = "system",
                historyMessages = listOf(UIMessage.assistant("history")),
                dynamicContext = "",
                processedUserMessages = listOf(UIMessage.user("final instruction")),
            )
        )

        // SYSTEM, ASSISTANT(history), USER(instruction) — no dynamic snapshot
        assertEquals(3, messages.size)
        assertEquals(MessageRole.SYSTEM, messages[0].role)
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals(MessageRole.USER, messages[2].role)
        assertEquals("final instruction", messages[2].parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun adjacentAssistantMessages_mergedCorrectly() {
        // Two adjacent ASSISTANT messages should be merged (neither is a dynamic snapshot)
        val messages = mergeAdjacentSameRoleMessages(
            listOf(
                UIMessage.system("system"),
                UIMessage.assistant("reply 1"),
                UIMessage.assistant("reply 2"),
                UIMessage.user("question"),
            )
        )

        assertEquals(3, messages.size)
        assertEquals(MessageRole.SYSTEM, messages[0].role)
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals(MessageRole.USER, messages[2].role)

        // The two assistant messages should be merged
        val mergedText = messages[1].parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        assertTrue("Merged assistant should contain both replies", mergedText.contains("reply 1"))
        assertTrue("Merged assistant should contain both replies", mergedText.contains("reply 2"))
    }

    // --- New tests from Step 4 ---

    @Test
    fun historyEndsWithUser_thenDynamicSnapshotAndFinalInstruction() {
        // History ends with USER, dynamic snapshot and final instruction follow
        val dynamicContext = "<dynamic_context generated_at=\"2026-08-20 16:00\">Battery: 50%</dynamic_context>"
        val messages = mergeAdjacentSameRoleMessages(
            buildInitialProactiveMessages(
                systemPrompt = "system",
                historyMessages = listOf(
                    UIMessage.assistant("old reply"),
                    UIMessage.user("old question"),
                ),
                dynamicContext = dynamicContext,
                processedUserMessages = listOf(
                    UIMessage.user("定时触发指令，距离用户上次回复已过去 10 分钟。"),
                ),
            )
        )

        // SYSTEM, ASSISTANT(old), USER(old), USER(dynamic), USER(instruction)
        assertEquals(5, messages.size)
        assertEquals(MessageRole.SYSTEM, messages[0].role)
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals(MessageRole.USER, messages[2].role)
        assertEquals(MessageRole.USER, messages[3].role)
        assertEquals(MessageRole.USER, messages[4].role)

        // Dynamic snapshot preserved with metadata
        assertEquals(dynamicContext, messages[3].parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals("true", messages[3].metadata?.get("dynamic_environment")?.toString())

        // Final instruction at the end
        val finalText = messages[4].parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(finalText.contains("10 分钟"))
        assertNull(messages[4].metadata)
    }

    @Test
    fun processedUserMessages_containsTimeReminderAndFinalInstruction() {
        // TimeReminderTransformer may prepend a time reminder USER message before the final instruction
        val timeReminder = UIMessage.user("<time_reminder>Current time: 2026-08-20 16:00</time_reminder>")
        val finalInstruction = UIMessage.user("定时触发指令，距离用户上次回复已过去 20 分钟。")

        val messages = mergeAdjacentSameRoleMessages(
            buildInitialProactiveMessages(
                systemPrompt = "system",
                historyMessages = listOf(UIMessage.assistant("history")),
                dynamicContext = "",
                processedUserMessages = listOf(timeReminder, finalInstruction),
            )
        )

        // SYSTEM, ASSISTANT(history), USER(merged timeReminder + finalInstruction)
        // Without dynamic context, adjacent USER messages are merged by mergeAdjacentSameRoleMessages
        assertEquals(3, messages.size)
        assertEquals(MessageRole.SYSTEM, messages[0].role)
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals(MessageRole.USER, messages[2].role)

        // The merged USER should contain both time reminder and final instruction
        val mergedText = messages[2].parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        assertTrue("Should contain time reminder", mergedText.contains("time_reminder"))
        assertTrue("Should contain final instruction", mergedText.contains("20 分钟"))
    }

    @Test
    fun dynamicSnapshot_normalizedStillIndependentWithMetadata() {
        // After mergeAdjacentSameRoleMessages, dynamic snapshot should remain independent
        val dynamicContext = "<dynamic_context generated_at=\"2026-08-20 17:00\">Location: Tokyo</dynamic_context>"
        val rawMessages = buildInitialProactiveMessages(
            systemPrompt = "system",
            historyMessages = listOf(UIMessage.user("history")),
            dynamicContext = dynamicContext,
            processedUserMessages = listOf(UIMessage.user("current request")),
        )

        val merged = mergeAdjacentSameRoleMessages(rawMessages)

        // The history USER and dynamic snapshot USER should NOT be merged because
        // the dynamic snapshot has dynamic_environment metadata
        val dynamicSnapshot = merged.find { msg ->
            msg.metadata?.get("dynamic_environment")?.toString() == "true"
        }
        assertNotNull("Dynamic snapshot should exist after merge", dynamicSnapshot)
        assertEquals(dynamicContext, dynamicSnapshot!!.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals("true", dynamicSnapshot.metadata?.get("dynamic_environment")?.toString())
    }

    @Test
    fun noDynamicContext_adjacentUserMessages_mergedByDefaultRules() {
        // Without dynamic context, adjacent USER messages from history and processedUserMessages
        // should be merged by mergeAdjacentSameRoleMessages (neither has dynamic_environment metadata)
        val messages = mergeAdjacentSameRoleMessages(
            buildInitialProactiveMessages(
                systemPrompt = "system",
                historyMessages = listOf(UIMessage.user("history question")),
                dynamicContext = "",
                processedUserMessages = listOf(UIMessage.user("current request")),
            )
        )

        // SYSTEM, USER(merged history+current)
        assertEquals(2, messages.size)
        assertEquals(MessageRole.SYSTEM, messages[0].role)
        assertEquals(MessageRole.USER, messages[1].role)

        // The merged USER should contain both texts
        val mergedText = messages[1].parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        assertTrue("Should contain history question", mergedText.contains("history question"))
        assertTrue("Should contain current request", mergedText.contains("current request"))
    }

    @Test
    fun workflowWake_customRequirementAtTail() {
        // Workflow wakeup: custom requirement should be at the end of the message list
        val workflowInstruction = UIMessage.user("自定义处理要求：调用通知工具提醒用户明天有会议")
        val dynamicContext = "<dynamic_context generated_at=\"2026-08-20 18:00\">Battery: 90%</dynamic_context>"

        val messages = mergeAdjacentSameRoleMessages(
            buildInitialProactiveMessages(
                systemPrompt = "system",
                historyMessages = listOf(
                    UIMessage.user("old msg"),
                    UIMessage.assistant("old reply"),
                ),
                dynamicContext = dynamicContext,
                processedUserMessages = listOf(workflowInstruction),
            )
        )

        // SYSTEM, USER(old), ASSISTANT(old), USER(dynamic), USER(workflow)
        assertEquals(5, messages.size)

        // Last message should be the workflow instruction
        val lastMessage = messages.last()
        assertEquals(MessageRole.USER, lastMessage.role)
        val lastText = lastMessage.parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue("Last message should be workflow instruction", lastText.contains("自定义处理要求"))
        assertTrue("Last message should contain the custom prompt", lastText.contains("调用通知工具提醒用户明天有会议"))
        assertNull("Workflow instruction should not have dynamic metadata", lastMessage.metadata)
    }

    @Test
    fun timerThrottleAction_desireActiveBypassesWithoutTouchingTimerState() {
        // Desire active send must bypass timer deduplication and NOT touch last_triggered_time,
        // even if isForceTrigger is true.
        val action1 = determineTimerThrottleAction(
            isWorkflowWake = false,
            isDesireActive = true,
            isForceTrigger = true,
        )
        assertEquals(ProactiveTimerThrottleAction.BYPASS_NO_WRITE, action1)

        val action2 = determineTimerThrottleAction(
            isWorkflowWake = false,
            isDesireActive = true,
            isForceTrigger = false,
        )
        assertEquals(ProactiveTimerThrottleAction.BYPASS_NO_WRITE, action2)
    }

    @Test
    fun timerThrottleAction_workflowWakeBypassesWithoutTouchingTimerState() {
        val action = determineTimerThrottleAction(
            isWorkflowWake = true,
            isDesireActive = false,
            isForceTrigger = true,
        )
        assertEquals(ProactiveTimerThrottleAction.BYPASS_NO_WRITE, action)
    }

    @Test
    fun timerThrottleAction_scheduledTriggerChecksAndWrites() {
        val action = determineTimerThrottleAction(
            isWorkflowWake = false,
            isDesireActive = false,
            isForceTrigger = false,
        )
        assertEquals(ProactiveTimerThrottleAction.CHECK_AND_WRITE, action)
    }

    @Test
    fun timerThrottleAction_deviceEventOrManualForceTriggerWritesTimestamp() {
        val action = determineTimerThrottleAction(
            isWorkflowWake = false,
            isDesireActive = false,
            isForceTrigger = true,
        )
        assertEquals(ProactiveTimerThrottleAction.FORCE_WRITE, action)
    }
}

