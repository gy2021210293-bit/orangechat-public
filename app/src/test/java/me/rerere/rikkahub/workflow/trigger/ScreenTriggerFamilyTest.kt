/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.trigger

import android.content.Intent
import kotlinx.serialization.json.buildJsonObject
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.tools.WorkflowApprovalRenderer
import me.rerere.rikkahub.workflow.ui.oneLineTriggerSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTriggerFamilyTest {

    private fun dummyWorkflow(id: String, trigger: TriggerSpec): WorkflowDefinition {
        return WorkflowDefinition(
            id = id,
            name = "Test workflow $id",
            trigger = trigger,
            actions = listOf(WorkflowAction(tool = "noop", args = buildJsonObject { })),
        )
    }

    @Test
    fun testUserPresentSerializationAndDeserialization() {
        // 1. Flat trigger JSON
        val flatJson = """
            {
              "name": "Unlock workflow",
              "trigger": {"type": "user_present"},
              "actions": [{"tool": "noop", "args": {}}]
            }
        """.trimIndent()
        val parsedFlat = WorkflowJson.parse(flatJson, emptySet())
        assertTrue(parsedFlat is WorkflowJson.ParseResult.Ok)
        val defFlat = (parsedFlat as WorkflowJson.ParseResult.Ok).definition
        assertEquals(TriggerSpec.UserPresent, defFlat.trigger)

        // 2. Nested trigger JSON (with empty params)
        val nestedJson = """
            {
              "name": "Unlock workflow nested",
              "trigger": {"type": "user_present", "params": {}},
              "actions": [{"tool": "noop", "args": {}}]
            }
        """.trimIndent()
        val parsedNested = WorkflowJson.parse(nestedJson, emptySet())
        assertTrue(parsedNested is WorkflowJson.ParseResult.Ok)
        val defNested = (parsedNested as WorkflowJson.ParseResult.Ok).definition
        assertEquals(TriggerSpec.UserPresent, defNested.trigger)

        // 3. Round-trip serialization & persistence parse
        val encoded = WorkflowJson.encode(defFlat)
        val stored = WorkflowJson.parseStored(encoded)
        assertNotNull(stored)
        assertEquals(TriggerSpec.UserPresent, stored?.trigger)
    }

    @Test
    fun testUiAndSummaryDescriptions() {
        val def = dummyWorkflow("wf-unlock", TriggerSpec.UserPresent)
        assertEquals("手机解锁", oneLineTriggerSummary(def))
        assertEquals("device is unlocked", WorkflowApprovalRenderer.triggerSummary(TriggerSpec.UserPresent))
    }

    @Test
    fun testScreenTriggerFamilyHandles() {
        assertTrue(ScreenTriggerFamily.handles(TriggerSpec.ScreenOn))
        assertTrue(ScreenTriggerFamily.handles(TriggerSpec.ScreenOff))
        assertTrue(ScreenTriggerFamily.handles(TriggerSpec.UserPresent))
        assertFalse(ScreenTriggerFamily.handles(TriggerSpec.Manual))
        assertFalse(ScreenTriggerFamily.handles(TriggerSpec.BootCompleted))
    }

    @Test
    fun testMatchActionMatchesOnlyCorrespondingAction() {
        val wfScreenOn = dummyWorkflow("wf-on", TriggerSpec.ScreenOn)
        val wfScreenOff = dummyWorkflow("wf-off", TriggerSpec.ScreenOff)
        val wfUserPresent = dummyWorkflow("wf-present", TriggerSpec.UserPresent)
        val workflows = listOf(wfScreenOn, wfScreenOff, wfUserPresent)

        // ACTION_SCREEN_ON
        val onMatches = ScreenTriggerFamily.matchAction(Intent.ACTION_SCREEN_ON, workflows)
        assertEquals(listOf("wf-on" to TriggerSpec.ScreenOn), onMatches)

        // ACTION_SCREEN_OFF
        val offMatches = ScreenTriggerFamily.matchAction(Intent.ACTION_SCREEN_OFF, workflows)
        assertEquals(listOf("wf-off" to TriggerSpec.ScreenOff), offMatches)

        // ACTION_USER_PRESENT
        val presentMatches = ScreenTriggerFamily.matchAction(Intent.ACTION_USER_PRESENT, workflows)
        assertEquals(listOf("wf-present" to TriggerSpec.UserPresent), presentMatches)

        // Unknown action should not match anything (specifically not misidentified as screen off)
        val unknownMatches = ScreenTriggerFamily.matchAction(Intent.ACTION_BATTERY_LOW, workflows)
        assertTrue(unknownMatches.isEmpty())

        val nullActionMatches = ScreenTriggerFamily.matchAction(null, workflows)
        assertTrue(nullActionMatches.isEmpty())
    }
}
