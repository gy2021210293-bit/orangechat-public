/*
 * Portions adapted from Living Memory Coordinate-5 (LMC-5),
 * upstream commit fb3e72c9ee7357c8b17311097b0750082a8a1237, GNU AGPL v3.
 * Original source: extras/pgvector_backend/ob_recall.py
 * Adapted for Kotlin/Room on 2026-09-13.
 */

package me.rerere.rikkahub.data.memory.v1

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * LMC-5 E 轴召回评分与 Russell 情绪圆环距离纯算法。
 */
object ExperienceRecallScoring {

    val CATEGORY_HALF_LIVES: Map<String, Double> = mapOf(
        "heartbeat" to Double.POSITIVE_INFINITY,
        "identity" to Double.POSITIVE_INFINITY,
        "core" to 90.0,
        "fragments" to 90.0,
        "important" to 90.0,
        "reviews" to 60.0,
        "diary" to 60.0,
        "mailbox" to 60.0,
        "knowledge" to 30.0,
        "notebook" to 30.0,
        "conversation" to 14.0,
    )

    const val DEFAULT_HALF_LIFE: Double = 45.0
    private val SQRT_2 = sqrt(2.0)
    private val LN_2 = ln(2.0)

    fun getHalfLife(category: String?, source: String? = null): Double {
        val cat = category?.trim()?.lowercase().orEmpty()
        val src = source?.trim()?.lowercase().orEmpty()
        return CATEGORY_HALF_LIVES[cat]
            ?: CATEGORY_HALF_LIVES[src]
            ?: DEFAULT_HALF_LIFE
    }

    /**
     * 分段时间权重：0-1天=1.0；第2天线性降到 0.9；之后指数衰减到地板 0.3。
     */
    fun timeWeight(daysSince: Double): Double {
        val safeDays = max(0.0, daysSince)
        if (safeDays <= 1.0) {
            return 1.0
        }
        if (safeDays <= 2.0) {
            return 1.0 - 0.1 * (safeDays - 1.0)
        }
        val raw = 0.9 * exp(-0.2197 * (safeDays - 2.0))
        return max(0.3, raw)
    }

    /**
     * OB 统一活力评分。
     *
     * @param eInitialPriority 主 AI 决定的初始优先级 1..100 (优先于 weight)
     * @param weight 普通卡片权重 (默认 1.0)
     * @param hitCount 命中次数
     * @param daysSince 距今时间（天）
     * @param category 分类标签
     * @param source 来源标签
     * @param isProtected 是否受保护 (受保护固定 999.0)
     * @param isResolved 是否已解决 (衰减 0.05)
     * @param isDigested 是否已消化 (衰减 0.3)
     * @param activationBoost 激活增益 (封顶 3.0)
     */
    fun vitalityScore(
        eInitialPriority: Int? = null,
        weight: Double = 1.0,
        hitCount: Int = 1,
        daysSince: Double = 30.0,
        category: String? = null,
        source: String? = null,
        isProtected: Boolean = false,
        isResolved: Boolean = false,
        isDigested: Boolean = false,
        activationBoost: Double = 0.0,
    ): Double {
        if (isProtected) {
            return 999.0
        }

        val importance = if (eInitialPriority != null) {
            max(1.0, min(10.0, eInitialPriority.toDouble() / 10.0))
        } else {
            max(1.0, min(10.0, weight * 3.3))
        }

        val activation = min(30, max(1, hitCount))
        val safeDays = max(0.0, daysSince)
        val halfLife = getHalfLife(category, source)
        val decay = if (halfLife.isInfinite()) {
            1.0
        } else {
            exp(-LN_2 * safeDays / halfLife)
        }

        val boostFactor = 1.0 + min(max(0.0, activationBoost), 3.0) * 0.15
        val timeDominance = if (safeDays <= 3.0) 1.0 else 0.5

        val base = importance * (activation.toDouble().pow(0.3)) * decay * boostFactor
        var score = timeWeight(safeDays) * timeDominance * base

        if (isResolved) {
            score *= 0.05
        }
        if (isDigested) {
            score *= 0.3
        }
        return round(score * 10000.0) / 10000.0
    }

    /**
     * Russell 圆环情绪欧氏距离 / sqrt(2)。返回 [0, 1]，越小越近。
     */
    fun russellDistance(coordA: Pair<Double, Double>, coordB: Pair<Double, Double>): Double {
        val dv = coordA.first - coordB.first
        val da = coordA.second - coordB.second
        val dist = sqrt(dv * dv + da * da) / SQRT_2
        return dist.coerceIn(0.0, 1.0)
    }

    /**
     * 情绪共鸣评分：距离越近分越高 × (1 + OB 活力 / 10.0)。
     */
    fun resonanceScore(
        userCoord: Pair<Double, Double>,
        memoryCoord: Pair<Double, Double>,
        vitality: Double,
    ): Double {
        val dist = russellDistance(userCoord, memoryCoord)
        val emotionScore = max(0.0, 1.0 - dist)
        return emotionScore * (1.0 + vitality / 10.0)
    }

    /**
     * 从候选列表中筛选情绪共振最高的 Top-N。
     */
    data class ResonantCandidate<T>(
        val item: T,
        val coord: Pair<Double, Double>,
        val vitality: Double,
    )

    data class ScoredCandidate<T>(
        val item: T,
        val resonanceScore: Double,
    )

    fun <T> findResonant(
        userCoord: Pair<Double, Double>,
        candidates: List<ResonantCandidate<T>>,
        limit: Int = 2,
        minScore: Double = 0.3,
    ): List<ScoredCandidate<T>> {
        if (candidates.isEmpty() || limit <= 0) return emptyList()

        return candidates.asSequence()
            .map { candidate ->
                val score = resonanceScore(userCoord, candidate.coord, candidate.vitality)
                ScoredCandidate(candidate.item, round(score * 1000.0) / 1000.0)
            }
            .filter { it.resonanceScore >= minScore }
            .sortedByDescending { it.resonanceScore }
            .take(limit)
            .toList()
    }

    /**
     * 便捷方法：针对 MemoryExperienceCoordinateEntity 列表计算共鸣分并返回 Top-N。
     */
    fun findResonantExperiences(
        userCoord: Pair<Double, Double>,
        candidates: List<me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity>,
        nowEpochMilli: Long = System.currentTimeMillis(),
        limit: Int = 1,
        minScore: Double = 0.3,
    ): List<ScoredCandidate<me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity>> {
        if (candidates.isEmpty() || limit <= 0) return emptyList()
        val resonantCandidates = candidates.map { exp ->
            val daysSince = ((nowEpochMilli - exp.createdAt) / (1000.0 * 86400.0)).coerceAtLeast(0.0)
            val vitality = vitalityScore(
                eInitialPriority = exp.eInitialPriority,
                daysSince = daysSince,
            )
            ResonantCandidate(
                item = exp,
                coord = exp.valence to exp.arousal,
                vitality = vitality,
            )
        }
        return findResonant(userCoord, resonantCandidates, limit, minScore)
    }
}
