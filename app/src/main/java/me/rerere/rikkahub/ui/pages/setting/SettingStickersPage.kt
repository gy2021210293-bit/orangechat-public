/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.StickerStorageSetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingStickersPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var stickerSetting by remember { mutableStateOf(settings.stickerStorageSetting) }
    var hasLocalEdits by remember { mutableStateOf(false) }

    LaunchedEffect(settings.init) {
        if (!settings.init && !hasLocalEdits) {
            stickerSetting = settings.stickerStorageSetting
        }
    }

    fun update(value: StickerStorageSetting) {
        hasLocalEdits = true
        stickerSetting = value
        vm.updateStickerStorageSetting(value)
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("表情包") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    title = { Text("共享图床") },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text("使用方式") },
                        supportingContent = {
                            Text("填写与表情包插件相同的 Supabase URL 和 anon key。App 只读取 stickers 表的名称和图片 URL。")
                        },
                    )
                    item(
                        headlineContent = { Text("Supabase URL") },
                        supportingContent = {
                            OutlinedTextField(
                                value = stickerSetting.supabaseUrl,
                                onValueChange = { update(stickerSetting.copy(supabaseUrl = it.trim())) },
                                placeholder = { Text("https://xxxx.supabase.co") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = transparentTextFieldColors(),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("Supabase anon key") },
                        supportingContent = {
                            OutlinedTextField(
                                value = stickerSetting.supabaseAnonKey,
                                onValueChange = { update(stickerSetting.copy(supabaseAnonKey = it.trim())) },
                                placeholder = { Text("仅使用 anon key，不要填写 service-role key") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                colors = transparentTextFieldColors(),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun transparentTextFieldColors() = TextFieldDefaults.colors(
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
)
