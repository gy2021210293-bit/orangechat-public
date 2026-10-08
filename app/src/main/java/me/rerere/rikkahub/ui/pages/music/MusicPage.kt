/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.MusicNote01
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Next
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Previous
import me.rerere.hugeicons.stroke.Repeat
import me.rerere.hugeicons.stroke.RepeatOne01
import me.rerere.hugeicons.stroke.Shuffle
import me.rerere.hugeicons.stroke.Stop
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.music.DailyDiscoveryService
import me.rerere.rikkahub.data.music.DailyDiscoverySong
import me.rerere.rikkahub.data.music.LyricLine
import me.rerere.rikkahub.data.music.MusicDao
import me.rerere.rikkahub.data.music.MusicPlaybackState
import me.rerere.rikkahub.data.music.MusicPlaylistEntity
import me.rerere.rikkahub.data.music.MusicSessionManager
import me.rerere.rikkahub.data.music.NeteaseMusicClient
import me.rerere.rikkahub.data.music.NeteasePlaylist
import me.rerere.rikkahub.data.music.NeteaseSong
import me.rerere.rikkahub.data.music.PlayMode
import me.rerere.rikkahub.data.music.PlayState
import me.rerere.rikkahub.data.music.PlaybackLane
import me.rerere.rikkahub.ui.context.LocalCurrentAssistant
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPage() {
    val navController = LocalNavController.current
    val currentAssistant = LocalCurrentAssistant.current
    val assistantId = currentAssistant.id.toString()
    val assistantName = currentAssistant.name

    val sessionManager: MusicSessionManager = koinInject()
    val musicDao: MusicDao = koinInject()
    val musicClient: NeteaseMusicClient = koinInject()
    val dailyDiscoveryService: DailyDiscoveryService = koinInject()
    val json: Json = koinInject()

    val playbackState by sessionManager.state.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var openedPlaylistId by remember { mutableStateOf<Long?>(null) }
    val tabs = listOf("当前队列", "我的歌单", "今日私选")
    BackHandler(enabled = openedPlaylistId != null) { openedPlaylistId = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (openedPlaylistId == null) "一起听歌" else "歌单详情",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "与 $assistantName 共听",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (openedPlaylistId != null) openedPlaylistId = null else navController.popBackStack()
                    }) {
                        Icon(imageVector = HugeIcons.ArrowLeft02, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            CompactPlayerBar(
                playbackState = playbackState,
                onOpen = { navController.navigate(Screen.MusicPlayer) },
                onPlayPause = {
                    if (playbackState.playState == PlayState.PLAYING) sessionManager.pause()
                    else sessionManager.resume()
                },
                onNext = { sessionManager.next() },
            )

            val playlistId = openedPlaylistId
            if (playlistId != null) {
                PlaylistDetailView(
                    assistantId = assistantId,
                    playlistId = playlistId,
                    musicDao = musicDao,
                    musicClient = musicClient,
                    sessionManager = sessionManager,
                    json = json,
                    onDeleted = { openedPlaylistId = null },
                    modifier = Modifier.weight(1f),
                )
            } else {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    text = title,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> QueueTabView(
                            sessionManager = sessionManager,
                            musicClient = musicClient,
                            playbackState = playbackState,
                        )
                        1 -> PlaylistsTabView(
                            assistantId = assistantId,
                            musicDao = musicDao,
                            musicClient = musicClient,
                            json = json,
                            onOpenPlaylist = { openedPlaylistId = it },
                        )
                        2 -> DailyDiscoveryTabView(
                            assistantId = assistantId,
                            dailyDiscoveryService = dailyDiscoveryService,
                            sessionManager = sessionManager,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactPlayerBar(
    playbackState: MusicPlaybackState,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val song = playbackState.currentSong
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!song?.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = song?.coverUrl,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(HugeIcons.MusicNote03, contentDescription = null, modifier = Modifier.size(40.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song?.name ?: "选择歌曲开始共听",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = playbackState.errorMessage ?: song?.artists ?: "点击打开播放器",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (playbackState.errorMessage != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (playbackState.playState == PlayState.PLAYING) HugeIcons.Pause else HugeIcons.Play,
                    contentDescription = if (playbackState.playState == PlayState.PLAYING) "暂停" else "播放",
                )
            }
            IconButton(onClick = onNext) {
                Icon(HugeIcons.Next, contentDescription = "下一首")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerPage() {
    val navController = LocalNavController.current
    val sessionManager: MusicSessionManager = koinInject()
    val musicClient: NeteaseMusicClient = koinInject()
    val playbackState by sessionManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showLyrics by remember { mutableStateOf(false) }
    var songToSave by remember { mutableStateOf<NeteaseSong?>(null) }
    var playlists by remember { mutableStateOf<List<NeteasePlaylist>>(emptyList()) }
    var loadingPlaylists by remember { mutableStateOf(false) }
    var savingPlaylistId by remember { mutableStateOf<Long?>(null) }

    if (songToSave != null) {
        AlertDialog(
            onDismissRequest = { if (savingPlaylistId == null) songToSave = null },
            title = { Text("添加《${songToSave?.name}》到网易云歌单") },
            text = {
                when {
                    loadingPlaylists -> CircularProgressIndicator()
                    playlists.isEmpty() -> Text("没有找到可添加歌曲的自建歌单")
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            TextButton(
                                onClick = {
                                    val songId = songToSave?.id ?: return@TextButton
                                    savingPlaylistId = playlist.id
                                    scope.launch {
                                        runCatching { musicClient.addSongToPlaylist(playlist.id, songId) }
                                            .onSuccess {
                                                songToSave = null
                                                savingPlaylistId = null
                                                snackbarHostState.showSnackbar("已添加到网易云歌单《${playlist.name}》")
                                            }
                                            .onFailure { error ->
                                                savingPlaylistId = null
                                                snackbarHostState.showSnackbar(error.message ?: "添加失败")
                                            }
                                    }
                                },
                                enabled = savingPlaylistId == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("${playlist.name} · ${playlist.trackCount} 首")
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { songToSave = null }, enabled = savingPlaylistId == null) { Text("取消") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("正在播放") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(HugeIcons.ArrowLeft02, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            songToSave = playbackState.currentSong
                            playlists = emptyList()
                            loadingPlaylists = true
                            scope.launch {
                                runCatching { musicClient.getMyPlaylists().filter { it.owned } }
                                    .onSuccess { playlists = it }
                                    .onFailure { error ->
                                        songToSave = null
                                        snackbarHostState.showSnackbar(error.message ?: "读取歌单失败")
                                    }
                                loadingPlaylists = false
                            }
                        },
                        enabled = playbackState.currentSong != null,
                    ) {
                        Icon(HugeIcons.Favourite, contentDescription = "收藏到网易云歌单")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PlayerHeroSection(
                playbackState = playbackState,
                showLyrics = showLyrics,
                artworkHeight = 280.dp,
                onToggleLyrics = { showLyrics = !showLyrics },
                onSeek = { sessionManager.seekTo(it) },
                onPlayPause = {
                    if (playbackState.playState == PlayState.PLAYING) sessionManager.pause()
                    else sessionManager.resume()
                },
                onNext = { sessionManager.next() },
                onPrevious = { sessionManager.previous() },
                onModeChange = {
                    val nextMode = when (playbackState.playMode) {
                        PlayMode.SEQUENCE -> PlayMode.REPEAT_ONE
                        PlayMode.REPEAT_ONE -> PlayMode.SHUFFLE
                        PlayMode.SHUFFLE -> PlayMode.SEQUENCE
                    }
                    sessionManager.setPlayMode(nextMode)
                },
                onStop = { sessionManager.stop() },
            )
        }
    }
}

@Composable
private fun PlayerHeroSection(
    playbackState: MusicPlaybackState,
    showLyrics: Boolean,
    artworkHeight: androidx.compose.ui.unit.Dp = 180.dp,
    onToggleLyrics: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onModeChange: () -> Unit,
    onStop: () -> Unit,
) {
    val song = playbackState.currentSong
    var isDraggingSlider by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableFloatStateOf(0f) }

    val currentPosition = if (isDraggingSlider) dragPositionMs.toLong() else playbackState.currentPositionMs
    val totalDuration = playbackState.durationMs

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 封面 / 歌词视窗
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(artworkHeight)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { onToggleLyrics() },
                contentAlignment = Alignment.Center,
            ) {
                if (showLyrics) {
                    LyricsView(
                        lyrics = playbackState.lyrics?.lines.orEmpty(),
                        positionMs = currentPosition,
                    )
                } else {
                    if (song != null && song.coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = song.coverUrl,
                            contentDescription = song.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = HugeIcons.MusicNote03,
                                contentDescription = null,
                                modifier = Modifier.size(54.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "点击切换歌词",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 歌曲信息
            Text(
                text = song?.name.orEmpty().ifBlank { "未在播放" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    song?.artists?.takeIf { it.isNotBlank() },
                    song?.album?.takeIf { it.isNotBlank() },
                ).joinToString(" · ").ifBlank { "点击下方曲目开启共听" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            playbackState.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }

            // 进度滑块
            Slider(
                value = if (totalDuration > 0) (currentPosition.toFloat() / totalDuration).coerceIn(0f, 1f) else 0f,
                onValueChange = { ratio ->
                    isDraggingSlider = true
                    dragPositionMs = ratio * totalDuration
                },
                onValueChangeFinished = {
                    onSeek(dragPositionMs.toLong())
                    isDraggingSlider = false
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatMs(currentPosition),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatMs(totalDuration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 控制按钮栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onModeChange) {
                    val icon = when (playbackState.playMode) {
                        PlayMode.SEQUENCE -> HugeIcons.Repeat
                        PlayMode.REPEAT_ONE -> HugeIcons.RepeatOne01
                        PlayMode.SHUFFLE -> HugeIcons.Shuffle
                    }
                    Icon(imageVector = icon, contentDescription = "播放模式")
                }

                IconButton(onClick = onPrevious) {
                    Icon(imageVector = HugeIcons.Previous, contentDescription = "上一首", modifier = Modifier.size(28.dp))
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(54.dp),
                ) {
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        val isPlaying = playbackState.playState == PlayState.PLAYING
                        Icon(
                            imageVector = if (isPlaying) HugeIcons.Pause else HugeIcons.Play,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }

                IconButton(onClick = onNext) {
                    Icon(imageVector = HugeIcons.Next, contentDescription = "下一首", modifier = Modifier.size(28.dp))
                }

                IconButton(onClick = onStop) {
                    Icon(imageVector = HugeIcons.Stop, contentDescription = "停止")
                }
            }
        }
    }
}

@Composable
private fun LyricsView(
    lyrics: List<LyricLine>,
    positionMs: Long,
) {
    if (lyrics.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "暂无歌词或纯音乐伴奏\n（轻触切回封面）",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val activeIndex = lyrics.indexOfLast { it.timestampMs <= positionMs }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    var userScrolledTime by remember { mutableStateOf(0L) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            userScrolledTime = System.currentTimeMillis()
        }
    }

    LaunchedEffect(activeIndex, userScrolledTime) {
        if (activeIndex in lyrics.indices) {
            val elapsed = System.currentTimeMillis() - userScrolledTime
            if (elapsed < 3000L) {
                kotlinx.coroutines.delay(3000L - elapsed)
            }
            if (!listState.isScrollInProgress && activeIndex in lyrics.indices) {
                listState.animateScrollToItem(activeIndex)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(lyrics.size) { index ->
            val line = lyrics[index]
            val isActive = index == activeIndex
            val color by animateColorAsState(
                targetValue = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                label = "lyric_color",
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = line.text,
                    style = if (isActive) MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyMedium,
                    color = color,
                    textAlign = TextAlign.Center,
                )
                if (!line.translation.isNullOrBlank()) {
                    Text(
                        text = line.translation,
                        style = MaterialTheme.typography.labelSmall,
                        color = color.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueTabView(
    sessionManager: MusicSessionManager,
    musicClient: NeteaseMusicClient,
    playbackState: MusicPlaybackState,
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<NeteaseSong>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val queue = playbackState.queue

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // 搜索加歌输入栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索歌曲加入队列…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                enabled = searchQuery.isNotBlank() && !isSearching,
                onClick = {
                    scope.launch {
                        isSearching = true
                        try {
                            searchResults = withContext(Dispatchers.IO) {
                                musicClient.searchSongs(searchQuery.trim(), limit = 10)
                            }
                        } finally {
                            isSearching = false
                        }
                    }
                },
            ) {
                Text(if (isSearching) "…" else "搜索")
            }
        }

        // 搜索结果卡片
        if (searchResults.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("搜索结果：", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { searchResults = emptyList() }, modifier = Modifier.size(24.dp)) {
                            Icon(imageVector = HugeIcons.Cancel01, contentDescription = "关闭")
                        }
                    }
                    searchResults.forEach { song ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(song.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artists, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { sessionManager.playDirect(song, actor = "user") }) {
                                    Text("播放")
                                }
                                TextButton(onClick = {
                                    sessionManager.addToQueue(song)
                                    searchResults = emptyList()
                                    searchQuery = ""
                                }) {
                                    Text("加队列")
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 队列头部
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val laneDesc = when (playbackState.lane) {
                PlaybackLane.DIRECT -> "通道: 直接点播"
                PlaybackLane.PLAYLIST -> "通道: 歌单循环"
                PlaybackLane.QUEUE -> "通道: 自定义队列"
            }
            Text(
                text = "播放队列 (${queue.size} 首) · $laneDesc",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (queue.isNotEmpty()) {
                TextButton(onClick = { sessionManager.clearQueue() }) {
                    Text("清空队列")
                }
            }
        }

        if (queue.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "队列暂无歌曲\n可在上方搜索或从“我的歌单”/“今日私选”中添加",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(queue) { song ->
                    val isCurrent = song.id == playbackState.currentSong?.id
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val idx = queue.indexOfFirst { it.id == song.id }
                                if (idx >= 0) sessionManager.playFromLane(PlaybackLane.QUEUE, idx)
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (isCurrent) {
                                Icon(
                                    imageVector = HugeIcons.MusicNote01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = song.artists,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistsTabView(
    assistantId: String,
    musicDao: MusicDao,
    musicClient: NeteaseMusicClient,
    json: Json,
    onOpenPlaylist: (Long) -> Unit,
) {
    val playlistsFlow = remember(assistantId) { musicDao.getPlaylistsFlow(assistantId) }
    val playlists by playlistsFlow.collectAsState(initial = emptyList())

    var showImportDialog by remember { mutableStateOf(false) }
    var importInput by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "助手歌单 (${playlists.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            FilledTonalButton(onClick = { showImportDialog = true }) {
                Icon(imageVector = HugeIcons.Download04, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("导入歌单")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (playlists.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "当前助手暂无导入歌单\n点击右上角可输入网易云公开歌单链接或 ID 导入",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(playlists, key = { it.playlistId }) { pl ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenPlaylist(pl.playlistId) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (pl.coverUrl.isNotBlank()) {
                                AsyncImage(
                                    model = pl.coverUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pl.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${pl.trackCount} 首歌曲",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("查看", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { if (!isImporting) showImportDialog = false },
            title = { Text("导入网易云歌单") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("输入网易云歌单 ID，或粘贴网易云公开歌单分享链接：", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = importInput,
                        onValueChange = { importInput = it },
                        placeholder = { Text("例如 24381616 或分享链接") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    importError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(
                    enabled = importInput.isNotBlank() && !isImporting,
                    onClick = {
                        scope.launch {
                            isImporting = true
                            importError = null
                            try {
                                val playlistId = musicClient.resolvePlaylistId(importInput.trim())
                                    ?: throw IllegalArgumentException("无法解析歌单 ID")
                                val detail = withContext(Dispatchers.IO) {
                                    musicClient.getPlaylistDetail(playlistId)
                                }
                                musicDao.insertOrUpdatePlaylist(
                                    MusicPlaylistEntity(
                                        assistantId = assistantId,
                                        playlistId = detail.id,
                                        name = detail.name,
                                        source = "netease",
                                        trackCount = detail.trackCount,
                                        coverUrl = detail.coverUrl,
                                        tracksJson = json.encodeToString(detail.tracks),
                                        updatedAt = System.currentTimeMillis(),
                                    )
                                )
                                showImportDialog = false
                                importInput = ""
                            } catch (e: Exception) {
                                importError = e.message ?: "导入失败"
                            } finally {
                                isImporting = false
                            }
                        }
                    }
                ) {
                    Text(if (isImporting) "导入中…" else "确认导入")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isImporting,
                    onClick = { showImportDialog = false }
                ) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun PlaylistDetailView(
    assistantId: String,
    playlistId: Long,
    musicDao: MusicDao,
    musicClient: NeteaseMusicClient,
    sessionManager: MusicSessionManager,
    json: Json,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playlistsFlow = remember(assistantId) { musicDao.getPlaylistsFlow(assistantId) }
    val playlists by playlistsFlow.collectAsState(initial = emptyList())
    val playlist = playlists.firstOrNull { it.playlistId == playlistId }
    val songs = remember(playlist?.tracksJson) {
        runCatching { json.decodeFromString<List<NeteaseSong>>(playlist?.tracksJson.orEmpty()) }
            .getOrDefault(emptyList())
    }
    val scope = rememberCoroutineScope()
    var refreshing by remember(playlistId) { mutableStateOf(false) }
    var refreshFailed by remember(playlistId) { mutableStateOf(false) }

    fun refreshPlaylist() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                val detail = withContext(Dispatchers.IO) { musicClient.getPlaylistDetail(playlistId) }
                musicDao.getPlaylist(assistantId, playlistId)?.let { current ->
                    musicDao.insertOrUpdatePlaylist(
                        current.copy(
                            name = detail.name,
                            trackCount = detail.trackCount,
                            coverUrl = detail.coverUrl,
                            tracksJson = json.encodeToString(detail.tracks),
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
                refreshFailed = false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                refreshFailed = true
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(playlistId, playlist?.playlistId) {
        if (playlist != null && songs.size < playlist.trackCount) refreshPlaylist()
    }

    if (playlist == null) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("正在读取歌单…")
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item(key = "header") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (playlist.coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = playlist.coverUrl,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(playlist.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("${playlist.trackCount} 首歌曲", style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(
                        enabled = !refreshing && songs.isNotEmpty(),
                        onClick = { sessionManager.playAll(songs, 0) },
                    ) {
                        Text(if (songs.size < playlist.trackCount) "播放已载入" else "播放全部")
                    }
                }
            }
        }

        if (songs.size < playlist.trackCount) {
            item(key = "status") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when {
                            refreshing -> "正在补全歌曲列表（${songs.size}/${playlist.trackCount}）"
                            refreshFailed -> "补全失败，当前 ${songs.size}/${playlist.trackCount} 首"
                            else -> "已载入 ${songs.size}/${playlist.trackCount} 首"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!refreshing) {
                        TextButton(onClick = { refreshPlaylist() }) { Text("重新读取") }
                    }
                }
            }
        }

        item(key = "actions") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !refreshing, onClick = {
                    scope.launch {
                        musicDao.deletePlaylist(assistantId, playlistId)
                        onDeleted()
                    }
                }) {
                    Text("删除歌单", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        itemsIndexed(songs, key = { index, _ -> index }) { index, song ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    sessionManager.playDirect(song, actor = "user")
                },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${index + 1}. ${song.name} - ${song.artists}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(imageVector = HugeIcons.Play, contentDescription = "播放", modifier = Modifier.size(16.dp))
                }
            }
        }

    }
}

@Composable
private fun DailyDiscoveryTabView(
    assistantId: String,
    dailyDiscoveryService: DailyDiscoveryService,
    sessionManager: MusicSessionManager,
) {
    var loading by remember { mutableStateOf(true) }
    var discoveryTitle by remember { mutableStateOf("") }
    var discoverySubtitle by remember { mutableStateOf("") }
    var discoverySongs by remember { mutableStateOf<List<DailyDiscoverySong>>(emptyList()) }
    var feedbackMap by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    val scope = rememberCoroutineScope()

    fun loadDiscovery(force: Boolean = false) {
        scope.launch {
            loading = true
            try {
                val entity = dailyDiscoveryService.getTodayDiscovery(assistantId, forceRefresh = force)
                discoveryTitle = entity.title
                discoverySubtitle = entity.subtitle
                discoverySongs = dailyDiscoveryService.parseSongs(entity)
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(assistantId) {
        loadDiscovery(force = false)
    }

    if (loading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                Text("正在根据你们的共听品味策展今日私选…", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // 私选头部卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(discoveryTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(discoverySubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                    }
                    FilledTonalButton(onClick = { loadDiscovery(force = true) }) {
                        Text("换一批")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val neteaseSongs = discoverySongs.map { s ->
                            NeteaseSong(
                                id = s.id,
                                name = s.name,
                                artists = s.artist,
                                album = s.album,
                                coverUrl = s.coverUrl,
                            )
                        }
                        sessionManager.playAll(neteaseSongs, 0)
                    }
                ) {
                    Icon(imageVector = HugeIcons.Play, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("播放今日私选全部 (${discoverySongs.size} 首)")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 歌曲列表
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(discoverySongs) { song ->
                val neteaseSong = remember(song) {
                    NeteaseSong(
                        id = song.id,
                        name = song.name,
                        artists = song.artist,
                        album = song.album,
                        coverUrl = song.coverUrl,
                    )
                }
                val currentAction = feedbackMap[song.id]

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (song.coverUrl.isNotBlank()) {
                                AsyncImage(
                                    model = song.coverUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = song.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                    ) {
                                        Text(
                                            text = song.tag,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                                Text(
                                    text = "${song.artist} · ${song.album}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { sessionManager.playDirect(neteaseSong, actor = "user") }) {
                                Icon(imageVector = HugeIcons.Play, contentDescription = "播放")
                            }
                        }

                        if (song.reason.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "“${song.reason}”",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        // 反馈栏
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = {
                                sessionManager.addToQueue(neteaseSong)
                            }) {
                                Text("加队列", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    dailyDiscoveryService.recordFeedback(assistantId, song, "like")
                                    feedbackMap = feedbackMap + (song.id to "like")
                                }
                            }) {
                                Text(
                                    text = if (currentAction == "like") "已喜欢 ❤️" else "喜欢",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (currentAction == "like") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    dailyDiscoveryService.recordFeedback(assistantId, song, "less")
                                    feedbackMap = feedbackMap + (song.id to "less")
                                }
                            }) {
                                Text(
                                    text = if (currentAction == "less") "已减少 ❌" else "减少此类",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
