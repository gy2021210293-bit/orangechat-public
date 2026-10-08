/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.async
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Sticker
import me.rerere.rikkahub.data.service.StickerRepository
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject

@Composable
internal fun StickerPicker(
    settings: Settings,
    onSelect: (Sticker) -> Unit,
    onDismiss: () -> Unit,
) {
    val repository: StickerRepository = koinInject()
    val navController = LocalNavController.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var stickers by mutableStateOf<List<Sticker>?>(null)
    var error by mutableStateOf<String?>(null)
    val storage = settings.stickerStorageSetting

    LaunchedEffect(storage) {
        stickers = null
        error = null
        if (storage.isConfigured()) {
            val refresh = async { repository.refresh(storage) }
            stickers = repository.cached(storage)
            refresh.await()
                .onSuccess { stickers = it }
                .onFailure {
                    if (stickers == null) {
                        error = it.message ?: "表情包列表加载失败"
                    }
                }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("表情包", style = MaterialTheme.typography.titleLarge)
            when {
                !storage.isConfigured() -> {
                    Text("请先配置与表情包插件相同的 Supabase 图床。")
                    TextButton(onClick = {
                        onDismiss()
                        navController.navigate(Screen.SettingStickers)
                    }) {
                        Text("前往配置")
                    }
                }

                error != null -> {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }

                stickers == null -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                stickers!!.isEmpty() -> {
                    Text("表情包库为空，请先在插件中上传表情包。")
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(88.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(stickers!!, key = { it.url }) { sticker ->
                            StickerCard(sticker = sticker, onClick = { onSelect(sticker) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StickerCard(sticker: Sticker, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        AsyncImage(
            model = sticker.url,
            contentDescription = sticker.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(80.dp)
                .clip(MaterialTheme.shapes.medium),
        )
    }
}
