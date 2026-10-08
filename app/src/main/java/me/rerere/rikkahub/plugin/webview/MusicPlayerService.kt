/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.webview

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import me.rerere.rikkahub.MUSIC_PLAYER_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.music.MusicCredentialStore
import java.io.File

private const val TAG = "MusicPlayerService"
private const val NOTIFICATION_ID = 2001

private const val ACTION_PLAY = "me.rerere.rikkahub.MUSIC_PLAY"
private const val ACTION_PAUSE = "me.rerere.rikkahub.MUSIC_PAUSE"
private const val ACTION_RESUME = "me.rerere.rikkahub.MUSIC_RESUME"
private const val ACTION_STOP = "me.rerere.rikkahub.MUSIC_STOP"
private const val ACTION_PREVIOUS = "me.rerere.rikkahub.MUSIC_PREVIOUS"
private const val ACTION_NEXT = "me.rerere.rikkahub.MUSIC_NEXT"
private const val EXTRA_FILE_PATH = "filePath"
private const val EXTRA_MEDIA_URI = "mediaUri"
private const val EXTRA_TITLE = "title"
private const val EXTRA_ARTIST = "artist"
private const val EXTRA_CLIENT = "client"

class MusicPlayerService : Service() {

    companion object {
        const val CLIENT_SHARED_LISTENING = "shared_listening"
        const val CLIENT_PLUGIN = "plugin"

        const val ACTION_MUSIC_COMPLETED = "me.rerere.rikkahub.MUSIC_COMPLETED"
        const val ACTION_MUSIC_ERROR = "me.rerere.rikkahub.MUSIC_ERROR"
        const val EXTRA_ERROR_MESSAGE = "errorMessage"
        private const val ACTION_SEEK = "me.rerere.rikkahub.MUSIC_SEEK"
        private const val EXTRA_POSITION = "position"

        // 所有共享可变状态的读写都必须经过这把锁：onStartCommand 处理的各种 action、
        // ExoPlayer 的播放状态/错误回调、onDestroy，可能在不同时间点并发触碰这些字段
        private val stateLock = Any()

        private var exoPlayer: ExoPlayer? = null
        private var mediaSession: MediaSession? = null

        @Volatile
        var onNextListener: (() -> Unit)? = null
        @Volatile
        var onPreviousListener: (() -> Unit)? = null
        @Volatile
        var onCompletedListener: (() -> Unit)? = null
        @Volatile
        var onErrorListener: ((String) -> Unit)? = null
        @Volatile
        var onPlayListener: (() -> Unit)? = null
        @Volatile
        var onPauseListener: (() -> Unit)? = null
        @Volatile
        var onStopListener: (() -> Unit)? = null
        private var currentState: Int = STATE_STOPPED
        private var currentTitle: String = ""
        private var currentArtist: String = ""
        private var currentFilePath: String = ""
        private var currentClient: String = ""

        private const val STATE_PLAYING = 1
        private const val STATE_PAUSED = 2
        private const val STATE_STOPPED = 3
        private const val STATE_BUFFERING = 4

        fun play(context: Context, filePath: String, title: String, artist: String, client: String = CLIENT_PLUGIN) {
            val intent = Intent(context, MusicPlayerService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_CLIENT, client)
            }
            context.startForegroundService(intent)
        }

