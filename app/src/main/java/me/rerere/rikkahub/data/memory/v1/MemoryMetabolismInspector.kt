/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import android.util.Log
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryMaintenanceTaskEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryV1Dao
import kotlin.uuid.Uuid

private const val TAG = "MemoryMetabolismInspector"

private inline fun safeLogI(tag: String, message: String) {
    runCatching { Log.i(tag, message) }
}

private inline fun safeLogW(tag: String, message: String, throwable: Throwable? = null) {
    runCatching { Log.w(tag, message, throwable) }
}

/**
 * M 轴记忆代谢巡检器 (LMC-5 M-Axis Metabolism Inspector):
 *
 * 核心设计原则：
 * “巡检先报告，不静默清理”。
 * 本地规则引擎对知识主库进行非侵入式扫描，发现潜在事实冲突、冷记忆、高频纠正、
 * 归档回潮、积压审批与异常时间线，产出建议任务 (memory_maintenance_tasks)，
 * 绝不静默修改或删除任何长期事实卡片。
 */
open class MemoryMetabolismInspector(
    private val dao: MemoryV1Dao,
) {
    companion object {
        const val TASK_FACT_CONFLICT = "FACT_CONFLICT"
        const val TASK_FREQUENT_CORRECTION = "FREQUENT_CORRECTION"
        const val TASK_COLD_INACTIVE = "COLD_INACTIVE"
        const val TASK_ARCHIVED_ACCESSED = "ARCHIVED_ACCESSED"
        const val TASK_STALE_REVIEW = "STALE_REVIEW"
        const val TASK_TIMELINE_RESTRUCTURE = "TIMELINE_RESTRUCTURE"
        const val TASK_EXPERIENCE_ANOMALY = "EXPERIENCE_ANOMALY"
        const val TASK_EXPERIENCE_PROPOSAL_STALE = "EXPERIENCE_PROPOSAL_STALE"

        const val TARGET_CARD = "CARD"
        const val TARGET_PROPOSAL = "PROPOSAL"
        const val TARGET_TIMELINE = "TIMELINE"
        const val TARGET_FACT_KEY = "FACT_KEY"
        const val TARGET_EXPERIENCE = "EXPERIENCE"

        const val SEVERITY_INFO = "INFO"
        const val SEVERITY_WARNING = "WARNING"
        const val SEVERITY_CRITICAL = "CRITICAL"

        // 时间常量
        const val DAY_MS = 86_400_000L
        const val COLD_MEMORY_THRESHOLD_MS = 30 * DAY_MS // 30 天未唤醒
        const val COLD_MEMORY_MAX_IMPORTANCE = 0.4f     // 低重要度阈值
        const val FREQUENT_CORRECTION_THRESHOLD = 3      // 经历 3 次及以上修订
        const val STALE_REVIEW_THRESHOLD_MS = 14 * DAY_MS// 14 天积压提议
        const val TIMELINE_MAX_MEMBERS = 20              // 时间线单条容量阈值
        const val SUPPRESSION_DURATION_MS = 14 * DAY_MS  // 任务抑制期限（已忽略或已处理任务默认14天内不再提醒）

        val PROTECTED_COLD_CATEGORIES = setOf(
            MemoryConstants.CATEGORY_SELF,
            MemoryConstants.CATEGORY_RELATIONSHIP,
            MemoryConstants.CATEGORY_BOUNDARY,
        )
    }

    /**
     * 判断卡片是否获得冷记忆保护。
     * 满足任一条件时，不生成 COLD_INACTIVE 建议：
     * 1. isProtected = true
     * 2. 类别为 self、relationship 或 boundary
     * 3. 属于 ACTIVE 时间线
     * 4. importance 高于现有冷记忆阈值
     */
    fun isColdMemoryProtected(
        card: MemoryCardEntity,
        activeTimelineCardIds: Set<String>,
        coldMemoryMaxImportance: Float = COLD_MEMORY_MAX_IMPORTANCE,
    ): Boolean {
        if (card.isProtected) return true
        if (card.category.lowercase().trim() in PROTECTED_COLD_CATEGORIES) return true
        if (card.id in activeTimelineCardIds) return true
        if (card.importance > coldMemoryMaxImportance) return true
        return false
    }

    /**
     * 执行指定 owner 的全量代谢巡检。
     *
     * @return 本轮巡检新创建的建议任务列表
     */
    open suspend fun inspect(
        ownerType: String,
        ownerId: String,
        currentTimeMs: Long = System.currentTimeMillis(),
    ): List<MemoryMaintenanceTaskEntity> {
        val createdTasks = mutableListOf<MemoryMaintenanceTaskEntity>()

        try {
            // 1. 扫描事实潜在冲突 (FACT_CONFLICT)
            inspectFactConflicts(ownerType, ownerId, currentTimeMs, createdTasks)

            // 2. 扫描高频纠正卡片 (FREQUENT_CORRECTION)
            inspectFrequentCorrections(ownerType, ownerId, currentTimeMs, createdTasks)

            // 3. 扫描冷记忆归档建议 (COLD_INACTIVE)
            inspectColdMemories(ownerType, ownerId, currentTimeMs, createdTasks)

            // 4. 扫描已归档但近期回潮记忆 (ARCHIVED_ACCESSED)
            inspectArchivedAccessed(ownerType, ownerId, currentTimeMs, createdTasks)

            // 5. 扫描长期积压的未决提议 (STALE_REVIEW)
            inspectStaleProposals(ownerType, ownerId, currentTimeMs, createdTasks)

            // 6. 扫描需拆分或重构的时间线 (TIMELINE_RESTRUCTURE)
            inspectTimelines(ownerType, ownerId, currentTimeMs, createdTasks)

            // 7. 扫描积压的经验提议与孤儿/多重 CURRENT 异常 (EXPERIENCE_ANOMALY / EXPERIENCE_PROPOSAL_STALE)
            inspectExperienceCoordinates(ownerType, ownerId, currentTimeMs, createdTasks)

            safeLogI(TAG, "Metabolism inspection completed for $ownerId: generated ${createdTasks.size} new maintenance tasks")
        } catch (e: Exception) {
            safeLogW(TAG, "Metabolism inspection encountered error for $ownerId", e)
        }

        return createdTasks
    }

    private suspend fun inspectFactConflicts(
        ownerType: String,
        ownerId: String,
        now: Long,
        outTasks: MutableList<MemoryMaintenanceTaskEntity>,
    ) {
        // [P2 优化] 事实冲突已全面收拢至 Z 轴演化审核 (MemoryFactAuditEntity) 进行专属人工/模型双轨处理。
        // 此处不再向 M 轴冗余生成 TASK_FACT_CONFLICT 维护任务，避免用户在界面收到双重提示噪音。
    }

    private suspend fun inspectFrequentCorrections(
        ownerType: String,
        ownerId: String,
        now: Long,
        outTasks: MutableList<MemoryMaintenanceTaskEntity>,
    ) {
        val activeCards = dao.getCardsByStatus(ownerType, ownerId, MemoryConstants.STATUS_ACTIVE)
        for (card in activeCards) {
            if (card.revision >= FREQUENT_CORRECTION_THRESHOLD) {
                maybeCreateTask(
                    ownerType = ownerType,
                    ownerId = ownerId,
                    taskType = TASK_FREQUENT_CORRECTION,
                    targetType = TARGET_CARD,
                    targetId = card.id,
                    title = "事实频繁变动: ${card.factKey ?: card.content.take(20)}",
                    suggestion = "该记忆已历经 ${card.revision} 次修订更新，可能属于易变或不稳定偏好，建议人工确认权威事实",
                    reason = "当前卡片修订版本已达 rev=${card.revision}",
                    severity = SEVERITY_WARNING,
                    now = now,
                    outTasks = outTasks,
                )
            }
        }
    }

    private suspend fun inspectColdMemories(
        ownerType: String,
        ownerId: String,
        now: Long,
        outTasks: MutableList<MemoryMaintenanceTaskEntity>,
    ) {
        val activeCards = dao.getCardsByStatus(ownerType, ownerId, MemoryConstants.STATUS_ACTIVE)
        val activeTimelines = dao.getTimelinesByStatus(ownerType, ownerId, "ACTIVE")
        val activeTimelineCardIds = activeTimelines
            .flatMap { dao.getTimelineMembers(it.id) }
            .map { it.cardId }
            .toSet()

        for (card in activeCards) {
            // 冷记忆保护：满足任一条件时，豁免归档建议
            if (isColdMemoryProtected(card, activeTimelineCardIds)) continue

            // 必须创建超过 30 天，且超过 30 天未被唤醒（或从未被唤醒）
            val isOldEnough = (now - card.createdAt) >= COLD_MEMORY_THRESHOLD_MS
            val isCold = card.lastRecalledAt == null || (now - card.lastRecalledAt) >= COLD_MEMORY_THRESHOLD_MS

            if (isOldEnough && isCold) {
                val daysSinceRecall = if (card.lastRecalledAt != null) {
                    ((now - card.lastRecalledAt) / DAY_MS).toInt()
                } else {
                    ((now - card.createdAt) / DAY_MS).toInt()
                }

                maybeCreateTask(
                    ownerType = ownerType,
                    ownerId = ownerId,
                    taskType = TASK_COLD_INACTIVE,
                    targetType = TARGET_CARD,
                    targetId = card.id,
                    title = "冷记忆归档建议: ${card.content.take(20)}",
                    suggestion = "该记忆已超过 ${daysSinceRecall} 天未在对话中被召回且重要度较低(${card.importance})，建议归档以精简活跃记忆库",
                    reason = "未受保护，importance=${card.importance}，hitCount=${card.hitCount}",
                    severity = SEVERITY_INFO,
                    now = now,
                    outTasks = outTasks,
                )
            }
        }
    }

    private suspend fun inspectArchivedAccessed(
        ownerType: String,
        ownerId: String,
        now: Long,
        outTasks: MutableList<MemoryMaintenanceTaskEntity>,
    ) {
        val archivedCards = dao.getCardsByStatus(ownerType, ownerId, MemoryConstants.STATUS_ARCHIVED)
        for (card in archivedCards) {
            val lastRecalled = card.lastRecalledAt
            // 7 天内被访问过
            if (lastRecalled != null && (now - lastRecalled) <= 7 * DAY_MS) {
                maybeCreateTask(
                    ownerType = ownerType,
                    ownerId = ownerId,
                    taskType = TASK_ARCHIVED_ACCESSED,
                    targetType = TARGET_CARD,
                    targetId = card.id,
                    title = "已归档记忆近期被触及: ${card.content.take(20)}",
                    suggestion = "已归档的事实近期再次在对话交流中被检索触及，建议核实是否需要重新激活该记忆",
                    reason = "已归档卡片于近期被触及，最近触及时间戳=$lastRecalled",
                    severity = SEVERITY_WARNING,
                    now = now,
                    outTasks = outTasks,
                )
            }
        }
    }

    private suspend fun inspectStaleProposals(
        ownerType: String,
        ownerId: String,
        now: Long,
        outTasks: MutableList<MemoryMaintenanceTaskEntity>,
    ) {
        val reviewProposals = dao.getProposalsByStatus(ownerType, ownerId, "REVIEW")
        for (proposal in reviewProposals) {
            if ((now - proposal.createdAt) >= STALE_REVIEW_THRESHOLD_MS) {
                val daysPending = ((now - proposal.createdAt) / DAY_MS).toInt()
                maybeCreateTask(
                    ownerType = ownerType,
                    ownerId = ownerId,
                    taskType = TASK_STALE_REVIEW,
                    targetType = TARGET_PROPOSAL,
                    targetId = proposal.id,
                    title = "长期积压的记忆待审项: ${proposal.title}",
                    suggestion = "该候选提议已搁置超过 ${daysPending} 天未处理，建议人工审批或予以清理",
                    reason = "proposal_id=${proposal.id}, type=${proposal.type}, 积压天数=$daysPending",
                    severity = SEVERITY_INFO,
                    now = now,
                    outTasks = outTasks,
                )
            }
        }
    }

    private suspend fun inspectTimelines(
        ownerType: String,
        ownerId: String,
        now: Long,
        outTasks: MutableList<MemoryMaintenanceTaskEntity>,
    ) {
        val activeTimelines = dao.getTimelinesByStatus(ownerType, ownerId, "ACTIVE")
        for (timeline in activeTimelines) {
            val members = dao.getTimelineMembers(timeline.id)
            if (members.size > TIMELINE_MAX_MEMBERS) {
                maybeCreateTask(
                    ownerType = ownerType,
                    ownerId = ownerId,
                    taskType = TASK_TIMELINE_RESTRUCTURE,
                    targetType = TARGET_TIMELINE,
                    targetId = timeline.id,
                    title = "时间线容量过大建议拆分: ${timeline.title}",
                    suggestion = "该时间线已容纳 ${members.size} 张卡片，跨度过大，建议按阶段细分子时间线",
                    reason = "timeline_id=${timeline.id}, member_count=${members.size}",
                    severity = SEVERITY_INFO,
                    now = now,
                    outTasks = outTasks,
                )
            } else if (members.isEmpty() && (now - timeline.createdAt) >= 7 * DAY_MS) {
                maybeCreateTask(
                    ownerType = ownerType,
                    ownerId = ownerId,
                    taskType = TASK_TIMELINE_RESTRUCTURE,
                    targetType = TARGET_TIMELINE,
                    targetId = timeline.id,
                    title = "空时间线建议清理: ${timeline.title}",
                    suggestion = "时间线已建立超过7天但未关联任何事实卡片，建议添加记忆或归档",
                    reason = "timeline_id=${timeline.id}, member_count=0",
                    severity = SEVERITY_INFO,
                    now = now,
                    outTasks = outTasks,
                )
            }
        }
    }

    private suspend fun inspectExperienceCoordinates(
        ownerType: String,
        ownerId: String,
        now: Long,
        outTasks: MutableList<MemoryMaintenanceTaskEntity>,
    ) {
        // 1. 扫描积压未审的主体经验提议
        val staleProposals = dao.getPendingEProposals(ownerType, ownerId)
        for (prop in staleProposals) {
            if ((now - prop.createdAt) >= STALE_REVIEW_THRESHOLD_MS) {
                val daysPending = ((now - prop.createdAt) / DAY_MS).toInt()
                maybeCreateTask(
                    ownerType = ownerType,
                    ownerId = ownerId,
                    taskType = TASK_EXPERIENCE_PROPOSAL_STALE,
                    targetType = TARGET_PROPOSAL,
                    targetId = prop.id.toString(),
                    title = "长期积压的主体经验提议: cardId=${prop.memoryCardId}",
                    suggestion = "该经验提议已搁置 $daysPending 天未被处理，建议人工审批或采纳演进",
                    reason = "e_proposal_id=${prop.id}, 积压天数=$daysPending",
                    severity = SEVERITY_INFO,
                    now = now,
                    outTasks = outTasks,
                )
            }
        }

        // 2. 扫描孤儿经验坐标与多重当前异常
        val currentEs = dao.getCurrentExperiencesForOwner(ownerType, ownerId)
        val groupedByCard = currentEs.groupBy { it.memoryCardId }

        for ((cardId, es) in groupedByCard) {
            val card = dao.getCardById(cardId)
            if (card == null || card.status == MemoryConstants.STATUS_ARCHIVED) {
                maybeCreateTask(
                    ownerType = ownerType,
                    ownerId = ownerId,
                    taskType = TASK_EXPERIENCE_ANOMALY,
                    targetType = TARGET_EXPERIENCE,
                    targetId = cardId,
                    title = "孤儿经验坐标: cardId=$cardId",
                    suggestion = "关联的事实卡片已不存在或已归档，但仍保留活跃经验坐标，建议清理或归档",
                    reason = "card status is null or ARCHIVED",
                    severity = SEVERITY_WARNING,
                    now = now,
                    outTasks = outTasks,
                )
            } else if (es.size > 1) {
                maybeCreateTask(
                    ownerType = ownerType,
                    ownerId = ownerId,
                    taskType = TASK_EXPERIENCE_ANOMALY,
                    targetType = TARGET_EXPERIENCE,
                    targetId = cardId,
                    title = "经验坐标多重活跃异常: cardId=$cardId",
                    suggestion = "同一张记忆卡片检测到 ${es.size} 个 CURRENT 活跃经验坐标，建议解决冲突并保留单一当前坐标",
                    reason = "multiple CURRENT experiences found: ${es.map { it.id }}",
                    severity = SEVERITY_CRITICAL,
                    now = now,
                    outTasks = outTasks,
                )
            }
        }
    }

    private suspend fun maybeCreateTask(
        ownerType: String,
        ownerId: String,
        taskType: String,
        targetType: String,
        targetId: String,
        title: String,
        suggestion: String,
        reason: String,
        severity: String,
        metadata: String? = null,
        now: Long,
        outTasks: MutableList<MemoryMaintenanceTaskEntity>,
    ) {
        // 幂等防重复与持久抑制策略：
        // 1. 若当前存在同 targetId + taskType 的 PENDING 任务，跳过生成
        // 2. 若最近一条任务状态为 IGNORED：在 SUPPRESSION_DURATION_MS (14 天) 内不重新提醒
        // 3. 若最近一条任务状态为 RESOLVED：
        //    - 若目标为 CARD：仅当卡片在 resolvedAt 之后发生过新变更 (updatedAt > resolvedAt) 时才重新提醒；否则抑制
        //    - 若目标为 PROPOSAL：若提议未变更，不重新提醒
        //    - 其它目标：在 SUPPRESSION_DURATION_MS (14 天) 内不重新提醒
        val latestTask = dao.getLatestMaintenanceTaskByTargetAndType(ownerType, ownerId, targetType, targetId, taskType)
        if (latestTask != null) {
            when (latestTask.status) {
                "PENDING" -> return
                "IGNORED" -> {
                    val resolvedTime = latestTask.resolvedAt ?: latestTask.createdAt
                    if (now - resolvedTime < SUPPRESSION_DURATION_MS) {
                        return
                    }
                }
                "RESOLVED" -> {
                    val resolvedTime = latestTask.resolvedAt ?: latestTask.createdAt
                    if (targetType == TARGET_CARD) {
                        val card = dao.getCardById(targetId)
                        if (card == null || card.updatedAt <= resolvedTime) {
                            return
                        }
                    } else if (targetType == TARGET_PROPOSAL) {
                        val proposal = dao.getProposalById(targetId)
                        if (proposal == null || (proposal.resolvedAt != null && proposal.resolvedAt <= resolvedTime)) {
                            return
                        }
                    } else {
                        if (now - resolvedTime < SUPPRESSION_DURATION_MS) {
                            return
                        }
                    }
                }
            }
        }

        val task = MemoryMaintenanceTaskEntity(
            id = Uuid.random().toString(),
            ownerType = ownerType,
            ownerId = ownerId,
            taskType = taskType,
            targetType = targetType,
            targetId = targetId,
            title = title,
            suggestion = suggestion,
            reason = reason,
            severity = severity,
            status = "PENDING",
            metadata = metadata,
            createdAt = now,
        )
        dao.insertMaintenanceTask(task)
        outTasks.add(task)
    }
}
