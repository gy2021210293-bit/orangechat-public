/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

data class SafetyStatus(
    val baselineDrifts: Map<String, Double>,
    val obsessionCount: Int,
    val fatigue: Double,
    val stress: Double,
    val anyWarning: Boolean,
)

object DesireSafety {

    const val BASELINE_WARN_THRESHOLD = 20.0
    const val BASELINE_FORCE_THRESHOLD = 30.0
    const val STORM_DIMENSION_COUNT = 3
    const val STORM_DELTA_THRESHOLD = 15.0

    fun safetyCheck(
        state: DesireState,
        tickDeltas: Map<String, Double>? = null,
    ): List<String> {
        val warnings = mutableListOf<String>()

        // 阀一：硬顶硬底
        for (drive in state.drives.values) {
            drive.clamp()
        }

        // 阀二：基线漂移检测
        val defaults = createDefaultDrives()
        for ((name, drive) in state.drives) {
            val defaultDrive = defaults[name] ?: continue
            val initialBaseline = defaultDrive.baseline
            val drift = abs(drive.baseline - initialBaseline)
            if (drift > BASELINE_FORCE_THRESHOLD) {
                val direction = if (drive.baseline < initialBaseline) 1.0 else -1.0
                drive.baseline += direction * 2.0
                warnings.add(
                    String.format(
                        Locale.ROOT,
                        "FORCE_RESET: %s baseline drift %.1f, correcting",
                        name,
                        drift
                    )
                )
            } else if (drift > BASELINE_WARN_THRESHOLD) {
                warnings.add(
                    String.format(
                        Locale.ROOT,
                        "WARN: %s baseline drift %.1f",
                        name,
                        drift
                    )
                )
            }
        }

        // 阀四：情绪风暴检测
        if (tickDeltas != null) {
            val bigChanges = tickDeltas.filter { abs(it.value) > STORM_DELTA_THRESHOLD }.keys.toList()
            if (bigChanges.size >= STORM_DIMENSION_COUNT) {
                for (name in bigChanges) {
                    val drive = state.drives[name] ?: continue
                    drive.value = (drive.value + drive.baseline) / 2.0
                    drive.clamp()
                }
                warnings.add("STORM: ${bigChanges.size} dimensions spiked, dampened: $bigChanges")
            }
        }

        // 阀五：疲劳硬闸
        val fatigueVal = state.drives["fatigue"]?.value ?: 0.0
        if (fatigueVal >= 90.0) {
            warnings.add("FATIGUE_GATE: fatigue>=90, blocking non-essential actions")
        }

        return warnings
    }

    fun getSafetyStatus(state: DesireState): SafetyStatus {
        val defaults = createDefaultDrives()
        val drifts = mutableMapOf<String, Double>()
        for ((name, drive) in state.drives) {
            val defaultDrive = defaults[name] ?: continue
            val drift = ((drive.baseline - defaultDrive.baseline) * 10.0).roundToLong() / 10.0
            drifts[name] = drift
        }

        val obsessionCount = state.thoughts.count { it.isObsession && !it.resolved }
        val fatigue = ((state.drives["fatigue"]?.value ?: 0.0) * 10.0).roundToLong() / 10.0
        val stress = ((state.drives["stress"]?.value ?: 0.0) * 10.0).roundToLong() / 10.0
        val anyWarning = drifts.values.any { abs(it) > BASELINE_WARN_THRESHOLD }

        return SafetyStatus(
            baselineDrifts = drifts,
            obsessionCount = obsessionCount,
            fatigue = fatigue,
            stress = stress,
            anyWarning = anyWarning,
        )
    }
}
