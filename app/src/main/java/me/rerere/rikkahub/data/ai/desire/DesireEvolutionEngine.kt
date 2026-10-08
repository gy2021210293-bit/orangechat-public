/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Desire 纯函数时间演化引擎
 *
 * 核心特性：
 * 1. 纯计算无 IO，不读取系统时钟；
 * 2. 按真实经过时间推进，消除心跳频率依赖；
 * 3. 单次连续演化最多按 72 小时计算；设备时间回拨（elapsedHours <= 0）不产生变化；
 * 4. 疲劳、压力、喜悦按半衰期指数回落至固定基线；
 * 5. 想念感、责任感、亲密渴求等按分段速率与高位减速演化；
 * 6. 采用区间解析积分，单次长步长演化与切片分步累积完全数学等价。
 */
object DesireEvolutionEngine {

    const val MAX_ELAPSED_HOURS = 72.0

    private data class LinearTier(val limit: Double, val rate: Double)

    private fun evolvePiecewise(startVal: Double, durationHours: Double, tiers: List<LinearTier>): Double {
        if (durationHours <= 0.0) return startVal.coerceIn(0.0, 100.0)
        var remT = durationHours
        var cur = startVal.coerceIn(0.0, 100.0)

        for (tier in tiers) {
            if (remT <= 0.0 || cur >= 100.0) break
            if (cur < tier.limit) {
                if (tier.rate <= 0.0) {
                    continue
                }
                val distance = tier.limit - cur
                val timeToLimit = distance / tier.rate
                val dt = min(remT, timeToLimit)
                cur += tier.rate * dt
                remT -= dt
            }
        }
        return cur.coerceIn(0.0, 100.0)
    }

    private fun evolveDrivenDecay(
        startVal: Double,
        baseline: Double,
        halfLifeHours: Double,
        inflowRate: Double,
        elapsedHours: Double,
    ): Double {
        if (elapsedHours <= 0.0) return startVal.coerceIn(0.0, 100.0)
        val lambda = ln(2.0) / halfLifeHours
        val x0 = startVal - baseline
        val xt = x0 * exp(-lambda * elapsedHours) + (inflowRate / lambda) * (1.0 - exp(-lambda * elapsedHours))
        return (baseline + xt).coerceIn(0.0, 100.0)
    }

    private fun decayExponential(startVal: Double, baseline: Double, halfLifeHours: Double, elapsedHours: Double): Double {
        return evolveDrivenDecay(startVal, baseline, halfLifeHours, 0.0, elapsedHours)
    }

