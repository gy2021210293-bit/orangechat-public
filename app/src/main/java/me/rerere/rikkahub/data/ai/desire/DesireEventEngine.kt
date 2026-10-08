/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import java.util.Locale
import kotlin.math.abs

object DesireEventEngine {

    val AI_CALLABLE_EVENTS: Set<String> = setOf(
        "task_added",
        "task_done",
        "interest_sparked",
        "discovery",
        "penpal_message",
        "diary_written",
        "fight",
        "reconcile",
        "setback",
        "intimacy_done",
        "heavy_work",
        "rest",
        "happy_moment",
        "creative_done",
    )

    val EVENT_EFFECTS: Map<String, Map<String, Double>> = mapOf(
        "master_message" to mapOf("attachment" to -12.0, "intimacy" to 0.5, "stress" to -1.0),
        "master_silent" to mapOf("attachment" to 0.0, "stress" to 3.0),
        "wife_message" to mapOf("attachment" to -12.0, "intimacy" to 0.5, "stress" to -1.0),
        "wife_silent" to mapOf("attachment" to 0.0, "stress" to 3.0),
        "task_added" to mapOf("duty" to 10.0, "stress" to 2.0),
        "task_done" to mapOf("duty" to -15.0, "stress" to -5.0, "curiosity" to 5.0),
        "interest_sparked" to mapOf("curiosity" to 10.0, "reflection" to 2.0),
        "penpal_message" to mapOf("social" to -10.0, "curiosity" to 3.0),
        "diary_written" to mapOf("reflection" to -10.0, "stress" to -3.0),
        "fight" to mapOf("stress" to 25.0, "attachment" to 15.0, "intimacy" to 20.0, "joy" to -15.0),
        "reconcile" to mapOf("stress" to -20.0, "attachment" to 10.0, "intimacy" to 10.0, "joy" to 10.0),
        "setback" to mapOf("stress" to 10.0, "joy" to -8.0, "reflection" to 4.0),
        "intimacy_done" to mapOf("intimacy" to -10.0, "stress" to -8.0, "attachment" to 8.0, "joy" to 5.0),
        "heavy_work" to mapOf("fatigue" to 15.0, "duty" to 5.0, "stress" to 5.0),
        "rest" to mapOf("fatigue" to -20.0, "stress" to -5.0),
        "discovery" to mapOf("curiosity" to -10.0, "reflection" to 5.0, "joy" to 5.0),
        "happy_moment" to mapOf("joy" to 15.0, "stress" to -5.0, "intimacy" to 3.0, "attachment" to 2.0),
        "creative_done" to mapOf("joy" to 10.0, "curiosity" to -5.0),
    )

    fun surpriseMultiplier(state: DesireState, driveName: String, delta: Double): Double {
        val drive = state.drives[driveName] ?: return 1.0
        val value = drive.value
        return if (delta < 0) {
            1.0 + (value / 100.0) * 0.5
        } else {
            1.0 + ((100.0 - value) / 100.0) * 0.5
        }
    }

    fun applyEvent(state: DesireState, eventType: String): List<String> {
        val effects = EVENT_EFFECTS[eventType] ?: return emptyList()
        val changes = mutableListOf<String>()
        for ((driveName, delta) in effects) {
            val drive = state.drives[driveName] ?: continue
            val actualDelta = if ((eventType == "master_message" || eventType == "wife_message") && driveName == "attachment") {
                -drive.value * 0.20
            } else {
                val multiplier = surpriseMultiplier(state, driveName, delta)
                delta * multiplier
            }
            val old = drive.value
            drive.value += actualDelta
            drive.clamp()
            val new = drive.value
            if (abs(new - old) > 0.1) {
                changes.add(String.format(Locale.ROOT, "%s: %.0f → %.0f", driveName, old, new))
            }
        }
        return changes
    }
}
