/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.music

import android.content.Context
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val LEASE_MS = 2 * 60 * 60 * 1000L // 2 小时活跃共听租约

fun createNeteaseMusicTool(context: Context): Tool {
    val client = NeteaseMusicClient(context.applicationContext)
    return Tool(
        name = "netease_music",
        description = "Search NetEase songs, prepare a play card, list playlists and songs, or read shared listening state. " +
            "Lists use offset and limit. Never claim playback started until the user taps the card.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("action") {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("search"))
                            add(JsonPrimitive("prepare_play"))
                            add(JsonPrimitive("search_and_play"))
                            add(JsonPrimitive("list_playlists"))
                            add(JsonPrimitive("playlist_songs"))
                            add(JsonPrimitive("get_current"))
                        })
                    }
                    putJsonObject("query") { put("type", "string") }
                    putJsonObject("playlist_id") { put("type", "integer") }
                    putJsonObject("song_id") { put("type", "integer") }
                    putJsonObject("offset") { put("type", "integer") }
                    putJsonObject("limit") { put("type", "integer") }
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val values = args.jsonObject
            val action = values["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
            try {
                val result = when (action) {
                    "search" -> {
                        val query = values["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (query.isBlank()) {
                            buildJsonObject {
                                put("ok", false)
                                put("status", "error")
                                putJsonObject("error") {
                                    put("code", "EMPTY_QUERY")
                                    put("message", "搜索关键词不能为空")
                                }
                            }
                        } else {
                            val songs = client.searchSongs(query, limit = 10)
                            buildJsonObject {
                                put("ok", true)
                                put("status", if (songs.isEmpty()) "no_results" else "candidates")
                                put("songs", buildJsonArray {
                                    songs.forEach { song -> add(song.toToolJson()) }
                                })
                            }
                        }
                    }
                    "prepare_play", "search_and_play" -> {
                        val explicitId = values["song_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        val query = values["query"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                        if (explicitId != null && explicitId > 0L) {
                            val song = client.getSong(explicitId)
                            val playable = client.isSongPlayable(explicitId)
                            if (playable) {
                                buildJsonObject {
                                    put("ok", true)
                                    put("status", "ready")
                                    put("song", song.toToolJson(playable = true))
                                }
                            } else {
                                buildJsonObject {
                                    put("ok", false)
                                    put("status", "error")
                                    putJsonObject("error") {
                                        put("code", "MUSIC_SOURCE_UNAVAILABLE")
                                        put("message", "《${song.name}》当前没有可用音源（可能受版权或会员限制）")
                                    }
                                }
                            }
                        } else if (query.isNotBlank()) {
                            val searchResults = client.searchSongs(query, limit = 6)
                            if (searchResults.isEmpty()) {
                                buildJsonObject {
                                    put("ok", false)
                                    put("status", "error")
                                    putJsonObject("error") {
                                        put("code", "NO_RESULTS")
                                        put("message", "没有找到与「$query」相关的歌曲，请尝试补充歌手名或更换关键词")
                                    }
                                }
                            } else if (searchResults.size > 1 && hasAmbiguity(query, searchResults)) {
                                buildJsonObject {
                                    put("ok", false)
                                    put("status", "needs_choice")
                                    put("message", "为你找到了多个版本，请选择：")
                                    put("candidates", buildJsonArray {
                                        searchResults.forEach { song -> add(song.toToolJson()) }
                                    })
                                }
                            } else {
                                val first = searchResults.first()
                                if (client.isSongPlayable(first.id)) {
                                    buildJsonObject {
                                        put("ok", true)
                                        put("status", "ready")
                                        put("song", first.toToolJson(playable = true))
                                    }
                                } else {
                                    val alternative = searchResults.drop(1).firstOrNull { client.isSongPlayable(it.id) }
                                    if (alternative != null) {
                                        buildJsonObject {
                                            put("ok", true)
                                            put("status", "ready")
                                            put("song", alternative.toToolJson(playable = true))
                                        }
                                    } else {
                                        buildJsonObject {
                                            put("ok", false)
                                            put("status", "error")
                                            putJsonObject("error") {
                                                put("code", "MUSIC_SOURCE_UNAVAILABLE")
                                                put("message", "找到了《${first.name}》，但受版权或会员限制暂无可用音源")
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            throw NeteaseMusicException("点歌操作必须提供 song_id 或 query 关键词")
                        }
                    }
                    "list_playlists" -> {
                        val playlists = client.getMyPlaylists()
                        val offset = values["offset"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                            ?.coerceAtLeast(0) ?: 0
                        val limit = values["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                            ?.coerceIn(1, 100) ?: 100
                        val page = playlists.drop(offset).take(limit)
                        buildJsonObject {
                            put("ok", true)
                            put("total", playlists.size)
                            put("offset", offset)
                            put("has_more", offset.toLong() + page.size < playlists.size)
                            put("playlists", buildJsonArray {
                                page.forEach { playlist ->
                                    add(buildJsonObject {
                                        put("id", playlist.id)
                                        put("name", playlist.name)
                                        put("track_count", playlist.trackCount)
                                    })
                                }
                            })
                        }
                    }
                    "playlist_songs" -> {
                        val id = values["playlist_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                            ?: throw NeteaseMusicException("缺少有效的 playlist_id")
                        val songs = client.getPlaylistSongs(id)
                        val offset = values["offset"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                            ?.coerceAtLeast(0) ?: 0
                        val limit = values["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                            ?.coerceIn(1, 100) ?: 100
                        val page = songs.drop(offset).take(limit)
                        buildJsonObject {
                            put("ok", true)
                            put("total", songs.size)
                            put("offset", offset)
                            put("has_more", offset.toLong() + page.size < songs.size)
                            put("songs", buildJsonArray { page.forEach { add(it.toToolJson()) } })
                        }
                    }
                    "get_current" -> {
                        val state = me.rerere.rikkahub.plugin.webview.MusicPlayerService.getNowPlaying()
                        val isShared = state["client"] == me.rerere.rikkahub.plugin.webview.MusicPlayerService.CLIENT_SHARED_LISTENING
                        buildJsonObject {
                            put("ok", true)
                            put("state", if (isShared) state["state"].orEmpty() else "stopped")
                            put("title", if (isShared) state["title"].orEmpty() else "")
                            put("artist", if (isShared) state["artist"].orEmpty() else "")
                        }
                    }
                    else -> throw NeteaseMusicException("不支持的音乐操作: $action")
                }
                listOf(UIMessagePart.Text(result.toString()))
            } catch (error: Exception) {
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("ok", false)
                    put("status", "error")
                    putJsonObject("error") {
                        put("code", "EXECUTION_FAILED")
                        put("message", error.message ?: "音乐服务调用失败")
                    }
                }.toString()))
            }
        },
    )
}

fun createNeteaseAddSongTool(context: Context): Tool {
    val client = NeteaseMusicClient(context.applicationContext)
    return Tool(
        name = "netease_add_song_to_playlist",
        description = "Add a song to one of the user's own NetEase Cloud Music playlists. Use only after the user explicitly asks to save or add the song.",
        needsApproval = true,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("playlist_id") { put("type", "integer") }
                    putJsonObject("song_id") { put("type", "integer") }
                },
                required = listOf("playlist_id", "song_id"),
            )
        },
        execute = { args ->
            val values = args.jsonObject
            val playlistId = values["playlist_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val songId = values["song_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            try {
                if (playlistId == null || songId == null) throw NeteaseMusicException("歌单或歌曲 ID 无效")
                client.addSongToPlaylist(playlistId, songId)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("ok", true)
                    put("message", "歌曲已成功添加到你的网易云歌单")
                }.toString()))
            } catch (error: Exception) {
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("ok", false)
                    put("error", error.message ?: "添加歌曲失败")
                }.toString()))
            }
        },
    )
}

