/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin.provider

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.first
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.ai.tools.ToolNaming
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import kotlin.uuid.Uuid
import me.rerere.rikkahub.plugin.loader.LoadedPlugin
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.model.PluginToolDefinition
import me.rerere.rikkahub.ui.pages.share.handler.SharedPostImageDownloader

/**
 * 插件工具提供者。
 * 将插件工具和插件提供的上下文接入模型请求。
 */
class PluginToolProvider(
    private val pluginLoader: PluginLoader,
    private val pluginManager: PluginManager,
    private val imageDownloader: SharedPostImageDownloader,
    private val filesManager: FilesManager,
    private val settingsStore: SettingsStore,
    private val appContext: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 获取所有插件提供的工具。
     * 会等待插件初始化完成，确保竞态条件下不会返回空列表。
     */
    suspend fun getTools(context: ToolInvocationContext = ToolInvocationContext.EMPTY): List<Tool> {
        pluginManager.awaitInitialization()
        return pluginLoader.getAllLoadedPlugins().flatMap { plugin ->
            plugin.info.manifest.tools.map { toolDef ->
                createTool(plugin, toolDef, context)
            }
        }
    }

    /**
     * 获取指定插件的工具。
     */
    suspend fun getPluginTools(
        pluginId: String,
        context: ToolInvocationContext = ToolInvocationContext.EMPTY,
    ): List<Tool> {
        pluginManager.awaitInitialization()
        val plugin = pluginLoader.getLoadedPlugin(pluginId) ?: return emptyList()
        return plugin.info.manifest.tools.map { toolDef ->
            createTool(plugin, toolDef, context)
        }
    }

    /**
     * 创建 Tool 对象。
     */
    private fun createTool(
        plugin: LoadedPlugin,
        toolDef: PluginToolDefinition,
        context: ToolInvocationContext = ToolInvocationContext.EMPTY,
    ): Tool {
        return Tool(
            name = ToolNaming.buildPluginToolName(plugin.id, toolDef.name),
            description = buildDescription(plugin, toolDef),
            needsApproval = true,
            parameters = {
                InputSchema.Obj(
                    properties = buildParameters(toolDef),
                    required = toolDef.parameters.filter { it.required }.map { it.name }
                )
            },
            execute = { params ->
                executeTool(plugin, toolDef, params, context)
            }
        )
    }

    private fun buildDescription(plugin: LoadedPlugin, toolDef: PluginToolDefinition): String {
        val sb = StringBuilder()
        sb.appendLine(toolDef.description)
        sb.appendLine()
        sb.appendLine("Provided by plugin: ${plugin.info.manifest.name} (${plugin.info.manifest.id})")
        return sb.toString().trim()
    }

    private fun buildParameters(toolDef: PluginToolDefinition): JsonObject {
        return buildJsonObject {
            toolDef.parameters.forEach { param ->
                put(param.name, buildJsonObject {
                    put("type", param.type)
                    if (param.description != null) {
                        put("description", param.description)
                    }
                    when (param.type) {
                        "array" -> {
                            put("items", buildJsonObject {
                                put("type", "string")
                            })
                        }
                        "object" -> Unit
                    }
                })
            }
        }
    }

    private suspend fun executeTool(
        plugin: LoadedPlugin,
        toolDef: PluginToolDefinition,
        params: JsonElement,
        context: ToolInvocationContext = ToolInvocationContext.EMPTY,
    ): List<UIMessagePart> {
        val result = pluginLoader.callTool(
            pluginId = plugin.id,
            toolName = toolDef.name,
            params = params
        )

        return result.fold(
            onSuccess = { jsonElement ->
                val sanitizedElement = if (plugin.id == MomentsImageAttachments.PLUGIN_ID && toolDef.name == "moments_read") {
                    MomentsImageAttachments.sanitizeResult(jsonElement)
                } else {
                    jsonElement
                }
                val resultStr = json.encodeToString(JsonElement.serializer(), sanitizedElement)
                if (plugin.id != MomentsImageAttachments.PLUGIN_ID || toolDef.name != "moments_read") {
                    listOf(UIMessagePart.Text(resultStr))
                } else {
                    val urls = runCatching {
                        jsonElement.jsonObject["data"]?.jsonObject?.get("images")
                            ?.jsonArray?.map { it.jsonPrimitive.content }
                            .orEmpty()
                    }.getOrDefault(emptyList()).filter(String::isNotBlank)
                    val supportsImages = if (context != ToolInvocationContext.EMPTY) {
                        context.modelCanSeeImages
                    } else {
                        val settings = settingsStore.settingsFlow.first()
                        val assistant = context.callerAssistantId?.let { id ->
                            runCatching { Uuid.parse(id) }.getOrNull()?.let { settings.getAssistantById(it) }
                        } ?: settings.getCurrentAssistant()
                        val model = assistant.chatModelId?.let { settings.findModelById(it) }
                            ?: settings.findModelById(settings.chatModelId)
                        model?.inputModalities?.contains(Modality.IMAGE) == true
                    }
                    val images = if (supportsImages) {
                        MomentsImageAttachments.download(
                            urls = urls,
                            allowedHosts = plugin.info.manifest.allowedHosts,
                            downloader = imageDownloader,
                            filesManager = filesManager,
                            pluginDataStore = PluginDataStore(appContext, plugin.id),
                        )
                    } else {
                        MomentsImageAttachments.DownloadResult(emptyList(), urls.size)
                    }
                    val text = buildString {
                        append(resultStr)
                        if (urls.isNotEmpty()) {
                            append("\n\n")
                            append(MomentsImageAttachments.statusText(urls.size, images, supportsImages))
                        }
                    }
                    listOf(UIMessagePart.Text(text)) + images.images
                }
            },
            onFailure = { error ->
                val errorObj = buildJsonObject {
                    put("success", false)
                    put("error", error.message ?: "Unknown error")
                }
                listOf(UIMessagePart.Text(errorObj.toString()))
            }
        )
    }


    /**
     * 获取插件提示词注入。
     *
     * 兼容三层来源：
     * 1. 已加载插件工具的能力总览；
     * 2. 旧版 manifest.promptTemplate + inject_as_prompt；
     * 3. 新版 exports.providePrompt(ctx) 动态上下文。
     *
     * 能力总览和旧版 promptTemplate 属于稳定提示，继续进入前部 system prompt。
     * providePrompt(ctx) 会在每次模型请求前重新计算，并以 <plugin-context> 标记；
     * GenerationHandler 会把这类动态上下文移到当前用户消息之前，避免频繁变化的插件状态
     * 破坏前部长前缀缓存，同时保持当前用户消息为生成前最后一条对话消息。
     */
    companion object {
        /**
         * 是否允许插件向提示词注入内容。
         * 设置为 false 可彻底杜绝插件动态状态引起的提示词长度波动，确保 Prompt Caching 100% 稳定命中。
         */
        const val ENABLE_PLUGIN_PROMPT_INJECTIONS = false
    }

    suspend fun getPluginPromptInjections(): List<String> {
        if (!ENABLE_PLUGIN_PROMPT_INJECTIONS) {
            return emptyList()
        }

        pluginManager.awaitInitialization()

        val pluginsWithTools = pluginLoader.getAllLoadedPlugins()
            .filter { it.info.manifest.tools.isNotEmpty() }

        val overview = if (pluginsWithTools.isNotEmpty()) {
            val pluginNames = pluginsWithTools.joinToString("、") { it.info.manifest.name }
            "你当前装载了以下插件提供的工具（完整工具列表和参数见 tools 定义）：${pluginNames}。" +
                "不要只在用户明确点名某个工具时才使用——只要对话场景与某个工具的用途相关，就应该主动考虑调用它，而不是被动等待用户指示。" +
                "部分工具绑定的是持续性的人设/系统状态（例如经营、社交、记录类），更需要你自己记得在合适的时机调用，而不是等用户提醒。"
        } else {
            null
        }

        val manualTemplates = pluginLoader.getAllLoadedPlugins().mapNotNull { plugin ->
            val manifest = plugin.info.manifest
            val promptTemplate = manifest.promptTemplate ?: return@mapNotNull null
            val injectConfig = plugin.info.config["inject_as_prompt"]
            val shouldInject = when (injectConfig) {
                is kotlinx.serialization.json.JsonPrimitive -> injectConfig.content == "true"
                else -> false
            }
            if (shouldInject) promptTemplate else null
        }

        val dynamicTemplates = pluginLoader.getDynamicPromptInjections().map { injection ->
            val plugin = pluginLoader.getLoadedPlugin(injection.pluginId)
            val pluginName = plugin?.name ?: injection.pluginId
            buildString {
                appendLine("<plugin-context id=\"${injection.pluginId}\" name=\"$pluginName\">")
                appendLine(injection.text)
                append("</plugin-context>")
            }
        }

        return buildList {
            if (overview != null) add(overview)
            addAll(manualTemplates)
            addAll(dynamicTemplates)
        }
    }

    fun getToolStats(): ToolStats {
        val plugins = pluginLoader.getAllLoadedPlugins()
        val totalTools = plugins.sumOf { it.info.manifest.tools.size }

        return ToolStats(
            totalPlugins = plugins.size,
            totalTools = totalTools,
            pluginDetails = plugins.map { plugin ->
                PluginToolDetail(
                    pluginId = plugin.id,
                    pluginName = plugin.info.manifest.name,
                    toolCount = plugin.info.manifest.tools.size,
                    toolNames = plugin.info.manifest.tools.map { it.name }
                )
            }
        )
    }

    data class ToolStats(
        val totalPlugins: Int,
        val totalTools: Int,
        val pluginDetails: List<PluginToolDetail>
    )

    data class PluginToolDetail(
        val pluginId: String,
        val pluginName: String,
        val toolCount: Int,
        val toolNames: List<String>
    )
}
