/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.music

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

import kotlinx.serialization.Serializable

@Serializable
data class NeteaseSong(
    val id: Long,
    val name: String,
    val artists: String,
    val album: String,
    val coverUrl: String,
)

@Serializable
data class NeteasePlaylist(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val owned: Boolean = true,
)

data class LyricLine(
    val timestampMs: Long,
    val text: String,
    val translation: String? = null,
)

data class SongLyrics(
    val songId: Long,
    val lines: List<LyricLine>,
    val rawLyric: String = "",
    val rawTranslation: String = "",
)

data class NeteasePlaylistDetail(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val trackCount: Int,
    val creatorName: String,
    val description: String,
    val tracks: List<NeteaseSong>,
)

object LrcParser {
    private val TIME_REGEX = Regex("""\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?]""")

    fun parse(lrc: String, translationLrc: String? = null): List<LyricLine> {
        if (lrc.isBlank()) return emptyList()
        val transMap = mutableMapOf<Long, String>()
        if (!translationLrc.isNullOrBlank()) {
            parseRaw(translationLrc).forEach { (time, text) ->
                transMap[time] = text
            }
        }
        val mainLines = parseRaw(lrc)
        return mainLines.map { (time, text) ->
            LyricLine(
                timestampMs = time,
                text = text,
                translation = transMap[time],
            )
        }.sortedBy { it.timestampMs }
    }

    private fun parseRaw(content: String): List<Pair<Long, String>> {
        val list = mutableListOf<Pair<Long, String>>()
        content.lineSequence().forEach { line ->
            val matchResults = TIME_REGEX.findAll(line).toList()
            if (matchResults.isNotEmpty()) {
                val text = line.substring(matchResults.last().range.last + 1).trim()
                for (match in matchResults) {
                    val min = match.groupValues[1].toLongOrNull() ?: 0L
                    val sec = match.groupValues[2].toLongOrNull() ?: 0L
                    val msStr = match.groupValues.getOrNull(3).orEmpty()
                    val ms = when (msStr.length) {
                        2 -> (msStr.toLongOrNull() ?: 0L) * 10
                        3 -> msStr.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    val timestamp = min * 60_000L + sec * 1_000L + ms
                    if (text.isNotBlank()) {
                        list.add(timestamp to text)
                    }
                }
            }
        }
        return list
    }
}

open class NeteaseMusicClient(
    context: Context? = null,
    credentialStore: MusicCredentialStore? = null,
) {
    private val credentials = credentialStore ?: context?.applicationContext?.let { MusicCredentialStore(it) }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun validateCookie(cookie: String? = null): String = withContext(Dispatchers.IO) {
        val response = postWeapi("/weapi/w/nuser/account/get", emptyMap(), cookie ?: requireCookie())
        val profile = response.optJSONObject("profile") ?: response.optJSONObject("data")?.optJSONObject("profile")
            ?: throw NeteaseMusicException("登录状态无效或 Cookie 已过期")
        profile.optString("nickname").ifBlank { "网易云音乐用户" }
    }

    open suspend fun searchSongs(keyword: String, limit: Int = 10): List<NeteaseSong> = withContext(Dispatchers.IO) {
        require(keyword.isNotBlank()) { "搜索内容不能为空" }
        val response = postWeapi(
            "/weapi/cloudsearch/get/web",
            mapOf("s" to keyword, "type" to "1", "offset" to "0", "limit" to limit.coerceIn(1, 30).toString(), "total" to "true")
        )
        val songs = response.optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
        (0 until songs.length()).mapNotNull { index -> songs.optJSONObject(index)?.toSong() }
    }

    suspend fun getMyPlaylists(limit: Int = Int.MAX_VALUE): List<NeteasePlaylist> = withContext(Dispatchers.IO) {
        val status = postWeapi("/weapi/w/nuser/account/get", emptyMap())
        val profile = status.optJSONObject("profile") ?: status.optJSONObject("data")?.optJSONObject("profile")
        val userId = profile?.optLong("userId")
            ?.takeIf { it > 0L } ?: throw NeteaseMusicException("登录状态无效或 Cookie 已过期")
        val allPlaylists = linkedMapOf<Long, NeteasePlaylist>()
        var offset = 0
        while (allPlaylists.size < limit) {
            val pageSize = minOf(200, limit - allPlaylists.size)
            val response = postWeapi(
                "/weapi/user/playlist",
                mapOf(
                    "uid" to userId.toString(),
                    "limit" to pageSize.toString(),
                    "offset" to offset.toString(),
                    "includeVideo" to "true",
                )
            )
            val playlists = response.optJSONArray("playlist") ?: JSONArray()
            val previousCount = allPlaylists.size
            for (index in 0 until playlists.length()) {
                val item = playlists.optJSONObject(index) ?: continue
                val id = item.optLong("id")
                allPlaylists[id] = NeteasePlaylist(
                    id = id,
                    name = item.optString("name"),
                    trackCount = item.optInt("trackCount"),
                    owned = item.optLong("userId", item.optJSONObject("creator")?.optLong("userId") ?: 0L) == userId,
                )
            }
            offset += playlists.length()
            if (playlists.length() == 0 || allPlaylists.size == previousCount) break
            if (response.has("more") && !response.optBoolean("more")) break
            if (!response.has("more") && playlists.length() < pageSize) break
        }
        allPlaylists.values.take(limit)
    }

    suspend fun getPlaylistSongs(playlistId: Long): List<NeteaseSong> = withContext(Dispatchers.IO) {
        require(playlistId > 0L) { "歌单 ID 无效" }
        val response = postWeapi(
            "/weapi/v3/playlist/detail",
            mapOf("id" to playlistId.toString(), "n" to "1000", "limit" to "1000", "offset" to "0", "total" to "true")
        )
        val playlist = response.optJSONObject("playlist") ?: throw NeteaseMusicException("无法读取歌单")
        playlist.loadAllSongs()
    }

    suspend fun getSong(songId: Long): NeteaseSong = withContext(Dispatchers.IO) {
        require(songId > 0L) { "歌曲 ID 无效" }
        val ids = "[$songId]"
        val response = postWeapi(
            "/weapi/v3/song/detail",
            mapOf("c" to "[{\"id\":\"$songId\"}]", "ids" to ids)
        )
        response.optJSONArray("songs")?.optJSONObject(0)?.toSong()
            ?: throw NeteaseMusicException("找不到这首歌")
    }

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) = withContext(Dispatchers.IO) {
        require(playlistId > 0L && songId > 0L) { "歌单或歌曲 ID 无效" }
        val response = postWeapi(
            "/weapi/playlist/manipulate/tracks",
            mapOf("op" to "add", "pid" to playlistId.toString(), "trackIds" to "[$songId]", "imme" to "true")
        )
        if (response.optInt("code") != 200) {
            throw NeteaseMusicException(response.optString("message").ifBlank { "添加歌曲失败" })
        }
    }

