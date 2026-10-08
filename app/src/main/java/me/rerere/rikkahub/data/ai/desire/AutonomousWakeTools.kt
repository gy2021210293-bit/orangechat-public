package me.rerere.rikkahub.data.ai.desire

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.desire.db.AutonomousItemEntity
import kotlin.uuid.Uuid

fun buildAutonomousWakeTools(
    assistantId: String,
    repository: DesireRepository,
    share: suspend (body: String, itemId: String?, resultId: String?, requestId: String?) -> AutonomousShareResult,
): List<Tool> = listOf(
    Tool(
        name = "autonomous_item_create",
        description = "仅为确实需要跨轮次续接的具体事情创建事项；单次问候、感想或等待不创建事项。resume_at 必须是带时区的 ISO-8601 时间，例如 2026-09-30T14:00:00+08:00。",
        parameters = { schema(mapOf("content" to "string", "resume_at" to "string"), listOf("content")) },
        execute = { args ->
            val fields = args.jsonObject
            val item = repository.createAutonomousItem(
                assistantId,
                Uuid.random().toString(),
                fields.string("content").orEmpty(),
                fields.string("resume_at"),
            )
            jsonText(itemJson(item))
        },
    ),
    Tool(
        name = "autonomous_item_update",
        description = "更新同一事项的内容、进展或到期时间；不要为同一事项创建新 ID。仅更新 progress 不会改变 resume_at；已到期但仍需继续时同时设未来 resume_at，或用 clear_resume_at=true 清除到期时间。",
        parameters = {
            schema(
                mapOf("item_id" to "string", "content" to "string", "progress" to "string", "resume_at" to "string", "clear_resume_at" to "boolean"),
                listOf("item_id"),
            )
        },
        execute = { args ->
            val fields = args.jsonObject
            val clearDue = fields["clear_resume_at"]?.jsonPrimitive?.booleanOrNull == true
            val item = repository.updateAutonomousItem(
                assistantId = assistantId,
                id = fields.string("item_id").orEmpty(),
                content = fields.string("content"),
                progress = fields.string("progress"),
                resumeAt = if (clearDue) null else fields.string("resume_at"),
                updateResumeAt = clearDue || fields.containsKey("resume_at"),
            )
            jsonText(itemJson(item))
        },
    ),
    itemStatusTool(assistantId, repository, "autonomous_item_complete", "completed"),
    itemStatusTool(assistantId, repository, "autonomous_item_cancel", "cancelled"),
    Tool(
        name = "autonomous_share_result",
        description = "向用户分享一条新的结果。具体事项结果必须同时传 item_id 和 result_id；普通关心或日常表达不传 item_id/result_id，但必须传 request_id。重试必须复用原标识。",
        parameters = {
            schema(
                mapOf("body" to "string", "item_id" to "string", "result_id" to "string", "request_id" to "string"),
                listOf("body"),
            )
        },
        execute = { args ->
            val fields = args.jsonObject
            val result = share(
                fields.string("body").orEmpty(),
                fields.string("item_id"),
                fields.string("result_id"),
                fields.string("request_id"),
            )
            jsonText(result.toJson())
        },
    ),
)

data class AutonomousShareResult(
    val status: String,
    val alreadyShared: Boolean = false,
    val content: String = "",
    val sentAt: Long? = null,
    val messageId: String? = null,
    val reason: String? = null,
    val earliestRetryAt: Long? = null,
) {
    fun toJson() = buildJsonObject {
        put("status", status)
        put("already_shared", alreadyShared)
        put("content", content)
        sentAt?.let { put("sent_at", it) }
        messageId?.let { put("message_id", it) }
        reason?.let { put("reason", it) }
        earliestRetryAt?.let { put("earliest_retry_at", it) }
    }
}

private fun itemStatusTool(assistantId: String, repository: DesireRepository, toolName: String, status: String) = Tool(
    name = toolName,
    description = "将指定活动事项标记为${if (status == "completed") "完成" else "取消"}。",
    parameters = { schema(mapOf("item_id" to "string"), listOf("item_id")) },
    execute = { args ->
        val itemId = args.jsonObject.string("item_id").orEmpty()
        repository.setAutonomousItemStatus(assistantId, itemId, status)
        jsonText(buildJsonObject {
            put("success", true)
            put("item_id", itemId)
            put("status", status)
        })
    },
)

private fun schema(properties: Map<String, String>, required: List<String>) = InputSchema.Obj(
    properties = buildJsonObject {
        properties.forEach { (name, type) ->
            put(name, buildJsonObject {
                put("type", type)
                put("description", name)
            })
        }
    },
    required = required,
)

private fun JsonElement.string(name: String) = this.jsonObject[name]?.jsonPrimitive?.contentOrNull

private fun itemJson(item: AutonomousItemEntity) = buildJsonObject {
    put("id", item.id)
    put("content", item.content)
    put("progress", item.progress)
    put("status", item.status)
    item.resumeAt?.let { put("resume_at", it) }
}

private fun jsonText(json: kotlinx.serialization.json.JsonElement) = listOf(UIMessagePart.Text(json.toString()))
