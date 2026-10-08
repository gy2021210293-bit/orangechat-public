/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.music

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
data class DailyDiscoverySong(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val coverUrl: String,
    val reason: String = "",
    val tag: String = "稳准",
    val playable: Boolean = true,
)

@Serializable
data class MusicInterruption(
    val lane: String,
    val activePlaylistId: Long? = null,
    val cursor: Int = -1,
    val song: NeteaseSong? = null,
)

@Entity(
    tableName = "music_playlists",
    primaryKeys = ["assistant_id", "playlist_id"],
    indices = [Index(value = ["assistant_id"])]
)
data class MusicPlaylistEntity(
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "source", defaultValue = "'netease'")
    val source: String = "netease",
    @ColumnInfo(name = "track_count")
    val trackCount: Int,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String,
    @ColumnInfo(name = "tracks_json")
    val tracksJson: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "music_session_states",
    indices = [Index(value = ["assistant_id"])]
)
data class MusicSessionStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "current_song_json")
    val currentSongJson: String? = null,
    @ColumnInfo(name = "queue_json", defaultValue = "'[]'")
    val queueJson: String = "[]",
    @ColumnInfo(name = "cursor", defaultValue = "-1")
    val cursor: Int = -1,
    @ColumnInfo(name = "playback_lane", defaultValue = "'direct'")
    val playbackLane: String = "direct",
    @ColumnInfo(name = "active_playlist_id")
    val activePlaylistId: Long? = null,
    @ColumnInfo(name = "playlist_cursor", defaultValue = "-1")
    val playlistCursor: Int = -1,
    @ColumnInfo(name = "interruption_json")
    val interruptionJson: String? = null,
    @ColumnInfo(name = "play_mode", defaultValue = "'sequence'")
    val playMode: String = "sequence",
    @ColumnInfo(name = "last_state", defaultValue = "'stopped'")
    val lastState: String = "stopped",
    @ColumnInfo(name = "last_actor", defaultValue = "'user'")
    val lastActor: String = "user",
    @ColumnInfo(name = "position_ms", defaultValue = "0")
    val positionMs: Long = 0L,
    @ColumnInfo(name = "duration_ms", defaultValue = "0")
    val durationMs: Long = 0L,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "music_events",
    indices = [Index(value = ["assistant_id", "created_at"])]
)
data class MusicEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "event_type")
    val eventType: String,
    @ColumnInfo(name = "actor")
    val actor: String,
    @ColumnInfo(name = "song_id")
    val songId: Long,
    @ColumnInfo(name = "song_name")
    val songName: String,
    @ColumnInfo(name = "song_artist")
    val songArtist: String,
    @ColumnInfo(name = "song_album")
    val songAlbum: String,
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long? = null,
    @ColumnInfo(name = "playlist_name")
    val playlistName: String? = null,
    @ColumnInfo(name = "note")
    val note: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "music_daily_discoveries",
    primaryKeys = ["assistant_id", "date"],
    indices = [Index(value = ["assistant_id"])]
)
data class MusicDailyDiscoveryEntity(
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "date")
    val date: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "subtitle")
    val subtitle: String,
    @ColumnInfo(name = "note")
    val note: String,
    @ColumnInfo(name = "llm_ranked", defaultValue = "0")
    val llmRanked: Boolean = false,
    @ColumnInfo(name = "songs_json")
    val songsJson: String,
    @ColumnInfo(name = "generated_at")
    val generatedAt: Long,
)

@Entity(
    tableName = "music_feedbacks",
    indices = [Index(value = ["assistant_id", "song_id"])]
)
data class MusicFeedbackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "song_id")
    val songId: Long,
    @ColumnInfo(name = "song_name")
    val songName: String,
    @ColumnInfo(name = "song_artist")
    val songArtist: String,
    @ColumnInfo(name = "action")
    val action: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "music_summaries",
    primaryKeys = ["assistant_id", "period"],
    indices = [Index(value = ["assistant_id"])]
)
data class MusicSummaryEntity(
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "period")
    val period: String,
    @ColumnInfo(name = "top_songs_json")
    val topSongsJson: String,
    @ColumnInfo(name = "top_artists_json")
    val topArtistsJson: String,
    @ColumnInfo(name = "recommendation_clues_json")
    val recommendationCluesJson: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
