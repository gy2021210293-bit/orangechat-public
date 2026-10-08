/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Sticker

private const val USER_STICKER_MARKER = "orangechat_user_sticker"
private const val USER_STICKER_NAME = "sticker_name"

fun Sticker.toUserStickerMessage(): UIMessagePart.Text = UIMessagePart.Text(
    text = "![${name.replace("]", "\\]")}]($url)",
    metadata = buildJsonObject {
        put(USER_STICKER_MARKER, true)
        put(USER_STICKER_NAME, name)
    },
)

private fun UIMessagePart.Text.userStickerNameOrNull(): String? {
    val data = metadata ?: return null
    if (data[USER_STICKER_MARKER]?.jsonPrimitive?.booleanOrNull != true) return null
    return data[USER_STICKER_NAME]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}

/**
 * Keeps sticker Markdown in local history while exposing only its chosen name to the model.
 */
fun List<UIMessage>.replaceUserStickersWithNames(): List<UIMessage> = map { message ->
    message.copy(parts = message.parts.map { part ->
        val name = (part as? UIMessagePart.Text)?.userStickerNameOrNull() ?: return@map part
        UIMessagePart.Text("用户发送了表情包：$name")
    })
}
