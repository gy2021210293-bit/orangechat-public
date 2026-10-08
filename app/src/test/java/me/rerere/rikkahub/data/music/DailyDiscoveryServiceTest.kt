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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyDiscoveryServiceTest {

    private val json = Json { ignoreUnknownKeys = true }

    // 内存 Fake 实现 MusicDao
    private class FakeMusicDao : MusicDao {
        val playlists = mutableListOf<MusicPlaylistEntity>()
        val events = mutableListOf<MusicEventEntity>()
        val discoveries = mutableMapOf<String, MusicDailyDiscoveryEntity>()
        val feedbacks = mutableListOf<MusicFeedbackEntity>()

        override fun getPlaylistsFlow(assistantId: String): Flow<List<MusicPlaylistEntity>> = emptyFlow()
        override suspend fun getPlaylists(assistantId: String): List<MusicPlaylistEntity> = playlists.filter { it.assistantId == assistantId }
        override suspend fun getPlaylist(assistantId: String, playlistId: Long): MusicPlaylistEntity? = playlists.find { it.assistantId == assistantId && it.playlistId == playlistId }
        override suspend fun insertOrUpdatePlaylist(playlist: MusicPlaylistEntity) {
            playlists.removeAll { it.assistantId == playlist.assistantId && it.playlistId == playlist.playlistId }
            playlists.add(playlist)
        }
        override suspend fun deletePlaylist(assistantId: String, playlistId: Long) {
            playlists.removeAll { it.assistantId == assistantId && it.playlistId == playlistId }
        }

        override fun observeSessionState(assistantId: String): Flow<MusicSessionStateEntity?> = emptyFlow()
        override suspend fun getSessionState(assistantId: String): MusicSessionStateEntity? = null
        override suspend fun saveSessionState(sessionState: MusicSessionStateEntity) {}

        override suspend fun insertEvent(event: MusicEventEntity) { events.add(event) }
        override suspend fun getRecentEvents(assistantId: String, limit: Int): List<MusicEventEntity> =
            events.filter { it.assistantId == assistantId }.takeLast(limit)
        override suspend fun trimEvents(assistantId: String, keepCount: Int) {}

        override suspend fun getDailyDiscovery(assistantId: String, date: String): MusicDailyDiscoveryEntity? =
            discoveries["${assistantId}_$date"]
        override suspend fun getLatestDailyDiscovery(assistantId: String): MusicDailyDiscoveryEntity? =
            discoveries.values.lastOrNull { it.assistantId == assistantId }
        override suspend fun saveDailyDiscovery(dailyDiscovery: MusicDailyDiscoveryEntity) {
            discoveries["${dailyDiscovery.assistantId}_${dailyDiscovery.date}"] = dailyDiscovery
        }

        override suspend fun insertFeedback(feedback: MusicFeedbackEntity) { feedbacks.add(feedback) }
        override suspend fun getFeedbacks(assistantId: String, limit: Int): List<MusicFeedbackEntity> =
            feedbacks.filter { it.assistantId == assistantId }.takeLast(limit)
        override suspend fun getSongFeedback(assistantId: String, songId: Long): String? =
            feedbacks.findLast { it.assistantId == assistantId && it.songId == songId }?.action

        override suspend fun getRecentSummaries(assistantId: String, limit: Int): List<MusicSummaryEntity> = emptyList()
        override suspend fun saveSummary(summary: MusicSummaryEntity) {}
    }

    // 可控的 Fake 音乐客户端，继承 NeteaseMusicClient (在测试环境拦截网络调用)
    private class FakeNeteaseClient : NeteaseMusicClient() {
        val searchResults = mutableListOf<NeteaseSong>()
        val unplayableIds = mutableSetOf<Long>()

        override suspend fun searchSongs(keyword: String, limit: Int): List<NeteaseSong> {
            return searchResults.filter { it.name.contains(keyword) || it.artists.contains(keyword) || keyword in listOf("治愈", "纯音乐", "周杰伦", "轻音乐", "民谣") }
        }

        override suspend fun isSongPlayable(songId: Long): Boolean {
            return songId !in unplayableIds
        }
    }

    @Test
    fun testExclusionAndDiversity() = runBlocking {
        val dao = FakeMusicDao()
        val client = FakeNeteaseClient()
        val service = DailyDiscoveryService(dao, client, json)

        val assistantId = "asst_1"

        // 1. 设置已存在的歌单（包含歌曲 101）
        dao.insertOrUpdatePlaylist(
            MusicPlaylistEntity(
                assistantId = assistantId,
                playlistId = 1L,
                name = "我的收藏",
                trackCount = 1,
                coverUrl = "",
                tracksJson = json.encodeToString(listOf(
                    NeteaseSong(id = 101L, name = "已在歌单中的歌", artists = "歌手A", album = "专辑A", coverUrl = "")
                )),
                updatedAt = System.currentTimeMillis()
            )
        )

        // 2. 设置负反馈（歌曲 102 被标记为 less）
        dao.insertFeedback(
            MusicFeedbackEntity(
                assistantId = assistantId,
                songId = 102L,
                songName = "不喜欢的歌",
                songArtist = "歌手B",
                action = "less",
                createdAt = System.currentTimeMillis()
            )
        )

        // 3. 配置候选池：包含 101（歌单中）、102（负反馈）、同歌手超过2首（103, 104, 105 同为歌手C）、以及不可播歌曲 106
        client.searchResults.addAll(listOf(
            NeteaseSong(id = 101L, name = "已在歌单中的歌", artists = "歌手A", album = "专辑A", coverUrl = ""),
            NeteaseSong(id = 102L, name = "不喜欢的歌", artists = "歌手B", album = "专辑B", coverUrl = ""),
            NeteaseSong(id = 103L, name = "歌手C的第一首歌", artists = "歌手C", album = "专辑C", coverUrl = ""),
            NeteaseSong(id = 104L, name = "歌手C的第二首歌", artists = "歌手C", album = "专辑C", coverUrl = ""),
            NeteaseSong(id = 105L, name = "歌手C的第三首歌", artists = "歌手C", album = "专辑C", coverUrl = ""), // 应当被歌手数量限制过滤
            NeteaseSong(id = 106L, name = "不可播的VIP歌", artists = "歌手D", album = "专辑D", coverUrl = ""), // 不可播
            NeteaseSong(id = 107L, name = "优质好歌E", artists = "歌手E", album = "专辑E", coverUrl = ""),
        ))
        client.unplayableIds.add(106L)

        // 执行私选策展
        val discovery = service.getTodayDiscovery(assistantId, forceRefresh = true)
        assertNotNull(discovery)

        val songs = service.parseSongs(discovery)
        val songIds = songs.map { it.id }

        // 验证排除项：101（歌单歌曲）和 102（负反馈歌曲）绝对不在推荐列表中
        assertFalse("已在歌单中的歌曲应被排除", songIds.contains(101L))
        assertFalse("被标记负反馈的歌曲应被排除", songIds.contains(102L))

        // 验证不可播歌曲 106 被过滤
        assertFalse("不可播歌曲应被过滤", songIds.contains(106L))

        // 验证歌手多样性约束：歌手C最多保留 2 首
        val artistCSongs = songs.filter { it.artist == "歌手C" }
        assertTrue("单歌手最多保留 2 首", artistCSongs.size <= 2)

        // 验证标签与理由完整性
        songs.forEach { song ->
            assertTrue("推荐歌曲应具备有效标签", song.tag.isNotBlank())
            assertTrue("推荐歌曲应具备推荐理由", song.reason.isNotBlank())
            assertTrue(song.playable)
        }
    }

    @Test
    fun testRecordFeedback() = runBlocking {
        val dao = FakeMusicDao()
        val client = FakeNeteaseClient()
        val service = DailyDiscoveryService(dao, client, json)
        val assistantId = "asst_2"

        val song = DailyDiscoverySong(
            id = 999L,
            name = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            coverUrl = "",
            reason = "华语经典",
            tag = "稳准",
        )

        service.recordFeedback(assistantId, song, "like")
        val fb = dao.getSongFeedback(assistantId, 999L)
        assertEquals("like", fb)
    }

    @Test
    fun testAllUnplayableReturnsEmptyList() = runBlocking {
        val dao = FakeMusicDao()
        val client = FakeNeteaseClient()
        val service = DailyDiscoveryService(dao, client, json)
        val assistantId = "asst_unplayable"

        client.searchResults.addAll(listOf(
            NeteaseSong(id = 201L, name = "VIP专享A", artists = "歌手A", album = "专辑A", coverUrl = ""),
            NeteaseSong(id = 202L, name = "VIP专享B", artists = "歌手B", album = "专辑B", coverUrl = ""),
        ))
        client.unplayableIds.addAll(listOf(201L, 202L))

        val discovery = service.getTodayDiscovery(assistantId, forceRefresh = true)
        assertNotNull(discovery)
        assertFalse("无可用音源时不应标记为 llmRanked", discovery.llmRanked)

        val songs = service.parseSongs(discovery)
        assertTrue("所有候选均不可播时，推荐列表必须为空，绝不能包含未验证歌曲", songs.isEmpty())
        assertTrue("subtitle 应提示暂无可用推荐曲目", discovery.subtitle.contains("暂无可用推荐"))
    }

    @Test
    fun testLlmCuratedDiscoverySuccess() = runBlocking {
        val dao = FakeMusicDao()
        val client = FakeNeteaseClient()
        val assistantId = "asst_llm"

        client.searchResults.addAll(listOf(
            NeteaseSong(id = 301L, name = "好歌A", artists = "歌手A", album = "专辑A", coverUrl = ""),
            NeteaseSong(id = 302L, name = "好歌B", artists = "歌手B", album = "专辑B", coverUrl = ""),
        ))

        val mockLlmResponse = """
            {
              "note": "今天这组偏安静治愈，适合夜晚。",
              "songs": [
                {
                  "id": 301,
                  "reason": "安静温暖的吉他弹唱，很适合今天",
                  "tag": "心动"
                },
                {
                  "id": 302,
                  "reason": "慢速舒缓的旋律，放空思绪",
                  "tag": "治愈精选"
                },
                {
                  "id": 999999,
                  "reason": "幻觉编造的歌曲",
                  "tag": "小冒险"
                }
              ]
            }
        """.trimIndent()

        val service = DailyDiscoveryService(
            musicDao = dao,
            musicClient = client,
            json = json,
            llmCurator = { _ -> mockLlmResponse }
        )

        val discovery = service.getTodayDiscovery(assistantId, forceRefresh = true)
        assertNotNull(discovery)
        assertTrue("大模型策展成功后 llmRanked 应为 true", discovery.llmRanked)
        assertEquals("今天这组偏安静治愈，适合夜晚。", discovery.note)

        val songs = service.parseSongs(discovery)
        assertEquals(2, songs.size)
        // 验证幻觉歌曲 999999 被丢弃
        assertFalse(songs.any { it.id == 999999L })
        // 验证理由和标签来自模型策展
        assertEquals("安静温暖的吉他弹唱，很适合今天", songs[0].reason)
        assertEquals("心动", songs[0].tag)
        assertEquals("慢速舒缓的旋律，放空思绪", songs[1].reason)
        assertEquals("治愈精选", songs[1].tag)
    }

    @Test
    fun testLlmCuratedDiscoveryFallbackOnInvalidJson() = runBlocking {
        val dao = FakeMusicDao()
        val client = FakeNeteaseClient()
        val assistantId = "asst_llm_fallback"

        client.searchResults.addAll(listOf(
            NeteaseSong(id = 401L, name = "好歌C", artists = "歌手C", album = "专辑C", coverUrl = ""),
        ))

        val service = DailyDiscoveryService(
            musicDao = dao,
            musicClient = client,
            json = json,
            llmCurator = { _ -> "抱歉，我无法生成JSON" }
        )

        val discovery = service.getTodayDiscovery(assistantId, forceRefresh = true)
        assertNotNull(discovery)
        assertFalse("模型输出无效时应回退到确定性规则，llmRanked 应为 false", discovery.llmRanked)

        val songs = service.parseSongs(discovery)
        assertEquals(1, songs.size)
        assertEquals(401L, songs[0].id)
        assertTrue(songs[0].playable)
    }
}