    /**
     * 演化驱动力状态
     *
     * @param drives 当前驱动力映射
     * @param elapsedHours 距离上次结算经过的时间（小时）
     * @param absentHoursStart 距离上次结算时用户的离线时长（小时）
     * @return 演化后的新驱动力映射
     */
    fun evolve(
        drives: Map<String, Drive>,
        elapsedHours: Double,
        absentHoursStart: Double,
    ): Map<String, Drive> {
        val effectiveElapsed = elapsedHours.coerceIn(0.0, MAX_ELAPSED_HOURS)
        val defaultDrives = createDefaultDrives()

        if (effectiveElapsed <= 0.0) {
            // 归一化基线并返回
            return drives.mapValues { (key, drive) ->
                val def = defaultDrives[key]
                drive.copy(
                    baseline = def?.baseline ?: drive.baseline,
                    value = drive.value.coerceIn(0.0, 100.0)
                )
            }
        }

        val startAbsent = max(0.0, absentHoursStart)
        val endAbsent = startAbsent + effectiveElapsed

        // 计算离开时间窗口重叠
        val overlap05To60 = max(0.0, min(endAbsent, 6.0) - max(startAbsent, 0.5))
        val overlap60Plus = max(0.0, endAbsent - max(startAbsent, 6.0))
        val activeAbsentHours = overlap05To60 + overlap60Plus

        // 1. 先演化主驱动力（想念感、责任感、亲密渴求、反思欲、喜悦）
        val result = mutableMapOf<String, Drive>()

        // 喜悦演化与高喜悦窗口（joy > 60 时微调好奇心并温和缩短压力半衰期）
        val initialJoy = drives["joy"]?.value?.coerceIn(0.0, 100.0) ?: 50.0
        val joyBaseline = defaultDrives["joy"]?.baseline ?: 50.0
        val evolvedJoy = decayExponential(initialJoy, joyBaseline, halfLifeHours = 3.0, elapsedHours = effectiveElapsed)
        val lambdaJoy = ln(2.0) / 3.0
        val highJoyHours = if (initialJoy > 60.0) {
            val t60 = max(0.0, (1.0 / lambdaJoy) * ln((initialJoy - joyBaseline) / (60.0 - joyBaseline)))
            min(effectiveElapsed, t60)
        } else {
            0.0
        }

        // 想念感演化
        val initialAttachment = drives["attachment"]?.value?.coerceIn(0.0, 100.0) ?: 40.0
        val attachmentBaseline = defaultDrives["attachment"]?.baseline ?: 40.0
        val evolvedAttachment = if (activeAbsentHours > 0.0) {
            evolvePiecewise(
                initialAttachment,
                activeAbsentHours,
                listOf(LinearTier(limit = 100.0, rate = 8.0))
            )
        } else {
            initialAttachment
        }

        // 判定饱和（顶峰 100）停留时间：若初始已在 100 或想念感达到 100
        val hoursNeededTo100 = (100.0 - initialAttachment) / 8.0
        val satHours = if (drives.values.any { it.value >= 100.0 }) {
            effectiveElapsed
        } else if (activeAbsentHours > hoursNeededTo100) {
            min(effectiveElapsed, activeAbsentHours - hoursNeededTo100)
        } else {
            0.0
        }
        val unsatHours = max(0.0, effectiveElapsed - satHours)

        // 责任感演化
        val initialDuty = drives["duty"]?.value?.coerceIn(0.0, 100.0) ?: 40.0
        val dutyBaseline = defaultDrives["duty"]?.baseline ?: 40.0
        val evolvedDuty = if (activeAbsentHours > 0.0) {
            evolvePiecewise(
                initialDuty,
                activeAbsentHours,
                listOf(
                    LinearTier(limit = 65.0, rate = 2.0),
                    LinearTier(limit = 100.0, rate = 0.5),
                )
            )
        } else {
            initialDuty
        }

        // 亲密渴求演化
        val initialIntimacy = drives["intimacy"]?.value?.coerceIn(0.0, 100.0) ?: 40.0
        val intimacyBaseline = defaultDrives["intimacy"]?.baseline ?: 40.0
        val evolvedIntimacy = evolvePiecewise(
            initialIntimacy,
            effectiveElapsed,
            listOf(
                LinearTier(limit = 75.0, rate = 2.0),
                LinearTier(limit = 90.0, rate = 0.8),
                LinearTier(limit = 100.0, rate = 0.2),
            )
        )

        // 反思欲演化
        val initialReflection = drives["reflection"]?.value?.coerceIn(0.0, 100.0) ?: 30.0
        val reflectionBaseline = defaultDrives["reflection"]?.baseline ?: 30.0
        val evolvedReflection = evolvePiecewise(
            initialReflection,
            effectiveElapsed,
            listOf(
                LinearTier(limit = 70.0, rate = 0.5),
                LinearTier(limit = 85.0, rate = 0.2),
                LinearTier(limit = 100.0, rate = 0.05),
            )
        )

        // 2. 压力演化（高喜悦温和缩短半衰期 8h->6h，顶峰饱和期间注入 +5.0/h 压力）
        val initialStress = drives["stress"]?.value?.coerceIn(0.0, 100.0) ?: 20.0
        val stressBaseline = defaultDrives["stress"]?.baseline ?: 20.0
        val stressHalfLife = if (highJoyHours > 0.0) {
            (highJoyHours * 6.0 + (effectiveElapsed - highJoyHours) * 8.0) / effectiveElapsed
        } else {
            8.0
        }
        val midStress = evolveDrivenDecay(
            startVal = initialStress,
            baseline = stressBaseline,
            halfLifeHours = stressHalfLife,
            inflowRate = 0.0,
            elapsedHours = unsatHours,
        )
        val evolvedStress = evolveDrivenDecay(
            startVal = midStress,
            baseline = stressBaseline,
            halfLifeHours = stressHalfLife,
            inflowRate = if (satHours > 0.0) 5.0 else 0.0,
            elapsedHours = satHours,
        )

        // 3. 疲劳演化（方案 1：极高压 stress >= 68 时向疲劳渗透 +6.3/h）
        val initialFatigue = drives["fatigue"]?.value?.coerceIn(0.0, 100.0) ?: 20.0
        val fatigueBaseline = defaultDrives["fatigue"]?.baseline ?: 20.0
        val lambdaStress = ln(2.0) / stressHalfLife
        val stressSteadyState = stressBaseline + 5.0 / lambdaStress
        val fatigueInflowHours = if (satHours > 0.0) {
            if (midStress >= 68.0) {
                satHours
            } else if (stressSteadyState > 68.0) {
                val t68 = (1.0 / lambdaStress) * ln((stressSteadyState - midStress) / (stressSteadyState - 68.0))
                if (t68 < satHours) {
                    satHours - t68
                } else {
                    0.0
                }
            } else {
                0.0
            }
        } else if (initialStress >= 68.0) {
            val t68 = (1.0 / lambdaStress) * ln((initialStress - stressBaseline) / (68.0 - stressBaseline))
            min(effectiveElapsed, max(0.0, t68))
        } else {
            0.0
        }
        val fatigueNoInflowHours = max(0.0, effectiveElapsed - fatigueInflowHours)
        val midFatigue = evolveDrivenDecay(
            startVal = initialFatigue,
            baseline = fatigueBaseline,
            halfLifeHours = 6.0,
            inflowRate = 0.0,
            elapsedHours = fatigueNoInflowHours,
        )
        val evolvedFatigue = evolveDrivenDecay(
            startVal = midFatigue,
            baseline = fatigueBaseline,
            halfLifeHours = 6.0,
            inflowRate = if (fatigueInflowHours > 0.0) 6.3 else 0.0,
            elapsedHours = fatigueInflowHours,
        )

        // 4. 疲劳高位冻结判定（fatigue >= 70 时好奇心与社交欲积累被完全冻结）
        val lambdaFatigue = ln(2.0) / 6.0
        val fatigueSteadyState = fatigueBaseline + 6.3 / lambdaFatigue
        val freezeDuration = if (initialFatigue >= 70.0) {
            effectiveElapsed
        } else if (fatigueInflowHours > 0.0 && fatigueSteadyState > 70.0) {
            if (midFatigue >= 70.0) {
                fatigueInflowHours
            } else {
                val t70 = (1.0 / lambdaFatigue) * ln((fatigueSteadyState - midFatigue) / (fatigueSteadyState - 70.0))
                if (t70 < fatigueInflowHours) {
                    fatigueInflowHours - t70
                } else {
                    0.0
                }
            }
        } else {
            0.0
        }
        val activeCognitiveHours = max(0.0, effectiveElapsed - freezeDuration)

        // 好奇心演化（疲劳高位冻结 + 喜悦额外流入 +0.3/h）
        val initialCuriosity = drives["curiosity"]?.value?.coerceIn(0.0, 100.0) ?: 40.0
        val curiosityBaseline = defaultDrives["curiosity"]?.baseline ?: 40.0
        var evolvedCuriosity = evolvePiecewise(
            initialCuriosity,
            activeCognitiveHours,
            listOf(
                LinearTier(limit = 70.0, rate = 0.8),
                LinearTier(limit = 85.0, rate = 0.3),
                LinearTier(limit = 100.0, rate = 0.05),
            )
        )
        if (highJoyHours > 0.0 && activeCognitiveHours > 0.0) {
            val boostHours = min(activeCognitiveHours, highJoyHours)
            evolvedCuriosity = (evolvedCuriosity + 0.3 * boostHours).coerceIn(0.0, 100.0)
        }

        // 社交欲演化（疲劳高位冻结）
        val initialSocial = drives["social"]?.value?.coerceIn(0.0, 100.0) ?: 30.0
        val socialBaseline = defaultDrives["social"]?.baseline ?: 30.0
        val evolvedSocial = evolvePiecewise(
            initialSocial,
            activeCognitiveHours,
            listOf(
                LinearTier(limit = 70.0, rate = 0.6),
                LinearTier(limit = 85.0, rate = 0.25),
                LinearTier(limit = 100.0, rate = 0.05),
            )
        )

        // 组装最终结果
        for ((key, drive) in drives) {
            val baseline = defaultDrives[key]?.baseline ?: drive.baseline
            val finalVal = when (key) {
                "fatigue" -> evolvedFatigue
                "stress" -> evolvedStress
                "joy" -> evolvedJoy
                "attachment" -> evolvedAttachment
                "duty" -> evolvedDuty
                "intimacy" -> evolvedIntimacy
                "curiosity" -> evolvedCuriosity
                "reflection" -> evolvedReflection
                "social" -> evolvedSocial
                else -> drive.value
            }
            result[key] = drive.copy(
                baseline = baseline,
                value = finalVal.coerceIn(0.0, 100.0),
            )
        }

        return result
    }
}