        fun playUri(context: Context, mediaUri: String, title: String, artist: String, client: String = CLIENT_SHARED_LISTENING) {
            val parsed = runCatching { Uri.parse(mediaUri) }.getOrNull()
            require((parsed?.scheme == "https" || parsed?.scheme == "http") && !parsed.host.isNullOrBlank()) {
                "Only valid HTTP/HTTPS music streams are supported"
            }
            val intent = Intent(context, MusicPlayerService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_MEDIA_URI, mediaUri)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
                putExtra(EXTRA_CLIENT, client)
            }
            context.startForegroundService(intent)
        }

        fun pause(context: Context) {
            val intent = Intent(context, MusicPlayerService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, MusicPlayerService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MusicPlayerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun next(context: Context) {
            val isShared = synchronized(stateLock) { currentClient == CLIENT_SHARED_LISTENING }
            if (isShared) {
                onNextListener?.invoke() ?: run {
                    val intent = Intent(context, MusicPlayerService::class.java).apply {
                        action = ACTION_NEXT
                    }
                    context.startService(intent)
                }
            }
        }

        fun previous(context: Context) {
            val isShared = synchronized(stateLock) { currentClient == CLIENT_SHARED_LISTENING }
            if (isShared) {
                onPreviousListener?.invoke() ?: run {
                    val intent = Intent(context, MusicPlayerService::class.java).apply {
                        action = ACTION_PREVIOUS
                    }
                    context.startService(intent)
                }
            }
        }

        fun isPlaying(): Boolean = synchronized(stateLock) { currentState == STATE_PLAYING }

        fun getCurrentClient(): String = synchronized(stateLock) { currentClient }

        fun getState(): String = synchronized(stateLock) {
            when (currentState) {
                STATE_PLAYING -> "playing"
                STATE_PAUSED -> "paused"
                STATE_BUFFERING -> "buffering"
                else -> "stopped"
            }
        }

        fun getNowPlaying(): Map<String, String> = synchronized(stateLock) {
            mapOf(
                "title" to currentTitle,
                "artist" to currentArtist,
                "filePath" to currentFilePath,
                "state" to getState(),
                "client" to currentClient,
            )
        }

        fun setClientForTesting(client: String) {
            synchronized(stateLock) {
                currentClient = client
            }
        }

        fun resetForTesting() {
            synchronized(stateLock) {
                currentState = STATE_STOPPED
                currentTitle = ""
                currentArtist = ""
                currentFilePath = ""
                currentClient = ""
                onNextListener = null
                onPreviousListener = null
                onCompletedListener = null
                onErrorListener = null
                onPlayListener = null
                onPauseListener = null
                onStopListener = null
            }
        }

        fun seekTo(context: Context, position: Int) {
            val intent = Intent(context, MusicPlayerService::class.java).apply {
                action = ACTION_SEEK
                putExtra(EXTRA_POSITION, position)
            }
            context.startService(intent)
        }

        // ExoPlayer 的 currentPosition/duration 本身是 Long（毫秒），且只能在创建它的那个线程
        // （这里是 Service 所在的主线程）访问，不能跨线程调用，这点跟原来 MediaPlayer 比要求更严格
        fun getCurrentPosition(): Int = synchronized(stateLock) {
            try {
                exoPlayer?.currentPosition?.toInt() ?: 0
            } catch (e: Exception) {
                Log.e(TAG, "getCurrentPosition failed, err=${e.message}", e)
                0
            }
        }

        fun getDuration(): Int = synchronized(stateLock) {
            try {
                val d = exoPlayer?.duration ?: 0L
                // 播放器还没真正 ready 时，duration 会是 C.TIME_UNSET（一个特殊的很大负数），
                // 不能直接转 Int 返回给前端，否则会显示一个荒谬的数字
                if (d == C.TIME_UNSET || d < 0) 0 else d.toInt()
            } catch (e: Exception) {
                Log.e(TAG, "getDuration failed, err=${e.message}", e)
                0
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须最先调用 startForeground()，否则 Android 12+ 会抛出 ForegroundServiceStartNotAllowedException
        startForeground(NOTIFICATION_ID, buildNotification(currentTitle.ifEmpty { "Music" }, currentArtist))

        when (intent?.action) {
            ACTION_PLAY -> {
                val client = intent.getStringExtra(EXTRA_CLIENT) ?: run {
                    if (intent.hasExtra(EXTRA_MEDIA_URI)) CLIENT_SHARED_LISTENING else CLIENT_PLUGIN
                }
                val source = intent.getStringExtra(EXTRA_MEDIA_URI)
                    ?: intent.getStringExtra(EXTRA_FILE_PATH)
                    ?: ""
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
                if (source.isNotEmpty()) {
                    startPlayback(source, title, artist, client)
                }
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> resumePlayback()
            ACTION_STOP -> stopPlayback()
            ACTION_PREVIOUS -> {
                val isShared = synchronized(stateLock) { currentClient == CLIENT_SHARED_LISTENING }
                if (isShared) onPreviousListener?.invoke()
            }
            ACTION_NEXT -> {
                val isShared = synchronized(stateLock) { currentClient == CLIENT_SHARED_LISTENING }
                if (isShared) onNextListener?.invoke()
            }
            ACTION_SEEK -> {
                val position = intent.getIntExtra(EXTRA_POSITION, 0)
                seekPlayback(position)
            }
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(source: String, title: String, artist: String, client: String) {
        var startedOk = false
        var startupErrorMessage: String? = null
        var shouldStopPreviousSharedListening = false

        synchronized(stateLock) {
            if (currentClient == CLIENT_SHARED_LISTENING && client != CLIENT_SHARED_LISTENING) {
                shouldStopPreviousSharedListening = true
            }
            currentClient = client

            try {
                exoPlayer?.release()
            } catch (_: Exception) {}
            exoPlayer = null

            currentTitle = title
            currentArtist = artist
            currentFilePath = source
        }

        if (shouldStopPreviousSharedListening) {
            onStopListener?.invoke()
        }

        synchronized(stateLock) {
            try {
                val sourceHost = Uri.parse(source).host.orEmpty().lowercase()
                val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
                    setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
                    val headers = mutableMapOf<String, String>()
                    if (client == CLIENT_SHARED_LISTENING && source.startsWith("https://", ignoreCase = true) &&
                        (sourceHost == "music.163.com" || sourceHost.endsWith(".music.163.com"))
                    ) {
                        headers["Referer"] = "https://music.163.com/"
                        MusicCredentialStore(applicationContext).readCookie()?.let { headers["Cookie"] = it }
                    }
                    setDefaultRequestProperties(headers)
                }
                val dataSourceFactory = DefaultDataSource.Factory(applicationContext, httpDataSourceFactory)
                val player = ExoPlayer.Builder(applicationContext)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                    .build()

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build()
                player.setAudioAttributes(audioAttributes, true)

                // ExoPlayer 的 prepare() 是异步的：调用后立即返回，真正"是否能正常播放/是否出错"
                // 都要靠下面这个监听器异步上报，不再像原来 MediaPlayer 那样能在同一行代码里 try-catch 拿到结果。
                // STATE_ENDED 是 ExoPlayer 官方标准的"播放到底了"信号，比原来 MediaPlayer 的 onCompletion 更可靠。
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        val (state, isShared) = synchronized(stateLock) {
                            if (exoPlayer !== player) return
                            currentState = when {
                                isPlaying -> STATE_PLAYING
                                !player.playWhenReady && player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED -> STATE_PAUSED
                                player.playWhenReady && player.playbackState == Player.STATE_BUFFERING -> STATE_BUFFERING
                                player.playbackState == Player.STATE_READY -> STATE_PAUSED
                                else -> currentState
                            }
                            currentState to (currentClient == CLIENT_SHARED_LISTENING)
                        }
                        showNotification(currentTitle, currentArtist)
                        if (state == STATE_STOPPED) stopForeground(STOP_FOREGROUND_REMOVE)
                        if (isShared) {
                            if (isPlaying) {
                                onPlayListener?.invoke()
                            } else if (state == STATE_PAUSED) {
                                onPauseListener?.invoke()
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            var shouldNotify = false
                            var isShared = false
                            synchronized(stateLock) {
                                // 过期检查：如果这个回调对应的播放器已经不是当前播放器了，就什么都不做，
                                // 避免把新歌的状态错误地改回"已停止"
                                if (exoPlayer === player) {
                                    currentState = STATE_STOPPED
                                    shouldNotify = true
                                    isShared = (currentClient == CLIENT_SHARED_LISTENING)
                                }
                            }
                            if (shouldNotify) {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                LocalBroadcastManager.getInstance(this@MusicPlayerService)
                                    .sendBroadcast(Intent(ACTION_MUSIC_COMPLETED))
                                if (isShared) {
                                    onCompletedListener?.invoke()
                                }
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val httpStatus = generateSequence(error as Throwable?) { it.cause }
                            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                            .firstOrNull()?.responseCode
                        val errorMessage = if (httpStatus != null) "播放失败：音源 HTTP $httpStatus（$sourceHost）"
                            else "播放失败：${error.errorCodeName}"
                        Log.e(
                            TAG,
                            "ExoPlayer error: ${error.errorCodeName}, title=$title",
                            error
                        )
                        var shouldStopForeground = false
                        var isShared = false
                        synchronized(stateLock) {
                            if (exoPlayer === player) {
                                currentState = STATE_STOPPED
                                exoPlayer = null
                                shouldStopForeground = true
                                isShared = (currentClient == CLIENT_SHARED_LISTENING)
                            }
                        }
                        if (shouldStopForeground) stopForeground(STOP_FOREGROUND_REMOVE)
                        if (shouldStopForeground) {
                            LocalBroadcastManager.getInstance(this@MusicPlayerService)
                                .sendBroadcast(Intent(ACTION_MUSIC_ERROR).putExtra(EXTRA_ERROR_MESSAGE, errorMessage))
                            if (isShared) {
                                onErrorListener?.invoke(errorMessage)
                            }
                        }
                    }
                })

                val parsedUri = Uri.parse(source)
                val playbackUri = if (parsedUri.scheme.isNullOrBlank()) Uri.fromFile(File(source)) else parsedUri
                val metadata = MediaMetadata.Builder()
                    .setTitle(title.ifEmpty { "一起听歌" })
                    .setArtist(artist.ifEmpty { "OrangeChat" })
                    .build()
                val mediaItem = MediaItem.Builder()
                    .setUri(playbackUri)
                    .setMediaMetadata(metadata)
                    .build()
                player.setMediaItem(mediaItem)

                try {
                    mediaSession?.release()
                    mediaSession = MediaSession.Builder(this@MusicPlayerService, player)
                        .setId("OrangeChatMusicSession")
                        .setCallback(object : MediaSession.Callback {
                            override fun onMediaButtonEvent(
                                session: MediaSession,
                                controllerInfo: MediaSession.ControllerInfo,
                                intent: Intent
                            ): Boolean {
                                val ke = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                                } else {
                                    @Suppress("DEPRECATION")
                                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                                }
                                if (ke != null && ke.action == KeyEvent.ACTION_DOWN) {
                                    val isShared = synchronized(stateLock) { currentClient == CLIENT_SHARED_LISTENING }
                                    if (isShared) {
                                        when (ke.keyCode) {
                                            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                                onNextListener?.invoke()
                                                return true
                                            }
                                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                                onPreviousListener?.invoke()
                                                return true
                                            }
                                        }
                                    }
                                }
                                return super.onMediaButtonEvent(session, controllerInfo, intent)
                            }
                        })
                        .build()
                } catch (e: Exception) {
                    Log.w(TAG, "MediaSession initialization error: ${e.message}")
                }
                exoPlayer = player
                currentState = STATE_BUFFERING
                player.prepare()
                player.play()

                startedOk = true
                Log.i(TAG, "Playback started: $artist - $title")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start playback: ${e.message}", e)
                currentState = STATE_STOPPED
                startupErrorMessage = "播放失败：${e.message ?: "播放器初始化失败"}"
            }
        }

        if (startedOk) {
            showNotification(title, artist)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (client == CLIENT_SHARED_LISTENING) {
                onErrorListener?.invoke(startupErrorMessage ?: "播放失败：播放器初始化失败")
            }
            stopSelf()
        }
    }

    private fun pausePlayback() {
        var shouldNotify = false
        var isShared = false
        synchronized(stateLock) {
            try {
                exoPlayer?.let {
                    if (currentState != STATE_PAUSED && currentState != STATE_STOPPED) {
                        it.pause()
                        currentState = STATE_PAUSED
                        shouldNotify = true
                        isShared = (currentClient == CLIENT_SHARED_LISTENING)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pause error: ${e.message}", e)
            }
        }
        if (shouldNotify) {
            showNotification(currentTitle, currentArtist)
            if (isShared) {
                onPauseListener?.invoke()
            }
        }
    }

    private fun resumePlayback() {
        var shouldUpdateNotification = false
        synchronized(stateLock) {
            try {
                exoPlayer?.let {
                    if (!it.isPlaying && currentState == STATE_PAUSED) {
                        it.play()
                        currentState = STATE_BUFFERING
                        shouldUpdateNotification = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Resume error: ${e.message}", e)
            }
        }
        if (shouldUpdateNotification) {
            showNotification(currentTitle, currentArtist)
        }
    }

    private fun stopPlayback() {
        var isShared = false
        synchronized(stateLock) {
            try {
                exoPlayer?.let {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
            } catch (_: Exception) {}
            exoPlayer = null
            try {
                mediaSession?.release()
            } catch (_: Exception) {}
            mediaSession = null
            currentState = STATE_STOPPED
            currentTitle = ""
            currentArtist = ""
            currentFilePath = ""
            isShared = (currentClient == CLIENT_SHARED_LISTENING)
            currentClient = ""
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (isShared) {
            onStopListener?.invoke()
        }
        stopSelf()
    }

    private fun seekPlayback(position: Int) {
        synchronized(stateLock) {
            try {
                exoPlayer?.seekTo(position.toLong())
            } catch (e: Exception) {
                Log.e(TAG, "Seek failed, position=$position, err=${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        var isShared = false
        synchronized(stateLock) {
            try {
                exoPlayer?.let {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
            } catch (_: Exception) {}
            exoPlayer = null
            try {
                mediaSession?.release()
            } catch (_: Exception) {}
            mediaSession = null
            currentState = STATE_STOPPED
            isShared = (currentClient == CLIENT_SHARED_LISTENING)
            currentClient = ""
        }
        if (isShared) {
            onStopListener?.invoke()
        }
        super.onDestroy()
    }

    private fun buildNotification(title: String, artist: String): android.app.Notification {
        val launchPendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, MusicPlayerService::class.java).apply {
            action = ACTION_STOP
        }
        val prevIntent = Intent(this, MusicPlayerService::class.java).apply { action = ACTION_PREVIOUS }
        val prevPendingIntent = PendingIntent.getService(
            this, 10, prevIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseAction = if (currentState == STATE_PLAYING) ACTION_PAUSE else ACTION_RESUME
        val playPauseIntent = Intent(this, MusicPlayerService::class.java).apply { action = playPauseAction }
        val playPausePendingIntent = PendingIntent.getService(
            this, 11, playPauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nextIntent = Intent(this, MusicPlayerService::class.java).apply { action = ACTION_NEXT }
        val nextPendingIntent = PendingIntent.getService(
            this, 12, nextIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, MUSIC_PLAYER_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("🎵 $title")
            .setContentText(artist)
            .setSmallIcon(R.drawable.small_icon)
            .setContentIntent(launchPendingIntent)
            .addAction(android.R.drawable.ic_media_previous, "上一首", prevPendingIntent)
            .addAction(
                if (currentState == STATE_PLAYING) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (currentState == STATE_PLAYING) "暂停" else "播放",
                playPausePendingIntent
            )
            .addAction(android.R.drawable.ic_media_next, "下一首", nextPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", stopPendingIntent)
            .setOngoing(currentState == STATE_PLAYING)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                mediaSession?.let { session ->
                    setStyle(
                        MediaStyleNotificationHelper.MediaStyle(session)
                            .setShowActionsInCompactView(0, 1, 2)
                    )
                }
            }
            .build()
    }

    private fun showNotification(title: String, artist: String) {
        val notification = buildNotification(title, artist)

        if (currentState == STATE_PLAYING) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        }
    }
}
