/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.assistant.detail

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.data.ai.desire.DesireActiveSendPolicy
import me.rerere.rikkahub.data.ai.desire.DesireRepository
import me.rerere.rikkahub.data.ai.desire.DesireState
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.util.Locale
import kotlin.math.roundToInt

private data class DriveRuleMeta(
    val key: String,
    val name: String,
    val baseline: Double,
    val defaultThreshold: Double,
)

private val DRIVE_RULES = listOf(
    DriveRuleMeta("attachment", "想念感", 40.0, 60.0),
    DriveRuleMeta("curiosity", "好奇心", 40.0, 55.0),
    DriveRuleMeta("reflection", "反思欲", 30.0, 45.0),
    DriveRuleMeta("duty", "责任感", 40.0, 55.0),
    DriveRuleMeta("social", "社交欲", 30.0, 45.0),
    DriveRuleMeta("fatigue", "疲劳值", 20.0, 55.0),
    DriveRuleMeta("intimacy", "亲密渴求", 40.0, 65.0),
    DriveRuleMeta("stress", "压力值", 20.0, 55.0),
    DriveRuleMeta("joy", "分享喜悦", 50.0, 68.0),
)

private data class PromptEditDialogState(
    val key: String,
    val title: String,
    val initialText: String,
    val defaultText: String,
)

