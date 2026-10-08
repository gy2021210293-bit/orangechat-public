package me.rerere.rikkahub.ui.pages.assistant.detail

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.affect.AffectiveEngine
import me.rerere.rikkahub.data.ai.affect.AffectiveMode
import me.rerere.rikkahub.data.ai.affect.AffectiveRepository
import me.rerere.rikkahub.data.ai.affect.AffectiveStage
import me.rerere.rikkahub.data.ai.affect.AffectiveState
import me.rerere.rikkahub.data.ai.affect.JevKeyCipher
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AffectiveEnginePage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsState()
    val repository = koinInject<AffectiveRepository>()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var state by remember(id) { mutableStateOf<AffectiveState?>(null) }
    var url by remember(id) { mutableStateOf("") }
    var apiKey by remember(id) { mutableStateOf("") }
    var model by remember(id) { mutableStateOf("") }
    var configReady by remember(id) { mutableStateOf(false) }
    var isEditingState by remember(id) { mutableStateOf(false) }
    var stageMenuExpanded by remember(id) { mutableStateOf(false) }
    val isEditingStateCurrent by rememberUpdatedState(isEditingState)

    LaunchedEffect(id, assistant.id, assistant.affectiveJevUrl, assistant.affectiveJevApiKey, assistant.affectiveJevModel) {
        if (!configReady && assistant.id.toString() == id) {
            url = assistant.affectiveJevUrl
            apiKey = JevKeyCipher.decrypt(assistant.affectiveJevApiKey)
            model = assistant.affectiveJevModel
            configReady = true
        }
    }
    LaunchedEffect(id, assistant.id, assistant.affectiveEnabled, assistant.affectiveMode) {
        if (assistant.id.toString() != id) return@LaunchedEffect
        while (true) {
            if (!isEditingStateCurrent) state = if (assistant.affectiveEnabled) repository.state(assistant) else null
            delay(2_000)
        }
    }

    fun saveManualState() {
        val edited = state ?: return
        scope.launch {
            try {
                repository.updateManually(assistant, edited)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("AffectiveEnginePage", "Failed to save affective state", e)
                Toast.makeText(context, "情感状态保存失败", Toast.LENGTH_SHORT).show()
            } finally {
                isEditingState = false
                try {
                    state = repository.state(assistant)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("AffectiveEnginePage", "Failed to reload affective state", e)
                    state = null
                }
            }
        }
    }

    Scaffold(topBar = {
        LargeFlexibleTopAppBar(title = { Text("情感引擎 · ${assistant.name}") }, navigationIcon = { BackButton() })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CardGroup(title = { Text("情感引擎") }) {
                item(
                    headlineContent = { Text("启用") },
                    trailingContent = {
                        Switch(assistant.affectiveEnabled, onCheckedChange = { enabled ->
                            if (!enabled) repository.invalidatePending(assistant)
                            vm.update(assistant.copy(affectiveEnabled = enabled))
                            scope.launch {
                                if (enabled) repository.resume(assistant) else repository.pause(assistant)
                                state = if (enabled) repository.state(assistant.copy(affectiveEnabled = true)) else null
                            }
                        })
                    },
                )
                item(
                    headlineContent = { Text("关系 · ${if (assistant.affectiveMode == AffectiveMode.FRIEND) "朋友" else "恋人"}") },
                    supportingContent = {
                        TextButton(onClick = {
                            val mode = if (assistant.affectiveMode == AffectiveMode.FRIEND) AffectiveMode.LOVER else AffectiveMode.FRIEND
                            vm.update(assistant.copy(affectiveMode = mode))
                            scope.launch {
                                repository.setMode(assistant, mode)
                                state = repository.state(assistant.copy(affectiveMode = mode))
                            }
                        }) { Text("切换关系") }
                    },
                )
                if (assistant.affectiveEnabled) state?.let { current ->
                    item(
                        headlineContent = { Text("当前关系阶段：${AffectiveEngine.stageLabel(current)}") },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("关系阶段路径")
                                AffectiveStage.entries.forEachIndexed { index, stage ->
                                    val isCurrentStage = stage == current.stage
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "${index + 1}. ${affectiveStageLabel(current.mode, stage)}",
                                            color = if (isCurrentStage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = if (isCurrentStage) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                        if (isCurrentStage) {
                                            Text("当前阶段", color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                                Text("阶段会随互动与情绪变化演化，也可以手动调整。")
                            }
                        },
                        trailingContent = {
                            Column {
                                TextButton(onClick = { stageMenuExpanded = true }) { Text("调整阶段") }
                                DropdownMenu(
                                    expanded = stageMenuExpanded,
                                    onDismissRequest = { stageMenuExpanded = false },
                                ) {
                                    AffectiveStage.entries.forEach { stage ->
                                        DropdownMenuItem(
                                            text = { Text(affectiveStageLabel(current.mode, stage)) },
                                            onClick = {
                                                val edited = current.copy(stage = stage)
                                                isEditingState = true
                                                state = edited
                                                stageMenuExpanded = false
                                                saveManualState()
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    )
                    item(
                        headlineContent = { Text("最近变化") },
                        supportingContent = {
                            Text("${current.lastReason.ifBlank { "尚无事件" }} · ${current.lastEventSource}\n" +
                                "上次互动 ${current.lastUserAt.asTime()} · 上次联系 ${current.lastSentAt.asTime()}\n" +
                                "待回复 ${if (current.unansweredMessageId == null) "无" else "有"} · 契机 ${current.lastCandidateReason.ifBlank { "尚未评估" }}\n" +
                                "投递 ${current.lastDeliveryStatus.ifBlank { "尚无记录" }}")
                        },
                    )
                }
            }
            if (assistant.affectiveEnabled) state?.let { current ->
                CardGroup(title = { Text("情感维度 · 可手动调整") }) {
                    item(
                        headlineContent = { Text("数值范围") },
                        supportingContent = { Text("所有维度统一显示为两位小数。亲密、冲突、活跃、掌控感、社交欲、牵挂范围为 0.00–1.00；愉悦范围为 -1.00–1.00。调整后会保存，并继续随互动和时间演化。") },
                    )
                    affectiveDimension(
                        label = "亲密",
                        value = current.intimacy,
                        range = 0.0..1.0,
                        display = { formatAffectiveValue(it) },
                        onChange = { value -> isEditingState = true; state = current.copy(intimacy = value) },
                        onFinished = { saveManualState() },
                    )
                    affectiveDimension(
                        label = "冲突",
                        value = current.conflict,
                        range = 0.0..1.0,
                        display = { formatAffectiveValue(it) },
                        onChange = { value -> isEditingState = true; state = current.copy(conflict = value) },
                        onFinished = { saveManualState() },
                    )
                    affectiveDimension(
                        label = "愉悦",
                        value = current.valence,
                        range = -1.0..1.0,
                        display = { formatAffectiveValue(it) },
                        onChange = { value -> isEditingState = true; state = current.copy(valence = value) },
                        onFinished = { saveManualState() },
                    )
                    affectiveDimension(
                        label = "活跃",
                        value = current.arousal,
                        range = 0.0..1.0,
                        display = { formatAffectiveValue(it) },
                        onChange = { value -> isEditingState = true; state = current.copy(arousal = value) },
                        onFinished = { saveManualState() },
                    )
                    affectiveDimension(
                        label = "掌控感",
                        value = current.dominance,
                        range = 0.0..1.0,
                        display = { formatAffectiveValue(it) },
                        onChange = { value -> isEditingState = true; state = current.copy(dominance = value) },
                        onFinished = { saveManualState() },
                    )
                    affectiveDimension(
                        label = "社交欲",
                        value = current.socialDesire,
                        range = 0.0..1.0,
                        display = { formatAffectiveValue(it) },
                        onChange = { value -> isEditingState = true; state = current.copy(socialDesire = value) },
                        onFinished = { saveManualState() },
                    )
                    affectiveDimension(
                        label = "牵挂",
                        value = current.longing,
                        range = 0.0..0.95,
                        display = { formatAffectiveValue(it) },
                        onChange = { value -> isEditingState = true; state = current.copy(longing = value) },
                        onFinished = { saveManualState() },
                    )
                }
            }
            if (assistant.affectiveEnabled) state?.let { current ->
                val preview = repository.formatContext(current, System.currentTimeMillis())
                CardGroup(title = { Text("注入内容") }) {
                    item(
                        headlineContent = { Text("当前预览") },
                        supportingContent = { Text("主聊天与后台共用；后台抽中契机时会追加本轮说明。") },
                    )
                    item(
                        headlineContent = { Text(preview) },
                        supportingContent = {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(preview)) }) { Text("复制") }
                        },
                    )
                }
            }
            CardGroup(title = { Text("Jev · 可选") }) {
                item(
                    headlineContent = { Text("未配置时使用本地规则") },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(url, { url = it }, label = { Text("API URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation(), singleLine = true)
                            OutlinedTextField(model, { model = it }, label = { Text("模型 ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            TextButton(onClick = {
                                vm.update(assistant.copy(
                                    affectiveJevUrl = url.trim(), affectiveJevApiKey = JevKeyCipher.encrypt(apiKey.trim()),
                                    affectiveJevModel = model.trim(),
                                    affectiveJevProviderId = if (apiKey.isNotBlank()) null else assistant.affectiveJevProviderId,
                                ))
                            }) { Text("保存 Jev 配置") }
                            if (assistant.affectiveJevApiKey.isNotBlank() || assistant.affectiveJevProviderId != null) {
                                TextButton(onClick = {
                                    apiKey = ""
                                    vm.update(assistant.copy(affectiveJevApiKey = "", affectiveJevProviderId = null))
                                }) { Text("停用 Jev") }
                            }
                        }
                    },
                )
            }
            if (assistant.affectiveEnabled) {
                TextButton(onClick = {
                    repository.invalidatePending(assistant)
                    scope.launch { repository.reset(assistant); state = repository.state(assistant) }
                }) {
                    Text("重置情感状态")
                }
            }
        }
    }
}

private fun Long.asTime(): String = if (this <= 0) "无" else DateFormat.getDateTimeInstance().format(Date(this))

private fun affectiveStageLabel(mode: AffectiveMode, stage: AffectiveStage): String = when (mode) {
    AffectiveMode.FRIEND -> listOf("初识", "熟悉", "热络", "默契", "疏远", "和好")[stage.ordinal]
    AffectiveMode.LOVER -> listOf("试探", "甜蜜", "热恋", "安稳", "冷战", "修复")[stage.ordinal]
}

private fun formatAffectiveValue(value: Double): String = String.format(Locale.getDefault(), "%.2f", value)

private fun CardGroupScope.affectiveDimension(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    display: (Double) -> String,
    onChange: (Double) -> Unit,
    onFinished: () -> Unit,
) {
    item(
        headlineContent = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, fontWeight = FontWeight.Medium)
                Text(display(value), color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
            }
        },
        supportingContent = {
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.toDouble()) },
                onValueChangeFinished = onFinished,
                valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            )
        },
    )
}
