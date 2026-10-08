package me.rerere.rikkahub.data.service

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class AssistantWakeRequest(
    val id: String = UUID.randomUUID().toString(),
    val assistantId: String,
    val source: String,
    val sourceName: String,
    val prompt: String,
    val actionOutput: String? = null,
    val allowAllToolsAndPlugins: Boolean = false,
    val desireReason: String? = null,
    val desireEventId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val notBeforeMs: Long = 0L,
)

@Serializable
private data class AssistantWakeQueueState(
    val requests: List<AssistantWakeRequest> = emptyList(),
    val inFlightIds: List<String> = emptyList(),
    val inFlightStartedAt: Long = 0L,
)

@Serializable
private data class LegacyPluginWakeRequest(
    val id: String,
    val pluginId: String,
    val pluginName: String,
    val assistantId: String,
    val prompt: String,
    val allowAllToolsAndPlugins: Boolean,
    val createdAt: Long,
)

internal object AssistantWakeQueue {
    private const val TAG = "AssistantWakeQueue"
    private const val PREFS_NAME = "assistant_wake_queue"
    private const val KEY_STATE = "state"
    private const val LEGACY_PLUGIN_PREFS_NAME = "plugin_ai_wake_queue"
    private const val LEGACY_PLUGIN_KEY_REQUESTS = "requests"
    private const val MAX_BATCH_SIZE = 8
    private const val IN_FLIGHT_TIMEOUT_MS = 5 * 60 * 1000L
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    fun enqueue(context: Context, request: AssistantWakeRequest): AssistantWakeRequest = synchronized(lock) {
        val state = read(context)
        state.requests.firstOrNull { it.id == request.id }?.let { return@synchronized it }
        write(context, state.copy(requests = state.requests + request))
        request
    }

    fun migrateLegacyPluginQueue(context: Context) = synchronized(lock) {
        val legacyPrefs = context.getSharedPreferences(LEGACY_PLUGIN_PREFS_NAME, Context.MODE_PRIVATE)
        val raw = legacyPrefs.getString(LEGACY_PLUGIN_KEY_REQUESTS, null) ?: return@synchronized
        val legacy = runCatching { json.decodeFromString<List<LegacyPluginWakeRequest>>(raw) }
            .onFailure { Log.e(TAG, "Failed to migrate legacy plugin wake queue", it) }
            .getOrNull() ?: return@synchronized
        val state = read(context)
        val existingIds = state.requests.mapTo(mutableSetOf()) { it.id }
        val migrated = legacy.filterNot { it.id in existingIds }.map { request ->
            AssistantWakeRequest(
                id = request.id,
                assistantId = request.assistantId,
                source = "plugin",
                sourceName = request.pluginName,
                prompt = request.prompt,
                allowAllToolsAndPlugins = request.allowAllToolsAndPlugins,
                createdAt = request.createdAt,
            )
        }
        write(context, state.copy(requests = migrated + state.requests))
        legacyPrefs.edit().remove(LEGACY_PLUGIN_KEY_REQUESTS).commit()
    }

    fun claimBatch(context: Context): List<AssistantWakeRequest> = synchronized(lock) {
        val state = read(context)
        if (state.requests.isEmpty()) return@synchronized emptyList()
        val now = System.currentTimeMillis()
        if (state.inFlightIds.isNotEmpty() && now - state.inFlightStartedAt < IN_FLIGHT_TIMEOUT_MS) {
            return@synchronized emptyList()
        }
        val batch = selectBatch(state.requests, now)
        if (batch.isEmpty()) return@synchronized emptyList()
        write(context, state.copy(inFlightIds = batch.map { it.id }, inFlightStartedAt = now))
        batch
    }

    fun complete(context: Context, requestIds: Collection<String>) = synchronized(lock) {
        val ids = requestIds.toSet()
        val state = read(context)
        write(
            context,
            state.copy(
                requests = state.requests.filterNot { it.id in ids },
                inFlightIds = state.inFlightIds.filterNot { it in ids },
                inFlightStartedAt = if (state.inFlightIds.all { it in ids }) 0L else state.inFlightStartedAt,
            ),
        )
    }

