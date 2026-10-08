/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.desire.db.DesireActiveSendEntity
import me.rerere.rikkahub.data.ai.desire.db.DesireDao
import me.rerere.rikkahub.data.ai.desire.db.DesireEventReceiptEntity
import me.rerere.rikkahub.data.ai.desire.db.DesireStateEntity
import me.rerere.rikkahub.data.ai.desire.db.DesireTickLogEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousWakeRunEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousWakeStateEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousItemEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousWakePlanEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousShareEntity
import me.rerere.rikkahub.data.model.Assistant
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class DesireRepository(
    private val desireDao: DesireDao,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun saveAutonomousRun(run: AutonomousWakeRunEntity) {
        desireDao.upsertAutonomousRun(run)
    }

    suspend fun listAutonomousRuns(assistantId: String, limit: Int = 100) =
        desireDao.listAutonomousRuns(assistantId, limit)

    suspend fun listAutonomousRunsInRange(assistantId: String, startMs: Long, endMs: Long) =
        desireDao.listAutonomousRunsInRange(assistantId, startMs, endMs)

    suspend fun recoverStaleAutonomousRuns(assistantId: String, nowMs: Long = System.currentTimeMillis()) {
        desireDao.listAutonomousRuns(assistantId, 100)
            .filter {
                it.status in setOf("running", "waiting") &&
                    (it.startedAt ?: it.scheduledAt) < nowMs - 15 * 60 * 1000L
            }
            .forEach { markRunInterrupted(it, nowMs) }
    }

    suspend fun markAutonomousRunsInterrupted(assistantId: String, nowMs: Long = System.currentTimeMillis()) {
        desireDao.listAutonomousRuns(assistantId, 500)
            .filter { it.status in setOf("running", "waiting") }
            .forEach { markRunInterrupted(it, nowMs) }
    }

    private suspend fun markRunInterrupted(run: AutonomousWakeRunEntity, nowMs: Long) {
        desireDao.upsertAutonomousRun(run.copy(
            finishedAt = nowMs,
            status = "interrupted",
            errorText = "应用在本轮结束前被系统中断",
            toolCallsJson = markInterruptedToolStepsUncertain(run.toolCallsJson),
        ))
    }

    suspend fun recordAutonomousToolStep(
        runId: String,
        toolCallId: String,
        toolName: String,
        input: String,
        status: String,
        result: String = "",
    ) {
        val run = desireDao.getAutonomousRun(runId) ?: return
        val old = runCatching { json.parseToJsonElement(run.toolCallsJson).jsonArray }.getOrDefault(JsonArray(emptyList()))
        val updated = buildJsonArray {
            old.filterNot { element ->
                runCatching { element.jsonObject["tool_call_id"]?.toString()?.trim('"') == toolCallId }.getOrDefault(false)
            }.forEach(::add)
            add(buildJsonObject {
                put("tool_call_id", toolCallId)
                put("name", toolName)
                put("status", status)
                put("input", input)
                put("result", result)
            })
        }
        saveAutonomousRun(run.copy(toolCallsJson = updated.toString()))
    }

    private fun markInterruptedToolStepsUncertain(toolCallsJson: String): String {
        val calls = runCatching { json.parseToJsonElement(toolCallsJson).jsonArray }.getOrDefault(JsonArray(emptyList()))
        return buildJsonArray {
            calls.forEach { element ->
                val call = runCatching { element.jsonObject }.getOrNull()
                if (call == null || call["status"]?.toString()?.trim('"') != "executing") {
                    add(element)
                } else {
                    add(buildJsonObject {
                        call.forEach { (key, value) -> if (key != "status") put(key, value) }
                        put("status", "result_uncertain")
                        put("result", "应用在工具调用过程中中断，无法确认副作用是否完成")
                    })
                }
            }
        }.toString()
    }

    suspend fun getAutonomousRun(runId: String) = desireDao.getAutonomousRun(runId)

    suspend fun getAutonomousState(assistantId: String) = desireDao.getAutonomousState(assistantId)

    suspend fun saveAutonomousState(state: AutonomousWakeStateEntity) = desireDao.upsertAutonomousState(state)

    suspend fun listAutonomousItems(assistantId: String) = desireDao.listActiveAutonomousItems(assistantId)

    suspend fun listRecentAutonomousItems(assistantId: String, limit: Int = 50) = desireDao.listRecentAutonomousItems(assistantId, limit)

    suspend fun listTodayAutonomousItems(assistantId: String, nowMs: Long = System.currentTimeMillis()): List<AutonomousItemEntity> {
        val (startMs, endMs) = localDayBounds(nowMs)
        val changed = desireDao.listItemsChangedOnDay(assistantId, startMs, endMs)
        val dueToday = desireDao.listActiveAutonomousItems(assistantId).filter { item ->
            item.resumeAt?.let { value ->
                runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() in startMs until endMs }
                    .getOrDefault(false)
            } == true
        }
        return (changed + dueToday).distinctBy { it.id }.sortedByDescending { it.updatedAt }
    }

    suspend fun listCompletedAutonomousItemsRecentDays(assistantId: String, nowMs: Long): List<AutonomousItemEntity> {
        val (todayStart, endMs) = localDayBounds(nowMs)
        val startMs = java.time.Instant.ofEpochMilli(todayStart).atZone(java.time.ZoneId.systemDefault())
            .minusDays(2).toInstant().toEpochMilli()
        return desireDao.listCompletedItemsOnDay(assistantId, startMs, endMs)
    }

    private fun localDayBounds(nowMs: Long): Pair<Long, Long> {
        val start = java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.systemDefault())
            .toLocalDate().atStartOfDay(java.time.ZoneId.systemDefault())
        return start.toInstant().toEpochMilli() to start.plusDays(1).toInstant().toEpochMilli()
    }

    suspend fun getAutonomousItem(assistantId: String, itemId: String) = desireDao.getAutonomousItem(assistantId, itemId)

    suspend fun createAutonomousItem(
        assistantId: String,
        id: String,
        content: String,
        resumeAt: String?,
        progress: String = "",
    ): AutonomousItemEntity {
        require(content.isNotBlank()) { "事项内容不能为空" }
        validateResumeAt(resumeAt)
        val now = System.currentTimeMillis()
        return AutonomousItemEntity(
            id = id,
            assistantId = assistantId,
            content = content,
            progress = progress,
            resumeAt = resumeAt,
            createdAt = now,
            updatedAt = now,
        )
            .also { desireDao.upsertAutonomousItem(it) }
    }

    suspend fun createChatAutonomousItem(assistantId: String, content: String, resumeAt: String?): Pair<AutonomousItemEntity, Boolean> =
        getLock(assistantId).withLock {
            val normalizedContent = content.trim().replace(Regex("\\s+"), " ")
            require(normalizedContent.isNotBlank()) { "事项内容不能为空" }
            val existing = listAutonomousItems(assistantId).firstOrNull {
                it.content.trim().replace(Regex("\\s+"), " ") == normalizedContent
            }
            if (existing != null) existing to true
            else createAutonomousItem(assistantId, kotlin.uuid.Uuid.random().toString(), content.trim(), resumeAt) to false
        }

    suspend fun updateAutonomousItem(
        assistantId: String,
        id: String,
        content: String?,
        progress: String?,
        resumeAt: String?,
        updateResumeAt: Boolean,
    ): AutonomousItemEntity {
        val old = desireDao.getAutonomousItem(assistantId, id)?.takeIf { it.status == "active" }
            ?: error("活动事项不存在: $id")
        val nextResumeAt = if (updateResumeAt) resumeAt else old.resumeAt
        validateResumeAt(nextResumeAt)
        val updated = old.copy(
            content = content?.takeIf { it.isNotBlank() } ?: old.content,
            progress = progress ?: old.progress,
            resumeAt = nextResumeAt,
            updatedAt = System.currentTimeMillis(),
        )
        desireDao.upsertAutonomousItem(updated)
        return updated
    }

    suspend fun setAutonomousItemStatus(assistantId: String, id: String, status: String) {
        require(status == "completed" || status == "cancelled")
        check(desireDao.setAutonomousItemStatus(assistantId, id, status, System.currentTimeMillis()) > 0) {
            "活动事项不存在: $id"
        }
    }

    suspend fun replaceWakePlan(
        assistantId: String,
        autonomousAt: Long,
        source: String,
        reason: String,
        nowMs: Long = System.currentTimeMillis(),
    ): AutonomousWakePlanEntity {
        val itemAt = listAutonomousItems(assistantId).mapNotNull { item ->
            item.resumeAt?.let(::parseResumeAt)?.toEpochMilli()
        }.filter { it > nowMs }.minOrNull()
        return desireDao.replaceScheduledWakePlan(assistantId, autonomousAt, itemAt, source, reason, nowMs)
    }

    suspend fun getWakePlan(assistantId: String) = desireDao.getWakePlan(assistantId)

    suspend fun claimWakePlan(assistantId: String, version: Long, nowMs: Long) =
        desireDao.claimWakePlan(assistantId, version, nowMs) > 0

    suspend fun finishWakePlan(
        assistantId: String,
        expectedVersion: Long,
        proposedAutonomousAt: Long,
        source: String,
        reason: String,
        retryAt: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) = desireDao.finishAutonomousWakePlan(
        assistantId, expectedVersion, proposedAutonomousAt, source, reason, retryAt, nowMs,
    )

    suspend fun cancelWakePlan(assistantId: String, nowMs: Long = System.currentTimeMillis()) =
        desireDao.cancelWakePlan(assistantId, nowMs)

    suspend fun listAutonomousShares(assistantId: String, limit: Int = 20) =
        desireDao.listAutonomousShares(assistantId, limit)

    suspend fun getAutonomousShare(assistantId: String, dedupeKey: String) =
        desireDao.getAutonomousShare(assistantId, dedupeKey)

    suspend fun insertAutonomousShare(share: AutonomousShareEntity) = desireDao.insertAutonomousShare(share)

    suspend fun getLastAutonomousShare(assistantId: String) = desireDao.getLastAutonomousShare(assistantId)

    suspend fun postponeExpiredItems(assistantId: String, nowMs: Long = System.currentTimeMillis()): Int {
        var count = 0
        listAutonomousItems(assistantId).filter { item ->
            item.resumeAt?.let(::parseResumeAt)?.toEpochMilli()?.let { it <= nowMs } == true
        }.forEach { item ->
            desireDao.upsertAutonomousItem(item.copy(
                resumeAt = java.time.Instant.ofEpochMilli(nowMs + 15 * 60 * 1000L)
                    .atZone(ZoneId.systemDefault()).format(isoFormatter),
                updatedAt = nowMs,
            ))
            count++
        }
        return count
    }

    private fun validateResumeAt(value: String?) {
        if (value != null) require(runCatching { parseResumeAt(value) }.isSuccess) {
            "resume_at 必须是带时区的 ISO-8601 时间"
        }
    }

    private fun parseResumeAt(value: String) = java.time.OffsetDateTime.parse(value).toInstant()

    private val assistantLocks = ConcurrentHashMap<String, Mutex>()

    private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    private fun nowIso(): String {
        return isoFormatter.format(ZonedDateTime.now(ZoneId.systemDefault()))
    }

    private fun getLock(assistantId: String): Mutex =
        assistantLocks.computeIfAbsent(assistantId) { Mutex() }

    private val passCooldowns = ConcurrentHashMap<String, Long>()

    fun recordPassCooldown(
        assistantId: String,
        reason: String,
        cooldownDurationMs: Long = 30 * 60 * 1000L,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        passCooldowns[assistantId] = nowMs + cooldownDurationMs
    }

    fun getPassCooldownEnd(assistantId: String): Long {
        return passCooldowns[assistantId] ?: 0L
    }

    suspend fun advanceStateUnlocked(
        assistantId: String,
        nowMs: Long = System.currentTimeMillis(),
        explicitAbsentHours: Double? = null,
    ): DesireState {
        val entity = desireDao.getState(assistantId)
        if (entity == null) {
            val newState = DesireState()
            val defaultEntity = toEntity(assistantId, newState, nowMs, nextIntervalSeconds = 1800)
            desireDao.upsertState(defaultEntity)
            return newState
        }

        val lastEvaluatedAt = entity.lastTickAt
        val elapsedHours = (nowMs - lastEvaluatedAt).toDouble() / (3600.0 * 1000.0)

        val absentHours = explicitAbsentHours ?: run {
            val lastReceipt = desireDao.getLastUserMasterMessageReceipt(assistantId)
            if (lastReceipt != null) {
                ((nowMs - lastReceipt.createdAt).toDouble() / (3600.0 * 1000.0)).coerceAtLeast(0.0)
            } else {
                999.0
            }
        }
        val absentHoursStart = maxOf(0.0, absentHours - maxOf(0.0, elapsedHours))

        val state = fromEntity(entity)
        val evolvedDrives = DesireEvolutionEngine.evolve(
            drives = state.drives,
            elapsedHours = elapsedHours,
            absentHoursStart = absentHoursStart,
        )
        state.drives.clear()
        state.drives.putAll(evolvedDrives)

        if (elapsedHours > 0.0) {
            val updatedEntity = entity.copy(
                drivesJson = json.encodeToString(state.drives),
                lastTickAt = nowMs,
                updatedAt = nowMs,
            )
            desireDao.upsertState(updatedEntity)
        }
        return state
    }

    private suspend fun getOrCreateStateUnlocked(
        assistantId: String,
        nowMs: Long = System.currentTimeMillis(),
        absentHours: Double? = null,
    ): DesireState {
        return advanceStateUnlocked(assistantId, nowMs, absentHours)
    }

    suspend fun getOrCreateState(
        assistantId: String,
        nowMs: Long = System.currentTimeMillis(),
        absentHours: Double? = null,
    ): DesireState = getLock(assistantId).withLock {
        advanceStateUnlocked(assistantId, nowMs, absentHours)
    }

    companion object {
        const val MASTER_MESSAGE_COOLDOWN_MS: Long = 30 * 60 * 1000L
    }

    suspend fun applyMasterMessageOnce(
        assistantId: String,
        eventId: String,
        sourceId: String = "",
        nowMs: Long = System.currentTimeMillis(),
    ): List<String> = getLock(assistantId).withLock {
        if (desireDao.hasReceipt(eventId)) {
            return@withLock emptyList()
        }

        val lastReceipt = desireDao.getLastUserMasterMessageReceipt(assistantId)
        val shouldSettle = if (lastReceipt == null) {
            true
        } else {
            val diff = nowMs - lastReceipt.createdAt
            diff >= MASTER_MESSAGE_COOLDOWN_MS
        }

        val receipt = DesireEventReceiptEntity(
            eventId = eventId,
            assistantId = assistantId,
            eventName = "master_message",
            sourceType = "user_message",
            sourceId = sourceId,
            createdAt = nowMs,
            processedAt = nowMs,
        )

        if (!shouldSettle) {
            // 30 分钟内的连续消息只刷新最近活动时间，不重复扣数值，不推进演化
            desireDao.insertReceipt(receipt)
            return@withLock emptyList()
        }

        // 仅在真正结算时，先推进离开期间的自然变化
        val absentHoursAtArrival = if (lastReceipt != null) {
            ((nowMs - lastReceipt.createdAt).toDouble() / (3600.0 * 1000.0)).coerceAtLeast(0.0)
        } else {
            0.0
        }
        val state = advanceStateUnlocked(assistantId, nowMs, absentHoursAtArrival)
        val changes = DesireEventEngine.applyEvent(state, "master_message")
        val currentEntity = desireDao.getState(assistantId)
        val stateEntity = toEntity(assistantId, state, nowMs, explicitNextTickAt = currentEntity?.nextTickAt)

        val logEntity = DesireTickLogEntity(
            assistantId = assistantId,
            createdAt = nowMs,
            tickNumber = state.tickCount,
            eventName = "master_message",
            driveChangesJson = json.encodeToString(changes),
            thoughtChangesJson = "[]",
            behaviorHintsJson = "[]",
            monologue = "",
            warningsJson = "[]",
        )

        val committed = desireDao.saveEventWithReceipt(
            receipt = receipt,
            state = stateEntity,
            log = logEntity,
            keepLogs = 500,
        )
        if (!committed) {
            return@withLock emptyList()
        }
        changes
    }

    suspend fun applyEventOnce(
        assistantId: String,
        eventId: String,
        eventName: String,
        sourceType: String = "chat",
        sourceId: String = "",
    ): List<String> = getLock(assistantId).withLock {
        val nowMs = System.currentTimeMillis()
        val receipt = DesireEventReceiptEntity(
            eventId = eventId,
            assistantId = assistantId,
            eventName = eventName,
            sourceType = sourceType,
            sourceId = sourceId,
            createdAt = nowMs,
            processedAt = nowMs,
        )

        val state = advanceStateUnlocked(assistantId, nowMs)
        val changes = DesireEventEngine.applyEvent(state, eventName)
        val currentEntity = desireDao.getState(assistantId)
        val stateEntity = toEntity(assistantId, state, nowMs, explicitNextTickAt = currentEntity?.nextTickAt)

        // 记录日志
        val logEntity = DesireTickLogEntity(
            assistantId = assistantId,
            createdAt = nowMs,
            tickNumber = state.tickCount,
            eventName = eventName,
            driveChangesJson = json.encodeToString(changes),
            thoughtChangesJson = "[]",
            behaviorHintsJson = "[]",
            monologue = "",
            warningsJson = "[]",
        )

        val committed = desireDao.saveEventWithReceipt(
            receipt = receipt,
            state = stateEntity,
            log = logEntity,
            keepLogs = 500,
        )
        if (!committed) {
            // 已存在，幂等忽略
            return@withLock emptyList()
        }
        changes
    }

    suspend fun runTickOnce(
        assistantId: String,
        tickEventId: String,
        isMasterPresent: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        customThoughtTemplates: Map<String, List<String>>? = null,
    ): TickResult? = getLock(assistantId).withLock {
        val receipt = DesireEventReceiptEntity(
            eventId = tickEventId,
            assistantId = assistantId,
            eventName = "tick",
            sourceType = "scheduler",
            sourceId = tickEventId,
            createdAt = nowMs,
            processedAt = nowMs,
        )

        val state = getOrCreateStateUnlocked(assistantId)
        val currentIso = nowIso()

        // 1. tick 核心 8 步
        val tickResult = DesireTickEngine.tick(state, isMasterPresent = isMasterPresent, nowIso = currentIso)

        // 2. 念头演化
        DesireThoughtEngine.decayThoughts(state, nowMs) {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(nowMs)
        }
        val newThought = DesireThoughtEngine.maybeSpawnThought(
            state,
            nowIso = currentIso,
            customTemplates = customThoughtTemplates,
        )
        if (newThought != null) {
            state.thoughts.add(newThought)
        }
        val sampledThought = DesireThoughtEngine.sampleAndUpdate(state, nowIso = currentIso)

        // 3. 安全阀
        val warnings = DesireSafety.safetyCheck(state)

        // 4. 保存状态与日志
        val stateEntity = toEntity(assistantId, state, nowMs, nextIntervalSeconds = tickResult.nextInterval)

        val thoughtChanges = mutableListOf<String>()
        if (newThought != null) thoughtChanges.add("new: ${newThought.content}")
        if (sampledThought != null) thoughtChanges.add("sampled: ${sampledThought.content} (hits: ${sampledThought.hitCount})")

        val logEntity = DesireTickLogEntity(
            assistantId = assistantId,
            createdAt = nowMs,
            tickNumber = state.tickCount,
            eventName = null,
            driveChangesJson = json.encodeToString(tickResult.changes),
            thoughtChangesJson = json.encodeToString(thoughtChanges),
            behaviorHintsJson = json.encodeToString(tickResult.actionHints),
            monologue = "",
            warningsJson = json.encodeToString(warnings),
        )

        val committed = desireDao.saveTickWithReceipt(
            receipt = receipt,
            state = stateEntity,
            log = logEntity,
            keepLogs = 500,
        )
        if (!committed) {
            // 已有相同时间槽的心跳在执行或已执行，去重
            return@withLock null
        }

        tickResult
    }

    suspend fun checkShouldSendActiveMessage(
        assistantId: String,
        absentHours: Double,
        nowMs: Long = System.currentTimeMillis(),
        assistant: Assistant? = null,
    ): ActiveSendDecision = getLock(assistantId).withLock {
        val state = advanceStateUnlocked(assistantId, nowMs, absentHours)
        val drivesSnapshot = state.drives.mapValues { it.value.value }

        val lastSend = desireDao.getLastActiveSend(assistantId)
        val lastSentEpochMs = lastSend?.sentAt

        // 检查当前失联阶段是否已成功主动发送过
        val lastUserReceipt = desireDao.getLastUserMasterMessageReceipt(assistantId)
        val absenceStartEpoch = lastUserReceipt?.createdAt ?: 0L
        val hasSentInCurrentAbsence = desireDao.hasActiveSendSince(assistantId, absenceStartEpoch)

        val passCooldownEnd = getPassCooldownEnd(assistantId)

        // 计算今日起始（设备本地时区）
        val todayStartMs = ZonedDateTime.now(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val countToday = desireDao.countActiveSendsBetween(assistantId, todayStartMs, nowMs + 1000L)

        DesireActiveSendPolicy.shouldSend(
            drivesSnapshot = drivesSnapshot,
            absentHours = absentHours,
            lastSentEpochMs = lastSentEpochMs,
            countToday = countToday,
            nowEpochMs = nowMs,
            assistant = assistant,
            hasSentInCurrentAbsence = hasSentInCurrentAbsence,
            passCooldownEndEpochMs = passCooldownEnd,
        )
    }

    private suspend fun applyActiveSendSatisfactionUnlocked(
        assistantId: String,
        reason: String,
        nowMs: Long,
    ): List<String> {
        val state = advanceStateUnlocked(assistantId, nowMs)
        val changes = mutableListOf<String>()

        fun reduceDrive(driveKey: String, delta: Double) {
            val drive = state.drives[driveKey] ?: return
            val old = drive.value
            drive.value = (drive.value - delta).coerceIn(drive.floor, drive.ceiling)
            changes.add(String.format(Locale.ROOT, "%s: %.0f → %.0f (satisfied)", driveKey, old, drive.value))
        }

        when (reason) {
            "attachment", "miss" -> reduceDrive("attachment", 8.0)
            "duty" -> reduceDrive("duty", 15.0)
            "social" -> reduceDrive("social", 8.0)
            "curiosity" -> reduceDrive("curiosity", 6.0)
            "reflection" -> reduceDrive("reflection", 6.0)
            "joy" -> reduceDrive("joy", 10.0)
            "intimacy" -> reduceDrive("intimacy", 5.0)
            "fatigue" -> {
                reduceDrive("fatigue", 3.0)
                reduceDrive("stress", 3.0)
            }
            "stress" -> {
                reduceDrive("stress", 3.0)
                reduceDrive("fatigue", 3.0)
            }
            "down" -> {
                reduceDrive("stress", 3.0)
                reduceDrive("fatigue", 3.0)
            }
            "long_absent" -> reduceDrive("attachment", 5.0)
            else -> {}
        }

        val currentEntity = desireDao.getState(assistantId)
        val stateEntity = toEntity(assistantId, state, nowMs, explicitNextTickAt = currentEntity?.nextTickAt)
        desireDao.upsertState(stateEntity)
        return changes
    }

    suspend fun applyActiveSendSatisfaction(
        assistantId: String,
        reason: String,
        nowMs: Long = System.currentTimeMillis(),
    ): List<String> = getLock(assistantId).withLock {
        applyActiveSendSatisfactionUnlocked(assistantId, reason, nowMs)
    }

    suspend fun recordSuccessfulActiveSend(
        assistantId: String,
        reason: String,
        content: String,
        conversationId: String,
        messageId: String,
        nowMs: Long = System.currentTimeMillis(),
    ) = getLock(assistantId).withLock {
        val state = advanceStateUnlocked(assistantId, nowMs)
        val drivesSnapshot = state.drives.mapValues { it.value.value }
        desireDao.insertActiveSend(
            DesireActiveSendEntity(
                assistantId = assistantId,
                sentAt = nowMs,
                reason = reason,
                content = content,
                drivesSnapshotJson = json.encodeToString(drivesSnapshot),
                conversationId = conversationId,
                messageId = messageId,
            )
        )
        // 成功持久化后立即应用满足效果
        applyActiveSendSatisfactionUnlocked(assistantId, reason, nowMs)
    }

    suspend fun resolveThought(assistantId: String, keyword: String): Thought? = getLock(assistantId).withLock {
        val state = getOrCreateStateUnlocked(assistantId)
        val resolved = DesireThoughtEngine.resolveThought(state, keyword)
        if (resolved != null) {
            desireDao.upsertState(toEntity(assistantId, state, System.currentTimeMillis()))
        }
        resolved
    }

    suspend fun resetState(assistantId: String) = getLock(assistantId).withLock {
        desireDao.deleteAllForAssistant(assistantId)
        getOrCreateStateUnlocked(assistantId)
    }

    suspend fun isReceiptExists(eventId: String): Boolean {
        return desireDao.hasReceipt(eventId)
    }

    suspend fun insertReceipt(receipt: DesireEventReceiptEntity): Long {
        return desireDao.insertReceipt(receipt)
    }

    suspend fun getStateEntity(assistantId: String): DesireStateEntity? {
        return desireDao.getState(assistantId)
    }

    suspend fun buildDesireStatePrompt(assistantId: String): String {
        val state = getOrCreateState(assistantId)
        val driveDescriptions = mapOf(
            "attachment" to Pair("依恋感明显升高，格外想黏着对方", "略有些独立与抽离"),
            "curiosity" to Pair("好奇心旺盛，总想多探寻或了解一些", "提不起太多好奇心"),
            "reflection" to Pair("陷入深思与自我反省，渴望梳理思绪", "思维更关注当下外在，少有内在反思"),
            "duty" to Pair("责任感与使命感强烈，急切想要履行任务", "对事务较为随意放松，无紧迫感"),
            "social" to Pair("社交欲望高涨，渴望与外界交流互动", "倾向于独处或不愿过多社交打扰"),
            "fatigue" to Pair("感到有些疲惫乏力，想要休息放空", "精力格外充沛，神采奕奕"),
            "intimacy" to Pair("亲密渴望强烈，寻求更深层次的心灵或身体亲近", "保持克制距离感，对亲近有所回避"),
            "stress" to Pair("感受到一定心理压力，内在稍显紧绷", "心态轻松自在毫无负担"),
            "joy" to Pair("心情愉悦雀跃，忍不住想分享快乐", "情绪有些低落消沉"),
        )

        // 筛选明显偏离基线的 1-3 项 (偏差绝对值 >= 8.0)
        val deviatedDrives = state.drives.entries
            .map { (key, drive) ->
                val diff = drive.value - drive.baseline
                Triple(key, diff, kotlin.math.abs(diff))
            }
            .filter { it.third >= 8.0 }
            .sortedByDescending { it.third }
            .take(3)
            .mapNotNull { (key, diff, _) ->
                val pair = driveDescriptions[key] ?: return@mapNotNull null
                if (diff > 0) pair.first else pair.second
            }

        // 最显著的一条念头或执念
        val prominentThought = state.thoughts
            .filter { !it.resolved }
            .sortedWith(
                compareByDescending<Thought> { it.isObsession }
                    .thenByDescending { it.hitCount }
                    .thenByDescending { it.lastHit }
            )
            .firstOrNull()

        // 行为倾向提示
        val actionHints = state.drives.entries
            .filter { it.value.value >= it.value.actionThreshold }
            .map { DesireTickEngine.getActionHint(it.key, it.value.value, state) }
        val primaryHint = actionHints.firstOrNull()?.reason

        if (deviatedDrives.isEmpty() && prominentThought == null && primaryHint == null) {
            return ""
        }

        return buildString {
            appendLine("## 心理与情感倾向 (Desire State)")
            if (deviatedDrives.isNotEmpty()) {
                appendLine("- 当前内在状态：${deviatedDrives.joinToString("；")}")
            }
            if (prominentThought != null) {
                val prefix = if (prominentThought.isObsession) "心中盘旋不去的执念" else "内心的念头"
                appendLine("- $prefix：${prominentThought.content}")
            }
            if (primaryHint != null) {
                appendLine("- 行为倾向：$primaryHint")
            }
            appendLine("- 提示：将上述情感倾向自然融入说话语气与细节反应中，不要直接复述设定或暴露心理数值。")
        }.trim()
    }

    suspend fun batchBaselineRegression(assistantId: String, elapsedMs: Long): Unit = getLock(assistantId).withLock {
        val state = getOrCreateStateUnlocked(assistantId)
        val days = (elapsedMs.toDouble() / (24.0 * 3600.0 * 1000.0)).coerceAtLeast(1.0)
        state.drives.values.forEach { drive ->
            val diff = drive.baseline - drive.value
            drive.value = (drive.value + diff * (1.0 - kotlin.math.exp(-0.2 * days))).coerceIn(0.0, 100.0)
        }
        val nowMs = System.currentTimeMillis()
        state.thoughts.removeAll { thought ->
            val lastHitEpoch = runCatching { java.time.Instant.parse(thought.lastHit).toEpochMilli() }
                .getOrElse { runCatching { java.time.Instant.parse(thought.createdAt).toEpochMilli() }.getOrDefault(0L) }
            lastHitEpoch > 0 && (nowMs - lastHitEpoch > 72 * 3600 * 1000L)
        }
        desireDao.upsertState(toEntity(assistantId, state, nowMs - (24 * 3600 * 1000L)))
    }

    suspend fun listRecentLogs(assistantId: String, limit: Int = 100): List<DesireTickLogEntity> {
        return desireDao.listRecentLogs(assistantId, limit)
    }


    private fun toEntity(
        assistantId: String,
        state: DesireState,
        nowMs: Long,
        nextIntervalSeconds: Int? = null,
        explicitNextTickAt: Long? = null,
    ): DesireStateEntity {
        val targetNextTick = explicitNextTickAt
            ?: nextIntervalSeconds?.let { nowMs + it * 1000L }
            ?: (nowMs + 1800 * 1000L)

        return DesireStateEntity(
            assistantId = assistantId,
            drivesJson = json.encodeToString(state.drives),
            thoughtsJson = json.encodeToString(state.thoughts),
            lastTickAt = nowMs,
            tickCount = state.tickCount,
            nextTickAt = targetNextTick,
            schemaVersion = 1,
            updatedAt = nowMs,
        )
    }

    private fun fromEntity(entity: DesireStateEntity): DesireState {
        val drives = runCatching {
            json.decodeFromString<Map<String, Drive>>(entity.drivesJson)
        }.getOrDefault(createDefaultDrives()).toMutableMap()

        val thoughts = runCatching {
            json.decodeFromString<List<Thought>>(entity.thoughtsJson)
        }.getOrDefault(emptyList()).toMutableList()

        return DesireState(
            drives = drives,
            thoughts = thoughts,
            lastTick = Instant.ofEpochMilli(entity.lastTickAt).toString(),
            tickCount = entity.tickCount,
        )
    }
}
