/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.music

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import java.time.LocalDate

private const val TAG = "DailyDiscoveryService"

@Serializable
private data class LlmCurateResponse(
    val note: String? = null,
    val songs: List<LlmCurateSong> = emptyList(),
)

@Serializable
private data class LlmCurateSong(
    val id: Long,
    val reason: String = "",
    val tag: String = "稳准",
)

/**
 * 助手「今日私选」智能策展服务。
 * 流程：已知歌曲排除 -> 品味画像提取 -> 多查询外扩搜索 -> 候选去重与歌手多样性约束 ->
 * 可播性验证 -> 当前助手模型策展（失败则规则兜底） -> 持久化到 Room。
 */
class DailyDiscoveryService(
    private val musicDao: MusicDao,
    private val musicClient: NeteaseMusicClient,
    private val json: Json,
    private val settingsStore: SettingsStore? = null,
    private val providerManager: ProviderManager? = null,
    private val llmCurator: (suspend (prompt: String) -> String?)? = null,
) {

    suspend fun getTodayDiscovery(
        assistantId: String,
        forceRefresh: Boolean = false,
    ): MusicDailyDiscoveryEntity = withContext(Dispatchers.IO) {
        val todayStr = LocalDate.now().toString()
        if (!forceRefresh) {
            val existing = musicDao.getDailyDiscovery(assistantId, todayStr)
            if (existing != null) return@withContext existing
        }
        generateTodayDiscovery(assistantId, todayStr)
    }

    suspend fun parseSongs(discovery: MusicDailyDiscoveryEntity): List<DailyDiscoverySong> =
        withContext(Dispatchers.Default) {
            runCatching {
                json.decodeFromString<List<DailyDiscoverySong>>(discovery.songsJson)
            }.getOrElse { emptyList() }
        }

    suspend fun recordFeedback(
        assistantId: String,
        song: DailyDiscoverySong,
        action: String, // "like", "heard", "more", "less"
    ) = withContext(Dispatchers.IO) {
        musicDao.insertFeedback(
            MusicFeedbackEntity(
                assistantId = assistantId,
                songId = song.id,
                songName = song.name,
                songArtist = song.artist,
                action = action,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun generateTodayDiscovery(
        assistantId: String,
        date: String,
    ): MusicDailyDiscoveryEntity {
        // 1. 收集排除集（歌单歌曲、近期已播放、负反馈）
        val excludedSongIds = mutableSetOf<Long>()
        val playlists = musicDao.getPlaylists(assistantId)
        for (pl in playlists) {
            runCatching {
                val songs = json.decodeFromString<List<NeteaseSong>>(pl.tracksJson)
                excludedSongIds.addAll(songs.map { it.id })
            }
        }
        val recentEvents = musicDao.getRecentEvents(assistantId, limit = 200)
        excludedSongIds.addAll(recentEvents.map { it.songId })
        val feedbacks = musicDao.getFeedbacks(assistantId, limit = 300)
        feedbacks.filter { it.action in listOf("less", "dislike") }.forEach {
            excludedSongIds.add(it.songId)
        }

        // 2. 提取正向画像 Seeds
        val positiveArtists = mutableListOf<String>()
        feedbacks.filter { it.action in listOf("like", "more") }.forEach {
            if (it.songArtist.isNotBlank()) positiveArtists.add(it.songArtist)
        }
        val playCountsByArtist = recentEvents
            .filter { it.songArtist.isNotBlank() }
            .groupingBy { it.songArtist }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
        positiveArtists.addAll(playCountsByArtist)

        val searchQueries = mutableListOf<String>()
        searchQueries.addAll(positiveArtists.distinct().take(3))
        if (searchQueries.isEmpty()) {
            searchQueries.addAll(listOf("治愈", "纯音乐", "周杰伦", "轻音乐", "民谣"))
        }

        // 3. 多查询外扩搜索
        val rawCandidates = mutableListOf<NeteaseSong>()
        for (query in searchQueries.take(4)) {
            val res = runCatching { musicClient.searchSongs(query, limit = 15) }.getOrNull().orEmpty()
            rawCandidates.addAll(res)
        }

        // 4. 去重与歌手多样性约束（单歌手最多 2 首）
        val deduped = mutableListOf<NeteaseSong>()
        val seenSongIds = mutableSetOf<Long>()
        val artistCounts = mutableMapOf<String, Int>()

        for (song in rawCandidates) {
            if (song.id in excludedSongIds) continue
            if (!seenSongIds.add(song.id)) continue
            val artistKey = song.artists.split("/", ",", "&", " ").firstOrNull()?.trim() ?: song.artists
            val currentCount = artistCounts.getOrDefault(artistKey, 0)
            if (currentCount >= 2) continue
            artistCounts[artistKey] = currentCount + 1
            deduped.add(song)
        }

        // 5. 可播性验证（并发验证候选）
        val candidatesToCheck = deduped.take(40)
        val playableCandidates = coroutineScope {
            candidatesToCheck.map { song ->
                async {
                    val playable = musicClient.isSongPlayable(song.id)
                    if (playable) song else null
                }
            }.awaitAll().filterNotNull()
        }

        // 6. 音源验证结果为空时，返回空列表并提示，绝不使用未经验证的候选歌曲标为可播
        if (playableCandidates.isEmpty()) {
            val entity = MusicDailyDiscoveryEntity(
                assistantId = assistantId,
                date = date,
                title = "今日私选 · $date",
                subtitle = "今日暂无可用推荐曲目",
                note = "已检索候选曲目，但未检测到可用音源（可能受版权或会员限制），请稍后再试。",
                llmRanked = false,
                songsJson = json.encodeToString(emptyList<DailyDiscoverySong>()),
                generatedAt = System.currentTimeMillis(),
            )
            musicDao.saveDailyDiscovery(entity)
            return entity
        }

        // 7. 优先由当前助手的大模型从已验证的候选中策展
        val llmCurated = if (llmCurator != null || (settingsStore != null && providerManager != null)) {
            runCatching {
                curateWithLlm(assistantId, playableCandidates)
            }.onFailure {
                Log.w(TAG, "LLM curation failed, falling back to deterministic rules: ${it.message}", it)
            }.getOrNull()
        } else null

        val (dailySongs, note, llmRanked) = if (llmCurated != null && llmCurated.second.isNotEmpty()) {
            Triple(llmCurated.second, llmCurated.first ?: "由当前助手专属策展，已完成音源验证与品味匹配", true)
        } else {
            // 确定性规则兜底挑选 10-20 首已验证歌曲
            val selectedSongs = playableCandidates.take(20)
            val tagPool = listOf("稳准", "心动", "陪伴", "常听流派", "治愈精选", "独家品味")
            val fallbackSongs = selectedSongs.mapIndexed { index, song ->
                val tag = tagPool[index % tagPool.size]
                val reason = when (tag) {
                    "稳准" -> "契合你们最近常听的风格与节奏"
                    "心动" -> "旋律抓耳，推荐一起静静聆听"
                    "陪伴" -> "适合在聊天或工作时作为背景音陪伴"
                    "常听流派" -> "源自你们偏好的歌手与流派延伸"
                    "治愈精选" -> "温和治愈的氛围，放松心情"
                    else -> "为你和助手今日专属定制"
                }
                DailyDiscoverySong(
                    id = song.id,
                    name = song.name,
                    artist = song.artists,
                    album = song.album,
                    coverUrl = song.coverUrl.orEmpty(),
                    reason = reason,
                    tag = tag,
                    playable = true,
                )
            }
            Triple(fallbackSongs, "每天由品味画像与流派探索为你精选，已自动过滤重复与不可播曲目", false)
        }

        val entity = MusicDailyDiscoveryEntity(
            assistantId = assistantId,
            date = date,
            title = "今日私选 · $date",
            subtitle = "根据你们的听歌品味为你定制",
            note = note,
            llmRanked = llmRanked,
            songsJson = json.encodeToString(dailySongs),
            generatedAt = System.currentTimeMillis(),
        )
        musicDao.saveDailyDiscovery(entity)
        return entity
    }

    private suspend fun curateWithLlm(
        assistantId: String,
        playableCandidates: List<NeteaseSong>,
    ): Pair<String?, List<DailyDiscoverySong>>? {
        val candidatePool = playableCandidates.take(30)
        val candidateMap = candidatePool.associateBy { it.id }

        val candidatesJson = buildJsonArray {
            candidatePool.forEach { song ->
                add(buildJsonObject {
                    put("id", song.id)
                    put("name", song.name)
                    put("artist", song.artists)
                    put("album", song.album)
                })
            }
        }.toString()

        val prompt = buildString {
            appendLine("你是音乐策展助手。请从以下经过音源验证的真实候选歌曲中，精选 10 至 20 首作为今天的「今日私选」推荐列表。")
            appendLine()
            appendLine("要求：")
            appendLine("1. 只能从给定的候选歌曲列表中挑选（使用候选中的 id），严禁编造或修改歌曲 ID、歌名或歌手；")
            appendLine("2. 为挑选出的每首歌提供一句短推荐理由（15-40字，温和真诚，从曲风、氛围、节奏或情感契合度说明为什么推荐）；")
            appendLine("3. 为每首歌选择一个推荐标签，只能从以下标签中选择：[\"稳准\", \"心动\", \"陪伴\", \"常听流派\", \"治愈精选\", \"独家品味\", \"旁支\", \"私心\", \"小冒险\"]；")
            appendLine("4. 保证曲风多样性，同一歌手最多推荐 2 首；")
            appendLine("5. 提供一句整体的今日私选寄语（note，20-60字）；")
            appendLine("6. 必须且仅输出合法的 JSON 格式，不要包含任何前导解释或 Markdown 外的多余文本：")
            appendLine("```json")
            appendLine("{")
            appendLine("  \"note\": \"今日私选寄语\",")
            appendLine("  \"songs\": [")
            appendLine("    {")
            appendLine("      \"id\": 123456,")
            appendLine("      \"reason\": \"推荐理由\",")
            appendLine("      \"tag\": \"稳准\"")
            appendLine("    }")
            appendLine("  ]")
            appendLine("}")
            appendLine("```")
            appendLine()
            appendLine("候选歌曲列表：")
            appendLine(candidatesJson)
        }

        val rawText = if (llmCurator != null) {
            llmCurator.invoke(prompt).orEmpty()
        } else {
            val settings = settingsStore?.settingsFlow?.first() ?: return null
            val manager = providerManager ?: return null

            val assistant = settings.assistants.find { it.id.toString() == assistantId }
                ?: settings.getCurrentAssistant()
            val model = assistant.chatModelId?.let { settings.findModelById(it) }
                ?: settings.getCurrentChatModel()
                ?: return null

            val provider = model.findProvider(settings.providers) ?: return null
            val providerImpl = runCatching { manager.getProviderByType(provider) }.getOrNull() ?: return null

            val result = providerImpl.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text(prompt))
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.OFF,
                    maxTokens = 2048,
                ),
            )

            result.choices.firstOrNull()?.message?.toText()?.trim().orEmpty()
        }
        if (rawText.isBlank()) return null

        val jsonText = extractJsonBlock(rawText)
        val parsed = runCatching {
            json.decodeFromString<LlmCurateResponse>(jsonText)
        }.getOrNull() ?: return null

        val validTags = setOf("稳准", "心动", "陪伴", "常听流派", "治愈精选", "独家品味", "旁支", "私心", "小冒险")
        val curatedSongs = mutableListOf<DailyDiscoverySong>()
        val seenSongIds = mutableSetOf<Long>()
        val artistCounts = mutableMapOf<String, Int>()

        for (item in parsed.songs) {
            val candidate = candidateMap[item.id] ?: continue
            if (!seenSongIds.add(item.id)) continue
            val artistKey = candidate.artists.split("/", ",", "&", " ").firstOrNull()?.trim() ?: candidate.artists
            val currentCount = artistCounts.getOrDefault(artistKey, 0)
            if (currentCount >= 2) continue
            artistCounts[artistKey] = currentCount + 1

            val tag = if (item.tag in validTags) item.tag else "稳准"
            val reason = item.reason.trim().ifBlank { "契合你们最近常听的风格与节奏" }
            curatedSongs.add(
                DailyDiscoverySong(
                    id = candidate.id,
                    name = candidate.name,
                    artist = candidate.artists,
                    album = candidate.album,
                    coverUrl = candidate.coverUrl.orEmpty(),
                    reason = reason,
                    tag = tag,
                    playable = true,
                )
            )
            if (curatedSongs.size >= 20) break
        }

        if (curatedSongs.isEmpty()) return null
        return (parsed.note?.trim()?.takeIf { it.isNotBlank() }) to curatedSongs
    }

    private fun extractJsonBlock(text: String): String {
        val trimmed = text.trim()
        val codeFenceRegex = Regex("""```(?:json)?\s*([\s\S]*?)\s*```""", RegexOption.IGNORE_CASE)
        val match = codeFenceRegex.find(trimmed)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace in 0 until lastBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1).trim()
        }
        return trimmed
    }
}