@Composable
fun AssistantDesireActiveRulesPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    val desireRepo = koinInject<DesireRepository>()
    var desireState by remember { mutableStateOf<DesireState?>(null) }
    LaunchedEffect(id) {
        runCatching {
            desireState = desireRepo.getOrCreateState(id)
        }
    }

    var editDialogState by remember { mutableStateOf<PromptEditDialogState?>(null) }
    var editInputText by remember { mutableStateOf("") }
    var showResetAllDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("主动联络规则与动机") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { showResetAllDialog = true }) {
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 全局失联门槛设置卡片
            CardGroup(title = { Text("失联与等待门槛") }) {
                // 超久未联络硬触发
                item(
                    headlineContent = { Text("超久未联系主动找我") },
                    supportingContent = {
                        val hours = assistant.desireActiveSendLongAbsentHours
                        Text(
                            if (assistant.desireActiveSendLongAbsentEnabled) {
                                "超过 ${String.format(Locale.getDefault(), "%.1f", hours)} 小时未聊天时，即使内在驱动未达标也直接主动找你"
                            } else {
                                "已关闭久未联系硬触发机制"
                            }
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = assistant.desireActiveSendLongAbsentEnabled,
                            onCheckedChange = { checked ->
                                vm.update(assistant.copy(desireActiveSendLongAbsentEnabled = checked))
                            }
                        )
                    }
                )

                AnimatedVisibility(
                    visible = assistant.desireActiveSendLongAbsentEnabled,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "触发等待门槛",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${String.format(Locale.getDefault(), "%.1f", assistant.desireActiveSendLongAbsentHours)} 小时",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Slider(
                            value = assistant.desireActiveSendLongAbsentHours.toFloat(),
                            onValueChange = { value ->
                                val stepped = (value * 2f).roundToInt() / 2f
                                vm.update(assistant.copy(desireActiveSendLongAbsentHours = stepped.toDouble()))
                            },
                            valueRange = 2.0f..24.0f,
                            steps = 43
                        )

                        // 提示词预览与修改
                        val defaultLongAbsentPrompt = DesireActiveSendPolicy.getActiveSendPrompt(
                            null, "long_absent", settings.displaySetting.userNickname,
                        )
                        val currentLongAbsentPrompt = assistant.desireActiveSendPrompts["long_absent"]
                            ?.takeIf { it.isNotBlank() } ?: defaultLongAbsentPrompt
                        val isCustomized = assistant.desireActiveSendPrompts.containsKey("long_absent")

                        PromptPreviewCard(
                            prompt = currentLongAbsentPrompt,
                            isCustomized = isCustomized,
                            onEdit = {
                                editInputText = currentLongAbsentPrompt
                                editDialogState = PromptEditDialogState(
                                    key = "long_absent",
                                    title = "久未联系契机",
                                    initialText = currentLongAbsentPrompt,
                                    defaultText = defaultLongAbsentPrompt
                                )
                            },
                            onReset = {
                                val updated = assistant.desireActiveSendPrompts.toMutableMap()
                                updated.remove("long_absent")
                                vm.update(assistant.copy(desireActiveSendPrompts = updated))
                                Toast.makeText(context, "已恢复久未联系默认提示词", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }

                // 驱动力触发的最低离线门槛
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "驱动力评估离线门槛",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "离开超过此时长后才允许根据驱动力主动发消息",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Text(
                            text = "${String.format(Locale.getDefault(), "%.1f", assistant.desireActiveSendMinAbsentHours)} 小时",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Slider(
                        value = assistant.desireActiveSendMinAbsentHours.toFloat(),
                        onValueChange = { value ->
                            val stepped = (value * 2f).roundToInt() / 2f
                            vm.update(assistant.copy(desireActiveSendMinAbsentHours = stepped.toDouble()))
                        },
                        valueRange = 0.5f..12.0f,
                        steps = 22
                    )
                }
            }

            // 2. 九维内在驱动力联络规则
            CardGroup(title = { Text("九维内在驱动力触发规则") }) {
                DRIVE_RULES.forEach { rule ->
                    val defaultPrompt = DesireActiveSendPolicy.getActiveSendPrompt(
                        null, rule.key, settings.displaySetting.userNickname,
                    )
                    val isEnabled = assistant.desireActiveSendDriveEnabled[rule.key] ?: true
                    val currentThreshold = assistant.desireActiveSendDriveThresholds[rule.key]
                        ?: rule.defaultThreshold
                    val isThresholdCustomized = assistant.desireActiveSendDriveThresholds.containsKey(rule.key) &&
                        assistant.desireActiveSendDriveThresholds[rule.key] != rule.defaultThreshold

                    val currentPrompt = assistant.desireActiveSendPrompts[rule.key]
                        ?.takeIf { it.isNotBlank() } ?: defaultPrompt
                    val isPromptCustomized = assistant.desireActiveSendPrompts.containsKey(rule.key) &&
                        assistant.desireActiveSendPrompts[rule.key] != defaultPrompt

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 标题行与开关
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = rule.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "(${rule.key})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { checked ->
                                    val updated = assistant.desireActiveSendDriveEnabled.toMutableMap()
                                    updated[rule.key] = checked
                                    vm.update(assistant.copy(desireActiveSendDriveEnabled = updated))
                                }
                            )
                        }

                        // 展开内容：阈值与提示词
                        AnimatedVisibility(
                            visible = isEnabled,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val currentDriveVal = desireState?.drives?.get(rule.key)?.value

                                // 阈值滑块
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "触发阈值",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (currentDriveVal != null) {
                                            Text(
                                                text = "当前 ${currentDriveVal.roundToInt()}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                            Text(
                                                text = "·",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                        Text(
                                            text = "基线 ${rule.baseline.roundToInt()}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                        Text(
                                            text = "·",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                        Text(
                                            text = "阈值 ${currentThreshold.roundToInt()}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isThresholdCustomized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "(默认 ${rule.defaultThreshold.toInt()})",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                        if (isThresholdCustomized) {
                                            IconButton(
                                                modifier = Modifier.size(24.dp),
                                                onClick = {
                                                    val updated = assistant.desireActiveSendDriveThresholds.toMutableMap()
                                                    updated.remove(rule.key)
                                                    vm.update(assistant.copy(desireActiveSendDriveThresholds = updated))
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = HugeIcons.Refresh01,
                                                    contentDescription = "恢复默认阈值",
                                                    modifier = Modifier.size(14.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }

                                Slider(
                                    value = currentThreshold.toFloat(),
                                    onValueChange = { value ->
                                        val stepped = (value).roundToInt().toDouble()
                                        val updated = assistant.desireActiveSendDriveThresholds.toMutableMap()
                                        updated[rule.key] = stepped
                                        vm.update(assistant.copy(desireActiveSendDriveThresholds = updated))
                                    },
                                    valueRange = 0.0f..100.0f,
                                    steps = 99
                                )

                                if (currentThreshold <= rule.baseline) {
                                    Text(
                                        text = "该阈值不高于常态基线，即使没有明显波动也可能满足主动联络条件。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }

                                // 契机提示词区域
                                PromptPreviewCard(
                                    prompt = currentPrompt,
                                    isCustomized = isPromptCustomized,
                                    onEdit = {
                                        editInputText = currentPrompt
                                        editDialogState = PromptEditDialogState(
                                            key = rule.key,
                                            title = rule.name,
                                            initialText = currentPrompt,
                                            defaultText = defaultPrompt
                                        )
                                    },
                                    onReset = {
                                        val updated = assistant.desireActiveSendPrompts.toMutableMap()
                                        updated.remove(rule.key)
                                        vm.update(assistant.copy(desireActiveSendPrompts = updated))
                                        Toast.makeText(context, "已恢复【${rule.name}】默认契机提示词", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 编辑契机提示词对话框
    editDialogState?.let { state ->
        AlertDialog(
            onDismissRequest = { editDialogState = null },
            title = { Text("编辑【${state.title}】契机提示词") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "该提示词将在满足触发条件时注入给 AI，指导主动发消息的动机与契机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    OutlinedTextField(
                        value = editInputText,
                        onValueChange = { editInputText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        placeholder = { Text(state.defaultText) },
                        maxLines = 5
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { editInputText = state.defaultText }
                        ) {
                            Text("填入默认")
                        }
                        Text(
                            text = "${editInputText.length} 字",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val text = editInputText.trim()
                        val updated = assistant.desireActiveSendPrompts.toMutableMap()
                        if (text.isEmpty() || text == state.defaultText) {
                            updated.remove(state.key)
                        } else {
                            updated[state.key] = text
                        }
                        vm.update(assistant.copy(desireActiveSendPrompts = updated))
                        editDialogState = null
                        Toast.makeText(context, "已保存契机提示词", Toast.LENGTH_SHORT).show()
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

    // 全部恢复默认确认弹窗
    if (showResetAllDialog) {
        AlertDialog(
            onDismissRequest = { showResetAllDialog = false },
            title = { Text("恢复全部主动联络规则") },
            text = { Text("是否将所有失联门槛、九维触发阈值与契机提示词全部恢复为系统默认值？") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.update(
                            assistant.copy(
                                desireActiveSendMinAbsentHours = 3.0,
                                desireActiveSendLongAbsentHours = 6.0,
                                desireActiveSendLongAbsentEnabled = true,
                                desireActiveSendDriveEnabled = emptyMap(),
                                desireActiveSendDriveThresholds = emptyMap(),
                                desireActiveSendPrompts = emptyMap(),
                            )
                        )
                        showResetAllDialog = false
                        Toast.makeText(context, "已重置所有主动联络规则", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("重置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetAllDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun PromptPreviewCard(
    prompt: String,
    isCustomized: Boolean,
    onEdit: () -> Unit,
    onReset: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "契机提示词",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (isCustomized) {
                        Text(
                            text = "已自定义",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isCustomized) {
                    IconButton(
                        modifier = Modifier.size(28.dp),
                        onClick = onReset
                    ) {
                        Icon(
                            imageVector = HugeIcons.Refresh01,
                            contentDescription = "恢复默认提示词",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(
                    modifier = Modifier.size(28.dp),
                    onClick = onEdit
                ) {
                    Icon(
                        imageVector = HugeIcons.Edit01,
                        contentDescription = "编辑提示词",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}
