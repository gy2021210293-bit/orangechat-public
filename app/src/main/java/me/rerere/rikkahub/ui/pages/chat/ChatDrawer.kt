/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import me.rerere.rikkahub.plugin.webview.PluginWebViewPool
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.MessageMultiple01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Pulse01
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Zap
import me.rerere.hugeicons.stroke.Earth
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.memory.v1.MemoryConstants
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.model.PluginQuickEntry
import me.rerere.rikkahub.plugin.model.PluginQuickEntryTarget
import me.rerere.rikkahub.plugin.model.toQuickEntryOrNull
import me.rerere.rikkahub.ui.components.ai.AssistantPicker
import me.rerere.rikkahub.ui.components.ui.BackupReminderCard
import me.rerere.rikkahub.ui.components.ui.Greeting
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.UpdateCard
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.rememberIsPlayStoreVersion
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.toDp
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun ChatDrawerContent(
    navController: Navigator,
    vm: ChatVM,
    settings: Settings,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isPlayStore = rememberIsPlayStoreVersion()
    val repo = koinInject<ConversationRepository>()
    val conversation by vm.conversation.collectAsStateWithLifecycle()

    // 昵称编辑状态
    val nicknameEditState = useEditState<String> { newNickname ->
        vm.updateSettings(
            settings.copy(
                displaySetting = settings.displaySetting.copy(
                    userNickname = newNickname
                )
            )
        )
    }

    ModalDrawerSheet(
        modifier = Modifier.width(300.dp),
        drawerContainerColor = MaterialTheme.colorScheme.background.copy(alpha = 1f),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 侧边栏背景图（最底层）
            val drawerBgPath = settings.displaySetting.drawerBackgroundPath
            if (drawerBgPath.isNotEmpty()) {
                val bgFile = java.io.File(drawerBgPath)
                if (bgFile.exists()) {
                    val bgBitmap = remember(drawerBgPath) {
                        BitmapFactory.decodeFile(drawerBgPath)
                    }
                    if (bgBitmap != null) {
                        Image(
                            bitmap = bgBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(0.15f),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxHeight().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (settings.displaySetting.showUpdates && !isPlayStore) {
                    UpdateCard(vm)
                }

                BackupReminderCard(
                    settings = settings,
                    onClick = { navController.navigate(Screen.Backup) },
                )

                // 用户头像和昵称自定义区域
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    UIAvatar(
                        name = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                        value = settings.displaySetting.userAvatar,
                        onUpdate = { newAvatar ->
                            vm.updateSettings(
                                settings.copy(
                                    displaySetting = settings.displaySetting.copy(
                                        userAvatar = newAvatar
                                    )
                                )
                            )
                        },
                        modifier = Modifier.size(50.dp),
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    nicknameEditState.open(settings.displaySetting.userNickname)
                                }
                            )

                            Icon(
                                imageVector = HugeIcons.PencilEdit01,
                                contentDescription = "Edit",
                                modifier = Modifier
                                    .onClick {
                                        nicknameEditState.open(settings.displaySetting.userNickname)
                                    }
                                    .size(LocalTextStyle.current.fontSize.toDp())
                            )
                        }
                        Greeting(
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                DrawerAppGrid(
                    navController = navController,
                    settings = settings,
                    conversationId = conversation.id.toString(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .alpha(settings.displaySetting.drawerItemAlpha),
                )

                // 助手选择器
                AssistantPicker(
                    settings = settings,
                    onUpdateSettings = {
                        vm.updateSettings(it)
                        scope.launch {
                            val id = if (context.readBooleanPreference("create_new_conversation_on_start", true)) {
                                Uuid.random()
                            } else {
                                repo.getConversationsOfAssistant(it.assistantId)
                                    .first()
                                    .firstOrNull()
                                    ?.id ?: Uuid.random()
                            }
                            navigateToChatPage(navigator = navController, chatId = id)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    onClickSetting = {
                        val currentAssistantId = settings.assistantId
                        navController.navigate(Screen.AssistantDetail(id = currentAssistantId.toString()))
                    }
                )

                Row(
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .alpha(settings.displaySetting.drawerItemAlpha),
                ) {
                    DrawerAction(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.LookTop,
                                contentDescription = stringResource(R.string.assistant_page_title)
                            )
                        },
                        label = {
                            Text(stringResource(R.string.assistant_page_title))
                        },
                        onClick = {
                            navController.navigate(Screen.Assistant)
                        },
                    )

                    DrawerAction(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.MessageMultiple01,
                                contentDescription = stringResource(R.string.history_page_title)
                            )
                        },
                        label = {
                            Text(stringResource(R.string.history_page_title))
                        },
                        onClick = {
                            navController.navigate(Screen.History)
                        },
                    )

                    DrawerAction(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Image02,
                                contentDescription = stringResource(R.string.chat_page_menu_image_generation)
                            )
                        },
                        label = {
                            Text(stringResource(R.string.chat_page_menu_image_generation))
                        },
                        onClick = {
                            navController.navigate(Screen.ImageGen)
                        },
                    )

                    DrawerAction(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.ChartColumn,
                                contentDescription = stringResource(R.string.stats_page_title)
                            )
                        },
                        label = {
                            Text(stringResource(R.string.stats_page_title))
                        },
                        onClick = {
                            navController.navigate(Screen.Stats)
                        },
                    )

                    DrawerAction(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Settings03,
                                contentDescription = stringResource(R.string.settings),
                            )
                        },
                        label = { Text(stringResource(R.string.settings)) },
                        onClick = {
                            navController.navigate(Screen.Setting)
                        },
                    )
                }
            }
        }
    }

    // 昵称编辑对话框
    nicknameEditState.EditStateContent { nickname, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                nicknameEditState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_nickname))
            },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_nickname_placeholder)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}

