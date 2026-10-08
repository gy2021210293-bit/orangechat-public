/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSessionManagerTest {

    private class TestMusicDao : MusicDao {
        val states = mutableMapOf<String, MusicSessionStateEntity>()
        val events = mutableListOf<MusicEventEntity>()

        override fun getPlaylistsFlow(assistantId: String): Flow<List<MusicPlaylistEntity>> = emptyFlow()
        override suspend fun getPlaylists(assistantId: String): List<MusicPlaylistEntity> = emptyList()
        override suspend fun getPlaylist(assistantId: String, playlistId: Long): MusicPlaylistEntity? = null
        override suspend fun insertOrUpdatePlaylist(playlist: MusicPlaylistEntity) {}
        override suspend fun deletePlaylist(assistantId: String, playlistId: Long) {}
        override fun observeSessionState(assistantId: String): Flow<MusicSessionStateEntity?> = emptyFlow()
        override suspend fun getSessionState(assistantId: String): MusicSessionStateEntity? = synchronized(states) { states[assistantId] }
        override suspend fun saveSessionState(sessionState: MusicSessionStateEntity) {
            synchronized(states) { states[sessionState.assistantId] = sessionState }
        }
        override suspend fun insertEvent(event: MusicEventEntity) {
            synchronized(events) { events.add(event) }
        }
        override suspend fun getRecentEvents(assistantId: String, limit: Int): List<MusicEventEntity> = synchronized(events) {
            events.filter { it.assistantId == assistantId }.takeLast(limit)
        }
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

    private class TestNeteaseClient : NeteaseMusicClient() {
        override suspend fun getPlayableUrl(songId: Long, preferredQuality: String): String {
            return "https://music.163.com/fake/$songId.mp3"
        }
        override suspend fun isSongPlayable(songId: Long): Boolean = true
        override suspend fun getLyrics(songId: Long): SongLyrics = SongLyrics(songId, emptyList())
    }

    private suspend fun eventually(timeoutMs: Long = 1500, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(20)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", condition())
    }

    @Test
    fun testPlaybackBufferingDoesNotPrematurelyRecordPlayOrPlayingState() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val dao = TestMusicDao()
        val client = TestNeteaseClient()

        val manager = MusicSessionManager(
            context = null,
            musicDao = dao,
            client = client,
            settingsStore = null,
            scope = testScope,
        )
        manager.switchAssistant("asst_1")

        val song = NeteaseSong(id = 1001L, name = "夜曲", artists = "周杰伦", album = "十一月的萧邦", coverUrl = "")

        // 1. 触发播放：进入 BUFFERING
        manager.playDirect(song, actor = "user")
        eventually { manager.state.value.playState == PlayState.BUFFERING }

        // 尚未发声时，绝不能记录 play 事件，也不能把 Room 写成 playing
        delay(100)
        assertFalse("缓冲期间绝不应记录 play 事件", dao.events.any { it.eventType == "play" })
        val bufferedState = dao.getSessionState("asst_1")
        if (bufferedState != null) {
            assertEquals("buffering", bufferedState.lastState)
        }

        // 2. 模拟播放器缓冲完成真正发声 (onPlaybackStarted)
        manager.onPlaybackStarted()
        assertEquals(PlayState.PLAYING, manager.state.value.playState)
        eventually { dao.events.any { it.eventType == "play" && it.songId == 1001L } }
        eventually { dao.getSessionState("asst_1")?.lastState == "playing" }
    }

    @Test
    fun testPlaybackErrorDuringBufferingLeavesNoPlayEvent() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val dao = TestMusicDao()
        val client = TestNeteaseClient()

        val manager = MusicSessionManager(
            context = null,
            musicDao = dao,
            client = client,
            settingsStore = null,
            scope = testScope,
        )
        manager.switchAssistant("asst_1")

        val song = NeteaseSong(id = 1002L, name = "七里香", artists = "周杰伦", album = "七里香", coverUrl = "")

        manager.playDirect(song, actor = "user")
        eventually { manager.state.value.playState == PlayState.BUFFERING }

        // 模拟缓冲失败抛出错误
        manager.onPlaybackError("播放失败：ERROR_CODE_IO_BAD_HTTP_STATUS")
        assertEquals(PlayState.STOPPED, manager.state.value.playState)
        assertEquals("播放失败：ERROR_CODE_IO_BAD_HTTP_STATUS", manager.state.value.errorMessage)

        eventually { dao.getSessionState("asst_1")?.lastState == "stopped" }
        // 验证整个失败流程中从未产生过 "play" 事件
        assertFalse("播放失败时不应产生 play 事件", dao.events.any { it.eventType == "play" })
    }

    @Test
    fun testSystemMediaActionsSyncManagerAndRoom() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val dao = TestMusicDao()
        val client = TestNeteaseClient()

        val manager = MusicSessionManager(
            context = null,
            musicDao = dao,
            client = client,
            settingsStore = null,
            scope = testScope,
        )
        manager.switchAssistant("asst_1")

        val song = NeteaseSong(id = 1003L, name = "晴天", artists = "周杰伦", album = "叶惠美", coverUrl = "")

        manager.playDirect(song)
        eventually { manager.state.value.playState == PlayState.BUFFERING }
        manager.onPlaybackStarted()
        assertEquals(PlayState.PLAYING, manager.state.value.playState)

        // 系统通知栏 / 媒体按钮触发暂停
        manager.onPlaybackPaused()
        assertEquals(PlayState.PAUSED, manager.state.value.playState)
        eventually { dao.events.any { it.eventType == "pause" } }
        eventually { dao.getSessionState("asst_1")?.lastState == "paused" }

        // 系统通知栏 / 媒体按钮触发恢复
        manager.onPlaybackStarted()
        assertEquals(PlayState.PLAYING, manager.state.value.playState)
        eventually { dao.events.any { it.eventType == "resume" } }
        eventually { dao.getSessionState("asst_1")?.lastState == "playing" }

        // 系统通知栏 / 媒体按钮触发停止关闭
        manager.onPlaybackStopped()
        assertEquals(PlayState.STOPPED, manager.state.value.playState)
        eventually { dao.events.any { it.eventType == "close" } }
        eventually { dao.getSessionState("asst_1")?.lastState == "stopped" }
    }

    @Test
    fun testSwitchAssistantPersistsStoppedInsteadOfPaused() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val dao = TestMusicDao()
        val client = TestNeteaseClient()

        val manager = MusicSessionManager(
            context = null,
            musicDao = dao,
            client = client,
            settingsStore = null,
            scope = testScope,
        )

        // 初始为助手 asst_1
        manager.switchAssistant("asst_1")
        val song = NeteaseSong(id = 1004L, name = "退后", artists = "周杰伦", album = "依然范特西", coverUrl = "")
        manager.playDirect(song)
        eventually { manager.state.value.playState == PlayState.BUFFERING }
        manager.onPlaybackStarted()
        assertEquals(PlayState.PLAYING, manager.state.value.playState)

        // 切换到助手 asst_2
        manager.switchAssistant("asst_2")

        // 验证旧助手 asst_1 的持久化状态为 stopped，绝不能是 paused
        eventually { dao.getSessionState("asst_1")?.lastState == "stopped" }
        val asst1State = dao.getSessionState("asst_1")
        assertNotNull(asst1State)
        assertEquals("旧会话应持久化为 stopped 而非 paused", "stopped", asst1State?.lastState)

        // 验证 AI 提示词上下文构建器不会将该已关闭会话称作“共听已暂停”或“正在共听”
        val promptContext = SharedListeningContextHelper.buildPromptContext("asst_1", dao)
        if (promptContext != null) {
            assertFalse("已关闭会话不得在提示词中描述为共听已暂停", promptContext.contains("共听已暂停："))
            assertFalse("已关闭会话不得在提示词中描述为正在共听", promptContext.contains("正在共听："))
        }
    }

    @Test
    fun testRepeatOnePreservesPlaylistAndQueueLanes() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val dao = TestMusicDao()
        val client = TestNeteaseClient()

        val manager = MusicSessionManager(
            context = null,
            musicDao = dao,
            client = client,
            settingsStore = null,
            scope = testScope,
        )
        manager.switchAssistant("asst_1")

        // 1. 测试歌单通道下的单曲循环
        val playlist = NeteasePlaylistDetail(
            id = 5001L,
            name = "杰伦精选",
            coverUrl = "",
            trackCount = 3,
            creatorName = "用户",
            description = "",
            tracks = listOf(
                NeteaseSong(id = 1L, name = "歌1", artists = "歌手1", album = "专1", coverUrl = ""),
                NeteaseSong(id = 2L, name = "歌2", artists = "歌手2", album = "专2", coverUrl = ""),
                NeteaseSong(id = 3L, name = "歌3", artists = "歌手3", album = "专3", coverUrl = ""),
            )
        )
        manager.setActivePlaylist(playlist)
        manager.playFromLane(PlaybackLane.PLAYLIST, 1)
        manager.setPlayMode(PlayMode.REPEAT_ONE)

        eventually {
            manager.state.value.lane == PlaybackLane.PLAYLIST &&
            manager.state.value.playlistCursor == 1 &&
            manager.state.value.currentSong?.id == 2L
        }

        // 触发切歌 (上一曲放完或点击下一首)
        manager.next()

        // 验证：通道仍然是 PLAYLIST，游标仍然是 1，interruption 仍然为 null，没有丢失歌单通道
        eventually {
            manager.state.value.lane == PlaybackLane.PLAYLIST &&
            manager.state.value.playlistCursor == 1
        }
        assertEquals("单曲循环切歌必须保留原歌单通道", PlaybackLane.PLAYLIST, manager.state.value.lane)
        assertEquals(1, manager.state.value.playlistCursor)
        assertNull("单曲循环不应生成打断记录", manager.state.value.interruption)

        // 2. 测试队列通道下的单曲循环
        val queueSongs = listOf(
            NeteaseSong(id = 11L, name = "队列1", artists = "歌手1", album = "专1", coverUrl = ""),
            NeteaseSong(id = 12L, name = "队列2", artists = "歌手2", album = "专2", coverUrl = ""),
        )
        manager.playAll(queueSongs, startIndex = 1)
        manager.setPlayMode(PlayMode.REPEAT_ONE)

        eventually {
            manager.state.value.lane == PlaybackLane.QUEUE &&
            manager.state.value.cursor == 1
        }

        manager.next()

        eventually {
            manager.state.value.lane == PlaybackLane.QUEUE &&
            manager.state.value.cursor == 1
        }
        assertEquals("单曲循环切歌必须保留原队列通道", PlaybackLane.QUEUE, manager.state.value.lane)
        assertEquals(1, manager.state.value.cursor)
        assertNull("单曲循环不应生成打断记录", manager.state.value.interruption)
    }

    @Test
    fun testPauseDuringBufferingEntersPausedStateAndDoesNotRecordPlay() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val dao = TestMusicDao()
        val client = TestNeteaseClient()

        val manager = MusicSessionManager(
            context = null,
            musicDao = dao,
            client = client,
            settingsStore = null,
            scope = testScope,
        )
        manager.switchAssistant("asst_1")

        val song = NeteaseSong(id = 1005L, name = "枫", artists = "周杰伦", album = "十一月的萧邦", coverUrl = "")

        manager.playDirect(song)
        eventually { manager.state.value.playState == PlayState.BUFFERING }

        // 缓冲期间用户点击暂停
        manager.pause()
        assertEquals(PlayState.PAUSED, manager.state.value.playState)

        eventually { dao.events.any { it.eventType == "pause" } }
        assertFalse("缓冲期暂停不应记录任何 play 事件", dao.events.any { it.eventType == "play" })
        eventually { dao.getSessionState("asst_1")?.lastState == "paused" }
    }

    @Test
    fun testResumeEntersBufferingFirstAndOnlyRecordsResumeWhenAudioActuallyStarts() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val dao = TestMusicDao()
        val client = TestNeteaseClient()

        val manager = MusicSessionManager(
            context = null,
            musicDao = dao,
            client = client,
            settingsStore = null,
            scope = testScope,
        )
        manager.switchAssistant("asst_1")

        val song = NeteaseSong(id = 1006L, name = "借口", artists = "周杰伦", album = "七里香", coverUrl = "")

        manager.playDirect(song)
        eventually { manager.state.value.playState == PlayState.BUFFERING }
        manager.onPlaybackStarted()
        assertEquals(PlayState.PLAYING, manager.state.value.playState)

        manager.pause()
        assertEquals(PlayState.PAUSED, manager.state.value.playState)

        // 恢复播放：必须首先进入 BUFFERING，绝不能立即写成 PLAYING 或记录 resume 事件
        manager.resume()
        assertEquals(PlayState.BUFFERING, manager.state.value.playState)
        delay(100)
        assertFalse("恢复尚未真正发声时不应提前记录 resume 事件", dao.events.any { it.eventType == "resume" })
        eventually { dao.getSessionState("asst_1")?.lastState == "buffering" }

        // 模拟底层播放器获取音频焦点并完成缓冲真正发声
        manager.onPlaybackStarted()
        assertEquals(PlayState.PLAYING, manager.state.value.playState)
        eventually { dao.events.any { it.eventType == "resume" && it.songId == 1006L } }
        eventually { dao.getSessionState("asst_1")?.lastState == "playing" }
    }

    @Test
    fun testSwitchAssistantFlushesChannelInFifoOrderWithoutStaleStateOverwritingStopped() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val dao = TestMusicDao()
        val client = TestNeteaseClient()

        val manager = MusicSessionManager(
            context = null,
            musicDao = dao,
            client = client,
            settingsStore = null,
            scope = testScope,
        )
        manager.switchAssistant("asst_1")

        val song = NeteaseSong(id = 1007L, name = "珊瑚海", artists = "周杰伦", album = "十一月的萧邦", coverUrl = "")
        manager.playDirect(song)
        eventually { manager.state.value.playState == PlayState.BUFFERING }
        manager.onPlaybackStarted()
        assertEquals(PlayState.PLAYING, manager.state.value.playState)

        // 频繁触发状态以压入队列
        manager.setPlayMode(PlayMode.REPEAT_ONE)
        manager.seekTo(1000L)

        // 立即切换到助手 asst_2
        manager.switchAssistant("asst_2")

        // 验证：asst_1 最终在 Room 中绝对为 stopped，排队的旧状态绝不能覆盖为 playing
        val asst1State = dao.getSessionState("asst_1")
        assertNotNull(asst1State)
        assertEquals("旧助手状态最终必须严格保持为 stopped", "stopped", asst1State?.lastState)

        // 延迟一段时间确保后台所有队列消费完毕，再次确认未被旧状态覆盖
        delay(150)
        assertEquals("旧助手状态在队列消费完毕后仍必须为 stopped", "stopped", dao.getSessionState("asst_1")?.lastState)
    }

    @Test
    fun testPluginPlaybackEventsDoNotLeakIntoSharedListeningSession() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val dao = TestMusicDao()
        val client = TestNeteaseClient()

        me.rerere.rikkahub.plugin.webview.MusicPlayerService.resetForTesting()
        me.rerere.rikkahub.plugin.webview.MusicPlayerService.setClientForTesting(
            me.rerere.rikkahub.plugin.webview.MusicPlayerService.CLIENT_PLUGIN
        )

        val manager = MusicSessionManager(
            context = null,
            musicDao = dao,
            client = client,
            settingsStore = null,
            scope = testScope,
        )
        manager.switchAssistant("asst_1")

        // 初始状态为 STOPPED，且无 currentSong
        assertEquals(PlayState.STOPPED, manager.state.value.playState)
        assertNull(manager.state.value.currentSong)

        // 模拟外部（插件）触发了底层的播放回调、暂停回调和歌曲结束回调
        manager.onPlaybackStarted()
        assertEquals("插件播放开始绝不应唤醒共听为 PLAYING", PlayState.STOPPED, manager.state.value.playState)
        assertFalse("插件播放开始绝不应记录 play 事件", dao.events.any { it.eventType == "play" || it.eventType == "resume" })

        manager.onPlaybackPaused()
        assertEquals("插件暂停绝不应修改共听为 PAUSED", PlayState.STOPPED, manager.state.value.playState)
        assertFalse("插件暂停绝不应记录 pause 事件", dao.events.any { it.eventType == "pause" })

        manager.onTrackCompleted()
        assertEquals("插件播完绝不应触发切歌", PlayState.STOPPED, manager.state.value.playState)

        // 再测试有 currentSong 但处于 STOPPED 时的场景
        val song = NeteaseSong(id = 1008L, name = "七里香", artists = "周杰伦", album = "七里香", coverUrl = "")
        manager.playDirect(song)
        eventually { manager.state.value.playState == PlayState.BUFFERING }
        manager.onPlaybackStarted()
        assertEquals(PlayState.PLAYING, manager.state.value.playState)

        // 停止共听
        manager.stop()
        assertEquals(PlayState.STOPPED, manager.state.value.playState)
        eventually { dao.events.any { it.eventType == "close" } }
        val initialEventsCount = dao.events.size

        // 此时插件开始播放，ExoPlayer 再次触发播放/暂停/结束回调
        manager.onPlaybackStarted()
        assertEquals("共听已停止后，插件播放绝不能将网易云歌曲复活为 PLAYING", PlayState.STOPPED, manager.state.value.playState)
        assertEquals("共听已停止后，插件播放绝不能产生新的播放事件", initialEventsCount, dao.events.size)

        manager.onPlaybackPaused()
        assertFalse("共听已停止后，插件暂停绝不能产生 pause 事件", dao.events.any { it.eventType == "pause" })
        assertEquals("共听已停止后，插件暂停绝不能产生新事件", initialEventsCount, dao.events.size)

        manager.onTrackCompleted()
        assertEquals("共听已停止后，插件播完绝不能触发切歌", PlayState.STOPPED, manager.state.value.playState)

        me.rerere.rikkahub.plugin.webview.MusicPlayerService.resetForTesting()
    }

    @Test
    fun testSharedListeningInterruptedByPluginSwitchesToStopped() = runBlocking {
        val testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val dao = TestMusicDao()
        val client = TestNeteaseClient()

        me.rerere.rikkahub.plugin.webview.MusicPlayerService.resetForTesting()

        val manager = MusicSessionManager(
            context = null,
            musicDao = dao,
            client = client,
            settingsStore = null,
            scope = testScope,
        )
        manager.switchAssistant("asst_1")

        val song = NeteaseSong(id = 1009L, name = "晴天", artists = "周杰伦", album = "叶惠美", coverUrl = "")
        manager.playDirect(song)
        eventually { manager.state.value.playState == PlayState.BUFFERING }
        manager.onPlaybackStarted()
        assertEquals(PlayState.PLAYING, manager.state.value.playState)

        // 模拟插件抢占播放，底层调用了 onStopListener
        me.rerere.rikkahub.plugin.webview.MusicPlayerService.onStopListener?.invoke()
        assertEquals("插件抢占播放时，共听会话必须被优雅中止转为 STOPPED", PlayState.STOPPED, manager.state.value.playState)
        eventually { dao.events.any { it.eventType == "close" && it.songId == 1009L } }
        eventually { dao.getSessionState("asst_1")?.lastState == "stopped" }

        me.rerere.rikkahub.plugin.webview.MusicPlayerService.resetForTesting()
    }
}
