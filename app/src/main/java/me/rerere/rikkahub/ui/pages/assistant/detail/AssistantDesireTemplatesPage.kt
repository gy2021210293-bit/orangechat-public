/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.assistant.detail

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.data.ai.desire.DesireThoughtEngine
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private data class CategoryMeta(
    val key: String,
    val name: String,
    val desc: String,
)

private data class TemplateEditDialogState(
    val categoryKey: String,
    val itemIndex: Int? = null,
    val initialText: String = "",
)

private data class ResetCategoryDialogState(
    val categoryKey: String,
    val title: String,
)

@Composable
fun AssistantDesireTemplatesPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current

    var editDialogState by remember { mutableStateOf<TemplateEditDialogState?>(null) }
    var editInputText by remember { mutableStateOf("") }
    var resetCategoryDialogState by remember { mutableStateOf<ResetCategoryDialogState?>(null) }
    var showResetAllDialog by remember { mutableStateOf(false) }

    val thoughtCategories = remember {
        listOf(
            CategoryMeta("attachment", "想念感", "当想念偏离基线时萌生的内心念头"),
            CategoryMeta("curiosity", "好奇心", "当对新鲜事物、知识或对方生活感兴趣时产生的念头"),
            CategoryMeta("reflection", "反思欲", "当想要整理思绪、写日记或倾诉内心感受时产生的念头"),
            CategoryMeta("duty", "责任感", "当牵挂日常生活起居或提醒关照对方时产生的念头"),
            CategoryMeta("social", "社交欲", "当渴望人际互动或寻找话题时产生的念头"),
            CategoryMeta("fatigue", "疲劳值", "当精力透支、想要休息停歇时产生的念头"),
            CategoryMeta("intimacy", "亲密渴求", "当渴望亲近、依偎或身体接触时产生的念头"),
            CategoryMeta("stress", "压力值", "当压力过大、需要安慰或宣泄时产生的念头"),
            CategoryMeta("joy", "分享喜悦", "当心情极佳、想要分享快乐时产生的念头"),
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("念头模板库 (${assistant.name})") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = { showResetAllDialog = true }
                    ) {
                        Icon(HugeIcons.Refresh01, contentDescription = "全部恢复默认")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            thoughtCategories.forEach { meta ->
                val isCustom = meta.key in assistant.desireCustomThoughtTemplates
                val items = if (isCustom) {
                    assistant.desireCustomThoughtTemplates.getValue(meta.key).filter { it.isNotBlank() }
                } else {
                    DesireThoughtEngine.THOUGHT_TEMPLATES[meta.key].orEmpty()
                }

                CategoryCard(
                    title = meta.name,
                    subtitle = "${meta.key} · ${meta.desc}",
                    isCustom = isCustom,
                    items = items,
                    onAdd = {
                        editInputText = ""
                        editDialogState = TemplateEditDialogState(meta.key, null, "")
                    },
                    onEdit = { idx, text ->
                        editInputText = text
                        editDialogState = TemplateEditDialogState(meta.key, idx, text)
                    },
                    onDelete = { idx ->
                        val newList = items.toMutableList().also {
                            if (idx in it.indices) it.removeAt(idx)
                        }.filter { it.isNotBlank() }
                        val updated = assistant.desireCustomThoughtTemplates + (meta.key to newList)
                        vm.update(assistant.copy(desireCustomThoughtTemplates = updated))
                    },
                    onReset = {
                        resetCategoryDialogState = ResetCategoryDialogState(meta.key, meta.name)
                    }
                )
            }
        }
    }

    // 编辑/添加对话框
    editDialogState?.let { state ->
        AlertDialog(
            onDismissRequest = { editDialogState = null },
            title = {
                Text(if (state.itemIndex == null) "添加念头模板" else "编辑念头模板")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "类别：${state.categoryKey}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    OutlinedTextField(
                        value = editInputText,
                        onValueChange = { editInputText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5,
                        placeholder = { Text("请输入念头内容...") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = editInputText.trim()
                        if (text.isEmpty()) {
                            Toast.makeText(context, "内容不能为空", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        val current = if (state.categoryKey in assistant.desireCustomThoughtTemplates) {
                            assistant.desireCustomThoughtTemplates.getValue(state.categoryKey).filter { it.isNotBlank() }
                        } else {
                            DesireThoughtEngine.THOUGHT_TEMPLATES[state.categoryKey].orEmpty()
                        }
                        val updated = if (state.itemIndex == null) {
                            current + text
                        } else {
                            current.toMutableList().also {
                                if (state.itemIndex in it.indices) it[state.itemIndex] = text else it.add(text)
                            }
                        }.filter { it.isNotBlank() }
                        vm.update(
                            assistant.copy(
                                desireCustomThoughtTemplates = assistant.desireCustomThoughtTemplates + (state.categoryKey to updated)
                            )
                        )
                        editDialogState = null
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editDialogState = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 单项类别重置默认确认弹窗
    resetCategoryDialogState?.let { state ->
        RikkaConfirmDialog(
            show = true,
            title = "恢复“${state.title}”为默认",
            confirmText = "确认恢复",
            dismissText = "取消",
            onConfirm = {
                val newMap = assistant.desireCustomThoughtTemplates.toMutableMap().also { it.remove(state.categoryKey) }
                vm.update(assistant.copy(desireCustomThoughtTemplates = newMap))
                resetCategoryDialogState = null
                Toast.makeText(context, "已恢复系统默认", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { resetCategoryDialogState = null },
            text = {
                Text("确定要将“${state.title}”的念头模板清空并恢复为系统内置的默认内容吗？")
            }
        )
    }

    // 全部重置为默认确认框
    if (showResetAllDialog) {
        RikkaConfirmDialog(
            show = showResetAllDialog,
            title = "重置所有念头模板",
            confirmText = "全部恢复默认",
            dismissText = "取消",
            onConfirm = {
                vm.update(
                    assistant.copy(
                        desireCustomThoughtTemplates = emptyMap()
                    )
                )
                showResetAllDialog = false
                Toast.makeText(context, "所有念头模板已全部重置为系统默认", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showResetAllDialog = false },
            text = {
                Text("确定要重置当前助手的所有自定义念头模板吗？此操作不可撤回。")
            }
        )
    }
}

@Composable
private fun CategoryCard(
    title: String,
    subtitle: String,
    isCustom: Boolean,
    items: List<String>,
    onAdd: () -> Unit,
    onEdit: (Int, String) -> Unit,
    onDelete: (Int) -> Unit,
    onReset: () -> Unit,
) {
    CardGroup(
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (isCustom) "(已自定义 · ${items.size}条)" else "(系统内置 · ${items.size}条)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isCustom) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
                        )
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isCustom) {
                        IconButton(
                            onClick = onReset,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                HugeIcons.Refresh01,
                                contentDescription = "恢复此项默认",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    IconButton(
                        onClick = onAdd,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            HugeIcons.Add01,
                            contentDescription = "添加条目",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    ) {
        if (items.isEmpty()) {
            item(
                headlineContent = {
                    Text(
                        text = "暂无模板条目，可点击右上角「+」新增或恢复默认",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            )
        } else {
            items.forEachIndexed { index, itemText ->
                item(
                    key = itemText to items.take(index).count { it == itemText },
                    headlineContent = {
                        Text(
                            text = itemText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    trailingContent = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { onEdit(index, itemText) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    HugeIcons.Edit01,
                                    contentDescription = "编辑",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { onDelete(index) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    HugeIcons.Delete01,
                                    contentDescription = "删除",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}
