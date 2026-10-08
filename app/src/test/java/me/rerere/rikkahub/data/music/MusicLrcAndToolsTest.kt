/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.music

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLrcAndToolsTest {

    @Test
    fun testLrcParser_basicAndTranslation() {
        val lrc = """
            [00:01.50]Hello world
            [00:05.00]Second line
            [00:10.250]Third line
        """.trimIndent()

        val tlyric = """
            [00:01.50]你好世界
            [00:05.00]第二行
        """.trimIndent()

        val lines = LrcParser.parse(lrc, tlyric)
        assertEquals(3, lines.size)

        assertEquals(1500L, lines[0].timestampMs)
        assertEquals("Hello world", lines[0].text)
        assertEquals("你好世界", lines[0].translation)

        assertEquals(5000L, lines[1].timestampMs)
        assertEquals("Second line", lines[1].text)
        assertEquals("第二行", lines[1].translation)

        assertEquals(10250L, lines[2].timestampMs)
        assertEquals("Third line", lines[2].text)
        assertNull(lines[2].translation)
    }

    private class FakeDaoForContext : MusicDao {
        var state: MusicSessionStateEntity? = null
        var lastEvent: MusicEventEntity? = null

        override fun getPlaylistsFlow(assistantId: String): Flow<List<MusicPlaylistEntity>> = emptyFlow()
        override suspend fun getPlaylists(assistantId: String): List<MusicPlaylistEntity> = emptyList()
        override suspend fun getPlaylist(assistantId: String, playlistId: Long): MusicPlaylistEntity? = null
        override suspend fun insertOrUpdatePlaylist(playlist: MusicPlaylistEntity) {}
        override suspend fun deletePlaylist(assistantId: String, playlistId: Long) {}
        override fun observeSessionState(assistantId: String): Flow<MusicSessionStateEntity?> = emptyFlow()
        override suspend fun getSessionState(assistantId: String): MusicSessionStateEntity? = state
        override suspend fun saveSessionState(sessionState: MusicSessionStateEntity) { state = sessionState }
        override suspend fun insertEvent(event: MusicEventEntity) { lastEvent = event }
        override suspend fun getRecentEvents(assistantId: String, limit: Int): List<MusicEventEntity> =
            listOfNotNull(lastEvent)
        override suspend fun trimEvents(assistantId: String, keepCount: Int) {}
        override suspend fun getDailyDiscovery(assistantId: String, date: String): MusicDailyDiscoveryEntity? = null
        override suspend fun getLatestDailyDiscovery(assistantId: String): MusicDailyDiscoveryEntity? = null
        override suspend fun saveDailyDiscovery(dailyDiscovery: MusicDailyDiscoveryEntity) {}
        override suspend fun insertFeedback(feedback: MusicFeedbackEntity) {}
        override suspend fun getFeedbacks(assistantId: String, limit: Int): List<MusicFeedbackEntity> = emptyList()
        override suspend fun getSongFeedback(assistantId: String, songId: Long): String? = null
        override suspend fun getRecentSummaries(assistantId: String, limit: Int): List<MusicSummaryEntity> = emptyList()
        override suspend fun saveSummary(summary: MusicSummaryEntity) {}
    }

    @Test
    fun testSharedListeningContextHelper_activePlaybackIncludesOnlyRelatedEvents() = runBlocking {
        val dao = FakeDaoForContext()
        val now = System.currentTimeMillis()
        val song = NeteaseSong(id = 12345L, name = "稻香", artists = "周杰伦", album = "魔杰座", coverUrl = "")

        dao.state = MusicSessionStateEntity(
            assistantId = "asst_music",
            playbackLane = "direct",
            lastState = "playing",
            playMode = "sequence",
            currentSongJson = Json.encodeToString(song),
            durationMs = 210000L,
            updatedAt = now - 60_000L, // 1 分钟前更新，在 2 小时租约内
        )

        dao.lastEvent = MusicEventEntity(
            assistantId = "asst_music", eventType = "play", actor = "user",
            songId = song.id, songName = song.name, songArtist = song.artists,
            songAlbum = song.album, createdAt = now,
        )
        val playback = MusicPlaybackState(
            assistantId = "asst_music", currentSong = song, playState = PlayState.PLAYING,
        )
        val context = SharedListeningContextHelper.buildPromptContext("asst_music", dao, playback)
        assertNotNull(context)
        assertTrue(context!!.contains("正在共听"))
        assertTrue(context.contains("稻香"))
        assertTrue(context.contains("周杰伦"))
        assertTrue(context.contains("用户 曾开始播放"))
        dao.lastEvent = dao.lastEvent!!.copy(songId = 999L, songName = "另一首歌")
        val unrelated = SharedListeningContextHelper.buildPromptContext("asst_music", dao, playback).orEmpty()
        assertTrue(!unrelated.contains("另一首歌"))
    }

    @Test
    fun testSharedListeningContextHelper_usesOnlyMatchingCurrentLyrics() = runBlocking {
        val dao = FakeDaoForContext()
        val song = NeteaseSong(12345L, "稻香", "周杰伦", "魔杰座", "")
        dao.state = MusicSessionStateEntity(
            assistantId = "asst_music",
            playbackLane = "direct",
            lastState = "playing",
            playMode = "sequence",
            currentSongJson = Json.encodeToString(song),
            updatedAt = System.currentTimeMillis(),
        )
        val playback = MusicPlaybackState(
            currentSong = song,
            playState = PlayState.PLAYING,
            assistantId = "asst_music",
            currentPositionMs = 5_000L,
            lyrics = SongLyrics(12345L, listOf(
                LyricLine(0L, "上一句"), LyricLine(4_000L, "当前句"), LyricLine(8_000L, "下一句")
            )),
        )

        val context = SharedListeningContextHelper.buildPromptContext("asst_music", dao, playback).orEmpty()
        assertTrue(context.contains("当前及下一句歌词：当前句 / 下一句"))
        assertTrue(!context.contains("上一句"))
        val otherAssistant = SharedListeningContextHelper.buildPromptContext(
            "asst_music", dao, playback.copy(assistantId = "other")
        ).orEmpty()
        assertTrue(!otherAssistant.contains("当前及下一句歌词"))
        assertEquals("当前没有音乐正在播放。", otherAssistant)
    }

    @Test
    fun testSharedListeningContextHelper_savedSessionWithoutLivePlaybackIsInactive() = runBlocking {
        val dao = FakeDaoForContext()
        val now = System.currentTimeMillis()
        val song = NeteaseSong(id = 12345L, name = "稻香", artists = "周杰伦", album = "魔杰座", coverUrl = "")

        // 超过 2 小时租约 (2.5 小时前)
        dao.state = MusicSessionStateEntity(
            assistantId = "asst_music",
            playbackLane = "direct",
            lastState = "playing",
            playMode = "sequence",
            currentSongJson = Json.encodeToString(song),
            durationMs = 210000L,
            updatedAt = now - 2 * 3600 * 1000L - 1000L,
        )

        val context = SharedListeningContextHelper.buildPromptContext("asst_music", dao)
        assertEquals("当前没有音乐正在播放。", context)
        dao.state = dao.state!!.copy(updatedAt = now)
        assertEquals("当前没有音乐正在播放。", SharedListeningContextHelper.buildPromptContext("asst_music", dao))
    }

    @Test
    fun testSharedListeningContextHelper_stoppedOrFinishedWithinLease() = runBlocking {
        val dao = FakeDaoForContext()
        val now = System.currentTimeMillis()
        val song = NeteaseSong(id = 12345L, name = "青花瓷", artists = "周杰伦", album = "我很忙", coverUrl = "")

        dao.state = MusicSessionStateEntity(
            assistantId = "asst_music",
            playbackLane = "direct",
            lastState = "stopped",
            playMode = "sequence",
            currentSongJson = Json.encodeToString(song),
            durationMs = 240000L,
            updatedAt = now - 100_000L,
        )
        dao.lastEvent = MusicEventEntity(
            assistantId = "asst_music",
            eventType = "play",
            actor = "user",
            songId = 12345L,
            songName = "青花瓷",
            songArtist = "周杰伦",
            songAlbum = "我很忙",
            createdAt = now - 100_000L,
        )

        for (playState in listOf(PlayState.PAUSED, PlayState.STOPPED, PlayState.BUFFERING)) {
            val playback = MusicPlaybackState(
                assistantId = "asst_music", currentSong = song, playState = playState,
            )
            assertEquals(
                "当前没有音乐正在播放。",
                SharedListeningContextHelper.buildPromptContext("asst_music", dao, playback),
            )
        }
    }
}
