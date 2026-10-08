/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.loader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.service.MemoryBankService
import me.rerere.rikkahub.data.service.AssistantWakeCoordinator
import me.rerere.rikkahub.data.service.AssistantWakeRequest
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.model.PluginInfo
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import kotlin.uuid.Uuid



data class PluginPromptInjection(
    val pluginId: String,
    val text: String,
    val priority: Int = 0,
)

data class PluginScheduledHook(
    val pluginId: String,
    val handler: String,
    val schedule: String,
)

/**
 * 插件加载器。
 * 在原有 tool/hook 能力之上补充生命周期、统一事件、动态提示词、定时任务和宿主动作。
 */
class PluginLoader(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val memoryBankService: MemoryBankService? = null,
    private val settingsStore: SettingsStore? = null
) {
    companion object {
        private const val TAG = "PluginLoader"
        private const val HOOK_TIMEOUT_MS = 16_500L
        private const val DEFAULT_DAILY_CRON = "0 3 * * *"
        private const val MIN_NOTIFICATION_INTERVAL_MS = 2_000L
        private const val MAX_QUEUED_NOTIFICATIONS_PER_PLUGIN = 10
        private const val MAX_HOST_PROMPT_LENGTH = 8_000
        private const val MAX_NOTIFICATION_TITLE_LENGTH = 120
        private const val MAX_NOTIFICATION_TEXT_LENGTH = 2_000

        const val PERMISSION_PROMPT_INJECT = "prompt_inject"
        const val PERMISSION_AI_CHAT = "ai_chat"
        const val PERMISSION_AI_TOOLS = "ai_tools"
        const val PERMISSION_NOTIFICATION = "notification"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val pluginDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "plugin-quickjs").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val hostActionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val loadedPlugins = mutableMapOf<String, LoadedPlugin>()
    val notificationScheduler = PluginNotificationScheduler(
        minIntervalMs = MIN_NOTIFICATION_INTERVAL_MS,
        maxQueueSize = MAX_QUEUED_NOTIFICATIONS_PER_PLUGIN,
    )

    suspend fun loadPlugin(pluginInfo: PluginInfo): Result<LoadedPlugin> = withContext(pluginDispatcher) {
        try {
            if (loadedPlugins.containsKey(pluginInfo.manifest.id)) {
                doUnloadPlugin(pluginInfo.manifest.id)
            }

            if (!pluginInfo.isEnabled) {
                return@withContext Result.failure(IllegalStateException("Plugin is disabled"))
            }

            val entryFile = pluginInfo.getEntryFile()
            if (!entryFile.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("Entry file not found: ${pluginInfo.manifest.entry}")
                )
            }

            val callbackDispatcher = Executors.newSingleThreadExecutor { r ->
                Thread(r, "plugin-quickjs-${pluginInfo.manifest.id}").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            val loadedPlugin = withContext(callbackDispatcher) {
                val dataStore = PluginDataStore(context, pluginInfo.manifest.id)
                val sandbox = PluginSandbox(context, okHttpClient, memoryBankService, dataStore, settingsStore)
                sandbox.allowedHosts = pluginInfo.manifest.allowedHosts
                val lp = LoadedPlugin(info = pluginInfo, sandbox = sandbox, dispatcher = callbackDispatcher)
                sandbox.onHostAction = { actionJson ->
                    try {
                        val element = json.parseToJsonElement(actionJson)
                        val actions = when (element) {
                            is JsonObject -> listOf(element)
                            is JsonArray -> element.filterIsInstance<JsonObject>()
                            else -> emptyList()
                        }

                        for (action in actions) {
                            val isNotification = (action["hostAction"] as? JsonPrimitive)?.contentOrNull == "notification.show"
                            val reserved = if (isNotification) notificationScheduler.preReserve(lp.id) else false
                            if (isNotification && !reserved) {
                                Log.w(TAG, "Notification preReserve rejected (queue full) for plugin=${lp.id}")
                                continue
                            }
                            lp.pluginScope.launch {
                                try {
                                    handleHostAction(lp, action, alreadyReserved = reserved)
                                } catch (t: Throwable) {
                                    if (reserved) notificationScheduler.cancelReservation(lp.id)
                                    throw t
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse host action from JS: $actionJson", e)
                    }
                }
                sandbox.initialize()
                sandbox.injectConfig(resolveModelConfig(pluginInfo))
                sandbox.evaluateFile(entryFile)
                lp
            }
            val exportedNames = withContext(callbackDispatcher) { loadedPlugin.sandbox.getExportedFunctionNames() }
            Log.i(TAG, "Plugin ${pluginInfo.manifest.id} exported functions: $exportedNames")

            pluginInfo.manifest.tools.forEach { tool ->
                if (!loadedPlugin.sandbox.hasFunction(tool.name)) {
                    Log.w(TAG, "Tool '${tool.name}' declared in manifest but not found in exports (available: $exportedNames)")
                } else {
                    Log.i(TAG, "Tool '${tool.name}' registered successfully")
                }
            }

            loadedPlugins[pluginInfo.manifest.id] = loadedPlugin

            val loadContext = buildLifecycleContext(loadedPlugin, "plugin.loaded")
            withContext(callbackDispatcher) {
                invokeOptionalFunction(loadedPlugin, "onLoad", loadContext)
                dispatchGenericEvent(loadedPlugin, "plugin.loaded", loadContext)
            }

            val enableContext = buildLifecycleContext(loadedPlugin, "plugin.enabled")
            withContext(callbackDispatcher) {
                invokeOptionalFunction(loadedPlugin, "onEnable", enableContext)
                dispatchGenericEvent(loadedPlugin, "plugin.enabled", enableContext)
            }

            Result.success(loadedPlugin)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin ${pluginInfo.manifest.id}", e)
            loadedPlugins.remove(pluginInfo.manifest.id)?.let { plugin ->
                withContext(plugin.dispatcher) { plugin.sandbox.destroy() }
                plugin.dispatcher.close()
            }
            Result.failure(e)
        }
    }

    private suspend fun doUnloadPlugin(pluginId: String) {
        val plugin = loadedPlugins[pluginId] ?: return

        withContext(plugin.dispatcher) {
            val disableContext = buildLifecycleContext(plugin, "plugin.disabled")
            invokeOptionalFunction(plugin, "onDisable", disableContext)
            dispatchGenericEvent(plugin, "plugin.disabled", disableContext)
            val unloadContext = buildLifecycleContext(plugin, "plugin.unloaded")
            invokeOptionalFunction(plugin, "onUnload", unloadContext)
            dispatchGenericEvent(plugin, "plugin.unloaded", unloadContext)
            plugin.sandbox.destroy()
        }

        loadedPlugins.remove(pluginId)
        plugin.pluginScope.cancel()
        notificationScheduler.clearPlugin(pluginId)
        plugin.dispatcher.close()
        Log.d(TAG, "Unloaded plugin: $pluginId")
    }

    suspend fun unloadPlugin(pluginId: String) = withContext(pluginDispatcher) {
        doUnloadPlugin(pluginId)
    }

    suspend fun reloadPlugin(pluginInfo: PluginInfo): Result<LoadedPlugin> = loadPlugin(pluginInfo)

    fun getLoadedPlugin(pluginId: String): LoadedPlugin? = loadedPlugins[pluginId]
    fun getAllLoadedPlugins(): List<LoadedPlugin> = loadedPlugins.values.toList()
    fun getEnabledPlugins(): List<LoadedPlugin> = loadedPlugins.values.filter { it.info.isEnabled }

    suspend fun callTool(pluginId: String, toolName: String, params: JsonElement): Result<JsonElement> {
        return withContext(pluginDispatcher) {
            try {
                val plugin = loadedPlugins[pluginId]
                    ?: return@withContext Result.failure(IllegalStateException("Plugin not loaded: $pluginId"))

                if (!plugin.hasTool(toolName)) {
                    return@withContext Result.failure(IllegalArgumentException("Tool not found: $toolName"))
                }

                withContext(plugin.dispatcher) { Result.success(plugin.sandbox.callFunction(toolName, params)) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call tool=$toolName in plugin=$pluginId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 触发插件事件。
     * 旧 manifest.hooks 继续生效；新版插件可只导出 onEvent(event) 统一监听。
     */
    suspend fun callEvent(event: String, params: JsonElement) {
        val plugins = withContext(pluginDispatcher) { loadedPlugins.values.toList() }
        coroutineScope {
            plugins.map { plugin -> async(plugin.dispatcher) {
                if (!plugin.info.isEnabled) return@async

                val matchingHooks = plugin.info.manifest.hooks.filter { it.event == event }
                for (hook in matchingHooks) {
                    invokeOptionalFunction(
                        plugin = plugin,
                        functionName = hook.handler,
                        params = params,
                        logLabel = "event='$event'"
                    )
                }

                if (matchingHooks.none { it.handler == "onEvent" }) {
                    val canonicalType = canonicalEventName(event)
                    val envelope = buildJsonObject {
                        put("type", canonicalType)
                        put("legacyType", event)
                        put("timestamp", System.currentTimeMillis())
                        put("pluginId", plugin.id)
                        put("data", params)
                    }
                    dispatchGenericEvent(plugin, canonicalType, envelope)
                }
            } }.awaitAll()
        }
    }

    /**
     * 只触发一个已经声明的定时 hook。
     * 通用 Cron 调度器使用该入口，避免某个插件到点时把全部 daily_cron 插件一起唤醒。
     */
    suspend fun callScheduledHook(pluginId: String, handler: String, params: JsonElement) {
        withContext(pluginDispatcher) {
            val plugin = loadedPlugins[pluginId] ?: return@withContext
            if (!plugin.info.isEnabled) return@withContext
            withContext(plugin.dispatcher) {
                val declared = plugin.info.manifest.hooks.any {
                    it.event == "daily_cron" && it.handler == handler
                }
                if (!declared) {
                    Log.w(TAG, "Ignoring undeclared scheduled hook: plugin=$pluginId, handler=$handler")
                    return@withContext
                }
                invokeOptionalFunction(plugin, handler, params, "scheduled daily_cron")
                if (handler != "onEvent" && plugin.sandbox.hasFunction("onEvent")) {
                    val envelope = buildJsonObject {
                        put("type", "scheduler.daily_cron")
                        put("legacyType", "daily_cron")
                        put("timestamp", System.currentTimeMillis())
                        put("pluginId", plugin.id)
                        put("data", params)
                    }
                    dispatchGenericEvent(plugin, "scheduler.daily_cron", envelope)
                }
            }
        }
    }

    /**
     * 每次上游模型请求前重新调用 providePrompt(ctx)。
     * 需要 manifest.permissions 包含 prompt_inject。
     */
    suspend fun getDynamicPromptInjections(): List<PluginPromptInjection> {
        val plugins = withContext(pluginDispatcher) { loadedPlugins.values.toList() }
        return coroutineScope {
            plugins.map { plugin -> async(plugin.dispatcher) {
                if (!plugin.info.isEnabled ||
                    PERMISSION_PROMPT_INJECT !in plugin.info.manifest.permissions ||
                    !plugin.sandbox.hasFunction("providePrompt")
                ) {
                    return@async null
                }
                val promptContext = buildJsonObject {
                    put("pluginId", plugin.id)
                    put("pluginName", plugin.name)
                    put("timestamp", System.currentTimeMillis())
                }
                val result = withTimeoutOrNull(HOOK_TIMEOUT_MS) {
                    plugin.sandbox.callFunction("providePrompt", promptContext)
                }
                if (result == null) {
                    Log.w(TAG, "Dynamic prompt timed out: plugin=${plugin.id}")
                    null
                } else {
                    parsePromptInjection(plugin.id, result)
                }
            } }.awaitAll().filterNotNull().sortedByDescending { it.priority }
        }
    }

    fun getScheduledHooks(): List<PluginScheduledHook> {
        return loadedPlugins.values
            .filter { it.info.isEnabled }
            .flatMap { plugin ->
                plugin.info.manifest.hooks
                    .filter { it.event == "daily_cron" }
                    .map { hook ->
                        PluginScheduledHook(
                            pluginId = plugin.id,
                            handler = hook.handler,
                            schedule = hook.schedule?.takeIf { it.isNotBlank() } ?: DEFAULT_DAILY_CRON,
                        )
                    }
            }
    }

    fun getPluginsWithDailyCron(): List<Pair<LoadedPlugin, String>> {
        return loadedPlugins.values.filter { it.info.isEnabled }.flatMap { plugin ->
            plugin.info.manifest.hooks
                .filter { it.event == "daily_cron" }
                .map { hook -> plugin to hook.handler }
        }
    }

    /**
     * 执行可选插件回调，并解释其返回的宿主动作。
     *
     * 支持：
     * { "hostAction": "ai.wake", "prompt": "...", "assistantId": "...", "allowTools": false }
     * { "hostAction": "notification.show", "title": "...", "text": "..." }
     * 也支持返回上述对象的数组。
     */
    private suspend fun invokeOptionalFunction(
        plugin: LoadedPlugin,
        functionName: String,
        params: JsonElement,
        logLabel: String = functionName,
    ): JsonElement? {
        if (!plugin.sandbox.hasFunction(functionName)) return null

        return try {
            val completed = withTimeoutOrNull(HOOK_TIMEOUT_MS) {
                plugin.sandbox.callFunction(functionName, params)
            }
            if (completed == null) {
                Log.w(TAG, "Plugin callback timed out: plugin=${plugin.id}, function='$functionName', $logLabel")
                null
            } else {
                Log.d(TAG, "Plugin callback completed: plugin=${plugin.id}, function=$functionName, $logLabel")
                handleHostActionResult(plugin, completed)
                completed
            }
        } catch (e: Exception) {
            Log.e(TAG, "Plugin callback failed: plugin=${plugin.id}, function=$functionName, $logLabel", e)
            null
        }
    }

    private suspend fun dispatchGenericEvent(
        plugin: LoadedPlugin,
        eventType: String,
        data: JsonElement,
    ) {
        if (!plugin.sandbox.hasFunction("onEvent")) return

        val envelope = if (data is JsonObject && data.containsKey("type")) {
            data
        } else {
            buildJsonObject {
                put("type", eventType)
                put("timestamp", System.currentTimeMillis())
                put("pluginId", plugin.id)
                put("data", data)
            }
        }
        invokeOptionalFunction(plugin, "onEvent", envelope, "type='$eventType'")
    }

    /**
     * Host Action Bridge：事件函数通过返回结构化对象请求宿主执行 App 能力。
     * 这样无需把 Android 对象直接暴露进 QuickJS，同时所有敏感动作都经过 manifest 权限检查。
     */
    private suspend fun handleHostActionResult(
        plugin: LoadedPlugin,
        result: JsonElement,
        alreadyReserved: Boolean = false,
    ) {
        when (result) {
            is JsonObject -> {
                val isNotification = (result["hostAction"] as? JsonPrimitive)?.contentOrNull == "notification.show"
                if (!isNotification && alreadyReserved) {
                    notificationScheduler.cancelReservation(plugin.id)
                    handleHostAction(plugin, result, alreadyReserved = false)
                } else {
                    handleHostAction(plugin, result, alreadyReserved = alreadyReserved)
                }
            }
            is JsonArray -> {
                if (alreadyReserved) {
                    notificationScheduler.cancelReservation(plugin.id)
                }
                for (item in result) {
                    if (item is JsonObject) {
                        handleHostAction(plugin, item, alreadyReserved = false)
                    }
                }
            }
            else -> {
                if (alreadyReserved) {
                    notificationScheduler.cancelReservation(plugin.id)
                }
            }
        }
    }

    private suspend fun handleHostAction(
        plugin: LoadedPlugin,
        action: JsonObject,
        alreadyReserved: Boolean = false,
    ) {
        val actionName = (action["hostAction"] as? JsonPrimitive)?.contentOrNull ?: return

        when (actionName) {
            "ai.wake" -> {
                if (alreadyReserved) notificationScheduler.cancelReservation(plugin.id)
                if (PERMISSION_AI_CHAT !in plugin.info.manifest.permissions) {
                    Log.w(TAG, "Blocked ai.wake without ai_chat permission: plugin=${plugin.id}")
                    return
                }

                val prompt = (action["prompt"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
                    .take(MAX_HOST_PROMPT_LENGTH)
                if (prompt.isBlank()) {
                    Log.w(TAG, "Ignored ai.wake with empty prompt: plugin=${plugin.id}")
                    return
                }

                val assistantId = (action["assistantId"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
                val requestedTools = (action["allowTools"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.toBooleanStrictOrNull()
                    ?: false
                val allowTools = requestedTools && PERMISSION_AI_TOOLS in plugin.info.manifest.permissions

                val request = AssistantWakeRequest(
                    assistantId = assistantId,
                    source = "plugin",
                    sourceName = plugin.name,
                    prompt = prompt,
                    allowAllToolsAndPlugins = allowTools,
                )
                AssistantWakeCoordinator.submit(context, request).onSuccess {
                    Log.i(TAG, "Plugin AI wake queued: plugin=${plugin.id}, requestId=${request.id}, allowTools=$allowTools")
                    me.rerere.rikkahub.data.service.SchedulerDiagnostics.logAiWake(
                        target = "Plugin:${plugin.id}",
                        status = "queued",
                        details = "requestId=${request.id}, prompt='${prompt.take(60)}...', allowTools=$allowTools",
                    )
                }.onFailure { error ->
                    Log.e(TAG, "Plugin AI wake queue service failed to start: plugin=${plugin.id}", error)
                    me.rerere.rikkahub.data.service.SchedulerDiagnostics.logAiWake(
                        target = "Plugin:${plugin.id}",
                        status = "failed",
                        details = "requestId=${request.id}, queue start error: ${error.message}",
                    )
                }
            }

            "notification.show" -> {
                if (PERMISSION_NOTIFICATION !in plugin.info.manifest.permissions) {
                    Log.w(TAG, "Blocked notification.show without notification permission: plugin=${plugin.id}")
                    if (alreadyReserved) notificationScheduler.cancelReservation(plugin.id)
                    return
                }

                val title = (action["title"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.trim()
                    ?.take(MAX_NOTIFICATION_TITLE_LENGTH)
                val text = (action["text"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.trim()
                    .orEmpty()
                    .take(MAX_NOTIFICATION_TEXT_LENGTH)
                if (text.isBlank()) {
                    Log.w(TAG, "Ignored notification.show with empty text: plugin=${plugin.id}")
                    if (alreadyReserved) notificationScheduler.cancelReservation(plugin.id)
                    return
                }

                val scheduled = notificationScheduler.schedule(
                    pluginId = plugin.id,
                    scope = plugin.pluginScope,
                    alreadyReserved = alreadyReserved,
                ) {
                    val shown = PluginNotificationHost.show(
                        context = context,
                        pluginName = plugin.name,
                        title = title,
                        text = text,
                    )
                    if (shown) {
                        Log.i(TAG, "Plugin notification shown: plugin=${plugin.id}")
                        me.rerere.rikkahub.data.service.SchedulerDiagnostics.logNotification(
                            target = "Plugin:${plugin.id}",
                            shown = true,
                            details = "title='$title', text='${text.take(60)}...'",
                        )
                    } else {
                        Log.w(TAG, "Plugin notification not shown (system notification permission unavailable): plugin=${plugin.id}")
                        me.rerere.rikkahub.data.service.SchedulerDiagnostics.logNotification(
                            target = "Plugin:${plugin.id}",
                            shown = false,
                            details = "System notification permission unavailable",
                        )
                    }
                    shown
                }
                if (!scheduled.accepted) {
                    me.rerere.rikkahub.data.service.SchedulerDiagnostics.logNotification(
                        target = "Plugin:${plugin.id}",
                        shown = false,
                        details = "Queue full (pending=${scheduled.pendingCount}), dropped notification",
                    )
                }
            }

            else -> {
                if (alreadyReserved) notificationScheduler.cancelReservation(plugin.id)
                Log.w(TAG, "Unknown plugin host action '$actionName' from ${plugin.id}")
            }
        }
    }

    suspend fun awaitPendingNotifications(timeoutMs: Long = 25_000L) {
        notificationScheduler.awaitPending(timeoutMs)
    }

    private fun buildLifecycleContext(plugin: LoadedPlugin, eventType: String): JsonObject {
        return buildJsonObject {
            put("type", eventType)
            put("timestamp", System.currentTimeMillis())
            put("pluginId", plugin.id)
            put("pluginName", plugin.name)
            put("version", plugin.info.manifest.version)
        }
    }

    private fun canonicalEventName(event: String): String {
        return when (event) {
            "message_sent" -> "chat.user_message"
            "message_received" -> "chat.assistant_message"
            "daily_cron" -> "scheduler.daily_cron"
            "app_started" -> "app.started"
            "app_foreground" -> "app.foreground"
            "app_background" -> "app.background"
            else -> event.replace('_', '.')
        }
    }

    private fun parsePromptInjection(pluginId: String, result: JsonElement): PluginPromptInjection? {
        return when (result) {
            is JsonPrimitive -> {
                val text = result.contentOrNull?.trim().orEmpty()
                text.takeIf { it.isNotEmpty() }?.let {
                    PluginPromptInjection(pluginId = pluginId, text = it)
                }
            }
            is JsonObject -> {
                val text = (result["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (text.isEmpty()) return null
                val priority = (result["priority"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.toIntOrNull()
                    ?: 0
                PluginPromptInjection(pluginId = pluginId, text = text, priority = priority)
            }
            else -> null
        }
    }

    private fun resolveModelConfig(pluginInfo: PluginInfo): Map<String, JsonElement> {
        val config = pluginInfo.config.toMutableMap()
        val store = settingsStore ?: return config
        val settings = store.settingsFlow.value

        pluginInfo.manifest.config.forEach { field ->
            if (field.type == "model") {
                val modelUuidStr = (config[field.name] as? JsonPrimitive)?.contentOrNull
                if (modelUuidStr.isNullOrBlank()) return@forEach
                try {
                    val modelUuid = Uuid.parse(modelUuidStr)
                    val model = settings.findModelById(modelUuid) ?: return@forEach
                    val provider = model.findProvider(settings.providers) ?: return@forEach

                    val baseUrl = when (provider) {
                        is ProviderSetting.OpenAI -> provider.baseUrl
                        is ProviderSetting.Google -> provider.baseUrl
                        is ProviderSetting.Claude -> provider.baseUrl
                    }
                    val apiKey = when (provider) {
                        is ProviderSetting.OpenAI -> provider.apiKey
                        is ProviderSetting.Google -> provider.apiKey
                        is ProviderSetting.Claude -> provider.apiKey
                    }

                    config[field.name] = JsonPrimitive(model.modelId)
                    config["${field.name}_base_url"] = JsonPrimitive(baseUrl)
                    config["${field.name}_api_key"] = JsonPrimitive(apiKey)
                    Log.d(TAG, "Resolved model config '${field.name}': modelId=${model.modelId}, baseUrl=$baseUrl")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to resolve model config '${field.name}': ${e.message}")
                }
            }
        }
        return config
    }

    suspend fun unloadAll() = withContext(pluginDispatcher) {
        loadedPlugins.keys.toList().forEach { doUnloadPlugin(it) }
    }
}
