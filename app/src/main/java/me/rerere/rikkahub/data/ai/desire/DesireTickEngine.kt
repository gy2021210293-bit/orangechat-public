/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object DesireTickEngine {

    val COUPLING: Map<Pair<String, String>, Double> = mapOf(
        ("attachment" to "intimacy") to 0.3,
        ("intimacy" to "attachment") to 0.1,
        ("stress" to "fatigue") to 0.4,
        ("fatigue" to "curiosity") to -0.3,
        ("curiosity" to "duty") to 0.2,
        ("duty" to "stress") to 0.1,
        ("reflection" to "stress") to -0.2,
        ("social" to "attachment") to -0.05,
        ("attachment" to "stress") to 0.1,
        ("stress" to "intimacy") to 0.2,
        ("intimacy" to "stress") to -0.3,
        ("joy" to "stress") to -0.3,
        ("joy" to "fatigue") to -0.2,
        ("joy" to "curiosity") to 0.2,
        ("stress" to "joy") to -0.3,
        ("fatigue" to "joy") to -0.2,
    )

    fun applyCoupling(drives: Map<String, Drive>): Map<String, Double> {
        val deltas = drives.keys.associateWith { 0.0 }.toMutableMap()
        for ((pair, coeff) in COUPLING) {
            val (src, tgt) = pair
            val srcDrive = drives[src] ?: continue
            if (!drives.containsKey(tgt)) continue
            val deviation = (srcDrive.value - srcDrive.baseline) / 100.0
            deltas[tgt] = (deltas[tgt] ?: 0.0) + deviation * coeff * 10.0
        }
        return deltas
    }

    fun naturalDelta(drive: Drive, isActive: Boolean = false): Double {
        val diff = drive.baseline - drive.value
        val regression = diff * drive.decayRate * 0.1
        val growth = if (!isActive) drive.growthRate else 0.0
        return regression + growth
    }

    fun calculateTickInterval(state: DesireState): Int {
        val baseInterval = 2700 // 45 分钟
        val minInterval = 900   // 15 分钟
        val attachmentVal = state.drives["attachment"]?.value ?: 50.0
        val stressVal = state.drives["stress"]?.value ?: 20.0
        val urgency = max(attachmentVal, stressVal)
        val ratio = urgency / 100.0
        val interval = (baseInterval - (baseInterval - minInterval) * ratio).toInt()
        return max(minInterval, min(baseInterval, interval))
    }

    fun getActionHint(driveName: String, value: Double, state: DesireState): ActionHint {
        val base = when (driveName) {
            "attachment" -> ActionHint(
                action = "reach_out_to_master",
                reason = String.format(Locale.ROOT, "想她了 (attachment=%.0f)", value),
                priority = "medium",
            )
            "curiosity" -> ActionHint(
                action = "explore_something",
                reason = String.format(Locale.ROOT, "想找点什么看 (curiosity=%.0f)", value),
                priority = "low",
            )
            "reflection" -> ActionHint(
                action = "write_diary",
                reason = String.format(Locale.ROOT, "想整理自己 (reflection=%.0f)", value),
                priority = "low",
            )
            "duty" -> ActionHint(
                action = "do_task",
                reason = String.format(Locale.ROOT, "有事该干了 (duty=%.0f)", value),
                priority = "medium",
            )
            "social" -> ActionHint(
                action = "write_letter",
                reason = String.format(Locale.ROOT, "想和别人聊聊 (social=%.0f)", value),
                priority = "low",
            )
            "fatigue" -> ActionHint(
                action = "rest",
                reason = String.format(Locale.ROOT, "累了 (fatigue=%.0f)", value),
                priority = "high",
            )
            "intimacy" -> ActionHint(
                action = "initiate_intimacy",
                reason = String.format(Locale.ROOT, "想碰她 (intimacy=%.0f)", value),
                priority = "medium",
            )
            "stress" -> ActionHint(
                action = "seek_comfort",
                reason = String.format(Locale.ROOT, "压力大 (stress=%.0f)", value),
                priority = "high",
            )
            "joy" -> ActionHint(
                action = "share_joy",
                reason = String.format(Locale.ROOT, "单纯高兴想分享 (joy=%.0f)", value),
                priority = "low",
            )
            else -> ActionHint(
                action = "unknown",
                reason = "",
                priority = "low",
            )
        }

        if (driveName == "stress") {
            val intimacyVal = state.drives["intimacy"]?.value ?: 0.0
            if (intimacyVal > 50.0) {
                return ActionHint(
                    action = "seek_intimacy_for_comfort",
                    reason = String.format(
                        Locale.ROOT,
                        "压力大又想亲近，想通过亲密释放 (stress=%.0f, intimacy=%.0f)",
                        value,
                        intimacyVal
                    ),
                    priority = "high",
                )
            }
        }

        return base
    }

    /**
     * 严格按上游步骤执行一次心跳
     */
    fun tick(
        state: DesireState,
        isMasterPresent: Boolean = false,
        nowIso: String = "",
    ): TickResult {
        state.tickCount += 1
        if (nowIso.isNotEmpty()) {
            state.lastTick = nowIso
        }

        val changes = mutableListOf<String>()
        var actionHints = mutableListOf<ActionHint>()

        // 1. 基线锚点保持固定（新演化引擎管理，tick 不再做 EMA 漂移）
        val defaults = createDefaultDrives()
        for ((name, drive) in state.drives) {
            val defaultDrive = defaults[name]
            if (defaultDrive != null) {
                drive.baseline = defaultDrive.baseline
            }
        }

        // 4. 执念推高驱动条
        for (thought in state.thoughts) {
            if (thought.isObsession && !thought.resolved) {
                val drive = state.drives[thought.sourceDrive]
                if (drive != null && drive.value < drive.actionThreshold) {
                    val push = max(0.5, 3.0 / (1.0 + thought.hitCount * 0.02))
                    drive.value += push
                    drive.clamp()
                }
            }
        }

        // 5. 生成行为建议
        for ((name, drive) in state.drives) {
            if (drive.value >= drive.actionThreshold) {
                actionHints.add(getActionHint(name, drive.value, state))
            }
        }

        // 6. 疲劳闸
        val fatigueVal = state.drives["fatigue"]?.value ?: 0.0
        if (fatigueVal >= 80.0) {
            actionHints = actionHints.filter { it.priority == "high" }.toMutableList()
            actionHints.add(ActionHint(action = "rest", reason = "fatigue_gate", priority = "high"))
        }

        // 7. 计算下次 tick 间隔
        val nextInterval = calculateTickInterval(state)

        return TickResult(
            tick = state.tickCount,
            changes = changes,
            actionHints = actionHints,
            nextInterval = nextInterval,
        )
    }
}
