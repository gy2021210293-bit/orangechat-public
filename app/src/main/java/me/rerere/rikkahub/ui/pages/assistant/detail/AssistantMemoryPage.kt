/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Brain01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.memory.v1.MemoryConstants
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.ExternalMemory
import me.rerere.rikkahub.data.model.MemoryMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.context.LocalSettings
import kotlin.uuid.Uuid

@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val externalMemories by vm.externalMemories.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_memory))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantMemoryContent(
            innerPadding = innerPadding,
            assistant = assistant,
            memories = memories,
            externalMemories = externalMemories,
            onUpdateAssistant = { vm.update(it) },
            onDeleteMemory = { vm.deleteMemory(it) },
            onAddMemory = { vm.addMemory(it) },
            onUpdateMemory = { vm.updateMemory(it) },
            onUpdateExternalMemoryIds = { vm.updateExternalMemoryIds(it) },
            onRebuildMemoryEmbeddings = { onFinish -> vm.rebuildMemoryEmbeddings(onFinish) }
        )
    }
}

@Composable
private fun AssistantMemoryContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    memories: List<AssistantMemory>,
    externalMemories: List<ExternalMemory>,
    onUpdateAssistant: (Assistant) -> Unit,
    onAddMemory: (AssistantMemory) -> Unit,
    onUpdateMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
    onUpdateExternalMemoryIds: (Set<Uuid>) -> Unit,
    onRebuildMemoryEmbeddings: ((Int) -> Unit) -> Unit = {},
) {
    val navController = LocalNavController.current
    val settings = LocalSettings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRebuildingEmbeddings by remember { mutableStateOf(false) }
    val currentMode = assistant.effectiveMemoryMode
    var showAdvancedModes by remember {
        mutableStateOf(currentMode in listOf(MemoryMode.LEGACY_SIMPLE, MemoryMode.EXTERNAL, MemoryMode.LEGACY_BOTH))
    }

    val memoryDialogState = useEditState<AssistantMemory> {
        if (it.id == 0) {
            onAddMemory(it)
        } else {
            onUpdateMemory(it)
        }
    }
    var pendingDeleteMemory by remember { mutableStateOf<AssistantMemory?>(null) }
    var showExternalMemoryPicker by remember { mutableStateOf(false) }

    // 记忆对话框 (用于旧版简单记忆)
    memoryDialogState.EditStateContent { memory, update ->
        AlertDialog(
            onDismissRequest = {
                memoryDialogState.dismiss()
            },
            title = {
                Text(stringResource(R.string.assistant_page_manage_memory_title))
            },
            text = {
                TextField(
                    value = memory.content,
                    onValueChange = {
                        update(memory.copy(content = it))
                    },
                    label = {
                        Text(stringResource(R.string.assistant_page_manage_memory_title))
                    },
                    minLines = 2,
                    maxLines = 8
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        memoryDialogState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 记忆模式选择
        CardGroup(title = { Text("记忆模式") }) {
            item(
                headlineContent = { Text("关闭记忆") },
                supportingContent = { Text("不为当前 AI 启用任何长期记忆") },
                trailingContent = {
                    RadioButton(
                        selected = currentMode == MemoryMode.OFF,
                        onClick = {
                            onUpdateAssistant(
                                assistant.copy(
                                    memoryMode = MemoryMode.OFF,
                                    enableMemory = false
                                )
                            )
                        }
                    )
                },
                onClick = {
                    onUpdateAssistant(
                        assistant.copy(
                            memoryMode = MemoryMode.OFF,
                            enableMemory = false
                        )
                    )
                }
            )
            item(
                headlineContent = { Text("本地长期记忆 (推荐)") },
                supportingContent = { Text("基于本地 SQLite 结构化事实库，支持精准检索与可审计修订流水") },
                trailingContent = {
                    RadioButton(
                        selected = currentMode == MemoryMode.LOCAL_V1,
                        onClick = {
                            onUpdateAssistant(
                                assistant.copy(
                                    memoryMode = MemoryMode.LOCAL_V1,
                                    enableMemory = true
                                )
                            )
                        }
                    )
                },
                onClick = {
                    onUpdateAssistant(
                        assistant.copy(
                            memoryMode = MemoryMode.LOCAL_V1,
                            enableMemory = true
                        )
                    )
                }
            )
            item(
                headlineContent = {
                    Text(
                        text = if (showAdvancedModes) "收起更多模式" else "更多模式 (旧版 / 外置)...",
                        color = MaterialTheme.colorScheme.secondary
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = if (showAdvancedModes) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                        contentDescription = null
                    )
                },
                onClick = { showAdvancedModes = !showAdvancedModes }
            )
            if (showAdvancedModes) {
                if (currentMode == MemoryMode.LEGACY_BOTH) {
                    item(
                        headlineContent = { Text("旧版双模式组合 (简单 + 外置)") },
                        supportingContent = { Text("当前正在同时运行旧版简单记忆与外置记忆，切换至其他模式后将不再自动组合") },
                        trailingContent = {
                            RadioButton(
                                selected = true,
                                onClick = {}
                            )
                        }
                    )
                }
                item(
                    headlineContent = { Text("旧版简单记忆") },
                    supportingContent = { Text("早期简易文本列表注入方案") },
                    trailingContent = {
                        RadioButton(
                            selected = currentMode == MemoryMode.LEGACY_SIMPLE,
                            onClick = {
                                onUpdateAssistant(
                                    assistant.copy(
                                        memoryMode = MemoryMode.LEGACY_SIMPLE,
                                        enableMemory = true
                                    )
                                )
                            }
                        )
                    },
                    onClick = {
                        onUpdateAssistant(
                            assistant.copy(
                                memoryMode = MemoryMode.LEGACY_SIMPLE,
                                enableMemory = true
                            )
                        )
                    }
                )
                item(
                    headlineContent = { Text("外置记忆库 (Supabase)") },
                    supportingContent = { Text("基于 Supabase 远程向量库的外置记忆服务") },
                    trailingContent = {
                        RadioButton(
                            selected = currentMode == MemoryMode.EXTERNAL,
                            onClick = {
                                onUpdateAssistant(
                                    assistant.copy(
                                        memoryMode = MemoryMode.EXTERNAL,
                                        enableMemory = true
                                    )
                                )
                            }
                        )
                    },
                    onClick = {
                        onUpdateAssistant(
                            assistant.copy(
                                memoryMode = MemoryMode.EXTERNAL,
                                enableMemory = true
                            )
                        )
                    }
                )
            }
        }

        // 对应模式下的记忆配置与入口
        when (currentMode) {
            MemoryMode.LOCAL_V1 -> {
                CardGroup(title = { Text("本地长期记忆管理") }) {
                    item(
                        headlineContent = { Text("全局共享记忆") },
                        supportingContent = { Text("允许当前 AI 读取并沉淀全局通用的长期记忆卡片") },
                        trailingContent = {
                            Switch(
                                checked = assistant.useGlobalMemory,
                                onCheckedChange = {
                                    onUpdateAssistant(assistant.copy(useGlobalMemory = it))
                                }
                            )
                        }
                    )
                    item(
                        headlineContent = { Text("管理专属记忆卡片") },
                        supportingContent = { Text("查看、搜索、编辑或归档专属于此助手的长期记忆卡片") },
                        trailingContent = {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null)
                        },
                        onClick = {
                            navController.navigate(
                                Screen.LocalMemoryManager(
                                    ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                                    ownerId = assistant.id.toString()
                                )
                            )
                        }
                    )
                    if (assistant.useGlobalMemory) {
                        item(
                            headlineContent = { Text("管理全局共享记忆卡片") },
                            supportingContent = { Text("查看、搜索、编辑或归档跨助手通用的全局长期记忆卡片") },
                            trailingContent = {
                                Icon(HugeIcons.ArrowRight01, contentDescription = null)
                            },
                            onClick = {
                                navController.navigate(
                                    Screen.LocalMemoryManager(
                                        ownerType = MemoryConstants.OWNER_TYPE_GLOBAL,
                                        ownerId = MemoryConstants.GLOBAL_OWNER_ID
                                    )
                                )
                            }
                        )
                    }
                }

                CardGroup(title = { Text("自动召回与向量检索") }) {
                    item(
                        headlineContent = { Text("自动召回记忆") },
                        supportingContent = { Text("在生成回复前，自动根据对话意图检索相关记忆并注入提示词") },
                        trailingContent = {
                            Switch(
                                checked = assistant.autoRecallMemory,
                                onCheckedChange = {
                                    onUpdateAssistant(assistant.copy(autoRecallMemory = it))
                                }
                            )
                        }
                    )
                    if (assistant.autoRecallMemory) {
                        item(
                            headlineContent = { Text("最大召回条数") },
                            supportingContent = { Text("单次最多召回的相关记忆卡片条数 (当前: ${assistant.maxAutoRecallCount} 条)") },
                            trailingContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            val newCount = (assistant.maxAutoRecallCount - 1).coerceAtLeast(1)
                                            onUpdateAssistant(assistant.copy(maxAutoRecallCount = newCount))
                                        },
                                        enabled = assistant.maxAutoRecallCount > 1
                                    ) {
                                        Text("-", style = MaterialTheme.typography.titleMedium)
                                    }
                                    Text(
                                        text = "${assistant.maxAutoRecallCount}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    IconButton(
                                        onClick = {
                                            val newCount = (assistant.maxAutoRecallCount + 1).coerceAtMost(20)
                                            onUpdateAssistant(assistant.copy(maxAutoRecallCount = newCount))
                                        },
                                        enabled = assistant.maxAutoRecallCount < 20
                                    ) {
                                        Text("+", style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                        )
                        item(
                            headlineContent = { Text("向量嵌入模型") },
                            supportingContent = {
                                Text(
                                    if (assistant.memoryEmbeddingModelId == null) "未配置（使用纯文本 FTS 检索）"
                                    else "用于计算记忆向量，实现语义与关键词混合检索"
                                )
                            },
                            trailingContent = {
                                ModelSelector(
                                    modelId = assistant.memoryEmbeddingModelId,
                                    providers = settings.providers,
                                    type = ModelType.EMBEDDING,
                                    allowClear = true,
                                    onSelect = { model ->
                                        val newId = if (model.modelId.isBlank()) null else model.id
                                        onUpdateAssistant(assistant.copy(memoryEmbeddingModelId = newId))
                                    }
                                )
                            }
                        )
                        item(
                            headlineContent = { Text("立即补建记忆向量") },
                            supportingContent = { Text("为当前助手已有的有效记忆卡片重新计算并写入向量数据") },
                            trailingContent = {
                                if (isRebuildingEmbeddings) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                } else {
                                    IconButton(
                                        onClick = {
                                            if (assistant.memoryEmbeddingModelId == null) {
                                                Toast.makeText(context, "请先选择向量嵌入模型", Toast.LENGTH_SHORT).show()
                                                return@IconButton
                                            }
                                            isRebuildingEmbeddings = true
                                            onRebuildMemoryEmbeddings { count ->
                                                scope.launch(Dispatchers.Main) {
                                                    isRebuildingEmbeddings = false
                                                    Toast.makeText(
                                                        context,
                                                        "已成功补建 $count 条记忆向量",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(HugeIcons.Refresh01, contentDescription = "补建向量")
                                    }
                                }
                            },
                            onClick = {
                                if (!isRebuildingEmbeddings) {
                                    if (assistant.memoryEmbeddingModelId == null) {
                                        Toast.makeText(context, "请先选择向量嵌入模型", Toast.LENGTH_SHORT).show()
                                    } else {
                                        isRebuildingEmbeddings = true
                                        onRebuildMemoryEmbeddings { count ->
                                            scope.launch(Dispatchers.Main) {
                                                isRebuildingEmbeddings = false
                                                Toast.makeText(
                                                    context,
                                                    "已成功补建 $count 条记忆向量",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                }
                            }
                        )
                        item(
                            headlineContent = { Text("启用图关系联想 (Y-Axis)") },
                            supportingContent = { Text("召回种子记忆后，沿安全关系边展开 1 跳关联记忆（最多 3 条）") },
                            trailingContent = {
                                Switch(
                                    checked = assistant.enableGraphExpansion,
                                    onCheckedChange = {
                                        onUpdateAssistant(assistant.copy(enableGraphExpansion = it))
                                    }
                                )
                            }
                        )
                    }
                }

                CardGroup(title = { Text("主体经验记忆 (E-Axis)") }) {
                    item(
                        headlineContent = { Text("启用第一人称经验记忆") },
                        supportingContent = { Text("沉淀并维护主体的第一人称经验意义、情绪坐标与回应倾向") },
                        trailingContent = {
                            Switch(
                                checked = assistant.enableExperienceMemory,
                                onCheckedChange = {
                                    onUpdateAssistant(assistant.copy(enableExperienceMemory = it))
                                }
                            )
                        }
                    )
                    if (assistant.enableExperienceMemory) {
                        item(
                            headlineContent = { Text("允许情绪共振召回") },
                            supportingContent = { Text("在用户表达显著情绪时，自动拉取历史高共鸣经验并注入回应倾向") },
                            trailingContent = {
                                Switch(
                                    checked = assistant.allowEmotionResonanceRecall,
                                    onCheckedChange = {
                                        onUpdateAssistant(assistant.copy(allowEmotionResonanceRecall = it))
                                    }
                                )
                            }
                        )
                        item(
                            headlineContent = { Text("允许夜间整理提议经验") },
                            supportingContent = { Text("夜间整理器可生成经验提议（需经严格第一人称校验，不能直接篡改权威经验）") },
                            trailingContent = {
                                Switch(
                                    checked = assistant.allowNightlyExperienceConsolidation,
                                    onCheckedChange = {
                                        onUpdateAssistant(assistant.copy(allowNightlyExperienceConsolidation = it))
                                    }
                                )
                            }
                        )
                    }
                }

                CardGroup(title = { Text("每日主模型整理 (Daily Consolidation)") }) {
                    item(
                        headlineContent = { Text("允许核对记忆时读取历史原话") },
                        supportingContent = { Text("仅在模型主动调用证据工具时，读取关联记忆卡的少量原话与相邻上下文；默认关闭") },
                        trailingContent = {
                            Switch(
                                checked = assistant.allowMemoryEvidenceRead,
                                onCheckedChange = {
                                    onUpdateAssistant(assistant.copy(allowMemoryEvidenceRead = it))
                                }
                            )
                        }
                    )
                    item(
                        headlineContent = { Text("启用每日自动整理") },
                        supportingContent = { Text("每天清晨 04:00 自动审阅前一日对话，生成每日日记并沉淀长期记忆卡片") },
                        trailingContent = {
                            Switch(
                                checked = assistant.dailyConsolidationEnabled,
                                onCheckedChange = {
                                    onUpdateAssistant(assistant.copy(dailyConsolidationEnabled = it))
                                }
                            )
                        }
                    )
                    if (assistant.dailyConsolidationEnabled) {
                        item(
                            headlineContent = { Text("记忆日划分窗口") },
                            supportingContent = { Text("本地时间每日 04:00 到次日 03:59:59 为一个完整记忆日") }
                        )
                    }
                }
            }

            MemoryMode.LEGACY_BOTH -> {
                CardGroup(title = { Text("外置记忆库配置") }) {
                    item(
                        headlineContent = { Text("外置记忆库") },
                        supportingContent = {
                            val count = assistant.externalMemoryIds.size
                            Text(if (count == 0) "未配置" else "已选择 ${count} 个记忆库")
                        },
                        trailingContent = {
                            IconButton(onClick = { showExternalMemoryPicker = true }) {
                                Icon(HugeIcons.Brain01, contentDescription = null)
                            }
                        },
                        onClick = { showExternalMemoryPicker = true }
                    )
                }

                CardGroup(title = { Text("旧版简单记忆配置") }) {
                    item(
                        headlineContent = { Text(stringResource(R.string.assistant_page_global_memory)) },
                        supportingContent = {
                            Text(text = stringResource(R.string.assistant_page_global_memory_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = assistant.useGlobalMemory,
                                onCheckedChange = {
                                    onUpdateAssistant(assistant.copy(useGlobalMemory = it))
                                }
                            )
                        }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.assistant_page_manage_memory_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .align(Alignment.CenterStart)
                    )

                    IconButton(
                        onClick = {
                            memoryDialogState.open(AssistantMemory(0, ""))
                        },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = null
                        )
                    }
                }

                memories.fastForEach { memory ->
                    key(memory.id) {
                        MemoryItem(
                            memory = memory,
                            onEditMemory = {
                                memoryDialogState.open(it)
                            },
                            onDeleteMemory = {
                                pendingDeleteMemory = it
                            }
                        )
                    }
                }
            }

            MemoryMode.EXTERNAL -> {
                CardGroup(title = { Text("外置记忆库配置") }) {
                    item(
                        headlineContent = { Text("外置记忆库") },
                        supportingContent = {
                            val count = assistant.externalMemoryIds.size
                            Text(if (count == 0) "未配置" else "已选择 ${count} 个记忆库")
                        },
                        trailingContent = {
                            IconButton(onClick = { showExternalMemoryPicker = true }) {
                                Icon(HugeIcons.Brain01, contentDescription = null)
                            }
                        },
                        onClick = { showExternalMemoryPicker = true }
                    )
                }
            }

            MemoryMode.LEGACY_SIMPLE -> {
                CardGroup(title = { Text("旧版记忆配置") }) {
                    item(
                        headlineContent = { Text(stringResource(R.string.assistant_page_global_memory)) },
                        supportingContent = {
                            Text(text = stringResource(R.string.assistant_page_global_memory_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = assistant.useGlobalMemory,
                                onCheckedChange = {
                                    onUpdateAssistant(assistant.copy(useGlobalMemory = it))
                                }
                            )
                        }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.assistant_page_manage_memory_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .align(Alignment.CenterStart)
                    )

                    IconButton(
                        onClick = {
                            memoryDialogState.open(AssistantMemory(0, ""))
                        },
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = null
                        )
                    }
                }

                memories.fastForEach { memory ->
                    key(memory.id) {
                        MemoryItem(
                            memory = memory,
                            onEditMemory = {
                                memoryDialogState.open(it)
                            },
                            onDeleteMemory = {
                                pendingDeleteMemory = it
                            }
                        )
                    }
                }
            }

            MemoryMode.OFF -> {
                // 关闭模式下无需额外记忆管理项
            }
        }

        // 通用辅助设置（最近聊天上下文与时间提醒）
        CardGroup(title = { Text("对话辅助") }) {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_recent_chats)) },
                supportingContent = {
                    Text(text = stringResource(R.string.assistant_page_recent_chats_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableRecentChatsReference,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableRecentChatsReference = it
                                )
                            )
                        }
                    )
                }
            )
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder)) },
                supportingContent = {
                    Text(text = stringResource(R.string.assistant_page_time_reminder_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.enableTimeReminder,
                        onCheckedChange = {
                            onUpdateAssistant(
                                assistant.copy(
                                    enableTimeReminder = it
                                )
                            )
                        }
                    )
                }
            )
        }

        CardGroup(title = { Text("主动唤醒") }) {
            item(
                headlineContent = { Text("后台自主活动") },
                supportingContent = {
                    Text(
                        if (assistant.autonomousWakeEnabled) "已启用：后台延续事项与自主行动"
                        else "已关闭：点击设置后台运行间隔并查看活动记录"
                    )
                },
                trailingContent = {
                    Icon(HugeIcons.ArrowRight01, contentDescription = null)
                },
                onClick = {
                    navController.navigate(Screen.AssistantDesire(assistant.id.toString()))
                }
            )
        }
    }

    RikkaConfirmDialog(
        show = pendingDeleteMemory != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteMemory?.let(onDeleteMemory)
            pendingDeleteMemory = null
        },
        onDismiss = { pendingDeleteMemory = null },
        text = {
            Text(
                text = pendingDeleteMemory?.content.orEmpty(),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }
    )

    if (showExternalMemoryPicker) {
        ExternalMemoryPickerDialog(
            externalMemories = externalMemories,
            selectedIds = assistant.externalMemoryIds,
            onDismiss = { showExternalMemoryPicker = false },
            onConfirm = { selectedIds ->
                onUpdateExternalMemoryIds(selectedIds)
                showExternalMemoryPicker = false
            }
        )
    }
}

@Composable
private fun ExternalMemoryPickerDialog(
    externalMemories: List<ExternalMemory>,
    selectedIds: Set<Uuid>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Uuid>) -> Unit,
) {
    var currentSelection by remember { mutableStateOf(selectedIds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择外置记忆库") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (externalMemories.isEmpty()) {
                    Text(
                        text = "暂无可用的外置记忆库，请先在扩展管理中创建。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    externalMemories.forEach { memory ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = memory.id in currentSelection,
                                onCheckedChange = { checked ->
                                    currentSelection = if (checked) {
                                        currentSelection + memory.id
                                    } else {
                                        currentSelection - memory.id
                                    }
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = memory.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = memory.supabaseUrl,
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
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(currentSelection) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun MemoryItem(
    memory: AssistantMemory,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "#${memory.id}",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                    text = memory.content,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(
                onClick = { onEditMemory(memory) }
            ) {
                Icon(HugeIcons.PencilEdit01, null)
            }
            IconButton(
                onClick = { onDeleteMemory(memory) }
            ) {
                Icon(
                    HugeIcons.Delete01,
                    stringResource(R.string.assistant_page_delete)
                )
            }
        }
    }
}
