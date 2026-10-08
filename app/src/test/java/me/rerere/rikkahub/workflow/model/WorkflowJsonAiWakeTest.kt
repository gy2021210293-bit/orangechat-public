/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowJsonAiWakeTest {

    @Test
    fun parseAndEncode_preservesAiWakeConfiguration() {
        val parsed = WorkflowJson.parse(
            rawJson = """
                {
                  "name": "Weather follow-up",
                  "trigger": {"type": "manual", "params": {}},
                  "actions": [{"tool": "weather", "args": {}}],
                  "ai_wake": {
                    "prompt": "Tell me whether I need an umbrella",
                    "include_action_outputs": true,
                    "allow_all_tools_and_plugins": true
                  }
                }
            """.trimIndent(),
            knownToolNames = emptySet(),
        )

        assertTrue(parsed is WorkflowJson.ParseResult.Ok)
        val definition = (parsed as WorkflowJson.ParseResult.Ok).definition
        assertEquals("Tell me whether I need an umbrella", definition.aiWake?.prompt)
        assertTrue(definition.aiWake?.includeActionOutputs == true)
        assertTrue(definition.aiWake?.allowAllToolsAndPlugins == true)

        val stored = WorkflowJson.parseStored(WorkflowJson.encode(definition))
        assertEquals(definition.aiWake, stored?.aiWake)
    }

    @Test
    fun parse_legacyDefinitionDefaultsToNoAiWake() {
        val parsed = WorkflowJson.parse(
            rawJson = """
                {
                  "name": "Legacy",
                  "trigger": {"type": "manual", "params": {}},
                  "actions": [{"tool": "noop", "args": {}}]
                }
            """.trimIndent(),
            knownToolNames = emptySet(),
        )

        assertTrue(parsed is WorkflowJson.ParseResult.Ok)
        assertNull((parsed as WorkflowJson.ParseResult.Ok).definition.aiWake)
    }

    @Test
    fun parse_rejectsBlankAiWakePrompt() {
        val parsed = WorkflowJson.parse(
            rawJson = """
                {
                  "name": "Invalid wake",
                  "trigger": {"type": "manual", "params": {}},
                  "actions": [{"tool": "noop", "args": {}}],
                  "ai_wake": {"prompt": "   "}
                }
            """.trimIndent(),
            knownToolNames = emptySet(),
        )

        assertTrue(parsed is WorkflowJson.ParseResult.Err)
        val error = parsed as WorkflowJson.ParseResult.Err
        assertEquals("invalid_ai_wake", error.error)
        assertFalse(error.detail.isBlank())
    }
}
