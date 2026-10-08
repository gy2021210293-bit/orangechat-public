package me.rerere.rikkahub.data.ai.desire

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class WakeDecision(
    val autonomousCandidateAt: Long,
    val itemCandidateAt: Long?,
    val scheduledAt: Long,
    val source: String,
    val reason: String,
    val chosenDelayMinutes: Int,
    val adjustmentReason: String,
)

object AutonomousWakePolicy {
    const val MIN_DELAY_MINUTES = 15
    const val MAX_DELAY_MINUTES = 24 * 60
    private const val MINUTE_MS = 60_000L

    fun decide(
        endedAt: Long,
        defaultDelayMinutes: Int,
        suggestedDelayMinutes: Int?,
        suggestedReason: String?,
        itemDueAt: List<Long>,
        suggestionAdjustmentReason: String? = null,
    ): WakeDecision {
        val rawDelay = suggestedDelayMinutes ?: defaultDelayMinutes
        val chosenDelay = rawDelay.coerceIn(MIN_DELAY_MINUTES, MAX_DELAY_MINUTES)
        val boundaryAdjustment = if (rawDelay != chosenDelay) {
            "建议为${rawDelay}分钟，已限制为${chosenDelay}分钟（允许范围${MIN_DELAY_MINUTES}分钟至24小时）"
        } else ""
        val adjustment = listOf(suggestionAdjustmentReason, boundaryAdjustment)
            .filterNotNull().filter { it.isNotBlank() }.joinToString("；")
        val autonomousAt = endedAt + chosenDelay * MINUTE_MS
        val nextItemAt = itemDueAt.filter { it > endedAt }.minOrNull()
        val scheduledAt = minOf(autonomousAt, nextItemAt ?: Long.MAX_VALUE)
        val source = if (nextItemAt != null && nextItemAt < autonomousAt) "item" else "autonomous"
        val reason = if (source == "item") {
            "事项到期优先：${Instant.ofEpochMilli(nextItemAt!!)}；自主计划：${suggestedReason.orEmpty().ifBlank { "默认等待${chosenDelay}分钟" }}"
        } else suggestedReason.orEmpty().ifBlank { "默认等待${chosenDelay}分钟" }
        return WakeDecision(autonomousAt, nextItemAt, scheduledAt, source, reason, chosenDelay, adjustment)
    }
}

data class AutonomousWakeResult(
    val summary: String,
    val outcome: String,
    val nextStep: String,
    val shouldContact: Boolean,
    val message: String,
    val nextWakeDelayMinutes: Int?,
    val nextWakeReason: String?,
    val wakeDelayAdjustmentReason: String?,
    val formatValid: Boolean,
)

object AutonomousWakeResultParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): AutonomousWakeResult {
        val jsonStart = raw.indexOf('{')
        val jsonEnd = raw.lastIndexOf('}')
        val jsonText = if (jsonStart >= 0 && jsonEnd > jsonStart) {
            raw.substring(jsonStart, jsonEnd + 1)
        } else ""
        val parsed = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull()
        fun primitive(name: String) = parsed?.get(name) as? JsonPrimitive
        fun text(name: String): String? {
            val value = parsed?.get(name) ?: return null
            return if (value is JsonPrimitive) value.contentOrNull else value.toString()
        }
        val rawDelay = parsed?.get("next_wake_delay_minutes")
        val parsedDelay = runCatching { rawDelay?.jsonPrimitive?.intOrNull }.getOrNull()
        val textFields = listOf("summary", "outcome", "next_step", "message", "next_wake_reason")
        val formatValid = parsed != null &&
            (parsed["summary"] as? JsonPrimitive)?.isString == true &&
            textFields.all { name -> parsed[name]?.let { (it as? JsonPrimitive)?.isString == true } ?: true } &&
            parsed["should_contact"]?.let { (it as? JsonPrimitive)?.booleanOrNull != null } != false &&
            (rawDelay == null || rawDelay.toString() == "null" || parsedDelay != null)
        val delayAdjustment = when {
            !formatValid -> "自主输出为空、JSON无效或字段类型不符；已保留原文，不执行分享并使用默认等待时间"
            else -> null
        }
        return AutonomousWakeResult(
            summary = if (formatValid) text("summary").orEmpty() else raw,
            outcome = if (formatValid) text("outcome").orEmpty() else raw,
            nextStep = if (formatValid) text("next_step").orEmpty() else "",
            shouldContact = formatValid && primitive("should_contact")?.booleanOrNull == true,
            message = if (formatValid) text("message").orEmpty() else "",
            nextWakeDelayMinutes = parsedDelay.takeIf { formatValid },
            nextWakeReason = text("next_wake_reason").takeIf { formatValid },
            wakeDelayAdjustmentReason = delayAdjustment,
            formatValid = formatValid,
        )
    }
}
