/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.assistant.detail

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.desire.DesireRepository
import me.rerere.rikkahub.data.ai.desire.DesireScheduler
import me.rerere.rikkahub.data.ai.desire.DesireState
import me.rerere.rikkahub.data.ai.desire.db.DesireStateEntity
import me.rerere.rikkahub.data.ai.desire.db.DesireTickLogEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AssistantDesirePage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current

    val desireRepo = koinInject<DesireRepository>()
    val desireScheduler = koinInject<DesireScheduler>()

    var desireState by remember { mutableStateOf<DesireState?>(null) }
    var stateEntity by remember { mutableStateOf<DesireStateEntity?>(null) }
    var recentLogs by remember { mutableStateOf<List<DesireTickLogEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isTicking by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    suspend fun refreshData() {
        isLoading = true
        try {
            val s = desireRepo.getOrCreateState(id)
            val e = desireRepo.getStateEntity(id)
            val l = desireRepo.listRecentLogs(id, limit = 10)
            desireState = s
            stateEntity = e
            recentLogs = l
        } catch (err: Exception) {
            android.util.Log.e("AssistantDesirePage", "Failed to load desire state", err)
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(id) {
        refreshData()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("情感与欲望 (${assistant.name})") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch { refreshData() }
                        }
                    ) {
                        Icon(HugeIcons.Refresh01, contentDescription = "刷新")
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
            // 1. 设置项开关卡片
            CardGroup(title = { Text("系统开关") }) {
                item(
                    headlineContent = { Text("启用情感与欲望引擎") },
                    supportingContent = { Text("基于九维驱动力自然演化心理状态、念头与主动联络契机") },
                    trailingContent = {
                        Switch(
                            checked = assistant.desireSystemEnabled,
                            onCheckedChange = { checked ->
                                vm.update(assistant.copy(desireSystemEnabled = checked))
                                scope.launch(Dispatchers.IO) {
                                    if (checked) {
                                        desireScheduler.scheduleNextTick(id)
                                    } else {
                                        desireScheduler.cancel(id)
                                    }
                                    refreshData()
                                }
                            }
                        )
                    }
                )
                item(
                    headlineContent = { Text("允许主动找我") },
                    supportingContent = { Text("当积累了强烈的想念、分享欲或较长时间未联系时主动发来消息") },
                    trailingContent = {
                        Switch(
                            checked = assistant.desireActiveSendEnabled,
                            enabled = assistant.desireSystemEnabled,
                            onCheckedChange = { checked ->
                                vm.update(assistant.copy(desireActiveSendEnabled = checked))
                            }
                        )
                    }
                )

                item(
                    headlineContent = { Text("主动联络规则与动机") },
                    supportingContent = { Text("自定义 9 维内在驱动力触发阈值、开关与契机提示词") },
                    trailingContent = {
                        Icon(HugeIcons.ArrowRight01, contentDescription = null)
                    },
                    onClick = {
                        navController.navigate(Screen.AssistantDesireActiveRules(assistant.id.toString()))
                    }
                )
                item(
                    headlineContent = { Text("念头模板库") },
                    supportingContent = { Text("自定义 9 维念头生成库") },
                    trailingContent = {
                        Icon(HugeIcons.ArrowRight01, contentDescription = null)
                    },
                    onClick = {
                        navController.navigate(Screen.AssistantDesireTemplates(assistant.id.toString()))
                    }
                )
            }

            // 2. 状态信息
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val state = desireState
                if (state != null) {
                    // 九维欲望缺口与内在动机卡片
                    CardGroup(title = { Text("九维欲望缺口与内在动机 (0-100)") }) {
                        Text(
                            text = "数值表示当前尚未满足的行动动机与冲动，而非稳定关系评价。离开期间自然形成缺口，满足后回落。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )

                        val driveLabels = listOf(
                            "attachment" to ("想念感" to 40.0),
                            "curiosity" to ("好奇心" to 40.0),
                            "reflection" to ("反思欲" to 30.0),
                            "duty" to ("责任感" to 40.0),
                            "social" to ("社交欲" to 30.0),
                            "fatigue" to ("疲劳值" to 20.0),
                            "intimacy" to ("亲密渴求" to 40.0),
                            "stress" to ("压力值" to 20.0),
                            "joy" to ("分享喜悦" to 50.0),
                        )

                        driveLabels.forEach { (key, info) ->
                            val (label, defaultBaseline) = info
                            val drive = state.drives[key]
                            val currentValue = drive?.value ?: defaultBaseline
                            val baseline = drive?.baseline ?: defaultBaseline
                            val progress = (currentValue / 100.0).toFloat().coerceIn(0f, 1f)

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "$label ($key)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = String.format(Locale.getDefault(), "%.1f / 基线 %.1f", currentValue, baseline),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = when {
                                        key in listOf("stress", "fatigue", "insecurity") && currentValue > 70.0 -> MaterialTheme.colorScheme.error
                                        currentValue > 80.0 -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.secondary
                                    },
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }
                    }

                    // 念头与执念卡片
                    CardGroup(title = { Text("心中的念头与执念") }) {
                        val activeThoughts = state.thoughts.filter { !it.resolved }
                        if (activeThoughts.isEmpty()) {
                            item(
                                headlineContent = { Text("内心宁静") },
                                supportingContent = { Text("当前暂无活跃的念头或执念") }
                            )
                        } else {
                            activeThoughts.forEach { thought ->
                                item(
                                    headlineContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (thought.isObsession) {
                                                Text(
                                                    text = "[执念] ",
                                                    color = MaterialTheme.colorScheme.error,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            } else {
                                                Text(
                                                    text = "[念头] ",
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Text(thought.content)
                                        }
                                    },
                                    supportingContent = {
                                        Text("强化次数: ${thought.hitCount} | 驱动力: ${thought.sourceDrive}")
                                    },
                                    trailingContent = {
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch(Dispatchers.IO) {
                                                    desireRepo.resolveThought(id, thought.content)
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(context, "念头已解决", Toast.LENGTH_SHORT).show()
                                                    }
                                                    refreshData()
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text("释怀/解决")
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // 心跳与运维卡片
                    CardGroup(title = { Text("动态心跳 (Heartbeat)") }) {
                        val lastTickFormatted = stateEntity?.lastTickAt?.let {
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it))
                        } ?: "尚未记录"
                        val nextTickFormatted = stateEntity?.nextTickAt?.let {
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it))
                        } ?: "尚未安排"

                        item(
                            headlineContent = { Text("心跳统计") },
                            supportingContent = { Text("累计步数: ${state.tickCount} 步") }
                        )
                        item(
                            headlineContent = { Text("上次心跳时间") },
                            supportingContent = { Text(lastTickFormatted) }
                        )
                        item(
                            headlineContent = { Text("计划下次心跳") },
                            supportingContent = { Text(nextTickFormatted) }
                        )
                    }

                    // 最近日志记录
                    if (recentLogs.isNotEmpty()) {
                        CardGroup(title = { Text("近期日志 (最近 ${recentLogs.size} 条)") }) {
                            recentLogs.take(5).forEach { log ->
                                val timeStr = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(log.createdAt))
                                val title = log.eventName ?: "心跳 (tick)"
                                item(
                                    headlineContent = { Text("$title ($timeStr)") },
                                    supportingContent = {
                                        Text("步数: ${log.tickNumber} | 动作: ${log.behaviorHintsJson.take(60)}")
                                    }
                                )
                            }
                        }
                    }

                    // 运维操作按钮
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    isTicking = true
                                    try {
                                        desireScheduler.runTick(id, triggerSource = "manual")
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "心跳执行完成", Toast.LENGTH_SHORT).show()
                                        }
                                        refreshData()
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "心跳执行失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    } finally {
                                        isTicking = false
                                    }
                                }
                            },
                            enabled = !isTicking,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isTicking) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("手动执行一次心跳")
                        }

                        OutlinedButton(
                            onClick = { showResetDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(HugeIcons.Delete01, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("重置为基线")
                        }
                    }
                }
            }
        }
    }

    if (showResetDialog) {
        RikkaConfirmDialog(
            show = showResetDialog,
            title = "重置情感与欲望状态",
            confirmText = "确认重置",
            dismissText = "取消",
            onConfirm = {
                scope.launch(Dispatchers.IO) {
                    desireRepo.resetState(id)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "已重置为初始基线状态", Toast.LENGTH_SHORT).show()
                        showResetDialog = false
                    }
                    refreshData()
                }
            },
            onDismiss = { showResetDialog = false },
            text = {
                Text("确定要重置当前助手的情感与欲望状态吗？这将把九维驱动力恢复到初始默认基线，并清空所有念头与执念记录。现有对话历史、长期记忆卡片不受任何影响。")
            }
        )
    }
}
