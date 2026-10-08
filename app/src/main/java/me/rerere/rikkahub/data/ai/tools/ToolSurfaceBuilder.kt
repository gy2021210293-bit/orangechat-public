/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.desire.buildChatAutonomousItemTool
import me.rerere.ai.provider.Modality
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.memory.v1.MemoryEmbeddingService
import me.rerere.rikkahub.data.memory.v1.MemoryRepositoryV1
import me.rerere.rikkahub.data.model.MemoryMode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.plugin.provider.PluginToolProvider

/**
 * Builds the full tool surface for an assistant: search + local + system + workspace + skill
 * + MCP + plugin tools. This is the single source of truth shared by [me.rerere.rikkahub.service.ChatService]
 * (interactive) and [me.rerere.rikkahub.workflow.execution.WorkflowEngine] (headless fire), so a
 * workflow action can reference any tool the assistant actually has registered - not just the
 * local-tool subset. Without this, workflow_create would reject system/MCP/plugin tool names as
 * "unknown_tool" and the engine couldn't execute them at fire time.
 *
 * Headless callers (workflow fire) pass an empty [recentMessages] list and a null/assistant-default
 * [workspaceCwd]; the read-only context tools that consult recent messages simply see no history.
 */
class ToolSurfaceBuilder(
    private val context: Context? = null,
    private val localTools: LocalTools? = null,
    private val mcpManager: McpManager? = null,
    private val filesManager: FilesManager? = null,
    private val skillManager: SkillManager? = null,
    private val pluginToolProvider: PluginToolProvider? = null,
    private val workspaceRepository: WorkspaceRepository? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val memoryRepository: MemoryRepository? = null,
    private val memoryRepositoryV1: MemoryRepositoryV1? = null,
    private val memoryEmbeddingService: MemoryEmbeddingService? = null,
    private val conversationRepository: ConversationRepository? = null,
    private val desireRepository: me.rerere.rikkahub.data.ai.desire.DesireRepository? = null,
    private val xiaohongshuBrowser: XiaohongshuBackgroundBrowser? = null,
) {
    suspend fun closeXiaohongshuSession(sessionId: String) {
        xiaohongshuBrowser?.closeSession(sessionId)
    }

    suspend fun build(
        assistant: me.rerere.rikkahub.data.model.Assistant,
        settings: Settings,
        invocationContext: ToolInvocationContext,
        recentMessages: List<UIMessage> = emptyList(),
        workspaceCwd: String? = null,
    ): List<Tool> = buildList {
        if (!invocationContext.isHeadless && invocationContext.callerAssistantId == assistant.id.toString()) {
            if (desireRepository != null) {
                add(buildChatAutonomousItemTool(assistant.id.toString(), desireRepository))
            }
        }
        if ((!invocationContext.isHeadless || invocationContext.enableXiaohongshuBrowse) &&
            !invocationContext.callerConversationId.isNullOrBlank()
        ) {
            xiaohongshuBrowser?.let { browser ->
                add(browser.createTool(invocationContext.callerConversationId))
            }
        }

        // Write files tool
        add(buildWriteFilesTool(invocationContext.callerConversationId))

        // Memory tools - mutually exclusive by effectiveMemoryMode
        if (assistant.effectiveMemoryMode == MemoryMode.LOCAL_V1) {
            memoryRepositoryV1?.let { repo ->
                addAll(
                    buildMemoryV1Tools(
                        repository = repo,
                        assistant = assistant,
                        callerConversationId = invocationContext.callerConversationId,
                        embeddingService = memoryEmbeddingService,
                        settings = settings,
                        conversationRepository = conversationRepository,
                        json = json,
                    )
                )
            }
        } else if (assistant.isLegacySimpleMemoryEnabled) {
            memoryRepository?.let { repo ->
                val memoryAssistantId = if (assistant.useGlobalMemory) {
                    MemoryRepository.GLOBAL_MEMORY_ID
                } else {
                    assistant.id.toString()
                }
                addAll(buildMemoryTools(
                    json = json,
                    onCreation = { content -> repo.addMemory(memoryAssistantId, content) },
                    onUpdate = { id, content -> repo.updateContent(id, content) },
                    onDelete = { id -> repo.deleteMemory(id) },
                ))
            }
        }
        if (settings.enableWebSearch) {
            addAll(createSearchTools(settings))
        }
        localTools?.let {
            addAll(
                it.getTools(
                    assistant.localTools,
                    invocationContext.copy(
                        callerAssistantName = assistant.name,
                        userPatAction = settings.displaySetting.userPatAction,
                        userPatSuffix = settings.displaySetting.userPatSuffix,
                    ),
                )
            )
        }
        val systemToolsOptions = settings.systemToolsSetting.getEnabledOptions().toMutableSet()
        if (settings.externalMemories.any { it.enabled }) {
            systemToolsOptions.add(SystemToolOption.SupabaseQuery)
        }
        if (systemToolsOptions.isNotEmpty() && context != null && filesManager != null) {
            addAll(SystemTools(context, settings).getTools(systemToolsOptions, recentMessages, filesManager))
        }
        workspaceRepository?.let {
            addAll(createWorkspaceTools(assistant.workspaceId?.toString(), it, workspaceCwd))
        }
        if (assistant.enabledSkills.isNotEmpty() && skillManager != null) {
            addAll(createSkillTools(assistant.enabledSkills, skillManager.listSkills(), skillManager))
        }
        mcpManager?.getAllAvailableTools()?.forEach { (serverId, tool) ->
            add(
                Tool(
                    name = ToolNaming.buildMcpToolName(serverId, tool.name),
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = tool.needsApproval,
                    execute = {
                        mcpManager.callTool(serverId, tool.name, it.jsonObject)
                    },
                )
            )
        }
        pluginToolProvider?.let {
            val effectiveContext = if (invocationContext.callerAssistantId == null) {
                val effectiveModel = assistant.chatModelId?.let { settings.findModelById(it) }
                    ?: settings.findModelById(settings.chatModelId)
                invocationContext.copy(
                    callerAssistantId = assistant.id.toString(),
                    callerAssistantName = assistant.name,
                    modelCanSeeImages = effectiveModel?.inputModalities?.contains(Modality.IMAGE) == true,
                )
            } else {
                invocationContext
            }
            addAll(it.getTools(effectiveContext))
        }
    }
}
