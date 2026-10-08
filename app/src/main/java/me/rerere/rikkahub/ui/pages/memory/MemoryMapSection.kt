/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.memory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.rikkahub.data.memory.v1.MemoryConstants
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryMapSection(
    mapUiState: MemoryMapUiState,
    onSelectCenterCard: (String?) -> Unit,
    onCardClick: (MemoryCardEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var subMode by remember { mutableIntStateOf(0) } // 0: 时间线, 1: 关系, 2: 事实演化

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        SecondaryTabRow(
            selectedTabIndex = subMode,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        ) {
            Tab(
                selected = subMode == 0,
                onClick = { subMode = 0 },
                text = { Text("时间线 (${mapUiState.timelines.size})") }
            )
            Tab(
                selected = subMode == 1,
                onClick = { subMode = 1 },
                text = { Text("一跳关系") }
            )
            Tab(
                selected = subMode == 2,
                onClick = { subMode = 2 },
                text = { Text("事实演化 (${mapUiState.factEvolutionChains.size})") }
            )
        }

        if (mapUiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            when (subMode) {
                0 -> TimelineMapContent(
                    timelines = mapUiState.timelines,
                    onCardClick = onCardClick,
                    modifier = Modifier.weight(1f),
                )
                1 -> RelationMapContent(
                    allCards = mapUiState.allCards,
                    selectedCenterCard = mapUiState.selectedCenterCard,
                    oneHopRelations = mapUiState.oneHopRelations,
                    onSelectCenterCard = onSelectCenterCard,
                    onCardClick = onCardClick,
                    modifier = Modifier.weight(1f),
                )
                2 -> FactEvolutionMapContent(
                    chains = mapUiState.factEvolutionChains,
                    onCardClick = onCardClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 1. 时间线视图 (X 轴)
// ---------------------------------------------------------------------------

@Composable
private fun TimelineMapContent(
    timelines: List<MemoryTimelineWithCards>,
    onCardClick: (MemoryCardEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (timelines.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无进行中的时间线",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        return
    }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        items(timelines, key = { it.timeline.id }) { item ->
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.timeline.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "${item.cards.size} 张卡片",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (item.timeline.summary.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.timeline.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (item.cards.isEmpty()) {
                        Text(
                            text = "该时间线暂未加入有效卡片",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) {
                            items(item.cards, key = { it.id }) { card ->
                                TimelineCardItem(
                                    card = card,
                                    dateFormat = dateFormat,
                                    onClick = { onCardClick(card) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineCardItem(
    card: MemoryCardEntity,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .width(180.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
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
                        text = card.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                val timeMs = card.validFrom ?: card.createdAt
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = HugeIcons.Time02,
                        contentDescription = null,
                        modifier = Modifier.size(11.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = dateFormat.format(Date(timeMs)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = card.content,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 2. 关系视图 (Y 轴：一跳关系)
// ---------------------------------------------------------------------------

@Composable
private fun RelationMapContent(
    allCards: List<MemoryCardEntity>,
    selectedCenterCard: MemoryCardEntity?,
    oneHopRelations: List<MemoryOneHopRelation>,
    onSelectCenterCard: (String?) -> Unit,
    onCardClick: (MemoryCardEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchKey by remember { mutableStateOf("") }
    val filteredCandidateCards = remember(allCards, searchKey) {
        if (searchKey.isBlank()) {
            allCards.take(20)
        } else {
            allCards.filter {
                it.content.contains(searchKey, ignoreCase = true) ||
                    (it.factKey?.contains(searchKey, ignoreCase = true) == true) ||
                    it.category.contains(searchKey, ignoreCase = true)
            }.take(20)
        }
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // 搜索/筛选中心卡片输入框
        OutlinedTextField(
            value = searchKey,
            onValueChange = { searchKey = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            placeholder = { Text("搜索中心卡片...") },
            leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp)
        )

        // 快速选择候选卡片条
        if (filteredCandidateCards.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredCandidateCards, key = { it.id }) { card ->
                    val isSelected = card.id == selectedCenterCard?.id
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectCenterCard(card.id) },
                        label = {
                            Text(
                                text = card.content.take(15).replace("\n", " ") + if (card.content.length > 15) "..." else "",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (selectedCenterCard == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "请从上方选择一张中心卡片以查看一跳关系",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            return
        }

        // 展示当前中心卡片
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { onCardClick(selectedCenterCard) },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f))
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "中心卡片",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = selectedCenterCard.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = selectedCenterCard.content,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 一跳关系列表
        Text(
            text = "一跳关联 (${oneHopRelations.size})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 2.dp)
        )

        if (oneHopRelations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "该卡片暂无关联关系 (ACTIVE / REVIEW)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(oneHopRelations, key = { it.relation.id }) { relItem ->
                    OneHopRelationItem(
                        item = relItem,
                        onEndpointClick = { onCardClick(it) },
                        onSetAsCenter = { onSelectCenterCard(it.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OneHopRelationItem(
    item: MemoryOneHopRelation,
    onEndpointClick: (MemoryCardEntity) -> Unit,
    onSetAsCenter: (MemoryCardEntity) -> Unit,
) {
    val relTypeColor = getRelationTypeColor(item.relation.relationType)
    val endpointCard = if (item.isOutgoing) item.targetCard else item.sourceCard

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // 关系流条：[来源] -> (类型) -> [目标]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (item.isOutgoing) "出边" else "入边",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (item.isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = relTypeColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, relTypeColor.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = item.relation.relationType,
                            style = MaterialTheme.typography.labelSmall,
                            color = relTypeColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (item.relation.status == MemoryConstants.RELATION_STATUS_REVIEW) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                text = "待审核",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                TextButton(
                    onClick = { onSetAsCenter(endpointCard) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text("设为中心", fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 端点卡片内容
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEndpointClick(endpointCard) },
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "${if (item.isOutgoing) "目标" else "来源"}卡片 · ${endpointCard.category}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = endpointCard.content,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun getRelationTypeColor(relationType: String): Color {
    return when (relationType.trim().lowercase()) {
        "same_event" -> Color(0xFF1976D2)
        "temporal_sequence" -> Color(0xFFE65100)
        "derived_from" -> Color(0xFF7B1FA2)
        "supersedes" -> Color(0xFFC2185B)
        "cause_effect" -> Color(0xFF00796B)
        else -> Color(0xFF546E7A)
    }
}

// ---------------------------------------------------------------------------
// 3. 事实演化视图 (Z 轴：事实版本演化链)
// ---------------------------------------------------------------------------

@Composable
private fun FactEvolutionMapContent(
    chains: List<MemoryFactEvolutionChain>,
    onCardClick: (MemoryCardEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (chains.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无含事实键 (factKey) 的事实演化记录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        return
    }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        items(chains, key = { it.factKey }) { chain ->
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // factKey 标题
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = chain.factKey,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        val total = chain.historyCards.size + chain.currentCards.size + chain.pendingCards.size
                        Text(
                            text = "共 $total 个版本",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 横向流动/滚动版本流：历史版本 -> 当前版本 -> 待审核版本
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. 历史版本
                        chain.historyCards.forEach { card ->
                            EvolutionNodeCard(
                                card = card,
                                tag = "历史 (${card.status})",
                                tagBgColor = MaterialTheme.colorScheme.surfaceVariant,
                                tagTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                borderColor = MaterialTheme.colorScheme.outlineVariant,
                                dateFormat = dateFormat,
                                onClick = { onCardClick(card) }
                            )
                            Icon(
                                imageVector = HugeIcons.ArrowRight01,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }

                        // 2. 当前版本
                        if (chain.currentCards.isNotEmpty()) {
                            chain.currentCards.forEachIndexed { idx, card ->
                                EvolutionNodeCard(
                                    card = card,
                                    tag = "当前有效",
                                    tagBgColor = MaterialTheme.colorScheme.primaryContainer,
                                    tagTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    borderColor = MaterialTheme.colorScheme.primary,
                                    dateFormat = dateFormat,
                                    onClick = { onCardClick(card) }
                                )
                                if (idx < chain.currentCards.size - 1 || chain.pendingCards.isNotEmpty()) {
                                    Icon(
                                        imageVector = HugeIcons.ArrowRight01,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        } else if (chain.historyCards.isNotEmpty() && chain.pendingCards.isNotEmpty()) {
                            Icon(
                                imageVector = HugeIcons.ArrowRight01,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }

                        // 3. 待审核版本
                        chain.pendingCards.forEachIndexed { idx, card ->
                            EvolutionNodeCard(
                                card = card,
                                tag = "待审核",
                                tagBgColor = MaterialTheme.colorScheme.errorContainer,
                                tagTextColor = MaterialTheme.colorScheme.onErrorContainer,
                                borderColor = MaterialTheme.colorScheme.error,
                                dateFormat = dateFormat,
                                onClick = { onCardClick(card) }
                            )
                            if (idx < chain.pendingCards.size - 1) {
                                Icon(
                                    imageVector = HugeIcons.ArrowRight01,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EvolutionNodeCard(
    card: MemoryCardEntity,
    tag: String,
    tagBgColor: Color,
    tagTextColor: Color,
    borderColor: Color,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(170.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, borderColor),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = tagBgColor
                ) {
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = tagTextColor,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                Text(
                    text = "rev.${card.revision}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 10.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = card.content,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            val timeMs = card.validFrom ?: card.createdAt
            Text(
                text = dateFormat.format(Date(timeMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontSize = 9.sp
            )
        }
    }
}
