/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.execution

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.workflow.model.WorkflowAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowActionRunnerTest {

    @Test
    fun successfulRunKeepsBoundedHistoryAndRicherAiPayload() = runBlocking {
        val longOutput = "start-" + "x".repeat(400) + "-tail"
        val tool = Tool(
            name = "read_data",
            description = "",
            execute = { listOf(UIMessagePart.Text(longOutput)) },
        )

        val result = WorkflowActionRunner().run(
            actions = listOf(WorkflowAction(tool = "read_data", args = buildJsonObject {})),
            availableTools = listOf(tool),
        )

        assertTrue(result.success)
        assertFalse(result.summary.contains("-tail"))
        assertTrue(result.dataForAi.contains("-tail"))
    }
}
