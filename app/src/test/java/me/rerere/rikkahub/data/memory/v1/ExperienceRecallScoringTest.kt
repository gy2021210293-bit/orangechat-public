/*
 * Portions adapted from Living Memory Coordinate-5 (LMC-5),
 * upstream commit fb3e72c9ee7357c8b17311097b0750082a8a1237, GNU AGPL v3.
 * Original source: extras/pgvector_backend/ob_recall.py
 * Adapted for Kotlin/Room on 2026-09-13.
 */

package me.rerere.rikkahub.data.memory.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceRecallScoringTest {

    @Test
    fun testTimeWeightPiecewise() {
        assertEquals(1.0, ExperienceRecallScoring.timeWeight(0.0), 1e-6)
        assertEquals(1.0, ExperienceRecallScoring.timeWeight(1.0), 1e-6)
        assertEquals(0.95, ExperienceRecallScoring.timeWeight(1.5), 1e-6)
        assertEquals(0.9, ExperienceRecallScoring.timeWeight(2.0), 1e-6)
        // Beyond day 2, exponential down to floor 0.3
        assertEquals(0.3, ExperienceRecallScoring.timeWeight(10.0), 1e-6)
    }

    @Test
    fun testRussellDistanceCalculations() {
        // Same coordinates
        assertEquals(0.0, ExperienceRecallScoring.russellDistance(Pair(0.5, 0.5), Pair(0.5, 0.5)), 1e-6)
        // Opposite extremes (0,0) and (1,1) distance is sqrt(2)/sqrt(2) = 1.0
        assertEquals(1.0, ExperienceRecallScoring.russellDistance(Pair(0.0, 0.0), Pair(1.0, 1.0)), 1e-6)
        // Symmetry
        val dist1 = ExperienceRecallScoring.russellDistance(Pair(0.2, 0.8), Pair(0.8, 0.2))
        val dist2 = ExperienceRecallScoring.russellDistance(Pair(0.8, 0.2), Pair(0.2, 0.8))
        assertEquals(dist1, dist2, 1e-6)
        assertEquals(0.6, dist1, 1e-4)
    }

    @Test
    fun testPrimaryAgentInitialPrioritySetsBaseline() {
        // Upstream test_e_axis_ownership.py parity:
        // high e_initial_priority vs low e_initial_priority
        val scoreHigh = ExperienceRecallScoring.vitalityScore(
            eInitialPriority = 90,
            weight = 1.0,
            hitCount = 1,
            daysSince = 0.0,
        )
        val scoreLow = ExperienceRecallScoring.vitalityScore(
            eInitialPriority = 20,
            weight = 9.0,
            hitCount = 1,
            daysSince = 0.0,
        )
        assertTrue("High initial priority (90) must beat low initial priority (20)", scoreHigh > scoreLow)
    }

    @Test
    fun testProtectedMemoryHasFixedVitality() {
        val score = ExperienceRecallScoring.vitalityScore(
            eInitialPriority = 10,
            daysSince = 100.0,
            isProtected = true,
        )
        assertEquals(999.0, score, 1e-6)
    }

    @Test
    fun testResolvedAndDigestedDecay() {
        val baseScore = ExperienceRecallScoring.vitalityScore(
            eInitialPriority = 50,
            daysSince = 1.0,
        )
        val resolvedScore = ExperienceRecallScoring.vitalityScore(
            eInitialPriority = 50,
            daysSince = 1.0,
            isResolved = true,
        )
        val digestedScore = ExperienceRecallScoring.vitalityScore(
            eInitialPriority = 50,
            daysSince = 1.0,
            isDigested = true,
        )
        assertEquals(baseScore * 0.05, resolvedScore, 0.01)
        assertEquals(baseScore * 0.3, digestedScore, 0.01)
    }

    @Test
    fun testFindResonantOrderingAndCutoff() {
        val userCoord = Pair(0.8, 0.8)
        val candidates = listOf(
            ExperienceRecallScoring.ResonantCandidate(
                item = "very_close",
                coord = Pair(0.78, 0.82),
                vitality = 5.0,
            ),
            ExperienceRecallScoring.ResonantCandidate(
                item = "medium",
                coord = Pair(0.5, 0.5),
                vitality = 3.0,
            ),
            ExperienceRecallScoring.ResonantCandidate(
                item = "far_away",
                coord = Pair(0.0, 0.0),
                vitality = 1.0,
            ),
        )

        val results = ExperienceRecallScoring.findResonant(
            userCoord = userCoord,
            candidates = candidates,
            limit = 2,
            minScore = 0.3,
        )

        assertEquals(2, results.size)
        assertEquals("very_close", results[0].item)
        assertEquals("medium", results[1].item)
        assertTrue(results[0].resonanceScore > results[1].resonanceScore)
    }
}