private fun hasAmbiguity(query: String, songs: List<NeteaseSong>): Boolean {
    if (songs.size <= 1) return false
    val first = songs[0]
    val second = songs[1]
    // 同名不同歌手，或者歌手不同
    val sameName = first.name.trim().equals(second.name.trim(), ignoreCase = true)
    val differentArtist = !first.artists.trim().equals(second.artists.trim(), ignoreCase = true)
    if (sameName && differentArtist) return true

    // 如果用户只搜了歌名，没有指定歌手
    val queryContainsArtist = songs.take(3).any { song ->
        song.artists.split(" / ", "/", ",").any { artist ->
            artist.isNotBlank() && query.contains(artist, ignoreCase = true)
        }
    }
    return !queryContainsArtist
}

private fun NeteaseSong.toToolJson(playable: Boolean = true) = buildJsonObject {
    put("provider", "netease")
    put("id", id)
    put("name", name)
    put("artists", artists)
    put("album", album)
    put("cover", coverUrl)
    put("cover_url", coverUrl)
    put("playable", playable)
}

object SharedListeningContextHelper {
    suspend fun buildPromptContext(
        assistantId: String,
        musicDao: MusicDao,
        playbackState: MusicPlaybackState? = null,
    ): String? {
        val currentPlayback = playbackState?.takeIf {
            assistantId.isNotBlank() && it.assistantId == assistantId &&
                it.playState == PlayState.PLAYING && it.currentSong != null
        } ?: return "当前没有音乐正在播放。"
        val currentSong = currentPlayback.currentSong ?: return "当前没有音乐正在播放。"

        val lyricLine = currentPlayback.let { state ->
            val lines = state.lyrics?.takeIf { it.songId == state.currentSong?.id }?.lines.orEmpty()
            val currentIndex = lines.indexOfLast { it.timestampMs <= state.currentPositionMs }
            if (currentIndex < 0) null else {
                lines.subList(currentIndex, (currentIndex + 2).coerceAtMost(lines.size))
                    .joinToString(" / ") { it.text }
                    .takeIf { it.isNotBlank() }
            }
        }

        val relatedEvents = musicDao.getRecentEvents(assistantId, limit = 10)
            .filter { it.songId == currentSong.id }
            .take(3)
        val eventLines = relatedEvents.joinToString(separator = "\n") { event ->
            val actorName = if (event.actor == "companion") "陪伴者" else "用户"
            val actionName = when (event.eventType) {
                "play" -> "开始播放"
                "pause" -> "暂停"
                "resume" -> "继续播放"
                "finish" -> "听完"
                "close" -> "退出共听"
                else -> "播放"
            }
            "- $actorName 曾$actionName《${event.songName}》— ${event.songArtist}"
        }

        return buildString {
            appendLine("[当前共听状态]")
            appendLine("当前正在共听并播放：《${currentSong.name}》— ${currentSong.artists}")
            lyricLine?.let { appendLine("当前及下一句歌词：$it") }
            if (eventLines.isNotBlank()) {
                appendLine("这首歌的相关历史事件：")
                appendLine(eventLines)
            }
        }.trimEnd()
    }
}
