package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

const val GENERATED_IMAGE_TOOL_NAME = "generate_image"
const val GENERATED_IMAGE_MARKER = "orangechat_generated_image"

enum class GeneratedImageStatus(val wireValue: String) {
    PENDING("pending"), SUCCEEDED("succeeded"), FAILED("failed")
}

data class GeneratedImageRequest(
    val jobId: String,
    val description: String,
    val prompt: String,
    val systemPrompt: String,
    val aspectRatio: String,
    val modelId: String,
)

fun composeImageGenerationPrompt(systemPrompt: String, prompt: String): String =
    listOf(systemPrompt.trim(), prompt.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")

fun GeneratedImageRequest.toToolResult(): UIMessagePart.Text = UIMessagePart.Text(
    buildJsonObject {
        put(GENERATED_IMAGE_MARKER, true)
        put("job_id", jobId)
        put("description", description)
        put("prompt", prompt)
        put("system_prompt", systemPrompt)
        put("aspect_ratio", aspectRatio)
        put("model_id", modelId)
        put("accepted", true)
    }.toString()
)

fun JsonObject.toGeneratedImageRequestOrNull(): GeneratedImageRequest? {
    if (get(GENERATED_IMAGE_MARKER)?.jsonPrimitive?.booleanOrNull != true) return null
    fun value(key: String) = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
    return GeneratedImageRequest(
        jobId = value("job_id"),
        description = value("description"),
        prompt = value("prompt"),
        systemPrompt = value("system_prompt"),
        aspectRatio = value("aspect_ratio").ifBlank { "square" },
        modelId = value("model_id"),
    ).takeIf { it.jobId.isNotBlank() && it.description.isNotBlank() && it.prompt.isNotBlank() }
}

fun GeneratedImageRequest.toCardMessage(): UIMessage = UIMessage(
    role = MessageRole.ASSISTANT,
    parts = listOf(
        UIMessagePart.Image(
            url = "",
            metadata = buildGeneratedImageMetadata(GeneratedImageStatus.PENDING)
        )
    )
)

fun GeneratedImageRequest.buildGeneratedImageMetadata(
    status: GeneratedImageStatus,
    error: String = "",
): JsonObject = buildJsonObject {
    put("generated_image", true)
    put("job_id", jobId)
    put("description", description)
    put("prompt", prompt)
    put("system_prompt", systemPrompt)
    put("aspect_ratio", aspectRatio)
    put("model_id", modelId)
    put("status", status.wireValue)
    if (error.isNotBlank()) put("error", error)
}

fun UIMessagePart.Image.generatedImageRequestOrNull(): GeneratedImageRequest? {
    val data = metadata ?: return null
    if (data["generated_image"]?.jsonPrimitive?.booleanOrNull != true) return null
    fun value(key: String) = data[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    return GeneratedImageRequest(
        jobId = value("job_id"),
        description = value("description"),
        prompt = value("prompt"),
        systemPrompt = value("system_prompt"),
        aspectRatio = value("aspect_ratio").ifBlank { "square" },
        modelId = value("model_id"),
    ).takeIf { it.jobId.isNotBlank() && it.description.isNotBlank() }
}

fun UIMessagePart.Image.generatedImageStatus(): GeneratedImageStatus? = when (
    metadata?.get("status")?.jsonPrimitive?.contentOrNull
) {
    "pending" -> GeneratedImageStatus.PENDING
    "succeeded" -> GeneratedImageStatus.SUCCEEDED
    "failed" -> GeneratedImageStatus.FAILED
    else -> null
}

fun List<UIMessage>.replaceGeneratedImagesWithDescriptions(): List<UIMessage> = map { message ->
    message.copy(parts = message.parts.map { part ->
        if (part !is UIMessagePart.Image) return@map part
        val request = part.generatedImageRequestOrNull() ?: return@map part
        val text = when (part.generatedImageStatus()) {
            GeneratedImageStatus.PENDING -> "AI 正在生成一张图片：${request.description}"
            GeneratedImageStatus.FAILED -> "AI 曾尝试生成一张图片：${request.description}，但生成失败"
            else -> "AI 曾发送一张图片：${request.description}"
        }
        UIMessagePart.Text(text)
    })
}

fun newGeneratedImageJobId(): String = Uuid.random().toString()