    open suspend fun getPlayableUrl(songId: Long, preferredQuality: String = "standard"): String = withContext(Dispatchers.IO) {
        require(songId > 0L) { "歌曲 ID 无效" }
        val levels = when (preferredQuality.lowercase()) {
            "high", "lossless", "exhigh" -> listOf("lossless", "exhigh", "higher", "standard")
            "higher" -> listOf("higher", "exhigh", "standard")
            else -> listOf("standard", "higher", "exhigh")
        }

        for (level in levels) {
            val candidateUrl = runCatching {
                val values = mapOf("ids" to "[$songId]", "level" to level, "encodeType" to "flac")
                val response = runCatching { postPlayerEapi(values) }.getOrNull()
                    ?.takeIf { !it.optJSONArray("data")?.optJSONObject(0)?.optString("url").isNullOrBlank() }
                    ?: postWeapi("/weapi/song/enhance/player/url/v1", values + ("encodeType" to "mp3"))
                val url = response.optJSONArray("data")?.optJSONObject(0)?.optString("url")
                if (!url.isNullOrBlank()) {
                    val parsed = java.net.URI(url)
                    if ((parsed.scheme == "https" || parsed.scheme == "http") && !parsed.host.isNullOrBlank()) {
                        url
                    } else null
                } else null
            }.getOrNull()

            if (!candidateUrl.isNullOrBlank()) {
                return@withContext candidateUrl
            }
        }
        throw NeteaseMusicException("这首歌当前没有可用音源（可能受版权、会员或地区限制）")
    }