    fun release(context: Context, requestIds: Collection<String>) = synchronized(lock) {
        val ids = requestIds.toSet()
        val state = read(context)
        if (state.inFlightIds.any { it in ids }) {
            write(context, state.copy(inFlightIds = emptyList(), inFlightStartedAt = 0L))
        }
    }

    fun defer(context: Context, requestIds: Collection<String>) = synchronized(lock) {
        val ids = requestIds.toSet()
        val state = read(context)
        val now = System.currentTimeMillis()
        val deferred = state.requests.filter { it.id in ids }.map { request ->
            request.copy(
                retryCount = request.retryCount + 1,
                notBeforeMs = now + retryDelayMs(request.retryCount + 1),
            )
        }
        val remaining = state.requests.filterNot { it.id in ids }
        write(
            context,
            state.copy(
                requests = remaining + deferred,
                inFlightIds = state.inFlightIds.filterNot { it in ids },
                inFlightStartedAt = if (state.inFlightIds.all { it in ids }) 0L else state.inFlightStartedAt,
            ),
        )
    }

    fun hasPending(context: Context): Boolean = synchronized(lock) { read(context).requests.isNotEmpty() }

    internal fun selectBatch(
        requests: List<AssistantWakeRequest>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<AssistantWakeRequest> {
        val assistantId = requests.firstOrNull { it.notBeforeMs <= nowMs }?.assistantId ?: return emptyList()
        return requests.filter { it.assistantId == assistantId && it.notBeforeMs <= nowMs }.take(MAX_BATCH_SIZE)
    }

    internal fun buildCombinedPrompt(requests: List<AssistantWakeRequest>): String = buildString {
        appendLine("本次因多个后台事件被唤醒。请综合处理所有请求，组织成一次自然回应，不要逐项机械汇报。")
        if (requests.any { it.source == "workflow" || it.source == "plugin" }) {
            appendLine("只要任一工作流或插件有明确执行要求，就不允许用 [PASS] 跳过整个批次。")
        }
        requests.forEachIndexed { index, request ->
            appendLine()
            appendLine("## 请求 ${index + 1} [${request.source}] ${request.sourceName}")
            appendLine(request.prompt)
            if (!request.actionOutput.isNullOrBlank()) {
                appendLine("动作输出：")
                appendLine(request.actionOutput)
            }
        }
    }

    private fun read(context: Context): AssistantWakeQueueState {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_STATE, null)
            ?: return AssistantWakeQueueState()
        return runCatching { json.decodeFromString<AssistantWakeQueueState>(raw) }
            .onFailure { Log.e(TAG, "Failed to read assistant wake queue", it) }
            .getOrDefault(AssistantWakeQueueState())
    }

    private fun write(context: Context, state: AssistantWakeQueueState) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATE, json.encodeToString(state)).commit()
    }

    private fun retryDelayMs(retryCount: Int): Long = when (retryCount) {
        1 -> 15_000L
        2 -> 60_000L
        else -> 5 * 60_000L
    }
}

internal object AssistantWakeCoordinator {
    fun submit(context: Context, request: AssistantWakeRequest): Result<AssistantWakeRequest> {
        val queued = runCatching {
            AssistantWakeQueue.migrateLegacyPluginQueue(context)
            AssistantWakeQueue.enqueue(context, request)
        }
            .getOrElse { return Result.failure(it) }
        runCatching { AssistantWakeQueueRecoveryWorker.schedule(context) }
            .onFailure { Log.w("AssistantWakeCoordinator", "Failed to schedule wake recovery", it) }
        runCatching { AssistantWakeQueueService.start(context) }
            .onFailure { Log.w("AssistantWakeCoordinator", "Wake persisted; immediate start failed", it) }
        return Result.success(queued)
    }
}
