/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.music

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {

    // --- Playlists ---
    @Query("SELECT * FROM music_playlists WHERE assistant_id = :assistantId ORDER BY updated_at DESC")
    fun getPlaylistsFlow(assistantId: String): Flow<List<MusicPlaylistEntity>>

    @Query("SELECT * FROM music_playlists WHERE assistant_id = :assistantId ORDER BY updated_at DESC")
    suspend fun getPlaylists(assistantId: String): List<MusicPlaylistEntity>

    @Query("SELECT * FROM music_playlists WHERE assistant_id = :assistantId AND playlist_id = :playlistId LIMIT 1")
    suspend fun getPlaylist(assistantId: String, playlistId: Long): MusicPlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdatePlaylist(playlist: MusicPlaylistEntity)

    @Query("DELETE FROM music_playlists WHERE assistant_id = :assistantId AND playlist_id = :playlistId")
    suspend fun deletePlaylist(assistantId: String, playlistId: Long)

    // --- Session State ---
    @Query("SELECT * FROM music_session_states WHERE assistant_id = :assistantId LIMIT 1")
    fun observeSessionState(assistantId: String): Flow<MusicSessionStateEntity?>

    @Query("SELECT * FROM music_session_states WHERE assistant_id = :assistantId LIMIT 1")
    suspend fun getSessionState(assistantId: String): MusicSessionStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSessionState(sessionState: MusicSessionStateEntity)

    // --- Events ---
    @Insert
    suspend fun insertEvent(event: MusicEventEntity)

    @Query("SELECT * FROM music_events WHERE assistant_id = :assistantId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentEvents(assistantId: String, limit: Int = 100): List<MusicEventEntity>

    @Query("DELETE FROM music_events WHERE assistant_id = :assistantId AND id NOT IN (SELECT id FROM (SELECT id FROM music_events WHERE assistant_id = :assistantId ORDER BY created_at DESC LIMIT :keepCount))")
    suspend fun trimEvents(assistantId: String, keepCount: Int = 500)

    @Transaction
    suspend fun recordEvent(event: MusicEventEntity, keepCount: Int = 500) {
        insertEvent(event)
        trimEvents(event.assistantId, keepCount)
    }

    // --- Daily Discovery ---
    @Query("SELECT * FROM music_daily_discoveries WHERE assistant_id = :assistantId AND date = :date LIMIT 1")
    suspend fun getDailyDiscovery(assistantId: String, date: String): MusicDailyDiscoveryEntity?

    @Query("SELECT * FROM music_daily_discoveries WHERE assistant_id = :assistantId ORDER BY generated_at DESC LIMIT 1")
    suspend fun getLatestDailyDiscovery(assistantId: String): MusicDailyDiscoveryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDailyDiscovery(dailyDiscovery: MusicDailyDiscoveryEntity)

    // --- Feedbacks ---
    @Insert
    suspend fun insertFeedback(feedback: MusicFeedbackEntity)

    @Query("SELECT * FROM music_feedbacks WHERE assistant_id = :assistantId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getFeedbacks(assistantId: String, limit: Int = 500): List<MusicFeedbackEntity>

    @Query("SELECT action FROM music_feedbacks WHERE assistant_id = :assistantId AND song_id = :songId ORDER BY created_at DESC LIMIT 1")
    suspend fun getSongFeedback(assistantId: String, songId: Long): String?

    // --- Summaries ---
    @Query("SELECT * FROM music_summaries WHERE assistant_id = :assistantId ORDER BY updated_at DESC LIMIT :limit")
    suspend fun getRecentSummaries(assistantId: String, limit: Int = 5): List<MusicSummaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSummary(summary: MusicSummaryEntity)
}