    open suspend fun isSongPlayable(songId: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            getPlayableUrl(songId, "standard")
        }.isSuccess
    }

    open suspend fun getLyrics(songId: Long): SongLyrics = withContext(Dispatchers.IO) {
        require(songId > 0L) { "歌曲 ID 无效" }
        val response = postWeapi(
            "/weapi/song/lyric?lv=-1&kv=-1&tv=-1",
            mapOf("id" to songId.toString(), "lv" to "-1", "kv" to "-1", "tv" to "-1")
        )
        val lrc = response.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val tlyric = response.optJSONObject("tlyric")?.optString("lyric").orEmpty()
        val parsedLines = LrcParser.parse(lrc, tlyric)
        SongLyrics(
            songId = songId,
            lines = parsedLines,
            rawLyric = lrc,
            rawTranslation = tlyric,
        )
    }

    fun resolvePlaylistId(input: String): Long? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toLongOrNull()?.let { if (it > 0) return it }
        val regexes = listOf(
            Regex("""playlist\?id=(\d+)"""),
            Regex("""playlist/(\d+)"""),
            Regex("""id=(\d+)""")
        )
        for (regex in regexes) {
            val match = regex.find(trimmed)
            if (match != null) {
                val id = match.groupValues[1].toLongOrNull()
                if (id != null && id > 0) return id
            }
        }
        return null
    }

    suspend fun getPlaylistDetail(
        playlistId: Long,
        limit: Int = Int.MAX_VALUE,
        offset: Int = 0,
    ): NeteasePlaylistDetail = withContext(Dispatchers.IO) {
        require(playlistId > 0L) { "歌单 ID 无效" }
        val response = postWeapi(
            "/weapi/v3/playlist/detail",
            mapOf("id" to playlistId.toString(), "n" to "1000", "limit" to "1000", "offset" to "0", "total" to "true")
        )
        val playlist = response.optJSONObject("playlist") ?: throw NeteaseMusicException("无法读取歌单")
        val songList = playlist.loadAllSongs()

        val pagedTracks = if (offset > 0 || limit < songList.size) {
            songList.drop(offset).take(limit)
        } else {
            songList
        }

        val cover = playlist.optString("coverImgUrl").ifBlank { playlist.optString("picUrl") }
        val creator = playlist.optJSONObject("creator")?.optString("nickname").orEmpty()
        val desc = playlist.optString("description").orEmpty()

        NeteasePlaylistDetail(
            id = playlist.optLong("id"),
            name = playlist.optString("name"),
            coverUrl = cover,
            trackCount = playlist.optInt("trackCount", songList.size),
            creatorName = creator,
            description = desc,
            tracks = pagedTracks,
        )
    }

    private fun JSONObject.loadAllSongs(): List<NeteaseSong> {
        val tracks = optJSONArray("tracks") ?: JSONArray()
        val songsById = (0 until tracks.length())
            .mapNotNull { index -> tracks.optJSONObject(index)?.toSong() }
            .associateBy { it.id }
            .toMutableMap()
        val trackIds = optJSONArray("trackIds")?.let { ids ->
            (0 until ids.length()).mapNotNull { index ->
                ids.optJSONObject(index)?.optLong("id")?.takeIf { it > 0L }
            }
        }.orEmpty()
        val missingIds = trackIds.distinct().filterNot(songsById::containsKey)

        missingIds.chunked(100).forEach { batch ->
            val songIds = JSONArray(batch)
            val songObjects = JSONArray().apply {
                batch.forEach { id -> put(JSONObject().put("id", id.toString())) }
            }
            val response = postWeapi(
                "/weapi/v3/song/detail",
                mapOf("c" to songObjects.toString(), "ids" to songIds.toString()),
            )
            val songs = response.optJSONArray("songs") ?: JSONArray()
            for (index in 0 until songs.length()) {
                songs.optJSONObject(index)?.toSong()?.let { songsById[it.id] = it }
            }
        }

        return if (trackIds.isEmpty()) {
            songsById.values.toList()
        } else {
            trackIds.mapNotNull(songsById::get)
        }
    }

    private fun requireCookie(): String = credentials?.readCookie()
        ?: throw NeteaseMusicException("请先在音乐设置中填写并验证网易云 Cookie")

    private fun postWeapi(
        path: String,
        values: Map<String, String>,
        cookie: String = requireCookie(),
    ): JSONObject {
        val csrf = Regex("(?:^|;\\s*)__csrf=([^;]+)").find(cookie)?.groupValues?.get(1).orEmpty()
        val payload = JSONObject().apply {
            values.forEach { (key, value) -> put(key, value) }
            put("csrf_token", csrf)
        }
        val encrypted = encryptWeapi(payload.toString())
        val form = FormBody.Builder()
            .add("params", encrypted.first)
            .add("encSecKey", encrypted.second)
            .build()
        val request = Request.Builder()
            .url("https://music.163.com$path?csrf_token=${URLEncoder.encode(csrf, "UTF-8")}")
            .header("Cookie", cookie)
            .header("Referer", "https://music.163.com/")
            .header("User-Agent", USER_AGENT)
            .post(form)
            .build()
        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) throw NeteaseMusicException("网易云服务请求失败（HTTP ${it.code}）")
            val body = it.body.string()
            val json = runCatching { JSONObject(body) }
                .getOrElse { throw NeteaseMusicException("网易云服务返回了无法识别的数据") }
            if (!json.has("code") || json.optInt("code", -1) != 200) {
                val code = json.optInt("code")
                if (code == 301 || code == 302) throw NeteaseMusicException("登录状态无效或 Cookie 已过期")
                throw NeteaseMusicException(json.optString("message").ifBlank { "网易云服务请求失败（$code）" })
            }
            return json
        }
    }

    private fun postPlayerEapi(values: Map<String, String>): JSONObject {
        val cookie = requireCookie()
        val musicU = Regex("(?:^|;\\s*)MUSIC_U=([^;]+)").find(cookie)?.groupValues?.get(1)
            ?: throw NeteaseMusicException("Cookie 缺少 MUSIC_U")
        val path = "/api/song/enhance/player/url/v1"
        val header = JSONObject().apply {
            put("os", "pc")
            put("appver", "8.0.0")
            put("MUSIC_U", musicU)
        }
        val payload = JSONObject().apply {
            values.forEach { (key, value) -> put(key, value) }
            put("header", header)
        }.toString()
        val digest = MessageDigest.getInstance("MD5")
            .digest("nobody${path}use${payload}md5forencrypt".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val source = "$path-36cd479b6b5-$payload-36cd479b6b5-$digest"
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(StandardCharsets.UTF_8), "AES"))
        val params = cipher.doFinal(source.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
        val request = Request.Builder()
            .url("https://interface3.music.163.com/eapi/song/enhance/player/url/v1")
            .header("Cookie", "os=pc; appver=8.0.0; MUSIC_U=$musicU")
            .header("Referer", "https://music.163.com/")
            .header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().add("params", params).build())
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw NeteaseMusicException("网易云音源请求失败（HTTP ${response.code}）")
            return JSONObject(response.body.string())
        }
    }

    private fun JSONObject.toSong(): NeteaseSong {
        val artistArray = optJSONArray("ar") ?: optJSONArray("artists") ?: JSONArray()
        val artists = (0 until artistArray.length()).mapNotNull { artistArray.optJSONObject(it)?.optString("name") }
            .filter { it.isNotBlank() }
            .joinToString(" / ")
        val albumObject = optJSONObject("al") ?: optJSONObject("album") ?: JSONObject()
        return NeteaseSong(
            id = optLong("id"),
            name = optString("name"),
            artists = artists,
            album = albumObject.optString("name"),
            coverUrl = albumObject.optString("picUrl"),
        )
    }

    private fun encryptWeapi(text: String): Pair<String, String> {
        val random = SecureRandom()
        val secret = (0 until 16).map { SECRET_CHARS[random.nextInt(SECRET_CHARS.length)] }.joinToString("")
        val first = Base64.encodeToString(
            aesCbc(text.toByteArray(StandardCharsets.UTF_8), PRESET_KEY),
            Base64.NO_WRAP,
        )
        val second = aesCbc(first.toByteArray(StandardCharsets.UTF_8), secret)
        val params = Base64.encodeToString(second, Base64.NO_WRAP)

        val reversedKey = secret.reversed().toByteArray(StandardCharsets.UTF_8)
        val hex = reversedKey.joinToString("") { "%02x".format(it) }
        val encryptedKey = BigInteger(hex, 16)
            .modPow(BigInteger(RSA_EXPONENT, 16), BigInteger(RSA_MODULUS, 16))
            .toString(16)
            .padStart(256, '0')
        return params to encryptedKey
    }

    private fun aesCbc(input: ByteArray, key: String): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(StandardCharsets.UTF_8))
        )
        return cipher.doFinal(input)
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val SECRET_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
        private const val IV = "0102030405060708"
        private const val RSA_EXPONENT = "010001"
        private const val RSA_MODULUS = "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    }
}

class NeteaseMusicException(message: String) : Exception(message)
