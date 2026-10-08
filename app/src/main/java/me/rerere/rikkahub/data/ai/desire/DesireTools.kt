/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant

fun buildDesireTools(
    repository: DesireRepository,
    assistant: Assistant,
): List<Tool> {
    if (!assistant.desireSystemEnabled) {
        return emptyList()
    }

    val assistantId = assistant.id.toString()

    val statusTool = Tool(
        name = "desire_status",
        description = "View the current nine-dimensional desire state (drives representing unsatisfied needs and internal motivations), thoughts, and evaluation timestamps.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {},
                required = emptyList(),
            )
        },
        execute = { _ ->
            val state = repository.getOrCreateState(assistantId)
            val payload = buildJsonObject {
                put("assistant_id", assistantId)
                put("tick_count", state.tickCount)
                put("last_tick", state.lastTick)
                put("drives", buildJsonObject {
                    state.drives.forEach { (name, d) ->
                        put(name, buildJsonObject {
                            put("value", d.value)
                            put("baseline", d.baseline)
                            put("action_threshold", d.actionThreshold)
                        })
                    }
                })
                put("thoughts", buildJsonArray {
                    state.thoughts.filter { !it.resolved }.forEach { t ->
                        add(buildJsonObject {
                            put("id", t.id)
                            put("content", t.content)
                            put("source_drive", t.sourceDrive)
                            put("hit_count", t.hitCount)
                            put("is_obsession", t.isObsession)
                        })
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )

    val eventTool = Tool(
        name = "desire_event",
        description = """
            Report a specific life or interaction event to the desire system.
            Allowed events:
            - task_added: 新增待办、收到任务、形成明确计划
            - task_done: 完成待办任务
            - interest_sparked: 产生兴趣、发现值得进一步了解的内容
            - discovery: 完成一次资料探索、查天气或信息检索
            - penpal_message: 完成一次社交互动（如发动态、与他人交流）
            - diary_written: 写完日记
            - fight: 发生争执或冲突
            - reconcile: 争执后和解、重归于好
            - setback: 失败、失落、计划受阻（非关系冲突）
            - intimacy_done: 完成亲密互动
            - heavy_work: 进行高强度工作或体力脑力消耗
            - rest: 休息、放松恢复
            - happy_moment: 玩游戏、娱乐或经历开心的时刻
            - creative_done: 完成创作或作品
            Rules:
            1. Only report events that have clearly occurred or been explicitly confirmed in conversation.
            2. Do not speculate or guess events just to alter values.
            3. Distinguish wanting to do something vs having completed it (e.g. task_added vs task_done).
            4. Submit one event per tool call; invoke multiple times for separate distinct events.
            5. Do not call this tool if no allowed event matches.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("event_type", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            DesireEventEngine.AI_CALLABLE_EVENTS.forEach { add(it) }
                        })
                        put("description", "Event type to apply from the allowed whitelist: ${DesireEventEngine.AI_CALLABLE_EVENTS.joinToString(", ")}")
                    })
                },
                required = listOf("event_type"),
            )
        },
        execute = execute@ { element ->
            val params = runCatching { element.jsonObject }.getOrNull()
                ?: return@execute listOf(UIMessagePart.Text("""{"error": "INVALID_PARAMS", "message": "Parameters must be a JSON object"}"""))
            val eventType = params["event_type"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: return@execute listOf(UIMessagePart.Text("""{"error": "MISSING_EVENT_TYPE", "message": "'event_type' is required"}"""))

            if (eventType !in DesireEventEngine.AI_CALLABLE_EVENTS) {
                return@execute listOf(
                    UIMessagePart.Text(
                        """{"error": "UNKNOWN_EVENT_TYPE", "message": "Event '$eventType' is not allowed or unknown. Allowed events: ${DesireEventEngine.AI_CALLABLE_EVENTS.joinToString(", ")}"}"""
                    )
                )
            }

            val toolCallId = params["_tool_call_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: params["call_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "${assistantId}_event_${System.currentTimeMillis()}"
            val eventId = if (toolCallId.startsWith(assistantId)) toolCallId else "${assistantId}_event_${toolCallId}"

            val changes = repository.applyEventOnce(
                assistantId = assistantId,
                eventId = eventId,
                eventName = eventType,
                sourceType = "tool",
                sourceId = toolCallId,
            )
            val state = repository.getOrCreateState(assistantId)
            val payload = buildJsonObject {
                put("event_type", eventType)
                put("changes", buildJsonArray { changes.forEach { add(it) } })
                put("drives_snapshot", buildJsonObject {
                    state.drives.forEach { (name, d) -> put(name, d.value) }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )

    val resolveTool = Tool(
        name = "desire_resolve_thought",
        description = "Resolve a thought from the thought pool. Reflection thoughts add a small joy bonus when resolved.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("thought_text", buildJsonObject {
                        put("type", "string")
                        put("description", "Full thought text or a keyword contained in the thought.")
                    })
                },
                required = listOf("thought_text"),
            )
        },
        execute = execute@ { element ->
            val params = runCatching { element.jsonObject }.getOrNull()
                ?: return@execute listOf(UIMessagePart.Text("""{"error": "INVALID_PARAMS", "message": "Parameters must be a JSON object"}"""))
            val thoughtText = params["thought_text"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: return@execute listOf(UIMessagePart.Text("""{"error": "MISSING_THOUGHT_TEXT", "message": "'thought_text' is required"}"""))

            val resolved = repository.resolveThought(assistantId, thoughtText)
            val payload = buildJsonObject {
                put("success", resolved != null)
                put("resolved_id", resolved?.id)
                put("resolved_content", resolved?.content)
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )

    val introspectTool = Tool(
        name = "introspect_desires",
        description = "Introspect the current desire drive state, thoughts, tick count, and baselines (alias of desire_status).",
        parameters = statusTool.parameters,
        execute = statusTool.execute,
    )

    val resolveAliasTool = Tool(
        name = "resolve_thought",
        description = "Resolve a thought from the thought pool (alias of desire_resolve_thought).",
        parameters = resolveTool.parameters,
        execute = resolveTool.execute,
    )

    return listOf(
        statusTool,
        eventTool,
        resolveTool,
        introspectTool,
        resolveAliasTool,
    )
}
