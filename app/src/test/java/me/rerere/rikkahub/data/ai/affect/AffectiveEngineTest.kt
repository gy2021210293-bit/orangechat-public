package me.rerere.rikkahub.data.ai.affect

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AffectiveEngineTest {
    private val day = 24 * 60 * 60 * 1000L

    @Test fun relationshipModesBeginCloseAndKeepStateWhenSwitched() {
        val friend = AffectiveEngine.initial(AffectiveMode.FRIEND, day)
        val lover = AffectiveEngine.initial(AffectiveMode.LOVER, day)
        assertEquals("默契", AffectiveEngine.stageLabel(friend))
        assertEquals("安稳", AffectiveEngine.stageLabel(lover))
        assertEquals(.7, friend.intimacy, 0.0001)
        assertEquals(.8, lover.intimacy, 0.0001)
        assertEquals(friend.longing, AffectiveEngine.switchMode(friend, AffectiveMode.LOVER).longing, 0.0)
    }

    @Test fun silenceEvolvesAndDoesNotDoubleCount() {
        val initial = AffectiveEngine.initial(AffectiveMode.FRIEND, day)
        val afterDay = AffectiveEngine.advance(initial, day * 2)
        val withoutSilenceEvent = AffectiveEngine.advance(initial.copy(lastUserAt = 0L), day * 2)
        assertEquals(1L, afterDay.lastSilenceDay)
        assertTrue(afterDay.longing > initial.longing)
        assertEquals(withoutSilenceEvent.intimacy - .1, afterDay.intimacy, 0.0001)
        assertEquals(afterDay, AffectiveEngine.advance(afterDay, day * 2))
        val week = AffectiveEngine.advance(afterDay, day * 8)
        assertEquals(7L, week.lastSilenceDay)
        assertTrue(week.longing <= .95)
    }

    @Test fun clockRollbackDoesNotAdvance() {
        val initial = AffectiveEngine.initial(AffectiveMode.FRIEND, day)
        assertEquals(initial, AffectiveEngine.advance(initial, day - 60_000))
    }

    @Test fun socialDesireIsIndependentAndOldStatesUseDefault() {
        val initial = AffectiveEngine.initial(AffectiveMode.FRIEND, day)
        val oldJson = Json { encodeDefaults = true }.encodeToString(AffectiveState.serializer(), initial)
            .replace(Regex(",?\"socialDesire\":(?:[0-9.]+)"), "")
            .replace(Regex(",?\"socialRandomState\":-?[0-9]+"), "")
        assertFalse(oldJson.contains("socialDesire"))
        assertFalse(oldJson.contains("socialRandomState"))
        val restored = Json.decodeFromString(AffectiveState.serializer(), oldJson)
        assertEquals(.45, restored.socialDesire, 0.0)
        assertEquals(initial.socialRandomState, restored.socialRandomState)

        val afterSilence = AffectiveEngine.advance(initial, day * 2)
        val withoutSilence = AffectiveEngine.advance(initial.copy(lastUserAt = 0L), day * 2)
        assertEquals(withoutSilence.socialDesire, afterSilence.socialDesire, 0.0)
        assertEquals(.45, AffectiveEngine.observe(initial, AffectiveEvent.FIGHT).socialDesire, 0.0)
    }

    @Test fun socialDesireDoesNotChangeExistingRandomSequence() {
        val initial = AffectiveEngine.initial(AffectiveMode.FRIEND, day)
        val advanced = AffectiveEngine.advance(initial, day + 3_600_000L)
        val differentSocialDesire = AffectiveEngine.advance(initial.copy(socialDesire = .9), day + 3_600_000L)
        assertEquals(914183453880565712L, advanced.randomState)
        assertEquals(advanced.randomState, differentSocialDesire.randomState)
        assertEquals(advanced.stage, differentSocialDesire.stage)
        assertEquals(advanced.intimacy, differentSocialDesire.intimacy, 0.0)
    }

    @Test fun longingKeepsGrowingAfterDeliveryAndResetsOnUserMessage() {
        val state = AffectiveEngine.initial(AffectiveMode.FRIEND, day)
            .copy(longing = .95, randomState = 17L)
        val evaluation = AffectiveEngine.evaluate(state, day + 2 * 3_600_000)
        assertTrue(evaluation.contactCandidate)
        assertEquals(.95, evaluation.state.longing, 0.0)
        val delivered = AffectiveEngine.delivered(evaluation.state, "message-1", day + 2 * 3_600_000)
        assertEquals(evaluation.state.longing, delivered.longing, 0.0)
        assertFalse(AffectiveEngine.evaluate(delivered, day + 2 * 3_600_000 + 1000).contactCandidate)

        val partialLonging = state.copy(longing = .4, lastAdvancedAt = day + 2 * 3_600_000)
        val sentWhileWaiting = AffectiveEngine.delivered(partialLonging, "message-2", day + 2 * 3_600_000)
        val afterSilence = AffectiveEngine.advance(sentWhileWaiting, day + 3 * 3_600_000)
        assertTrue(afterSilence.longing > sentWhileWaiting.longing)
        assertTrue(AffectiveEngine.userMessage(afterSilence).longing < afterSilence.longing)
    }

    @Test fun realMemorySimilarityBoostsProbabilityBeforeDrawing() {
        val state = AffectiveEngine.initial(AffectiveMode.FRIEND, day)
            .copy(longing = .5, randomState = 17L)
        val withoutScore = AffectiveEngine.evaluate(state, day * 2)
        val withScore = AffectiveEngine.evaluate(state, day * 2, memorySimilarity = .8)
        assertFalse(withoutScore.contactCandidate)
        assertTrue(withScore.contactCandidate)
    }

    @Test fun valenceMayBeNegativeAndSemanticRuleRejectsOnlyContactBoundary() {
        val initial = AffectiveEngine.initial(AffectiveMode.FRIEND, day)
        val negative = (1..6).fold(initial) { state, _ -> AffectiveEngine.observe(state, AffectiveEvent.FIGHT) }
        assertTrue(negative.valence < 0.0)
        assertEquals(null, AffectiveEngine.classify("不要这个建议"))
        assertEquals(AffectiveEvent.REJECT, AffectiveEngine.classify("不要再找我"))
    }

}
