/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.StickerStorageSetting
import me.rerere.rikkahub.data.model.Sticker
import me.rerere.rikkahub.data.model.StickerCatalogCache
import java.net.HttpURLConnection
import java.net.URL

class StickerRepository(
    private val settingsStore: SettingsStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun cached(setting: StickerStorageSetting): List<Sticker>? = settingsStore.getStickerCatalogCache()
        ?.takeIf { it.supabaseUrl == setting.supabaseUrl.trimEnd('/') }
        ?.stickers

    suspend fun refresh(setting: StickerStorageSetting): Result<List<Sticker>> = withContext(Dispatchers.IO) {
        runCatching {
            require(setting.isConfigured()) { "请先配置表情包图床" }
            val endpoint = URL(
                "${setting.supabaseUrl.trimEnd('/')}/rest/v1/stickers?select=name,url"
            )
            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("apikey", setting.supabaseAnonKey)
                setRequestProperty("Authorization", "Bearer ${setting.supabaseAnonKey}")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            val responseCode = connection.responseCode
            val body = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            connection.disconnect()
            require(responseCode in 200..299) { "表情包列表加载失败 ($responseCode): $body" }
            json.decodeFromString<List<Sticker>>(body)
                .filter { it.name.isNotBlank() && it.url.startsWith("https://") }
                .also { stickers ->
                    settingsStore.updateStickerCatalogCache(
                        StickerCatalogCache(setting.supabaseUrl.trimEnd('/'), stickers)
                    )
                }
        }
    }
}
