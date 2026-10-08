/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migration_41_42 : Migration(41, 42) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. music_playlists
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `music_playlists` (
                `assistant_id` TEXT NOT NULL,
                `playlist_id` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `source` TEXT NOT NULL DEFAULT 'netease',
                `track_count` INTEGER NOT NULL,
                `cover_url` TEXT NOT NULL,
                `tracks_json` TEXT NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`assistant_id`, `playlist_id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_music_playlists_assistant_id` ON `music_playlists` (`assistant_id`)"
        )

        // 2. music_session_states
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `music_session_states` (
                `assistant_id` TEXT NOT NULL,
                `current_song_json` TEXT,
                `queue_json` TEXT NOT NULL DEFAULT '[]',
                `cursor` INTEGER NOT NULL DEFAULT -1,
                `playback_lane` TEXT NOT NULL DEFAULT 'direct',
                `active_playlist_id` INTEGER,
                `playlist_cursor` INTEGER NOT NULL DEFAULT -1,
                `interruption_json` TEXT,
                `play_mode` TEXT NOT NULL DEFAULT 'sequence',
                `last_state` TEXT NOT NULL DEFAULT 'stopped',
                `last_actor` TEXT NOT NULL DEFAULT 'user',
                `position_ms` INTEGER NOT NULL DEFAULT 0,
                `duration_ms` INTEGER NOT NULL DEFAULT 0,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`assistant_id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_music_session_states_assistant_id` ON `music_session_states` (`assistant_id`)"
        )

        // 3. music_events
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `music_events` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `event_type` TEXT NOT NULL,
                `actor` TEXT NOT NULL,
                `song_id` INTEGER NOT NULL,
                `song_name` TEXT NOT NULL,
                `song_artist` TEXT NOT NULL,
                `song_album` TEXT NOT NULL,
                `playlist_id` INTEGER,
                `playlist_name` TEXT,
                `note` TEXT,
                `created_at` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_music_events_assistant_id_created_at` ON `music_events` (`assistant_id`, `created_at`)"
        )

        // 4. music_daily_discoveries
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `music_daily_discoveries` (
                `assistant_id` TEXT NOT NULL,
                `date` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `subtitle` TEXT NOT NULL,
                `note` TEXT NOT NULL,
                `llm_ranked` INTEGER NOT NULL DEFAULT 0,
                `songs_json` TEXT NOT NULL,
                `generated_at` INTEGER NOT NULL,
                PRIMARY KEY(`assistant_id`, `date`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_music_daily_discoveries_assistant_id` ON `music_daily_discoveries` (`assistant_id`)"
        )

        // 5. music_feedbacks
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `music_feedbacks` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `assistant_id` TEXT NOT NULL,
                `song_id` INTEGER NOT NULL,
                `song_name` TEXT NOT NULL,
                `song_artist` TEXT NOT NULL,
                `action` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_music_feedbacks_assistant_id_song_id` ON `music_feedbacks` (`assistant_id`, `song_id`)"
        )

        // 6. music_summaries
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `music_summaries` (
                `assistant_id` TEXT NOT NULL,
                `period` TEXT NOT NULL,
                `top_songs_json` TEXT NOT NULL,
                `top_artists_json` TEXT NOT NULL,
                `recommendation_clues_json` TEXT NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`assistant_id`, `period`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_music_summaries_assistant_id` ON `music_summaries` (`assistant_id`)"
        )
    }
}
