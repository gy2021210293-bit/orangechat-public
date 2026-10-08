/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
enum class MemoryPatchAction {
    @SerialName("ADD")
    ADD,
    @SerialName("UPDATE")
    UPDATE,
    @SerialName("MERGE")
    MERGE,
    @SerialName("SUPERSEDE")
    SUPERSEDE,
    @SerialName("ARCHIVE")
    ARCHIVE,
    @SerialName("NOOP")
    NOOP,
}

@Serializable
data class DailyNoteResult(
    val summary: String = "",
    @SerialName("emotional_thread")
    val emotionalThread: String? = null,
    @SerialName("unfinished_topics")
    val unfinishedTopics: List<String> = emptyList(),
)

@Serializable
data class TimelineProposalDto(
    val title: String,
    val summary: String = "",
    @SerialName("card_ids")
    val cardIds: List<String> = emptyList(),
)

@Serializable
data class RelationProposalDto(
    @SerialName("source_card_id")
    val sourceCardId: String? = null,
    @SerialName("target_card_id")
    val targetCardId: String,
    @SerialName("relation_type")
    val relationType: String,
    val strength: Float = 1.0f,
    val reason: String? = null,
    @SerialName("source_ids")
    val sourceIds: List<String> = emptyList(),
    @SerialName("evidence_snippet")
    val evidenceSnippet: String? = null,
)

@Serializable
data class MemoryPatch(
    val action: MemoryPatchAction = MemoryPatchAction.NOOP,
    @SerialName("target_id")
    val targetId: String? = null,
    @SerialName("secondary_ids")
    val secondaryIds: List<String> = emptyList(),
    @SerialName("source_ids")
    val sourceIds: List<String> = emptyList(),
    @SerialName("expected_revision")
    val expectedRevision: Int? = null,
    @SerialName("fact_key")
    val factKey: String? = null,
    val category: String? = null,
    val content: String? = null,
    val confidence: Float? = null,
    val importance: Float? = null,
    val reason: String? = null,
    @SerialName("evidence_snippet")
    val evidenceSnippet: String? = null,
    @SerialName("timeline_id")
    val timelineId: String? = null,
    @SerialName("timeline_title")
    val timelineTitle: String? = null,
    @SerialName("related_card_ids")
    val relatedCardIds: List<String> = emptyList(),
    @SerialName("relation_type")
    val relationType: String? = null,
    val relations: List<RelationProposalDto> = emptyList(),
)

@Serializable
data class ExperienceCoordinateProposalDto(
    @SerialName("card_id")
    val cardId: String? = null,
    val meaning: String,
    @SerialName("initial_priority")
    val initialPriority: Int = 50,
    @SerialName("response_tendency")
    val responseTendency: String,
    val valence: Float? = null,
    val arousal: Float? = null,
    val dominance: Float? = null,
    val intimacy: Float? = null,
    @SerialName("social_distance")
    val socialDistance: Float? = null,
    val tension: Float? = null,
    @SerialName("growth_delta")
    val growthDelta: String = "STATIC",
    @SerialName("source_episode_key")
    val sourceEpisodeKey: String? = null,
)

@Serializable
data class DailyConsolidationResult(
    @SerialName("daily_note")
    val dailyNote: DailyNoteResult = DailyNoteResult(),
    val patches: List<MemoryPatch> = emptyList(),
    val relations: List<RelationProposalDto> = emptyList(),
    val timelines: List<TimelineProposalDto> = emptyList(),
    @SerialName("experience_coordinates")
    val experienceCoordinates: List<ExperienceCoordinateProposalDto> = emptyList(),
)

data class MemorySourceReference(
    val conversationId: String,
    val nodeId: String?,
    val messageId: String,
    val role: String,
    val messageCreatedAt: Long,
)

object MemoryConsolidationParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * 解析模型返回的每日整理文本，支持 Markdown 代码块提取与前后杂质剥离。
     */
    fun parse(rawText: String): DailyConsolidationResult {
        val cleaned = extractJsonPayload(rawText.trim())
        return json.decodeFromString(DailyConsolidationResult.serializer(), cleaned)
    }

    fun parseStructured(rawText: String): DailyConsolidationResult {
        val cleaned = extractJsonPayload(rawText.trim())
        val root = json.parseToJsonElement(cleaned) as? JsonObject
            ?: throw IllegalArgumentException("Structured consolidation output must be a JSON object")
        if (root.keys.none { it in STRUCTURED_KEYS }) {
            throw IllegalArgumentException("Structured consolidation output has no structured result")
        }
        return json.decodeFromString(DailyConsolidationResult.serializer(), cleaned)
    }

    fun parseDailyNote(rawText: String): DailyNoteResult {
        val cleaned = extractJsonPayload(rawText.trim())
        val root = json.parseToJsonElement(cleaned) as? JsonObject
            ?: throw IllegalArgumentException("Daily note output must be a JSON object")
        if ("daily_note" !in root) {
            throw IllegalArgumentException("Daily note output is missing daily_note")
        }
        val result = json.decodeFromString(DailyConsolidationResult.serializer(), cleaned).dailyNote
        if (result.summary.isBlank()) {
            throw IllegalArgumentException("Daily note output is missing summary")
        }
        return result
    }

    private fun extractJsonPayload(text: String): String {
        // 先剥离模型可能输出的 <think>...</think> 思考内容，防止其中的括号干扰截取
        val withoutThink = text.replace(Regex("<think>([\\s\\S]*?)(?:</think>|$)", RegexOption.IGNORE_CASE), "").trim()

        // 尝试剥离 ```json ... ``` 或 ``` ... ```
        val codeBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
        val match = codeBlockRegex.find(withoutThink)
        val candidate = if (match != null) {
            match.groupValues[1].trim()
        } else {
            withoutThink
        }

        // 寻找最外层的 '{' 与 '}'
        val startIdx = candidate.indexOf('{')
        val endIdx = candidate.lastIndexOf('}')
        if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
            return candidate.substring(startIdx, endIdx + 1)
        }
        return candidate
    }

    private val STRUCTURED_KEYS = setOf("patches", "timelines", "relations", "experience_coordinates")
}
