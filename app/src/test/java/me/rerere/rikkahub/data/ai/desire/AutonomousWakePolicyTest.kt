package me.rerere.rikkahub.data.ai.desire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertFalse

class AutonomousWakePolicyTest {
    @Test
    fun multilineFencedOutputIsParsedWithoutPlatformRegex() {
        val result = AutonomousWakeResultParser.parse(
            """
            ```json
            {
              "summary": "检查完成",
              "should_contact": true,
              "message": "有新进展",
              "next_wake_delay_minutes": 30
            }
            ```
            """.trimIndent(),
        )

        assertEquals("检查完成", result.summary)
        assertTrue(result.shouldContact)
        assertEquals("有新进展", result.message)
        assertEquals(30, result.nextWakeDelayMinutes)
    }

    @Test
    fun incompleteJsonUsesPlainTextFallback() {
        val raw = "检查完成 {尚无可分享进展"
        val result = AutonomousWakeResultParser.parse(raw)

        assertEquals(raw, result.summary)
        assertEquals(raw, result.outcome)
        assertFalse(result.shouldContact)
        assertFalse(result.formatValid)
        assertTrue(result.wakeDelayAdjustmentReason.orEmpty().isNotBlank())
    }

    @Test
    fun longValidFieldsAreNotTruncated() {
        val summary = "s".repeat(1_500)
        val outcome = "o".repeat(5_000)
        val result = AutonomousWakeResultParser.parse(
            """{"summary":"$summary","outcome":"$outcome","should_contact":false,"next_wake_delay_minutes":90}""",
        )

        assertTrue(result.formatValid)
        assertEquals(summary, result.summary)
        assertEquals(outcome, result.outcome)
        assertEquals(90, result.nextWakeDelayMinutes)
    }

    @Test
    fun wrongFieldTypesArePreservedButCannotTriggerActions() {
        val raw = """{"summary":{"text":"done"},"outcome":["part"],"should_contact":"true","next_wake_delay_minutes":60}"""
        val result = AutonomousWakeResultParser.parse(raw)

        assertFalse(result.formatValid)
        assertFalse(result.shouldContact)
        assertEquals(null, result.nextWakeDelayMinutes)
        assertEquals(raw, result.summary)
        assertEquals(raw, result.outcome)
    }

    @Test
    fun aiSuggestionCanExtendUserIntervalUpTo24Hours() {
        val result = AutonomousWakePolicy.decide(
            endedAt = 1_000L,
            defaultDelayMinutes = 60,
            suggestedDelayMinutes = 24 * 60,
            suggestedReason = "继续整理资料",
            itemDueAt = emptyList(),
        )

        assertEquals(1_000L + 24 * 60 * 60_000L, result.autonomousCandidateAt)
        assertEquals(result.autonomousCandidateAt, result.scheduledAt)
        assertEquals("autonomous", result.source)
        assertEquals(24 * 60, result.chosenDelayMinutes)
        assertEquals("", result.adjustmentReason)
    }

    @Test
    fun aiSuggestionCanWakeEarlierThanUserInterval() {
        val result = AutonomousWakePolicy.decide(
            endedAt = 1_000L,
            defaultDelayMinutes = 120,
            suggestedDelayMinutes = 30,
            suggestedReason = "继续读书",
            itemDueAt = emptyList(),
        )

        assertEquals(1_000L + 30 * 60_000L, result.scheduledAt)
        assertEquals(30, result.chosenDelayMinutes)
        assertEquals("", result.adjustmentReason)
    }

    @Test
    fun missingSuggestionUsesDefaultAndFarFutureItemDoesNotPauseIt() {
        val now = 10_000L
        val result = AutonomousWakePolicy.decide(
            endedAt = now,
            defaultDelayMinutes = 60,
            suggestedDelayMinutes = null,
            suggestedReason = null,
            itemDueAt = listOf(now + 3 * 24 * 60 * 60_000L),
        )

        assertEquals(now + 60 * 60_000L, result.scheduledAt)
        assertEquals("autonomous", result.source)
        assertEquals(60, result.chosenDelayMinutes)
    }

    @Test
    fun earlierItemMovesTheSinglePlanForward() {
        val now = 25_000L
        val itemAt = now + 20 * 60_000L
        val result = AutonomousWakePolicy.decide(
            endedAt = now,
            defaultDelayMinutes = 60,
            suggestedDelayMinutes = 90,
            suggestedReason = "等待更多资料",
            itemDueAt = listOf(itemAt),
        )

        assertEquals(now + 90 * 60_000L, result.autonomousCandidateAt)
        assertEquals(itemAt, result.scheduledAt)
        assertEquals("item", result.source)
    }

    @Test
    fun suggestionIsClampedAndAdjustmentIsRecorded() {
        val now = 40_000L
        val result = AutonomousWakePolicy.decide(
            endedAt = now,
            defaultDelayMinutes = 60,
            suggestedDelayMinutes = 5,
            suggestedReason = "马上再看",
            itemDueAt = emptyList(),
        )

        assertEquals(15, result.chosenDelayMinutes)
        assertEquals(now + 15 * 60_000L, result.scheduledAt)
        assertTrue(result.adjustmentReason.isNotBlank())
    }

    @Test
    fun overdueItemDoesNotCreatePastDuePlan() {
        val now = 100_000L
        val result = AutonomousWakePolicy.decide(
            endedAt = now,
            defaultDelayMinutes = 60,
            suggestedDelayMinutes = null,
            suggestedReason = null,
            itemDueAt = listOf(now - 1L),
        )

        assertNotEquals(now - 1L, result.scheduledAt)
        assertEquals(now + 60 * 60_000L, result.scheduledAt)
    }

    @Test
    fun legacyOutputRemainsValidWithoutDynamicDelayFields() {
        val result = AutonomousWakeResultParser.parse(
            """{"summary":"检查完成","should_contact":true,"message":"有一条进展可以告诉你。"}""",
        )

        assertEquals("检查完成", result.summary)
        assertTrue(result.shouldContact)
        assertEquals("有一条进展可以告诉你。", result.message)
        assertEquals(null, result.nextWakeDelayMinutes)
        assertEquals(null, result.nextWakeReason)
    }

    @Test
    fun invalidDynamicDelayFallsBackToDefaultPath() {
        val result = AutonomousWakeResultParser.parse(
            """{"summary":"已整理","next_wake_delay_minutes":"soon","next_wake_reason":"等反馈"}""",
        )

        assertFalse(result.shouldContact)
        assertEquals(null, result.nextWakeDelayMinutes)
        assertEquals(null, result.nextWakeReason)
        assertTrue(result.wakeDelayAdjustmentReason.orEmpty().isNotBlank())
    }

    @Test
    fun uncertainNonIdempotentToolCallIsBlockedButStableShareRetryIsAllowed() {
        val prior = """[{"name":"send_email","input":"{\"to\":\"a@example.com\",\"subject\":\"报告\"}","status":"result_uncertain"}]"""
        val uncertain = AutonomousToolReplayPolicy.uncertainCallKeys(listOf(prior))

        assertTrue(AutonomousToolReplayPolicy.shouldBlockReplay(
            "send_email", "{\"subject\":\"报告\",\"to\":\"a@example.com\"}", uncertain,
        ))
        assertFalse(AutonomousToolReplayPolicy.shouldBlockReplay(
            "autonomous_share_result", "{\"item_id\":\"i1\",\"result_id\":\"r1\"}", uncertain,
        ))
    }
}
