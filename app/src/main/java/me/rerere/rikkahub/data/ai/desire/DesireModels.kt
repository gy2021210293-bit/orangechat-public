/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 表达 AI 当前尚未满足的短期内在动机与欲望缺口（而非长期人格或关系评价）。
 * 各维度在事件或离线时间经过时形成缺口，到达行动阈值时转化为念头与行动动机，成功表达或满足后回落。
 */
@Serializable
data class Drive(
    val name: String,
    var value: Double = 50.0,
    var baseline: Double = 50.0,
    @SerialName("decay_rate") val decayRate: Double = 0.1,
    @SerialName("growth_rate") val growthRate: Double = 0.2,
    val ceiling: Double = 100.0,
    val floor: Double = 0.0,
    @SerialName("action_threshold") val actionThreshold: Double = 70.0,
) {
    fun clamp() {
        value = value.coerceIn(floor, ceiling)
    }

    fun copyDrive(): Drive = copy()
}

@Serializable
data class Thought(
    val id: String,
    val content: String,
    @SerialName("source_drive") val sourceDrive: String,
    var weight: Double = 1.0,
    @SerialName("hit_count") var hitCount: Int = 0,
    @SerialName("is_obsession") var isObsession: Boolean = false,
    @SerialName("created_at") var createdAt: String = "",
    @SerialName("last_hit") var lastHit: String = "",
    var resolved: Boolean = false,
) {
    fun copyThought(): Thought = copy()
}

@Serializable
data class ActionHint(
    val action: String,
    val reason: String,
    val priority: String = "low", // "low", "medium", "high"
)

@Serializable
data class DesireState(
    val drives: MutableMap<String, Drive> = createDefaultDrives().toMutableMap(),
    val thoughts: MutableList<Thought> = mutableListOf(),
    @SerialName("last_tick") var lastTick: String = "",
    @SerialName("tick_count") var tickCount: Int = 0,
) {
    fun copyState(): DesireState {
        return DesireState(
            drives = drives.mapValues { it.value.copyDrive() }.toMutableMap(),
            thoughts = thoughts.map { it.copyThought() }.toMutableList(),
            lastTick = lastTick,
            tickCount = tickCount,
        )
    }
}

@Serializable
data class TickResult(
    val tick: Int,
    val changes: List<String>,
    @SerialName("action_hints") val actionHints: List<ActionHint>,
    @SerialName("next_interval") val nextInterval: Int,
)

fun createDefaultDrives(): Map<String, Drive> = mapOf(
    "attachment" to Drive(
        name = "attachment",
        value = 40.0,
        baseline = 40.0,
        growthRate = 0.2,
        decayRate = 0.1,
        actionThreshold = 70.0,
    ),
    "curiosity" to Drive(
        name = "curiosity",
        value = 40.0,
        baseline = 40.0,
        growthRate = 0.2,
        decayRate = 0.1,
        actionThreshold = 60.0,
    ),
    "reflection" to Drive(
        name = "reflection",
        value = 30.0,
        baseline = 30.0,
        growthRate = 0.15,
        decayRate = 0.2,
        actionThreshold = 50.0,
    ),
    "duty" to Drive(
        name = "duty",
        value = 40.0,
        baseline = 40.0,
        growthRate = 0.1,
        decayRate = 0.2,
        actionThreshold = 70.0,
    ),
    "social" to Drive(
        name = "social",
        value = 30.0,
        baseline = 30.0,
        growthRate = 0.1,
        decayRate = 0.15,
        actionThreshold = 60.0,
    ),
    "fatigue" to Drive(
        name = "fatigue",
        value = 20.0,
        baseline = 20.0,
        growthRate = 0.0,
        decayRate = 0.3,
        actionThreshold = 80.0,
    ),
    "intimacy" to Drive(
        name = "intimacy",
        value = 40.0,
        baseline = 40.0,
        growthRate = 0.1,
        decayRate = 0.15,
        actionThreshold = 70.0,
    ),
    "stress" to Drive(
        name = "stress",
        value = 20.0,
        baseline = 20.0,
        growthRate = 0.0,
        decayRate = 0.2,
        actionThreshold = 80.0,
    ),
    "joy" to Drive(
        name = "joy",
        value = 50.0,
        baseline = 50.0,
        growthRate = 0.0,
        decayRate = 0.15,
        actionThreshold = 80.0,
    ),
)