@Composable
private fun DrawerAppGrid(
    navController: Navigator,
    settings: Settings,
    conversationId: String? = null,
    modifier: Modifier = Modifier,
) {
    val pluginManager: PluginManager = koinInject()
    val plugins by pluginManager.plugins.collectAsStateWithLifecycle()
    val entries = remember(plugins) {
        plugins.mapNotNull { it.toQuickEntryOrNull() }
            .sortedBy { it.pluginName.lowercase() }
    }

    val context = LocalContext.current
    LaunchedEffect(entries) {
        if (entries.any { it.target is PluginQuickEntryTarget.WebView }) {
            PluginWebViewPool.ensurePreheated(context)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        // A. 插件格子（动态，来自 PluginManager）
        items(
            items = entries,
            key = { "plugin_${it.pluginId}" },
        ) { entry ->
            DrawerPluginGridItem(
                entry = entry,
                onClick = {
                    navigateToPluginQuickEntry(navController, entry, conversationId)
                },
            )
        }

        // B. 系统功能格子（固定，写死顺序）
        item(key = "sys_health") {
            DrawerSystemGridItem(
                icon = HugeIcons.Pulse01,
                label = "健康数据",
                onClick = { navController.navigate(Screen.Health) },
            )
        }
        item(key = "sys_desire") {
            DrawerSystemGridItem(
                icon = HugeIcons.InLove,
                label = "主动唤醒",
                onClick = {
                    navController.navigate(
                        Screen.AssistantDesire(settings.assistantId.toString())
                    )
                },
            )
        }
        item(key = "sys_affective") {
            DrawerSystemGridItem(
                icon = HugeIcons.InLove,
                label = "情感引擎",
                onClick = { navController.navigate(Screen.AssistantAffective(settings.assistantId.toString())) },
            )
        }
        item(key = "sys_memory") {
            DrawerSystemGridItem(
                icon = HugeIcons.Brain02,
                label = "长期记忆",
                onClick = {
                    navController.navigate(
                        Screen.LocalMemoryManager(
                            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                            ownerId = settings.assistantId.toString()
                        )
                    )
                },
            )
        }
        item(key = "sys_miniapp") {
            DrawerSystemGridItem(
                icon = HugeIcons.Rocket01,
                label = "Mini Apps",
                onClick = { navController.navigate(Screen.MiniAppManager) },
            )
        }
        item(key = "sys_mcp") {
            DrawerSystemGridItem(
                icon = HugeIcons.McpServer,
                label = "MCP",
                onClick = { navController.navigate(Screen.SettingMcp) },
            )
        }
        item(key = "sys_workflows") {
            DrawerSystemGridItem(
                icon = HugeIcons.Zap,
                label = "工作流",
                onClick = { navController.navigate(Screen.Workflows) },
            )
        }
        item(key = "sys_workspaces") {
            DrawerSystemGridItem(
                icon = HugeIcons.ServerStack01,
                label = "工作区",
                onClick = { navController.navigate(Screen.Workspaces) },
            )
        }
        item(key = "sys_music") {
            DrawerSystemGridItem(
                icon = HugeIcons.MusicNote03,
                label = "一起听歌",
                onClick = { navController.navigate(Screen.Music) },
            )
        }
        item(key = "sys_together_browse") {
            DrawerSystemGridItem(
                icon = HugeIcons.Earth,
                label = "一起刷",
                onClick = {
                    conversationId?.let { id ->
                        navController.navigate(Screen.TogetherBrowse(conversationId = id))
                    }
                },
            )
        }
    }
}

@Composable
private fun DrawerPluginGridItem(
    entry: PluginQuickEntry,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp)
            .fillMaxWidth()
            .alpha(if (entry.isEnabled) 1f else 0.5f),
    ) {
        val isUrl = entry.icon.startsWith("http://") || entry.icon.startsWith("https://")
        if (isUrl) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                modifier = Modifier.size(48.dp),
            ) {
                AsyncImage(
                    model = entry.icon,
                    contentDescription = entry.pluginName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        } else {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
                modifier = Modifier.size(48.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = entry.icon.ifBlank { "🧩" },
                        fontSize = 22.sp,
                    )
                }
            }
        }
        Text(
            text = entry.pluginName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DrawerSystemGridItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp)
            .fillMaxWidth(),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
            modifier = Modifier.size(48.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private fun navigateToPluginQuickEntry(
    navController: Navigator,
    entry: PluginQuickEntry,
    conversationId: String? = null,
) {
    when (val target = entry.target) {
        PluginQuickEntryTarget.DeclarativeUi -> {
            navController.navigate(Screen.PluginDeclarativeUI(entry.pluginId))
        }

        is PluginQuickEntryTarget.WebView -> {
            navController.navigate(Screen.PluginWebView(entry.pluginId, target.entryPath, conversationId))
        }

        PluginQuickEntryTarget.MemoryBank -> {
            navController.navigate(Screen.MemoryBank)
        }
    }
}

@Composable
private fun DrawerAction(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CircleShape,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Tooltip(
            tooltip = {
                label()
            }
        ) {
            Box(
                modifier = Modifier
                    .padding(10.dp)
                    .size(20.dp),
            ) {
                icon()
            }
        }
    }
}
