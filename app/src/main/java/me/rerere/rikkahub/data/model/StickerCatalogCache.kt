/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/** Last successfully loaded catalog for one public sticker host. */
@Serializable
data class StickerCatalogCache(
    val supabaseUrl: String,
    val stickers: List<Sticker>,
)
