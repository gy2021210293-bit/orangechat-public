/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.music

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.plugin.webview.MusicPlayerService
import java.util.UUID

private const val TAG = "MusicSessionManager"

enum class PlaybackLane {
    DIRECT,
    PLAYLIST,
    QUEUE
}

enum class PlayMode {
    SEQUENCE,
    REPEAT_ONE,
    SHUFFLE
}

enum class PlayState {
    STOPPED,
    PLAYING,
    PAUSED,
    BUFFERING
}

data class MusicPlaybackState(
    val currentSong: NeteaseSong? = null,
    val queue: List<NeteaseSong> = emptyList(),
    val cursor: Int = -1,
    val lane: PlaybackLane = PlaybackLane.DIRECT,
    val activePlaylist: NeteasePlaylistDetail? = null,
    val playlistCursor: Int = -1,
    val interruption: MusicInterruption? = null,
    val playMode: PlayMode = PlayMode.SEQUENCE,
    val playState: PlayState = PlayState.STOPPED,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 1.0f,
    val quality: String = "standard",
    val lyrics: SongLyrics? = null,
    val errorMessage: String? = null,
    val lastActor: String = "user",
    val assistantId: String = "",
)

class MusicSessionManager(
    private val context: Context? = null,
    private val musicDao: MusicDao,
    private val client: NeteaseMusicClient,
    private val settingsStore: SettingsStore? = null,
    private val scope: CoroutineScope,
    private val assistantIdFlow: kotlinx.coroutines.flow.Flow<String>? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(MusicPlaybackState())
    val state: StateFlow<MusicPlaybackState> = _state.asStateFlow()

    private var activeAssistantId: String = ""
    private var progressJob: Job? = null
    private var playbackJob: Job? = null
    private var isResuming: Boolean = false

    private data class PersistCommand(
        val targetAssistantId: String,
        val state: MusicPlaybackState,
        val onDone: CompletableDeferred<Unit>? = null,
    )

    private val persistChannel = kotlinx.coroutines.channels.Channel<PersistCommand>(
        kotlinx.coroutines.channels.Channel.UNLIMITED
    )

    init {
        MusicPlayerService.onNextListener = { next(state.value.lastActor) }
        MusicPlayerService.onPreviousListener = { previous(state.value.lastActor) }
        MusicPlayerService.onCompletedListener = { onTrackCompleted() }
        MusicPlayerService.onErrorListener = { onPlaybackError(it) }
        MusicPlayerService.onPlayListener = { onPlaybackStarted() }
        MusicPlayerService.onPauseListener = { onPlaybackPaused() }
        MusicPlayerService.onStopListener = { onPlaybackStopped() }

        scope.launch(Dispatchers.IO) {
            for (cmd in persistChannel) {
                try {
                    persistStateForAssistantSync(cmd.targetAssistantId, cmd.state)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist music session state: ${e.message}", e)
                } finally {
                    cmd.onDone?.complete(Unit)
                }
            }
        }

        val flowToCollect = assistantIdFlow ?: settingsStore?.settingsFlow?.map { it.assistantId.toString() }
        if (flowToCollect != null) {
            scope.launch {
                flowToCollect
                    .distinctUntilChanged()
                    .collect { newAssistantId ->
                        onAssistantChanged(newAssistantId)
                    }
            }
        }
    }

    suspend fun switchAssistant(newAssistantId: String) {
        onAssistantChanged(newAssistantId)
    }

    private suspend fun onAssistantChanged(newAssistantId: String) {
        if (newAssistantId == activeAssistantId) return

        if (activeAssistantId.isNotBlank()) {
            val oldAssistantId = activeAssistantId
            playbackJob?.cancel()
            val finalState = if (_state.value.playState != PlayState.STOPPED) {
                context?.let { MusicPlayerService.stop(it) }
                progressJob?.cancel()
                recordEvent("close", _state.value.lastActor, _state.value.currentSong)
                _state.value.copy(playState = PlayState.STOPPED)
            } else {
                _state.value
            }
            val deferred = CompletableDeferred<Unit>()
            persistChannel.send(PersistCommand(oldAssistantId, finalState, deferred))
            deferred.await()
        }

        activeAssistantId = newAssistantId

        val savedState = withContext(Dispatchers.IO) {
            musicDao.getSessionState(newAssistantId)
        }

        val restoredSong = savedState?.currentSongJson?.let {
            runCatching { json.decodeFromString<NeteaseSong>(it) }.getOrNull()
        }
        val restoredQueue = savedState?.queueJson?.let {
            runCatching { json.decodeFromString<List<NeteaseSong>>(it) }.getOrDefault(emptyList())
        } ?: emptyList()
        val restoredInterruption = savedState?.interruptionJson?.let {
            runCatching { json.decodeFromString<MusicInterruption>(it) }.getOrNull()
        }
        val restoredLane = when (savedState?.playbackLane) {
            "playlist" -> PlaybackLane.PLAYLIST
            "queue" -> PlaybackLane.QUEUE
            else -> PlaybackLane.DIRECT
        }
        val restoredMode = when (savedState?.playMode) {
            "repeat-one" -> PlayMode.REPEAT_ONE
            "shuffle" -> PlayMode.SHUFFLE
            else -> PlayMode.SEQUENCE
        }

        val activePlaylistDetail = savedState?.activePlaylistId?.let { pid ->
            withContext(Dispatchers.IO) {
                musicDao.getPlaylist(newAssistantId, pid)?.let { entity ->
                    val tracks = runCatching { json.decodeFromString<List<NeteaseSong>>(entity.tracksJson) }
                        .getOrDefault(emptyList())
                    NeteasePlaylistDetail(
                        id = entity.playlistId,
                        name = entity.name,
                        coverUrl = entity.coverUrl,
                        trackCount = entity.trackCount,
                        creatorName = "",
                        description = "",
                        tracks = tracks,
                    )
                }
            }
        }

        _state.update {
            it.copy(
                assistantId = newAssistantId,
                currentSong = restoredSong,
                queue = restoredQueue,
                cursor = savedState?.cursor ?: -1,
                lane = restoredLane,
                activePlaylist = activePlaylistDetail,
                playlistCursor = savedState?.playlistCursor ?: -1,
                interruption = restoredInterruption,
                playMode = restoredMode,
                playState = PlayState.STOPPED, // 重启/切换不自动播放陈旧会话
                currentPositionMs = savedState?.positionMs ?: 0L,
                durationMs = savedState?.durationMs ?: 0L,
                errorMessage = null,
                lastActor = savedState?.lastActor ?: "user",
            )
        }

        if (restoredSong != null) {
            loadLyrics(restoredSong.id)
        }
    }

    fun playDirect(song: NeteaseSong, actor: String = "user") {
        isResuming = false
        playbackJob?.cancel()
        playbackJob = scope.launch {
            val currentState = _state.value
            val interruption = if (currentState.lane != PlaybackLane.DIRECT && currentState.currentSong != null) {
                MusicInterruption(
                    lane = if (currentState.lane == PlaybackLane.PLAYLIST) "playlist" else "queue",
                    activePlaylistId = currentState.activePlaylist?.id,
                    cursor = if (currentState.lane == PlaybackLane.PLAYLIST) currentState.playlistCursor else currentState.cursor,
                    song = currentState.currentSong,
                )
            } else {
                currentState.interruption
            }

            _state.update {
                it.copy(
                    currentSong = song,
                    lane = PlaybackLane.DIRECT,
                    interruption = interruption,
                    playState = PlayState.BUFFERING,
                    errorMessage = null,
                    lastActor = actor,
                    currentPositionMs = 0L,
                    durationMs = 0L,
                    lyrics = null,
                )
            }

            startPlaybackInternal(song, actor)
        }
    }

    fun playFromLane(lane: PlaybackLane, index: Int, actor: String = "user") {
        isResuming = false
        playbackJob?.cancel()
        playbackJob = scope.launch {
            val currentState = _state.value
            val targetSong = when (lane) {
                PlaybackLane.QUEUE -> currentState.queue.getOrNull(index)
                PlaybackLane.PLAYLIST -> currentState.activePlaylist?.tracks?.getOrNull(index)
                PlaybackLane.DIRECT -> currentState.currentSong
            }

            if (targetSong == null) {
                _state.update { it.copy(errorMessage = "未找到指定歌曲") }
                return@launch
            }

            _state.update {
                it.copy(
                    currentSong = targetSong,
                    lane = lane,
                    cursor = if (lane == PlaybackLane.QUEUE) index else it.cursor,
                    playlistCursor = if (lane == PlaybackLane.PLAYLIST) index else it.playlistCursor,
                    interruption = null,
                    playState = PlayState.BUFFERING,
                    errorMessage = null,
                    lastActor = actor,
                    currentPositionMs = 0L,
                    durationMs = 0L,
                    lyrics = null,
                )
            }

            startPlaybackInternal(targetSong, actor)
        }
    }

    fun next(actor: String = "user") {
        val currentState = _state.value
        when (currentState.playMode) {
            PlayMode.REPEAT_ONE -> {
                when (currentState.lane) {
                    PlaybackLane.PLAYLIST -> {
                        val tracks = currentState.activePlaylist?.tracks ?: emptyList()
                        if (currentState.playlistCursor in tracks.indices) {
                            playFromLane(PlaybackLane.PLAYLIST, currentState.playlistCursor, actor)
                        } else {
                            currentState.currentSong?.let { replayCurrentSong(it, actor) }
                        }
                    }
                    PlaybackLane.QUEUE -> {
                        if (currentState.cursor in currentState.queue.indices) {
                            playFromLane(PlaybackLane.QUEUE, currentState.cursor, actor)
                        } else {
                            currentState.currentSong?.let { replayCurrentSong(it, actor) }
                        }
                    }
                    PlaybackLane.DIRECT -> {
                        currentState.currentSong?.let { replayCurrentSong(it, actor) }
                    }
                }
            }
            PlayMode.SHUFFLE -> {
                val candidateList = when (currentState.lane) {
                    PlaybackLane.QUEUE -> currentState.queue
                    PlaybackLane.PLAYLIST -> currentState.activePlaylist?.tracks ?: emptyList()
                    PlaybackLane.DIRECT -> currentState.queue
                }
                if (candidateList.isNotEmpty()) {
                    val currentCursor = if (currentState.lane == PlaybackLane.PLAYLIST) currentState.playlistCursor else currentState.cursor
                    val nextIndex = candidateList.indices.filter { it != currentCursor }.randomOrNull() ?: 0
                    if (currentState.lane == PlaybackLane.PLAYLIST) {
                        playFromLane(PlaybackLane.PLAYLIST, nextIndex, actor)
                    } else {
                        playFromLane(PlaybackLane.QUEUE, nextIndex, actor)
                    }
                }
            }
            PlayMode.SEQUENCE -> {
                when (currentState.lane) {
                    PlaybackLane.QUEUE -> {
                        val nextIndex = currentState.cursor + 1
                        if (nextIndex < currentState.queue.size) {
                            playFromLane(PlaybackLane.QUEUE, nextIndex, actor)
                        } else {
                            recordEvent("finish", actor, currentState.currentSong)
                            stop()
                        }
                    }
                    PlaybackLane.PLAYLIST -> {
                        val nextIndex = currentState.playlistCursor + 1
                        val tracks = currentState.activePlaylist?.tracks ?: emptyList()
                        if (nextIndex < tracks.size) {
                            playFromLane(PlaybackLane.PLAYLIST, nextIndex, actor)
                        } else {
                            recordEvent("finish", actor, currentState.currentSong)
                            stop()
                        }
                    }
                    PlaybackLane.DIRECT -> {
                        val interruption = currentState.interruption
                        if (interruption != null) {
                            val lane = if (interruption.lane == "playlist") PlaybackLane.PLAYLIST else PlaybackLane.QUEUE
                            playFromLane(lane, (interruption.cursor + 1).coerceAtLeast(0), actor)
                        } else if (currentState.queue.isNotEmpty()) {
                            playFromLane(PlaybackLane.QUEUE, 0, actor)
                        } else {
                            stop()
                        }
                    }
                }
            }
        }
    }

    fun previous(actor: String = "user") {
        val currentState = _state.value
        if (currentState.currentPositionMs > 3000L) {
            seekTo(0L)
            return
        }

        when (currentState.lane) {
            PlaybackLane.QUEUE -> {
                val prevIndex = currentState.cursor - 1
                if (prevIndex >= 0 && prevIndex < currentState.queue.size) {
                    playFromLane(PlaybackLane.QUEUE, prevIndex, actor)
                } else {
                    seekTo(0L)
                }
            }
            PlaybackLane.PLAYLIST -> {
                val prevIndex = currentState.playlistCursor - 1
                val tracks = currentState.activePlaylist?.tracks ?: emptyList()
                if (prevIndex >= 0 && prevIndex < tracks.size) {
                    playFromLane(PlaybackLane.PLAYLIST, prevIndex, actor)
                } else {
                    seekTo(0L)
                }
            }
            PlaybackLane.DIRECT -> {
                seekTo(0L)
            }
        }
    }

    fun pause() {
        isResuming = false
        context?.let { MusicPlayerService.pause(it) }
        _state.update { it.copy(playState = PlayState.PAUSED) }
        recordEvent("pause", _state.value.lastActor, _state.value.currentSong)
        persistState()
    }

    fun resume() {
        val current = _state.value.currentSong
        if (current != null && _state.value.playState == PlayState.PAUSED) {
            isResuming = true
            context?.let { MusicPlayerService.resume(it) }
            _state.update { it.copy(playState = PlayState.BUFFERING) }
            persistState()
        } else if (current != null && _state.value.playState == PlayState.STOPPED) {
            isResuming = false
            playDirect(current, _state.value.lastActor)
        }
    }

    fun stop() {
        isResuming = false
        playbackJob?.cancel()
        context?.let { MusicPlayerService.stop(it) }
        progressJob?.cancel()
        _state.update {
            it.copy(
                playState = PlayState.STOPPED,
                currentPositionMs = 0L,
            )
        }
        recordEvent("close", _state.value.lastActor, _state.value.currentSong)
        persistState()
    }

    fun seekTo(positionMs: Long) {
        context?.let { MusicPlayerService.seekTo(it, positionMs.toInt()) }
        _state.update { it.copy(currentPositionMs = positionMs) }
        persistState()
    }

    fun setPlayMode(mode: PlayMode) {
        _state.update { it.copy(playMode = mode) }
        persistState()
    }

    fun setQuality(quality: String) {
        _state.update { it.copy(quality = quality) }
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _state.update { it.copy(volume = clamped) }
    }

    fun addToQueue(song: NeteaseSong) {
        _state.update {
            val updatedQueue = it.queue + song
            it.copy(queue = updatedQueue)
        }
        persistState()
    }

    fun removeFromQueue(index: Int) {
        _state.update {
            if (index !in it.queue.indices) return@update it
            val updated = it.queue.toMutableList().apply { removeAt(index) }
            val newCursor = when {
                it.cursor == index -> -1
                it.cursor > index -> it.cursor - 1
                else -> it.cursor
            }
            it.copy(queue = updated, cursor = newCursor)
        }
        persistState()
    }

    fun clearQueue() {
        _state.update {
            it.copy(queue = emptyList(), cursor = -1)
        }
        persistState()
    }

    fun playAll(songs: List<NeteaseSong>, startIndex: Int = 0, actor: String = "user") {
        if (songs.isEmpty()) return
        val clampedIndex = startIndex.coerceIn(0, songs.lastIndex)
        _state.update {
            it.copy(
                queue = songs,
                cursor = clampedIndex,
                lane = PlaybackLane.QUEUE,
                interruption = null,
            )
        }
        playFromLane(PlaybackLane.QUEUE, clampedIndex, actor)
    }

    fun setActivePlaylist(playlist: NeteasePlaylistDetail) {
        _state.update {
            it.copy(activePlaylist = playlist, playlistCursor = -1)
        }
        persistState()
    }

    fun onTrackCompleted() {
        val currentState = _state.value
        if (currentState.playState == PlayState.STOPPED || currentState.currentSong == null) {
            return
        }
        Log.i(TAG, "Track completed: ${currentState.currentSong.name}")
        next(currentState.lastActor)
    }

    fun onPlaybackStarted() {
        val current = _state.value
        if (current.playState == PlayState.PLAYING) return
        if (current.currentSong == null || (current.playState == PlayState.STOPPED && !isResuming)) {
            return
        }
        val isResume = isResuming || current.playState == PlayState.PAUSED
        isResuming = false
        val eventType = if (isResume) "resume" else "play"
        _state.update {
            it.copy(
                playState = PlayState.PLAYING,
                errorMessage = null,
            )
        }
        recordEvent(eventType, current.lastActor, current.currentSong)
        persistState()
        startProgressTracker()
    }

    fun onPlaybackPaused() {
        isResuming = false
        val current = _state.value
        if (current.playState == PlayState.PAUSED || current.playState == PlayState.STOPPED || current.currentSong == null) {
            return
        }
        _state.update { it.copy(playState = PlayState.PAUSED) }
        recordEvent("pause", current.lastActor, current.currentSong)
        persistState()
    }

    fun onPlaybackStopped() {
        isResuming = false
        if (_state.value.playState == PlayState.STOPPED) return
        progressJob?.cancel()
        _state.update {
            it.copy(
                playState = PlayState.STOPPED,
                currentPositionMs = 0L,
            )
        }
        recordEvent("close", _state.value.lastActor, _state.value.currentSong)
        persistState()
    }

    fun onPlaybackError(message: String) {
        isResuming = false
        if (_state.value.playState == PlayState.STOPPED && _state.value.currentSong == null) return
        progressJob?.cancel()
        _state.update {
            it.copy(
                playState = PlayState.STOPPED,
                errorMessage = message,
            )
        }
        persistState()
    }

    private fun replayCurrentSong(song: NeteaseSong, actor: String) {
        isResuming = false
        playbackJob?.cancel()
        playbackJob = scope.launch {
            _state.update {
                it.copy(
                    currentSong = song,
                    playState = PlayState.BUFFERING,
                    errorMessage = null,
                    lastActor = actor,
                    currentPositionMs = 0L,
                    durationMs = 0L,
                    lyrics = null,
                )
            }
            startPlaybackInternal(song, actor)
        }
    }

    private suspend fun startPlaybackInternal(song: NeteaseSong, actor: String) {
        try {
            val audioUrl = client.getPlayableUrl(song.id, _state.value.quality)
            context?.let {
                MusicPlayerService.playUri(
                    it,
                    audioUrl,
                    song.name,
                    song.artists,
                )
            }
            persistState()
            loadLyrics(song.id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed for song ${song.id}: ${e.message}", e)
            _state.update {
                it.copy(
                    playState = PlayState.STOPPED,
                    errorMessage = e.message ?: "播放失败",
                )
            }
            persistState()
        }
    }

    private fun loadLyrics(songId: Long) {
        scope.launch {
            try {
                val lyrics = client.getLyrics(songId)
                if (_state.value.currentSong?.id == songId) {
                    _state.update { it.copy(lyrics = lyrics) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load lyrics for $songId: ${e.message}")
            }
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                val isShared = MusicPlayerService.getCurrentClient() == MusicPlayerService.CLIENT_SHARED_LISTENING
                val isServicePlaying = MusicPlayerService.isPlaying()
                val pos = MusicPlayerService.getCurrentPosition().toLong()
                val dur = MusicPlayerService.getDuration().toLong()
                if ((isServicePlaying && isShared) || _state.value.playState == PlayState.PLAYING) {
                    _state.update {
                        it.copy(
                            currentPositionMs = pos,
                            durationMs = if (dur > 0L) dur else it.durationMs,
                        )
                    }
                } else if (_state.value.playState == PlayState.STOPPED) {
                    break
                }
            }
        }
    }

    private fun recordEvent(eventType: String, actor: String, song: NeteaseSong?) {
        val currentAssistant = activeAssistantId.ifBlank { return }
        if (song == null) return
        scope.launch(Dispatchers.IO) {
            val event = MusicEventEntity(
                assistantId = currentAssistant,
                eventType = eventType,
                actor = actor,
                songId = song.id,
                songName = song.name,
                songArtist = song.artists,
                songAlbum = song.album,
                playlistId = _state.value.activePlaylist?.id,
                playlistName = _state.value.activePlaylist?.name,
                note = null,
                createdAt = System.currentTimeMillis(),
            )
            musicDao.recordEvent(event)
        }
    }

    private fun buildSessionStateEntity(targetAssistantId: String, s: MusicPlaybackState): MusicSessionStateEntity {
        return MusicSessionStateEntity(
            assistantId = targetAssistantId,
            currentSongJson = s.currentSong?.let { json.encodeToString(it) },
            queueJson = json.encodeToString(s.queue),
            cursor = s.cursor,
            playbackLane = when (s.lane) {
                PlaybackLane.PLAYLIST -> "playlist"
                PlaybackLane.QUEUE -> "queue"
                PlaybackLane.DIRECT -> "direct"
            },
            activePlaylistId = s.activePlaylist?.id,
            playlistCursor = s.playlistCursor,
            interruptionJson = s.interruption?.let { json.encodeToString(it) },
            playMode = when (s.playMode) {
                PlayMode.REPEAT_ONE -> "repeat-one"
                PlayMode.SHUFFLE -> "shuffle"
                PlayMode.SEQUENCE -> "sequence"
            },
            lastState = when (s.playState) {
                PlayState.PLAYING -> "playing"
                PlayState.PAUSED -> "paused"
                PlayState.BUFFERING -> "buffering"
                PlayState.STOPPED -> "stopped"
            },
            lastActor = s.lastActor,
            positionMs = s.currentPositionMs,
            durationMs = s.durationMs,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun persistStateForAssistantSync(targetAssistantId: String, s: MusicPlaybackState) {
        if (targetAssistantId.isBlank()) return
        val entity = buildSessionStateEntity(targetAssistantId, s)
        musicDao.saveSessionState(entity)
    }

    private fun persistStateForAssistant(targetAssistantId: String, s: MusicPlaybackState) {
        if (targetAssistantId.isBlank()) return
        persistChannel.trySend(PersistCommand(targetAssistantId, s))
    }

    private fun persistState() {
        persistStateForAssistant(activeAssistantId, _state.value)
    }
}
