/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant

const val PAT_USER_TOOL_NAME = "pat_user"

private const val PAT_EVENT_METADATA_KEY = "orangechat_event"
private const val PAT_EVENT_METADATA_VALUE = "pat"
private const val PAT_DISPLAY_TEXT_KEY = "display_text"
private const val DEFAULT_PAT_ACTION = "拍了拍"

private fun normalizePatText(value: String): String =
    value.trim().replace(Regex("[\\r\\n]+"), " ")

private fun normalizePatAction(action: String): String =
    normalizePatText(action).ifEmpty { DEFAULT_PAT_ACTION }

fun formatUserPatAssistantDisplayText(
    assistantName: String,
    assistantPatSuffix: String,
    assistantPatAction: String = DEFAULT_PAT_ACTION,
): String {
    val name = assistantName.trim().ifEmpty { "AI" }
    return "你${normalizePatAction(assistantPatAction)}「$name」${normalizePatText(assistantPatSuffix)}"
}

fun formatAiPatUserDisplayText(
    assistantName: String,
    userPatSuffix: String,
    userPatAction: String = DEFAULT_PAT_ACTION,
): String {
    val name = assistantName.trim().ifEmpty { "AI" }
    return "${name}${normalizePatAction(userPatAction)}你${normalizePatText(userPatSuffix)}"
}

fun createUserPatAssistantPart(
    assistantName: String,
    assistantPatAction: String = DEFAULT_PAT_ACTION,
    assistantPatSuffix: String,
): UIMessagePart.Text {
    val action = normalizePatAction(assistantPatAction)
    val suffix = normalizePatText(assistantPatSuffix)
    val displayText = formatUserPatAssistantDisplayText(
        assistantName = assistantName,
        assistantPatAction = action,
        assistantPatSuffix = suffix,
    )
    val modelText = buildString {
        append("[头像互动事件] 用户")
        append(action)
        append("你")
        append(suffix)
        append("。请把它当作聊天中的真实互动，自然决定如何回应。")
    }
    return UIMessagePart.Text(
        text = modelText,
        metadata = buildJsonObject {
            put(PAT_EVENT_METADATA_KEY, PAT_EVENT_METADATA_VALUE)
            put(PAT_DISPLAY_TEXT_KEY, displayText)
        },
    )
}

fun UIMessagePart.Text.patDisplayTextOrNull(): String? {
    val metadata = metadata ?: return null
    if (metadata[PAT_EVENT_METADATA_KEY]?.jsonPrimitive?.contentOrNull != PAT_EVENT_METADATA_VALUE) {
        return null
    }
    return metadata[PAT_DISPLAY_TEXT_KEY]?.jsonPrimitive?.contentOrNull
}

fun UIMessagePart.Tool.patDisplayTextOrNull(): String? {
    if (toolName != PAT_USER_TOOL_NAME || !isExecuted) return null
    return output
        .filterIsInstance<UIMessagePart.Text>()
        .firstNotNullOfOrNull { outputPart ->
            runCatching {
                JsonInstant.parseToJsonElement(outputPart.text)
                    .let { it as? JsonObject }
                    ?.get(PAT_DISPLAY_TEXT_KEY)
                    ?.jsonPrimitive
                    ?.contentOrNull
            }.getOrNull()
        }
}

fun createPatUserTool(invocationContext: ToolInvocationContext): Tool = Tool(
    name = PAT_USER_TOOL_NAME,
    description = """
        Perform a lightweight avatar interaction with the user.
        Use this when a playful or comforting interaction fits the conversation.
        You may provide an action and content for this interaction; omit either field to use the user's configured default.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("action") {
                    put("type", "string")
                    put("description", "Displayed action, for example 捏一捏, 戳了戳, or 摸摸.")
                }
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "Displayed content after the user, for example 的小脑袋 or 说今天也辛苦啦.")
                }
            }
        )
    },
    execute = { args ->
        val parameters = args.jsonObject
        val action = parameters["action"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: invocationContext.userPatAction
        val content = parameters["content"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?: invocationContext.userPatSuffix
        val displayText = formatAiPatUserDisplayText(
            assistantName = invocationContext.callerAssistantName.orEmpty(),
            userPatAction = action,
            userPatSuffix = content,
        )
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put(PAT_EVENT_METADATA_KEY, PAT_EVENT_METADATA_VALUE)
                    put(PAT_DISPLAY_TEXT_KEY, displayText)
                }.toString()
            )
        )
    },
)
