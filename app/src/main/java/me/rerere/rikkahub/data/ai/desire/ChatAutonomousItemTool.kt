package me.rerere.rikkahub.data.ai.desire

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

fun buildChatAutonomousItemTool(
    assistantId: String,
    repository: DesireRepository,
) = Tool(
    name = "autonomous_item_manage",
    description = "管理当前助手的主动唤醒事项。先用 list 查看活动事项及 item_id；create 新增，update 修改，delete 取消事项。只有用户明确要求时才更改。delete 是取消记录，不会抹除历史。事项由之后的正常唤醒读取；已有定时事项可用 clear_resume_at 清除时间。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                listOf("action", "item_id", "content", "progress").forEach { name ->
                    put(name, buildJsonObject { put("type", "string") })
                }
                put("clear_resume_at", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("action"),
        )
    },
    execute = { args ->
        val fields = args.jsonObject
        fun string(name: String) = fields[name]?.jsonPrimitive?.contentOrNull
        val result = when (string("action")) {
            "list" -> buildJsonObject {
                put("items", kotlinx.serialization.json.JsonArray(repository.listAutonomousItems(assistantId).map(::itemJson)))
            }
            "create" -> {
                val (item, alreadyExists) = repository.createChatAutonomousItem(
                    assistantId, string("content").orEmpty(), null,
                )
                buildJsonObject {
                    put("item", itemJson(item))
                    put("already_exists", alreadyExists)
                }
            }
            "update" -> {
                val clearDue = fields["clear_resume_at"]?.jsonPrimitive?.booleanOrNull == true
                val item = repository.updateAutonomousItem(
                    assistantId = assistantId,
                    id = requireNotNull(string("item_id")) { "缺少 item_id" },
                    content = string("content"),
                    progress = string("progress"),
                    resumeAt = null,
                    updateResumeAt = clearDue,
                )
                itemJson(item)
            }
            "delete" -> {
                val itemId = requireNotNull(string("item_id")) { "缺少 item_id" }
                repository.setAutonomousItemStatus(assistantId, itemId, "cancelled")
                buildJsonObject { put("item_id", itemId); put("status", "cancelled") }
            }
            else -> error("action 必须为 list、create、update 或 delete")
        }
        listOf(UIMessagePart.Text(result.toString()))
    },
)

private fun itemJson(item: AutonomousItemEntity) = buildJsonObject {
    put("item_id", item.id)
    put("content", item.content)
    put("progress", item.progress)
    put("status", item.status)
    item.resumeAt?.let { put("resume_at", it) }
}
