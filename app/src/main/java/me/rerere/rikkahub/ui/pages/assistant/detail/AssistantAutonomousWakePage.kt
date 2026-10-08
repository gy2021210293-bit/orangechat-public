package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.desire.AutonomousWakeScheduler
import me.rerere.rikkahub.data.ai.desire.formatAutonomousWakeTranscript
import me.rerere.rikkahub.data.ai.desire.AutonomousWakeForegroundService
import me.rerere.rikkahub.data.ai.desire.DesireRepository
import me.rerere.rikkahub.data.ai.desire.db.AutonomousWakeRunEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousItemEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousWakePlanEntity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.text.DateFormat
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

@Composable
fun AssistantAutonomousWakePage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsState()
    val scheduler = koinInject<AutonomousWakeScheduler>()
    val repository = koinInject<DesireRepository>()
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var expandedToolRuns by remember(id) { mutableStateOf(emptySet<String>()) }
    var expandedDetailRuns by remember(id) { mutableStateOf(emptySet<String>()) }
    var diagnosticsExpanded by remember(id) { mutableStateOf(false) }
    var runs by remember { mutableStateOf<List<AutonomousWakeRunEntity>>(emptyList()) }
    var visibleRunCount by remember(id) { mutableStateOf(10) }
    var items by remember(id) { mutableStateOf<List<AutonomousItemEntity>>(emptyList()) }
    var wakePlan by remember { mutableStateOf<AutonomousWakePlanEntity?>(null) }
    var diagnostics by remember(id) { mutableStateOf("") }
    var recordsError by remember(id) { mutableStateOf("") }
    var currentItem by remember { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<AutonomousItemEntity?>(null) }
    var showNewItemDialog by remember { mutableStateOf(false) }
    var deletingItem by remember { mutableStateOf<AutonomousItemEntity?>(null) }
    var intervalDraft by remember { mutableStateOf(assistant.autonomousWakeIntervalHours.toFloat()) }
    suspend fun refresh() {
        repository.recoverStaleAutonomousRuns(id)
        runs = repository.listAutonomousRuns(id, visibleRunCount + 1)
        currentItem = repository.getAutonomousState(id)?.currentItem.orEmpty()
        items = repository.listAutonomousItems(id)
        wakePlan = repository.getWakePlan(id)
    }
    LaunchedEffect(id) {
        while (true) {
            diagnostics = scheduler.getDiagnostics(id)
            delay(1_000)
        }
    }
    LaunchedEffect(id, visibleRunCount) {
        while (true) {
            runCatching { refresh() }.onSuccess { recordsError = "" }.onFailure {
                recordsError = "运行记录读取失败：${it.javaClass.simpleName}: ${it.message}"
            }
            delay(2_000)
        }
    }
    LaunchedEffect(assistant.autonomousWakeIntervalHours) {
        intervalDraft = assistant.autonomousWakeIntervalHours.toFloat()
    }

    // 在页面层读取列表，使 CardGroup 的内容参数随查询结果变化，避免空态内容被跳过重组。
    val runRows = runs.take(visibleRunCount)
    Scaffold(topBar = {
        LargeFlexibleTopAppBar(title = { Text("主动唤醒（${assistant.name}）") }, navigationIcon = { BackButton() })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CardGroup(title = { Text("后台运行") }) {
                item(
                    headlineContent = { Text("启用主动唤醒") },
                    supportingContent = { Text("开启后立即执行一次后台唤醒，之后按设定间隔运行；是否联系你由 AI 决定") },
                    trailingContent = {
                        Switch(checked = assistant.autonomousWakeEnabled, onCheckedChange = { enabled ->
                            scheduler.recordDiagnostic(id, if (enabled) "用户打开开关，准备保存设置" else "用户关闭开关")
                            val updateJob = vm.update(assistant.copy(autonomousWakeEnabled = enabled))
                            scope.launch(Dispatchers.IO) {
                                updateJob.join()
                                if (updateJob.isCancelled) {
                                    scheduler.recordDiagnostic(id, "开关保存任务失败或被取消，未启动唤醒")
                                    return@launch
                                }
                                if (enabled) {
                                    runCatching {
                                        scheduler.recordDiagnostic(id, "设置保存任务已完成")
                                    val plan = scheduler.schedule(
                                        id,
                                        delayMs = 0L,
                                        enabledOverride = true,
                                        intervalHoursOverride = assistant.autonomousWakeIntervalHours,
                                    )
                                        // 前台点击直接启动，双通道调度在服务启动受限时兜底。
                                        runCatching {
                                            scheduler.recordDiagnostic(id, "正在请求立即启动服务")
                                        plan?.let { AutonomousWakeForegroundService.start(context, id, it.scheduledAt, it.version) }
                                        }.onFailure {
                                            scheduler.recordDiagnostic(id, "立即启动服务失败", it)
                                            android.util.Log.w("AutonomousWakePage", "Immediate wake service start failed", it)
                                        }
                                    }.onFailure {
                                        scheduler.recordDiagnostic(id, "后台调度登记失败，未执行立即启动", it)
                                    }
                                } else scheduler.cancel(id)
                            }
                        })
                    },
                )
                item(
                    headlineContent = { Text("后台工具权限") },
                    supportingContent = {
                        Text(if (settings.proactiveMessageSetting.allowAllToolsAndPlugins) "已开启：沿用全局设置，可调用已启用的工具和插件" else "已关闭：外部工具和插件不可用；内置事项与分享工具仍可用")
                    },
                )
                item(
                    headlineContent = { Text("唤醒间隔：${assistant.autonomousWakeIntervalHours} 小时") },
                    supportingContent = {
                        Slider(
                            value = intervalDraft,
                            onValueChange = { intervalDraft = it },
                            onValueChangeFinished = {
                                val hours = intervalDraft.toInt().coerceIn(1, 24)
                                if (hours != assistant.autonomousWakeIntervalHours) {
                                    vm.update(assistant.copy(autonomousWakeIntervalHours = hours))
                                    if (assistant.autonomousWakeEnabled) scope.launch(Dispatchers.IO) {
                                        scheduler.schedule(id, intervalHoursOverride = hours)
                                    }
                                }
                            },
                            valueRange = 1f..24f,
                            steps = 22,
                            enabled = assistant.autonomousWakeEnabled,
                        )
                    },
                )
            }
            wakePlan?.let { plan ->
                CardGroup(title = { Text("下一次唤醒计划") }) {
                    item(
                        headlineContent = { Text("自主候选：${formatWakeTime(plan.autonomousCandidateAt)}") },
                        supportingContent = { Text("独立于事项到期时间的下一轮候选") },
                    )
                    if (plan.itemCandidateAt != null) {
                        item(
                            headlineContent = { Text("最近事项到期：${formatWakeTime(plan.itemCandidateAt)}") },
                            supportingContent = { Text("完成或改期的事项不再参与排程") },
                        )
                    }
                    item(
                        headlineContent = { Text("最终计划：${formatWakeTime(plan.scheduledAt)} · ${plan.source}") },
                        supportingContent = { Text("${plan.reason} · 版本 ${plan.version} · ${plan.status}") },
                    )
                }
            }
            key(id, items) {
                CardGroup(title = { Text("后台事项（${items.size}）") }) {
                    item(
                        headlineContent = { Text("手动添加事项") },
                        trailingContent = {
                            TextButton(onClick = { showNewItemDialog = true }) { Text("添加") }
                        },
                    )
                    if (items.isEmpty()) item(headlineContent = { Text("暂无有效事项") })
                    items.forEach { autonomousItem ->
                        item(
                            key = autonomousItem.id,
                            headlineContent = { Text(autonomousItem.content) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("${autonomousItem.progress.ifBlank { "暂无进展" }} · 到期：${formatIsoTime(autonomousItem.resumeAt)}")
                                    Row {
                                        TextButton(onClick = { editingItem = autonomousItem }) { Text("编辑") }
                                        TextButton(onClick = { deletingItem = autonomousItem }) { Text("删除") }
                                    }
                                }
                            },
                        )
                    }
                }
            }
            if (diagnostics.isNotBlank() || recordsError.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("唤醒状态", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded }) {
                                Text(if (diagnosticsExpanded) "收起" else "查看详情")
                            }
                        }
                        if (diagnosticsExpanded) {
                            if (diagnostics.isNotBlank()) Text(diagnostics)
                            Text("运行记录查询：已读取 ${runRows.size} 条")
                        }
                        if (recordsError.isNotBlank()) Text(recordsError, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (currentItem.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("上轮想法", style = MaterialTheme.typography.titleMedium)
                        Text(currentItem)
                    }
                }
            }
            // 列表变化时重建 CardGroup，避免可变 scope 收集的内容沿用首次空态。
            key(id, runRows) {
                CardGroup(title = { Text("运行记录") }) {
                    if (runRows.isEmpty()) item(headlineContent = { Text("暂无后台运行记录") })
                    runRows.forEach { run ->
                        val calls = runCatching { Json.parseToJsonElement(run.toolCallsJson).jsonArray }
                            .getOrDefault(JsonArray(emptyList()))
                        item(
                            key = run.id,
                            headlineContent = { Text("${formatWakeTime(run.startedAt ?: run.scheduledAt)} · ${statusLabel(run.status)}") },
                            supportingContent = {
                                val toolsExpanded = run.id in expandedToolRuns
                                val detailsExpanded = run.id in expandedDetailRuns
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (run.summary.isNotBlank()) Text(run.summary)
                                    if (run.outcome.isNotBlank() && run.outcome != run.summary) Text(run.outcome)
                                    if (run.shouldContact && run.messageText.isNotBlank()) Text("分享：${run.messageText}")
                                    if (run.errorText.isNotBlank()) Text(run.errorText, color = MaterialTheme.colorScheme.error)
                                    if (calls.isNotEmpty()) {
                                        TextButton(onClick = {
                                            expandedToolRuns = if (toolsExpanded) expandedToolRuns - run.id
                                                else expandedToolRuns + run.id
                                        }) {
                                            Text("工具调用 · ${calls.size} 次${if (toolsExpanded) "（收起）" else ""}")
                                        }
                                        AnimatedVisibility(visible = toolsExpanded) {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                calls.forEach { call ->
                                                    val fields = runCatching { call.jsonObject }.getOrNull() ?: return@forEach
                                                    val name = (fields["name"] as? JsonPrimitive)?.contentOrNull ?: "未知工具"
                                                    val status = (fields["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                                                    var expanded by remember(run.id, call) { mutableStateOf(false) }
                                                    TextButton(onClick = { expanded = !expanded }) {
                                                        Text("$name · $status${if (expanded) "（收起）" else "（查看参数与返回）"}")
                                                    }
                                                    if (expanded) {
                                                        fields["input"]?.let { value -> Text("参数：${runCatching { value.jsonPrimitive.contentOrNull }.getOrNull() ?: value.toString()}") }
                                                        fields["result"]?.let { value -> Text("返回：${runCatching { value.jsonPrimitive.contentOrNull }.getOrNull() ?: value.toString()}") }
                                                    }
                                                    HorizontalDivider()
                                                }
                                            }
                                        }
                                    }
                                    if (run.nextStep.isNotBlank()) Text("下一步：${run.nextStep}")
                                    TextButton(onClick = {
                                        expandedDetailRuns = if (detailsExpanded) expandedDetailRuns - run.id
                                            else expandedDetailRuns + run.id
                                    }) {
                                        Text("运行详情${if (detailsExpanded) "（收起）" else ""}")
                                    }
                                    AnimatedVisibility(visible = detailsExpanded) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("计划：${formatWakeTime(run.scheduledAt)}")
                                            Text("开始：${formatWakeTime(run.startedAt)} · 完成：${formatWakeTime(run.finishedAt)}")
                                            Text("等待建议：${run.nextWakeDelayMinutes?.let { "$it 分钟" } ?: "默认"} · 实际采用：${run.chosenWakeDelayMinutes?.let { "$it 分钟" } ?: "—"}")
                                            if (run.nextWakeReason.isNotBlank()) Text("建议理由：${run.nextWakeReason}")
                                            if (run.wakeAdjustmentReason.isNotBlank()) Text("调整反馈：${run.wakeAdjustmentReason}")
                                            if (run.deliveryFeedback.isNotBlank()) Text("投递反馈：${run.deliveryFeedback}")
                                            var transcriptExpanded by remember(run.id) { mutableStateOf(false) }
                                            TextButton(onClick = { transcriptExpanded = !transcriptExpanded }) {
                                                Text("完整生成过程${if (transcriptExpanded) "（收起）" else "（展开）"}")
                                            }
                                            if (transcriptExpanded) {
                                                WakeTranscript(run.transcriptJson)
                                            }
                                            TextButton(onClick = {
                                                clipboard.setText(AnnotatedString(formatWakeRunForCopy(run)))
                                            }) { Text("复制全部") }
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
            if (runs.size > visibleRunCount) {
                TextButton(onClick = { visibleRunCount += 10 }) { Text("加载更早的记录") }
            }
        }
    }

    if (showNewItemDialog || editingItem != null) {
        AutonomousItemDialog(
            item = editingItem,
            onDismiss = {
                showNewItemDialog = false
                editingItem = null
            },
            onSave = { content, progress, resumeAt ->
                if (editingItem == null) {
                    repository.createAutonomousItem(
                        assistantId = id,
                        id = kotlin.uuid.Uuid.random().toString(),
                        content = content,
                        resumeAt = resumeAt,
                        progress = progress,
                    )
                } else {
                    repository.updateAutonomousItem(
                        assistantId = id,
                        id = editingItem!!.id,
                        content = content,
                        progress = progress,
                        resumeAt = resumeAt,
                        updateResumeAt = true,
                    )
                }
                refresh()
            },
        )
    }

    deletingItem?.let { itemToDelete ->
        AlertDialog(
            onDismissRequest = { deletingItem = null },
            title = { Text("删除事项？") },
            text = { Text("“${itemToDelete.content}”将从有效事项中移除，历史记录会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching {
                            repository.setAutonomousItemStatus(id, itemToDelete.id, "cancelled")
                            refresh()
                        }.onFailure {
                            recordsError = "事项删除失败：${it.message}"
                        }
                        deletingItem = null
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletingItem = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AutonomousItemDialog(
    item: AutonomousItemEntity?,
    onDismiss: () -> Unit,
    onSave: suspend (content: String, progress: String, resumeAt: String?) -> Unit,
) {
    var content by remember(item?.id) { mutableStateOf(item?.content.orEmpty()) }
    var progress by remember(item?.id) { mutableStateOf(item?.progress.orEmpty()) }
    var resumeAt by remember(item?.id) { mutableStateOf(formatResumeAtForInput(item?.resumeAt)) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "添加后台事项" else "编辑后台事项") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("事项内容") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = progress,
                    onValueChange = { progress = it },
                    label = { Text("进展") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = resumeAt,
                    onValueChange = { resumeAt = it },
                    label = { Text("到期时间（可选）") },
                    placeholder = { Text("2026-10-06 18:30") },
                    supportingText = { Text("格式：yyyy-MM-dd HH:mm；留空表示无到期时间") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                val normalizedContent = content.trim()
                val parsedResumeAt = if (resumeAt.isBlank()) {
                    null
                } else {
                    runCatching {
                        LocalDateTime.parse(resumeAt.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                            .atZone(ZoneId.systemDefault())
                            .toOffsetDateTime()
                            .toString()
                    }.getOrNull()
                }
                when {
                    normalizedContent.isBlank() -> error = "请填写事项内容"
                    resumeAt.isNotBlank() && parsedResumeAt == null -> error = "到期时间格式不正确，请使用 yyyy-MM-dd HH:mm"
                    else -> scope.launch {
                        saving = true
                        error = ""
                        runCatching { onSave(normalizedContent, progress.trim(), parsedResumeAt) }
                            .onSuccess { onDismiss() }
                            .onFailure { error = it.message ?: "保存失败" }
                        saving = false
                    }
                }
            }) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun formatResumeAtForInput(value: String?): String = value?.let {
    runCatching {
        OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrDefault("")
}.orEmpty()

@Composable
private fun WakeTranscript(raw: String) {
    val messages = remember(raw) {
        runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(UIMessage.serializer()), raw) }
            .getOrNull()
    }
    if (messages.isNullOrEmpty()) {
        Text(formatAutonomousWakeTranscript(raw))
    } else {
        messages.forEachIndexed { index, message ->
            Text("${index + 1}. AI · ${message.createdAt}", style = MaterialTheme.typography.titleSmall)
            message.parts.forEach { WakeTranscriptPart(it) }
        }
    }
}

@Composable
private fun WakeTranscriptPart(part: UIMessagePart) {
    when (part) {
        is UIMessagePart.Tool -> {
            var expanded by remember(part.toolCallId) { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) {
                Text("工具：${part.toolName} · ${if (part.isExecuted) "已执行" else "尚无返回"}${if (expanded) "（收起）" else "（展开）"}")
            }
            if (expanded) {
                Text("调用 ID：${part.toolCallId} · 审批状态：${part.approvalState}")
                Text("参数：\n${part.input}")
                Text("返回内容：")
                part.output.forEach { WakeTranscriptPart(it) }
            }
        }
        is UIMessagePart.Text -> Text(part.text)
        is UIMessagePart.Reasoning -> if (part.reasoning.isNotBlank()) Text("模型返回的思考内容：\n${part.reasoning}")
        else -> Text(Json.encodeToString(UIMessagePart.serializer(), part))
    }
}

private fun formatWakeRunForCopy(run: AutonomousWakeRunEntity): String = buildString {
    appendLine("状态：${statusLabel(run.status)}")
    appendLine("计划：${formatWakeTime(run.scheduledAt)}")
    appendLine("开始：${formatWakeTime(run.startedAt)} · 完成：${formatWakeTime(run.finishedAt)}")
    appendLine("摘要：${run.summary}")
    appendLine("结果与感想：${run.outcome}")
    appendLine("下一步：${run.nextStep}")
    appendLine("已联系用户：${run.shouldContact} · 分享内容：${run.messageText}")
    appendLine("完整生成过程：\n${formatAutonomousWakeTranscript(run.transcriptJson)}")
    appendLine("工具动作：\n${run.toolCallsJson}")
    appendLine("等待建议：${run.nextWakeDelayMinutes?.let { "$it 分钟" } ?: "默认"}")
    appendLine("实际采用：${run.chosenWakeDelayMinutes?.let { "$it 分钟" } ?: "—"}")
    appendLine("建议理由：${run.nextWakeReason}")
    appendLine("调整反馈：${run.wakeAdjustmentReason}")
    appendLine("投递反馈：${run.deliveryFeedback}")
    appendLine("异常或格式反馈：${run.errorText}")
}

private fun statusLabel(status: String) = when (status) {
    "waiting" -> "等待聊天空闲"
    "running" -> "运行中"
    "completed" -> "已完成"
    "interrupted" -> "已中断"
    "failed" -> "失败"
    "skipped" -> "已跳过"
    else -> status
}

private fun formatWakeTime(epochMs: Long?): String =
    epochMs?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "—"

private fun formatIsoTime(value: String?): String = value?.let {
    runCatching { DateFormat.getDateTimeInstance().format(Date(java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli())) }
        .getOrDefault(it)
} ?: "无"
