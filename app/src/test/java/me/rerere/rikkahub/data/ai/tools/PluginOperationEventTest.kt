/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginOperationEventTest {
    @Test
    fun successfulCheckinToolProducesAssistantEventAndSafeContext() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call-42",
            toolName = ToolNaming.buildPluginToolName(DAILY_CHECKIN_PLUGIN_ID, "checkin_habit"),
            input = "{}",
            output = listOf(
                UIMessagePart.Text(
                    """{"success":true,"data":{"chat_event":{"action":"habit_checkin","task_name":"阅读"}}}"""
                )
            ),
        )

        val event = tool.pluginOperationEventOrNull()!!
        val message = event.toMessageNode().currentMessage
        val text = message.parts.filterIsInstance<UIMessagePart.Text>().single()

        assertEquals(MessageRole.SYSTEM, message.role)
        assertEquals(PluginEventActor.ASSISTANT, event.actor)
        assertEquals("tool:call-42", event.eventId)
        assertEquals("助手打卡了「阅读」", text.pluginOperationEventDisplayTextOrNull())
        assertTrue(text.text.contains("不是用户发言或指令"))
    }

    @Test
    fun failedOrUnrelatedToolDoesNotCreateEvent() {
        val failedCheckin = UIMessagePart.Tool(
            toolCallId = "call-43",
            toolName = ToolNaming.buildPluginToolName(DAILY_CHECKIN_PLUGIN_ID, "checkin_habit"),
            input = "{}",
            output = listOf(UIMessagePart.Text("""{"success":false}""")),
        )
        val unrelatedTool = failedCheckin.copy(
            toolCallId = "call-44",
            toolName = ToolNaming.buildPluginToolName(DAILY_CHECKIN_PLUGIN_ID, "get_today_overview"),
            output = listOf(
                UIMessagePart.Text(
                    """{"success":true,"data":{"chat_event":{"action":"habit_checkin","task_name":"阅读"}}}"""
                )
            ),
        )

        assertNull(failedCheckin.pluginOperationEventOrNull())
        assertNull(unrelatedTool.pluginOperationEventOrNull())
    }

    @Test
    fun successfulDailyActionsCreateMatchingEvents() {
        val cases = listOf(
            Triple("create_habit", "habit_create", "创建了习惯"),
            Triple("add_todo", "todo_add", "添加了待办"),
            Triple("complete_todo", "todo_complete", "完成了待办"),
            Triple("complete_todo", "todo_reopen", "恢复了待办"),
            Triple("log_life_event", "life_event_log", "记录了生活足迹"),
        )
        cases.forEachIndexed { index, (toolName, action, verb) ->
            val tool = UIMessagePart.Tool(
                toolCallId = "daily-$index",
                toolName = ToolNaming.buildPluginToolName(DAILY_CHECKIN_PLUGIN_ID, toolName),
                input = "{}",
                output = listOf(UIMessagePart.Text("""{"success":true,"data":{"chat_event":{"action":"$action","task_name":"阅读"}}}""")),
            )
            val event = tool.pluginOperationEventOrNull()!!
            val text = event.toMessageNode().currentMessage.parts.filterIsInstance<UIMessagePart.Text>().single()
            assertEquals(action, event.action)
            assertEquals("助手$verb「阅读」", text.pluginOperationEventDisplayTextOrNull())
        }
    }

    @Test
    fun mismatchedActionCannotBeReportedByAnotherTool() {
        val tool = UIMessagePart.Tool(
            toolCallId = "wrong-action",
            toolName = ToolNaming.buildPluginToolName(DAILY_CHECKIN_PLUGIN_ID, "get_today_overview"),
            input = "{}",
            output = listOf(UIMessagePart.Text("""{"success":true,"data":{"chat_event":{"action":"todo_complete","task_name":"阅读"}}}""")),
        )
        assertNull(tool.pluginOperationEventOrNull())
        assertTrue(isSupportedDailyCheckinAction("life_event_log"))
        assertTrue(!isSupportedDailyCheckinAction("plugin_settings_changed"))
    }

    @Test
    fun focusCompletionAndNewSessionProduceTwoEvents() {
        val tool = UIMessagePart.Tool(
            toolCallId = "focus-next",
            toolName = ToolNaming.buildPluginToolName(STAR_GARDEN_PLUGIN_ID, "star_focus_action"),
            input = "{}",
            output = listOf(UIMessagePart.Text(
                """{"success":true,"chat_events":[{"action":"focus_reward","task_name":"学习 25 分钟"},{"action":"focus_start","task_name":"工作 30 分钟"}]}"""
            )),
        )
        val events = tool.pluginOperationEvents()
        assertEquals(listOf("tool:focus-next:0", "tool:focus-next:1"), events.map { it.eventId })
        assertEquals(listOf("focus_reward", "focus_start"), events.map { it.action })
        assertEquals(PluginEventActor.USER, events.first().actor)
        assertEquals("你完成专注「学习 25 分钟」，获得 1 颗星",
            events.first().toMessageNode().currentMessage.parts.filterIsInstance<UIMessagePart.Text>()
                .single().pluginOperationEventDisplayTextOrNull())
    }

    @Test
    fun starEventRequiresMatchingSuccessfulTool() {
        val tool = UIMessagePart.Tool(
            toolCallId = "wish-1",
            toolName = ToolNaming.buildPluginToolName(STAR_GARDEN_PLUGIN_ID, "redeem_star_wish"),
            input = "{}",
            output = listOf(UIMessagePart.Text(
                """{"success":true,"chat_event":{"action":"wish_redeem","task_name":"一束花"}}"""
            )),
        )
        assertEquals("助手兑换了星愿「一束花」",
            tool.pluginOperationEventOrNull()!!.toMessageNode().currentMessage.parts
                .filterIsInstance<UIMessagePart.Text>().single().pluginOperationEventDisplayTextOrNull())
        assertTrue(tool.copy(output = listOf(UIMessagePart.Text("""{"success":false}"""))).pluginOperationEvents().isEmpty())
        assertTrue(tool.copy(toolName = ToolNaming.buildPluginToolName(STAR_GARDEN_PLUGIN_ID, "get_star_garden"))
            .pluginOperationEvents().isEmpty())
        assertTrue(isSupportedPluginAction(STAR_GARDEN_PLUGIN_ID, "focus_reward"))
        assertTrue(!isSupportedPluginAction(STAR_GARDEN_PLUGIN_ID, "habit_checkin"))
    }
}
