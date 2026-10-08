package me.rerere.rikkahub.data.ai.desire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

object AutonomousToolReplayPolicy {
    private const val IDEMPOTENT_SHARE_TOOL = "autonomous_share_result"
    private val json = Json

    fun uncertainCallKeys(toolCallsJson: List<String>): Set<String> = buildSet {
        toolCallsJson.forEach { encoded ->
            val calls = runCatching { json.parseToJsonElement(encoded).jsonArray }.getOrNull() ?: return@forEach
            calls.forEach { element ->
                runCatching {
                    val call = element.jsonObject
                    if (call["status"]?.jsonPrimitive?.contentOrNull == "result_uncertain") {
                        val name = call["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val input = call["input"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (name.isNotBlank()) add(key(name, input))
                    }
                }
            }
        }
    }

    fun shouldBlockReplay(toolName: String, input: String, uncertainKeys: Set<String>): Boolean =
        toolName != IDEMPOTENT_SHARE_TOOL && key(toolName, input) in uncertainKeys

    fun key(toolName: String, input: String): String {
        val normalized = runCatching { canonicalize(json.parseToJsonElement(input.ifBlank { "{}" })).toString() }
            .getOrElse { input.trim() }
        return "$toolName\u0000$normalized"
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { (_, value) -> canonicalize(value) })
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }
}
