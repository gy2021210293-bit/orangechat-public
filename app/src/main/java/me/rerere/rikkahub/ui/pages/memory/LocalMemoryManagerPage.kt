/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Archive01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Time02
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import me.rerere.rikkahub.data.memory.v1.MemoryConstants
import me.rerere.rikkahub.data.memory.v1.MemoryDateUtils
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryDailyNoteEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEProposalEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryFactAuditEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryMaintenanceTaskEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryProposalEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryRelationEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryRevisionEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryTimelineEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import kotlinx.serialization.json.Json
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalMemoryManagerPage(
    ownerType: String,
    ownerId: String,
) {
    val vm: LocalMemoryManagerVM = koinViewModel(
        parameters = { parametersOf(ownerType, ownerId) }
    )

    val cards by vm.cards.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val selectedStatus by vm.selectedStatus.collectAsStateWithLifecycle()
    val activeCardForHistory by vm.activeCardForHistory.collectAsStateWithLifecycle()
    val revisions by vm.revisions.collectAsStateWithLifecycle()

    val dailyNotes by vm.dailyNotes.collectAsStateWithLifecycle()
    val isConsolidating by vm.isConsolidating.collectAsStateWithLifecycle()
    val consolidationMessage by vm.consolidationMessage.collectAsStateWithLifecycle()

    val pendingProposals by vm.pendingProposals.collectAsStateWithLifecycle()
    val pendingFactAudits by vm.pendingFactAudits.collectAsStateWithLifecycle()
    val maintenanceTargets by vm.maintenanceTargets.collectAsStateWithLifecycle()
    val pendingMaintenanceTasks by vm.pendingMaintenanceTasks.collectAsStateWithLifecycle()
    val pendingRelations by vm.pendingRelations.collectAsStateWithLifecycle()
    val pendingRelationCards by vm.pendingRelationCards.collectAsStateWithLifecycle()
    val pendingProposalCards by vm.pendingProposalCards.collectAsStateWithLifecycle()
    val pendingFactAuditCards by vm.pendingFactAuditCards.collectAsStateWithLifecycle()
    val pendingEProposals by vm.pendingEProposals.collectAsStateWithLifecycle()
    val cardExperiences by vm.cardExperiences.collectAsStateWithLifecycle()
    val pendingTotalCount = pendingProposals.size + pendingEProposals.size + pendingFactAudits.size + pendingMaintenanceTasks.size + pendingRelations.size
    val mapUiState by vm.mapUiState.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf(0) }
    var cardToEdit by remember { mutableStateOf<MemoryCardEntity?>(null) }
    var cardRelations by remember { mutableStateOf<List<MemoryRelationEntity>>(emptyList()) }
    var cardRelationMap by remember { mutableStateOf<Map<String, MemoryCardEntity>>(emptyMap()) }
    var cardTimeline by remember { mutableStateOf<me.rerere.rikkahub.data.memory.v1.db.MemoryTimelineEntity?>(null) }
    var availableTimelines by remember { mutableStateOf<List<me.rerere.rikkahub.data.memory.v1.db.MemoryTimelineEntity>>(emptyList()) }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 2) {
            vm.loadMemoryMapData()
        }
    }

    LaunchedEffect(cardToEdit?.id) {
        val c = cardToEdit
        if (c != null) {
            val rels = vm.getCardRelations(c.id)
            cardRelations = rels
            val otherIds = rels.flatMap { listOf(it.sourceCardId, it.targetCardId) }.filter { it != c.id }.distinct()
            cardRelationMap = if (otherIds.isEmpty()) emptyMap() else vm.getCardsByIds(otherIds).associateBy { it.id }
            cardTimeline = vm.getCardTimeline(c.id)
            availableTimelines = vm.getActiveTimelines()
        } else {
            cardRelations = emptyList()
            cardRelationMap = emptyMap()
            cardTimeline = null
        }
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var cardToDelete by remember { mutableStateOf<MemoryCardEntity?>(null) }
    var noteToDelete by remember { mutableStateOf<MemoryDailyNoteEntity?>(null) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val isAssistant = ownerType == MemoryConstants.OWNER_TYPE_ASSISTANT

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        if (!isAssistant) "全局共享记忆"
                        else when (selectedTab) {
                            0 -> "长期记忆"
                            1 -> "每日日记"
                            2 -> "记忆地图"
                            else -> "待处理中心"
                        }
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (!isAssistant || selectedTab == 0) {
                        IconButton(onClick = { showCreateDialog = true }) {
                            Icon(HugeIcons.Add01, contentDescription = "新建记忆")
                        }
                    } else if (selectedTab == 1) {
                        IconButton(
                            onClick = {
                                vm.consolidateDate(MemoryDateUtils.getYesterdayMemoryDate(), force = true)
                            },
                            enabled = !isConsolidating
                        ) {
                            if (isConsolidating) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(HugeIcons.Refresh, contentDescription = "重新整理昨天")
                            }
                        }
                    } else if (selectedTab == 2) {
                        IconButton(
                            onClick = { vm.loadMemoryMapData() },
                            enabled = !mapUiState.isLoading
                        ) {
                            if (mapUiState.isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(HugeIcons.Refresh, contentDescription = "刷新地图")
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .imePadding()
        ) {
            // 分区 Tab（仅在助手专属记忆下展示，全局记忆只管理长期卡片）
            if (isAssistant) {
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("卡片 (${cards.size})") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("日记 (${dailyNotes.size})") }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("地图") }
                    )
                    Tab(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        text = {
                            Text(if (pendingTotalCount > 0) "待处理 ($pendingTotalCount)" else "待处理")
                        }
                    )
                }
            }

            if (!isAssistant || selectedTab == 0) {
                // 搜索框
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { vm.searchQuery.value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    placeholder = { Text("全库搜索记忆...") },
                    leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                // 状态筛选 Chips
                StatusFilterChips(
                    selectedStatus = selectedStatus,
                    onSelectStatus = { vm.selectedStatus.value = it }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 记忆卡片列表
                if (cards.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank() || selectedStatus != null) {
                                "没有找到匹配的记忆卡片"
                            } else {
                                "暂无记忆卡片，可点击右上角新建"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(cards, key = { it.id }) { card ->
                            MemoryCardItem(
                                card = card,
                                experience = cardExperiences[card.id],
                                onEdit = { cardToEdit = card },
                                onArchive = { vm.archiveCard(card.id, card.revision) },
                                onRestore = { vm.restoreCard(card.id, card.revision) },
                                onHistory = { vm.showHistory(card) },
                                onDeletePermanently = { cardToDelete = card },
                                onToggleAlwaysInject = if (card.category == MemoryConstants.CATEGORY_SELF && card.status == MemoryConstants.STATUS_ACTIVE) {
                                    { vm.setSelfAlwaysInject(card.id, !card.alwaysInject) }
                                } else null,
                                onApprove = if (card.status == MemoryConstants.STATUS_REVIEW) {
                                    { vm.approveReviewCard(card.id) }
                                } else null,
                            )
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }
                }
            } else if (isAssistant && selectedTab == 1) {
                // 每日日记分区
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            vm.consolidateDate(MemoryDateUtils.getYesterdayMemoryDate(), force = true)
                        },
                        enabled = !isConsolidating,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("整理昨日日记")
                    }
                    OutlinedButton(
                        onClick = {
                            vm.consolidateDate(MemoryDateUtils.getTodayMemoryDate(), force = true)
                        },
                        enabled = !isConsolidating,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("整理今日对话")
                    }
                }

                consolidationMessage?.let { msg ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(
                                onClick = { vm.clearConsolidationMessage() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(HugeIcons.Delete01, contentDescription = "关闭", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                if (dailyNotes.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无每日日记。系统每日凌晨 04:00 自动整理，也可点击上方按钮立即整理。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(dailyNotes, key = { it.id }) { note ->
                            MemoryDailyNoteItem(
                                note = note,
                                onDelete = { noteToDelete = note },
                                onReConsolidate = {
                                    vm.consolidateDate(note.memoryDate, force = true)
                                }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }
                }
            } else if (isAssistant && selectedTab == 2) {
                // 地图分区（X/Y/Z 视图）
                MemoryMapSection(
                    mapUiState = mapUiState,
                    onSelectCenterCard = { vm.selectCenterCard(it) },
                    onCardClick = { cardToEdit = it },
                    modifier = Modifier.weight(1f),
                )
            } else if (isAssistant && selectedTab == 3) {
                // 待处理中心分区
                PendingManagementSection(
                    pendingProposals = pendingProposals,
                    pendingProposalCards = pendingProposalCards,
                    pendingEProposals = pendingEProposals,
                    pendingFactAudits = pendingFactAudits,
                    pendingFactAuditCards = pendingFactAuditCards,
                    pendingMaintenanceTasks = pendingMaintenanceTasks,
                    maintenanceTargets = maintenanceTargets,
                    pendingRelations = pendingRelations,
                    pendingRelationCards = pendingRelationCards,
                    onApproveProposal = { vm.approveProposal(it) },
                    onRejectProposal = { vm.rejectProposal(it) },
                    onAcceptEProposal = { vm.acceptEProposal(it) },
                    onRejectEProposal = { vm.rejectEProposal(it) },
                    onResolveFactAudit = { id, res -> vm.resolveFactAudit(id, res) },
                    onResolveTask = { id, st -> vm.resolveMaintenanceTask(id, st) },
                    onApproveRelation = { vm.approveRelation(it) },
                    onRejectRelation = { vm.rejectRelation(it) },
                )
            }
        }
    }

    // 新增弹窗
    if (showCreateDialog) {
        EditMemoryDialog(
            title = "新建长期记忆",
            initialContent = "",
            initialCategory = MemoryConstants.CATEGORY_PREFERENCE,
            initialFactKey = "",
            initialStatus = MemoryConstants.STATUS_ACTIVE,
            initialAlwaysInject = false,
            relations = emptyList(),
            relationCards = emptyMap(),
            currentCardId = null,
            onDismiss = { showCreateDialog = false },
            onConfirm = { content, category, factKey, alwaysInject ->
                vm.createCard(content, category, factKey.ifBlank { null }, alwaysInject = alwaysInject)
                showCreateDialog = false
            }
        )
    }

    // 编辑弹窗
    cardToEdit?.let { card ->
        EditMemoryDialog(
            title = "编辑长期记忆",
            initialContent = card.content,
            initialCategory = card.category,
            initialFactKey = card.factKey ?: "",
            initialStatus = card.status,
            initialAlwaysInject = card.alwaysInject,
            relations = cardRelations,
            relationCards = cardRelationMap,
            currentCardId = card.id,
            cardTimeline = cardTimeline,
            availableTimelines = availableTimelines,
            onAssignTimeline = { tlId ->
                vm.assignCardToTimeline(tlId, card.id)
                cardTimeline = availableTimelines.find { it.id == tlId }
            },
            onCreateTimeline = { title ->
                vm.createTimeline(title) { newTl ->
                    vm.assignCardToTimeline(newTl.id, card.id)
                    cardTimeline = newTl
                    availableTimelines = availableTimelines + newTl
                }
            },
            onRemoveTimeline = {
                cardTimeline?.let { vm.removeCardFromTimeline(it.id, card.id) }
                cardTimeline = null
            },
            onDismiss = { cardToEdit = null },
            onConfirm = { content, category, factKey, alwaysInject ->
                vm.updateCard(
                    id = card.id,
                    expectedRevision = card.revision,
                    content = content,
                    category = category,
                    factKey = factKey.ifBlank { null },
                    alwaysInject = alwaysInject,
                )
                cardToEdit = null
                if (selectedTab == 2) {
                    vm.loadMemoryMapData()
                }
            }
        )
    }

    // 历史版本弹窗
    activeCardForHistory?.let { card ->
        RevisionHistoryDialog(
            card = card,
            revisions = revisions,
            onDismiss = { vm.dismissHistory() }
        )
    }

    // 永久删除二次确认
    cardToDelete?.let { card ->
        AlertDialog(
            onDismissRequest = { cardToDelete = null },
            title = { Text("永久删除记忆") },
            text = {
                Text("此操作将从本地数据库中彻底抹除该记忆卡片及其全部修订历史，不可撤销。\n\n确定要删除吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deletePermanently(card.id)
                        cardToDelete = null
                    }
                ) {
                    Text("彻底删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { cardToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 日记删除弹窗
    noteToDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text("删除每日日记") },
            text = {
                Text("确定要删除 ${note.memoryDate} 的记忆日记吗？\n（相关长期记忆卡片不会被删除，如需重新生成可随时点击整理）")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteDailyNote(note.memoryDate)
                        noteToDelete = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { noteToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusFilterChips(
    selectedStatus: String?,
    onSelectStatus: (String?) -> Unit,
) {
    val statuses = listOf(
        null to "全部",
        MemoryConstants.STATUS_ACTIVE to "活跃",
        MemoryConstants.STATUS_REVIEW to "待确认",
        MemoryConstants.STATUS_SUPERSEDED to "已替代",
        MemoryConstants.STATUS_ARCHIVED to "已归档",
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        statuses.forEach { (status, label) ->
            FilterChip(
                selected = selectedStatus == status,
                onClick = { onSelectStatus(status) },
                label = { Text(label, fontSize = 12.sp) }
            )
        }
    }
}

@Composable
private fun MemoryCardItem(
    card: MemoryCardEntity,
    experience: MemoryExperienceCoordinateEntity? = null,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onHistory: () -> Unit,
    onDeletePermanently: () -> Unit,
    onToggleAlwaysInject: (() -> Unit)? = null,
    onApprove: (() -> Unit)? = null,
) {
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val formattedTime = remember(card.updatedAt) { timeFormat.format(Date(card.updatedAt)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 头部：标签、版本与状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CategoryBadge(category = card.category)
                    StatusBadge(status = card.status)
                    if (card.alwaysInject) {
                        AlwaysInjectBadge()
                    }
                    experience?.let { exp ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = "E-Axis P${exp.eInitialPriority}",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                    Text(
                        text = "v${card.revision}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // 正文
            Text(
                text = card.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis
            )

            // E 轴经验意义与回应倾向
            experience?.let { exp ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = "主体经验意义: ${exp.meaning}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "回应倾向: ${exp.responseTendency}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "情绪坐标: (${String.format(Locale.US, "%.2f", exp.valence)}, ${String.format(Locale.US, "%.2f", exp.arousal)})",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            // 事实键
            card.factKey?.let { key ->
                Text(
                    text = "事实键: $key",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 底部操作按钮栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (card.status == MemoryConstants.STATUS_REVIEW && onApprove != null) {
                    IconButton(onClick = onApprove, modifier = Modifier.size(36.dp)) {
                        Icon(
                            HugeIcons.CheckmarkCircle02,
                            contentDescription = "通过审核",
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (card.category == MemoryConstants.CATEGORY_SELF && card.status == MemoryConstants.STATUS_ACTIVE && onToggleAlwaysInject != null) {
                    IconButton(onClick = onToggleAlwaysInject, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (card.alwaysInject) HugeIcons.PinOff else HugeIcons.Pin,
                            contentDescription = if (card.alwaysInject) "取消常驻" else "设为常驻",
                            tint = if (card.alwaysInject) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                IconButton(onClick = onHistory, modifier = Modifier.size(36.dp)) {
                    Icon(HugeIcons.Time02, contentDescription = "修订历史", modifier = Modifier.size(18.dp))
                }

                if (card.status == MemoryConstants.STATUS_ARCHIVED) {
                    IconButton(onClick = onRestore, modifier = Modifier.size(36.dp)) {
                        Icon(HugeIcons.Refresh, contentDescription = "恢复", modifier = Modifier.size(18.dp))
                    }
                } else {
                    IconButton(onClick = onArchive, modifier = Modifier.size(36.dp)) {
                        Icon(HugeIcons.Archive01, contentDescription = "归档", modifier = Modifier.size(18.dp))
                    }
                }

                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(HugeIcons.PencilEdit01, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                }

                IconButton(onClick = onDeletePermanently, modifier = Modifier.size(36.dp)) {
                    Icon(
                        HugeIcons.Delete01,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AlwaysInjectBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "常驻",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
private fun CategoryBadge(category: String) {
    val (label, bg) = when (category) {
        MemoryConstants.CATEGORY_PREFERENCE -> "偏好" to MaterialTheme.colorScheme.primaryContainer
        MemoryConstants.CATEGORY_PROFILE -> "人设" to MaterialTheme.colorScheme.secondaryContainer
        MemoryConstants.CATEGORY_SELF -> "自我" to MaterialTheme.colorScheme.tertiaryContainer
        MemoryConstants.CATEGORY_RELATIONSHIP -> "关系" to MaterialTheme.colorScheme.tertiaryContainer
        MemoryConstants.CATEGORY_PLAN -> "规划" to MaterialTheme.colorScheme.surfaceVariant
        MemoryConstants.CATEGORY_EXPERIENCE -> "经历" to MaterialTheme.colorScheme.primaryContainer
        MemoryConstants.CATEGORY_BOUNDARY -> "界限" to MaterialTheme.colorScheme.errorContainer
        else -> "其他" to MaterialTheme.colorScheme.surfaceContainerHighest
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusBadge(status: String) {
    val (label, color) = when (status) {
        MemoryConstants.STATUS_ACTIVE -> "活跃" to Color(0xFF2E7D32)
        MemoryConstants.STATUS_REVIEW -> "待确认" to Color(0xFFEF6C00)
        MemoryConstants.STATUS_SUPERSEDED -> "已替代" to Color(0xFF757575)
        MemoryConstants.STATUS_ARCHIVED -> "已归档" to Color(0xFF9E9E9E)
        else -> status to MaterialTheme.colorScheme.outline
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(text = label, fontSize = 11.sp, color = color)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditMemoryDialog(
    title: String,
    initialContent: String,
    initialCategory: String,
    initialFactKey: String,
    initialStatus: String = MemoryConstants.STATUS_ACTIVE,
    initialAlwaysInject: Boolean = false,
    relations: List<MemoryRelationEntity> = emptyList(),
    relationCards: Map<String, MemoryCardEntity> = emptyMap(),
    currentCardId: String? = null,
    cardTimeline: me.rerere.rikkahub.data.memory.v1.db.MemoryTimelineEntity? = null,
    availableTimelines: List<me.rerere.rikkahub.data.memory.v1.db.MemoryTimelineEntity> = emptyList(),
    onAssignTimeline: ((String) -> Unit)? = null,
    onCreateTimeline: ((String) -> Unit)? = null,
    onRemoveTimeline: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (content: String, category: String, factKey: String, alwaysInject: Boolean) -> Unit,
) {
    var content by remember { mutableStateOf(initialContent) }
    var category by remember { mutableStateOf(initialCategory) }
    var factKey by remember { mutableStateOf(initialFactKey) }
    var alwaysInject by remember { mutableStateOf(initialAlwaysInject) }
    var categoryExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(category) {
        if (category != MemoryConstants.CATEGORY_SELF) {
            alwaysInject = false
        }
    }

    val categories = listOf(
        MemoryConstants.CATEGORY_PREFERENCE to "偏好 (preference)",
        MemoryConstants.CATEGORY_PROFILE to "人设 (profile)",
        MemoryConstants.CATEGORY_SELF to "自我 (self)",
        MemoryConstants.CATEGORY_RELATIONSHIP to "关系 (relationship)",
        MemoryConstants.CATEGORY_PLAN to "规划 (plan)",
        MemoryConstants.CATEGORY_EXPERIENCE to "经历 (experience)",
        MemoryConstants.CATEGORY_BOUNDARY to "界限 (boundary)",
        MemoryConstants.CATEGORY_OTHER to "其他 (other)",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("记忆内容") },
                    placeholder = { Text("记录完整且可独立理解的一条长期事实...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                )

                // 分类下拉选择
                ExposedDropdownMenuBox(
                    expanded = categoryExpanded,
                    onExpandedChange = { categoryExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = categories.find { it.first == category }?.second ?: category,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("分类") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false }
                    ) {
                        categories.forEach { (catKey, catLabel) ->
                            DropdownMenuItem(
                                text = { Text(catLabel) },
                                onClick = {
                                    category = catKey
                                    categoryExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = factKey,
                    onValueChange = { factKey = it },
                    label = { Text("事实键 (可选)") },
                    placeholder = { Text("如 person.residence.current") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                if (initialStatus == MemoryConstants.STATUS_ACTIVE) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "常驻于 AI 自我认识 (<stable_self>)",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = if (category == MemoryConstants.CATEGORY_SELF) "开启后，当前 AI 会在每次对话中参考这条长期自我认识" else "仅当分类为自我 (self) 时可开启常驻",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = alwaysInject && category == MemoryConstants.CATEGORY_SELF,
                            onCheckedChange = { alwaysInject = it },
                            enabled = category == MemoryConstants.CATEGORY_SELF
                        )
                    }
                }

                // X 轴：归属时间线
                if (onAssignTimeline != null) {
                    var timelineDropdownExpanded by remember { mutableStateOf(false) }
                    var showNewTimelineDialog by remember { mutableStateOf(false) }
                    var newTimelineTitle by remember { mutableStateOf("") }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "X 轴 · 归属时间线",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            if (cardTimeline != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surface,
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = cardTimeline.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        cardTimeline.summary?.let { s ->
                                            if (s.isNotBlank()) {
                                                Text(
                                                    text = s,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.outline,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                    IconButton(
                                        onClick = { onRemoveTimeline?.invoke() },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = HugeIcons.Cancel01,
                                            contentDescription = "移出时间线",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "当前未归属于任何时间线",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    OutlinedButton(
                                        onClick = { timelineDropdownExpanded = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("选择已有时间线", fontSize = 12.sp)
                                    }
                                    DropdownMenu(
                                        expanded = timelineDropdownExpanded,
                                        onDismissRequest = { timelineDropdownExpanded = false }
                                    ) {
                                        if (availableTimelines.isEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text("暂无可选时间线", color = MaterialTheme.colorScheme.outline) },
                                                onClick = { timelineDropdownExpanded = false },
                                                enabled = false
                                            )
                                        } else {
                                            availableTimelines.forEach { tl ->
                                                DropdownMenuItem(
                                                    text = { Text(tl.title) },
                                                    onClick = {
                                                        onAssignTimeline(tl.id)
                                                        timelineDropdownExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                if (onCreateTimeline != null) {
                                    OutlinedButton(
                                        onClick = { showNewTimelineDialog = true }
                                    ) {
                                        Text("+ 新建", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    if (showNewTimelineDialog) {
                        AlertDialog(
                            onDismissRequest = { showNewTimelineDialog = false },
                            title = { Text("新建时间线") },
                            text = {
                                OutlinedTextField(
                                    value = newTimelineTitle,
                                    onValueChange = { newTimelineTitle = it },
                                    label = { Text("时间线标题") },
                                    placeholder = { Text("如 2024年春季自驾游") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        if (newTimelineTitle.isNotBlank()) {
                                            onCreateTimeline?.invoke(newTimelineTitle.trim())
                                            newTimelineTitle = ""
                                            showNewTimelineDialog = false
                                        }
                                    },
                                    enabled = newTimelineTitle.isNotBlank()
                                ) {
                                    Text("创建并加入")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showNewTimelineDialog = false }) {
                                    Text("取消")
                                }
                            }
                        )
                    }
                }

                // Y 轴：关联图谱
                if (relations.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Y 轴 · 关联图谱 (共 ${relations.size} 条边)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            relations.forEach { rel ->
                                val isOutgoing = currentCardId != null && rel.sourceCardId == currentCardId
                                val otherId = if (isOutgoing) rel.targetCardId else rel.sourceCardId
                                val otherCard = relationCards[otherId]
                                val directionText = if (isOutgoing) "→ 出边" else "← 入边"

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surface,
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer
                                            ) {
                                                Text(
                                                    text = "$directionText · ${rel.relationType}",
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            otherCard?.let { CategoryBadge(category = it.category) }
                                        }
                                        Text(
                                            text = "强度: ${"%.2f".format(rel.strength)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    Text(
                                        text = otherCard?.content ?: "卡片ID: $otherId",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    rel.reason?.let { r ->
                                        if (r.isNotBlank()) {
                                            Text(
                                                text = "原因: $r",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                    rel.evidenceSourceIds?.let { evidence ->
                                        if (evidence.isNotBlank()) {
                                            Text(
                                                text = "证据来源: $evidence",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (content.isNotBlank()) {
                        onConfirm(content.trim(), category, factKey.trim(), alwaysInject && category == MemoryConstants.CATEGORY_SELF)
                    }
                },
                enabled = content.isNotBlank()
            ) {
                Text("保存")
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
private fun RevisionHistoryDialog(
    card: MemoryCardEntity,
    revisions: List<MemoryRevisionEntity>,
    onDismiss: () -> Unit,
) {
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("修订历史流水", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "ID: ${card.id.take(8)}...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        text = {
            if (revisions.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    Text("暂无修订历史", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(revisions.reversed(), key = { it.id }) { rev ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Rev #${rev.revision} · ${rev.operation}",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Text(
                                        text = "by ${rev.actor}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Text(
                                    text = timeFormat.format(Date(rev.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )

                                rev.reason?.let { r ->
                                    Text(
                                        text = "原因: $r",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (rev.afterContent != null && rev.afterContent != rev.beforeContent) {
                                    Text(
                                        text = "内容: ${rev.afterContent}",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun MemoryDailyNoteItem(
    note: MemoryDailyNoteEntity,
    onDelete: () -> Unit,
    onReConsolidate: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = note.memoryDate,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.wrapContentWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = onReConsolidate, modifier = Modifier.size(32.dp)) {
                        Icon(HugeIcons.Refresh, contentDescription = "重新整理", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(HugeIcons.Delete01, contentDescription = "删除日记", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!note.emotionalThread.isNullOrBlank()) {
                Text(
                    text = note.emotionalThread.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = note.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            val unfinishedTopics = parseUnfinishedTopics(note.unfinishedTopics)
            if (unfinishedTopics.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "待跟进话题：",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    unfinishedTopics.forEach { topic ->
                        Text(
                            text = "• $topic",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

internal fun parseUnfinishedTopics(rawTopics: String): List<String> {
    val trimmed = rawTopics.trim()
    if (trimmed.isEmpty() || trimmed == "[]" || trimmed.equals("null", ignoreCase = true)) return emptyList()
    return runCatching { Json.decodeFromString<List<String>>(trimmed) }
        .map { list -> list.map { it.trim() }.filter { it.isNotEmpty() } }
        .getOrElse {
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val inner = trimmed.substring(1, trimmed.length - 1).trim()
                if (inner.isEmpty()) return emptyList()
                val items = inner.split(",").map { it.trim().trim('"', '\'', ' ') }.filter { it.isNotEmpty() }
                if (items.isNotEmpty()) items else listOf(trimmed)
            } else {
                listOf(trimmed)
            }
        }
}

@Composable
private fun PendingManagementSection(
    pendingProposals: List<MemoryProposalEntity>,
    pendingProposalCards: Map<String, MemoryCardEntity> = emptyMap(),
    pendingEProposals: List<MemoryEProposalEntity> = emptyList(),
    pendingFactAudits: List<MemoryFactAuditEntity>,
    pendingFactAuditCards: Map<String, MemoryCardEntity> = emptyMap(),
    pendingMaintenanceTasks: List<MemoryMaintenanceTaskEntity>,
    maintenanceTargets: Map<String, String>,
    pendingRelations: List<MemoryRelationEntity> = emptyList(),
    pendingRelationCards: Map<String, MemoryCardEntity> = emptyMap(),
    onApproveProposal: (String) -> Unit,
    onRejectProposal: (String) -> Unit,
    onAcceptEProposal: (Long) -> Unit = {},
    onRejectEProposal: (Long) -> Unit = {},
    onResolveFactAudit: (String, String) -> Unit,
    onResolveTask: (String, String) -> Unit,
    onApproveRelation: (String) -> Unit = {},
    onRejectRelation: (String) -> Unit = {},
) {
    val totalPending = pendingProposals.size + pendingEProposals.size + pendingFactAudits.size + pendingMaintenanceTasks.size + pendingRelations.size
    if (totalPending == 0) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "当前暂无待处理的审核候选、事实冲突、图关系或代谢任务",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (pendingProposals.isNotEmpty()) {
                item {
                    Text(
                        text = "待审候选记忆 (${pendingProposals.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(pendingProposals, key = { it.id }) { proposal ->
                    ProposalReviewItem(
                        proposal = proposal,
                        targetCard = proposal.targetId?.let { pendingProposalCards[it] },
                        onApprove = { onApproveProposal(proposal.id) },
                        onReject = { onRejectProposal(proposal.id) },
                    )
                }
            }

            if (pendingEProposals.isNotEmpty()) {
                item {
                    Text(
                        text = "待审经验提议 (${pendingEProposals.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(pendingEProposals, key = { it.id }) { eProposal ->
                    EProposalReviewItem(
                        proposal = eProposal,
                        onAccept = { onAcceptEProposal(eProposal.id) },
                        onReject = { onRejectEProposal(eProposal.id) },
                    )
                }
            }

            if (pendingRelations.isNotEmpty()) {
                item {
                    Text(
                        text = "待确认图关系 (${pendingRelations.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(pendingRelations, key = { it.id }) { relation ->
                    RelationReviewItem(
                        relation = relation,
                        sourceCard = pendingRelationCards[relation.sourceCardId],
                        targetCard = pendingRelationCards[relation.targetCardId],
                        onApprove = { onApproveRelation(relation.id) },
                        onReject = { onRejectRelation(relation.id) },
                    )
                }
            }

            if (pendingFactAudits.isNotEmpty()) {
                item {
                    Text(
                        text = "事实冲突审计 (${pendingFactAudits.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(pendingFactAudits, key = { it.id }) { audit ->
                    FactAuditReviewItem(
                        audit = audit,
                        existingCard = pendingFactAuditCards[audit.existingCardId],
                        candidateCard = audit.candidateCardId?.let { pendingFactAuditCards[it] },
                        onResolve = { resolution -> onResolveFactAudit(audit.id, resolution) }
                    )
                }
            }

            if (pendingMaintenanceTasks.isNotEmpty()) {
                item {
                    Text(
                        text = "记忆代谢巡检建议 (${pendingMaintenanceTasks.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(pendingMaintenanceTasks, key = { it.id }) { task ->
                    MaintenanceTaskReviewItem(
                        task = task,
                        targetDescription = maintenanceTargets[task.id],
                        onResolve = { status -> onResolveTask(task.id, status) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun RelationReviewItem(
    relation: MemoryRelationEntity,
    sourceCard: MemoryCardEntity? = null,
    targetCard: MemoryCardEntity? = null,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = relation.relationType,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "强度: ${"%.2f".format(relation.strength)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            // 源卡片
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "源卡片",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        sourceCard?.let { CategoryBadge(category = it.category) }
                    }
                    Text(
                        text = sourceCard?.content ?: "卡片ID: ${relation.sourceCardId} (已移除或未找到)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 目标卡片
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "目标卡片",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        targetCard?.let { CategoryBadge(category = it.category) }
                    }
                    Text(
                        text = targetCard?.content ?: "卡片ID: ${relation.targetCardId} (已移除或未找到)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            relation.reason?.let { r ->
                if (r.isNotBlank()) {
                    Text(
                        text = "关联原因: $r",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onReject) {
                    Text("拒绝", color = MaterialTheme.colorScheme.outline)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onApprove) {
                    Text("通过边")
                }
            }
        }
    }
}

@Composable
private fun ProposalReviewItem(
    proposal: MemoryProposalEntity,
    targetCard: MemoryCardEntity? = null,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val requiresTarget = proposal.action.uppercase() != MemoryConstants.OP_ADD
    val targetMissing = requiresTarget && (proposal.targetId.isNullOrBlank() || targetCard == null)
    val targetArchived = targetCard?.status == MemoryConstants.STATUS_ARCHIVED
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CategoryBadge(category = proposal.type)
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = proposal.action,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "重要度: ${proposal.importance}/10",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Text(
                    text = proposal.memoryDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Text(
                text = proposal.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = proposal.content,
                style = MaterialTheme.typography.bodyMedium
            )

            if (proposal.evidence.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "证据原话: ${proposal.evidence}",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            proposal.factKey?.let { fk ->
                Text(
                    text = "事实键: $fk",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (targetMissing) {
                Text(
                    text = "目标记忆已不存在，确认不会执行；可保留此项或点击忽略移除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (targetArchived) {
                Text(
                    text = "目标记忆已归档，确认时会先恢复再执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onReject) {
                    Text("忽略", color = MaterialTheme.colorScheme.outline)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onApprove, enabled = !targetMissing) {
                    Text(if (targetArchived) "恢复并执行" else "批准入库")
                }
            }
        }
    }
}

@Composable
private fun FactAuditReviewItem(
    audit: MemoryFactAuditEntity,
    existingCard: MemoryCardEntity? = null,
    candidateCard: MemoryCardEntity? = null,
    onResolve: (String) -> Unit,
) {
    val existingMissing = existingCard == null
    val candidateMissingWithoutContent = candidateCard == null && audit.candidateContent.isBlank()
    val missingRequiredCard = existingMissing || candidateMissingWithoutContent
    val hasArchivedCard = existingCard?.status == MemoryConstants.STATUS_ARCHIVED ||
        candidateCard?.status == MemoryConstants.STATUS_ARCHIVED
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "事实键冲突: ${audit.factKey}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "建议: ${audit.modelSuggestion}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Text(
                text = "候选新内容: ${audit.candidateContent}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            audit.evidenceSnippet?.let { snippet ->
                if (snippet.isNotBlank()) {
                    Text(
                        text = "原话证据: $snippet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Text(
                text = "原因: ${audit.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when {
                existingMissing -> Text(
                    text = "所需记忆已不存在：旧事实卡无法找到，不能执行替代或保留；可保留此项，或点击拒绝移除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                candidateMissingWithoutContent -> Text(
                    text = "所需记忆已不存在：候选卡无法找到且没有候选文本，不能执行；可保留此项，或点击拒绝移除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                hasArchivedCard -> Text(
                    text = "相关记忆已归档，确认时会先恢复再执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                audit.candidateCardId != null && candidateCard == null -> Text(
                    text = "候选卡已不存在，将根据保存的候选文本重新创建。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onResolve("REJECT") }) {
                    Text("拒绝新事实")
                }
                TextButton(onClick = { onResolve("KEEP_BOTH") }, enabled = !missingRequiredCard) {
                    Text("两者都保留")
                }
                if (audit.candidateCardId != null || audit.candidateContent.isNotBlank()) {
                    Button(onClick = { onResolve("SUPERSEDE") }, enabled = !missingRequiredCard) {
                        Text(if (hasArchivedCard) "恢复并替代" else "替代旧事实")
                    }
                }
            }
        }
    }
}

@Composable
private fun MaintenanceTaskReviewItem(
    task: MemoryMaintenanceTaskEntity,
    targetDescription: String?,
    onResolve: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        text = task.taskType,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "级别: ${task.severity}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Text(
                text = task.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "关联记忆：\n${targetDescription ?: "正在读取…"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = task.suggestion,
                style = MaterialTheme.typography.bodyMedium
            )

            if (task.reason.isNotBlank()) {
                Text(
                    text = "巡检依据: ${task.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onResolve("DISMISSED") }) {
                    Text("忽略")
                }
                Button(onClick = { onResolve("RESOLVED") }) {
                    Text("标记已处理")
                }
            }
        }
    }
}

@Composable
private fun EProposalReviewItem(
    proposal: MemoryEProposalEntity,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        text = "E-Axis 经验提议",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                Text(
                    text = "提议者: ${proposal.proposer}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Text(
                text = "目标记忆卡 ID: ${proposal.memoryCardId}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )

            proposal.meaning?.let { meaning ->
                Text(
                    text = "体验意义: $meaning",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            proposal.responseTendency?.let { tendency ->
                Text(
                    text = "回应倾向: $tendency",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }

            Text(
                text = "坐标: V=${proposal.valence?.let { String.format(Locale.US, "%.2f", it) } ?: "-"} A=${proposal.arousal?.let { String.format(Locale.US, "%.2f", it) } ?: "-"} T=${proposal.tension?.let { String.format(Locale.US, "%.2f", it) } ?: "-"} | P=${proposal.initialPriority}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline
            )

            proposal.reason?.let { reason ->
                Text(
                    text = "理由: $reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onReject, modifier = Modifier.size(36.dp)) {
                    Icon(
                        HugeIcons.Cancel01,
                        contentDescription = "拒绝",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onAccept, modifier = Modifier.size(36.dp)) {
                    Icon(
                        HugeIcons.CheckmarkCircle02,
                        contentDescription = "通过",
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

