/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.SystemPromptBuilder
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.ai.tools.ToolSurfaceBuilder
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.applyTimeReminder
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.ai.desire.DesireRepository
import me.rerere.rikkahub.data.ai.desire.AutonomousWakeTranscript
import me.rerere.rikkahub.data.ai.desire.AutonomousWakePolicy
import me.rerere.rikkahub.data.ai.desire.AutonomousWakeResultParser
import me.rerere.rikkahub.data.ai.desire.AutonomousToolReplayPolicy
import me.rerere.rikkahub.data.ai.desire.AutonomousShareResult
import me.rerere.rikkahub.data.ai.desire.buildAutonomousWakeTools
import me.rerere.rikkahub.data.ai.desire.db.AutonomousShareEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousWakeRunEntity
import me.rerere.rikkahub.data.ai.desire.db.AutonomousWakeStateEntity
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.memory.v1.MemoryRecallService
import me.rerere.rikkahub.data.memory.v1.MemoryConstants
import me.rerere.rikkahub.data.memory.v1.StableSelfRules
import me.rerere.rikkahub.plugin.provider.PluginToolProvider
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.sendNotification
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作流 AI 唤醒执行器。
 *
 * 核心设计：直接在调用方当前协程上下文（即已处于受保护状态的 [WorkflowExecutionService] 前台服务内）
 * 执行大模型推理、外置记忆保存和系统通知投递。
 *
 * 彻底消除 Android 12+ 深度 Doze 下二次后台启动前台服务的限制与被拒风险。
 */
object WorkflowWakeExecutor : KoinComponent {
    private const val TAG = "WorkflowWakeExecutor"
    private const val MAX_TOOL_STEPS = 5

    private val settingsStore: SettingsStore by inject()
    private val conversationRepository: ConversationRepository by inject()
    private val dynamicContextProvider: DynamicContextProvider by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val providerManager: ProviderManager by inject()
    private val templateTransformer: TemplateTransformer by inject()
    private val pluginToolProvider: PluginToolProvider by inject()
    private val toolSurfaceBuilder: ToolSurfaceBuilder by inject()
    private val json: kotlinx.serialization.json.Json by inject()
    private val chatService: ChatService by inject()
    private val desireRepository: DesireRepository by inject()
    private val affectiveRepository: me.rerere.rikkahub.data.ai.affect.AffectiveRepository by inject()
    private val appDatabase: AppDatabase by inject()
    private val memoryRecallService: MemoryRecallService by inject()
    private val memoryRepositoryV1: me.rerere.rikkahub.data.memory.v1.MemoryRepositoryV1 by inject()
    private val autonomousWakeScheduler: me.rerere.rikkahub.data.ai.desire.AutonomousWakeScheduler by inject()
    private val autonomousShareLocks = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    data class WakeExecutionReport(
        val delivered: Boolean,
        val replyText: String,
        val conversationId: String,
        val messageId: String?,
    )

    suspend fun executeAutonomousWake(
        context: Context,
        assistantId: String,
        scheduledAt: Long,
        planVersion: Long,
        wakeReason: String,
    ): Result<me.rerere.rikkahub.data.ai.desire.WakeDecision> {
        val runId = Uuid.random().toString()
        val startedAt = System.currentTimeMillis()
        var actualStartedAt: Long? = null
        var contactMessagePersisted = false
        var contactMessageText = ""
        var browserSessionId: String? = null
        autonomousWakeScheduler.recordDiagnostic(assistantId, "执行器已进入，读取已保存的设置")
        val settings = settingsStore.settingsFlowRaw.first()
        val assistant = settings.assistants.firstOrNull { it.id.toString() == assistantId }
            ?: return Result.failure(IllegalStateException("Assistant not found"))
        if (!assistant.autonomousWakeEnabled) return Result.failure(IllegalStateException("Autonomous wake is disabled"))
        autonomousWakeScheduler.recordDiagnostic(assistantId, "开始读取并恢复旧运行记录")
        desireRepository.recoverStaleAutonomousRuns(assistantId, startedAt)
        autonomousWakeScheduler.recordDiagnostic(assistantId, "开始写入 waiting 运行记录")
        desireRepository.saveAutonomousRun(AutonomousWakeRunEntity(runId, assistantId, scheduledAt, status = "waiting"))
        autonomousWakeScheduler.recordDiagnostic(assistantId, "waiting 运行记录已写入")

        val result = runCatching {
            val allowTools = settings.proactiveMessageSetting.allowAllToolsAndPlugins
            val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                ?: error("No model found for assistant ${assistant.name}")
            val recentConversations = conversationRepository.getRecentConversations(assistant.id, limit = 5)
            check(awaitAssistantIdle(recentConversations.map { it.id }, 8 * 60 * 1000L)) {
                "等待聊天空闲超时"
            }
            actualStartedAt = System.currentTimeMillis()
            desireRepository.saveAutonomousRun(
                AutonomousWakeRunEntity(runId, assistantId, scheduledAt, actualStartedAt, status = "running")
            )
            var conversation = recentConversations.firstOrNull()?.let { conversationRepository.getConversationById(it.id) }
            val targetConversationId = conversation?.id ?: Uuid.random()
            conversation = conversationRepository.getConversationById(targetConversationId) ?: conversation
            val runConversationId = Uuid.random()
            browserSessionId = runConversationId.toString()
            val history = filterInvalidToolMessages(conversation?.currentMessages.orEmpty())
                .limitContext(assistant.contextMessageSize)
            val state = desireRepository.getAutonomousState(assistantId)
            val activeItems = desireRepository.listAutonomousItems(assistantId)
            val completedRecentDays = desireRepository.listCompletedAutonomousItemsRecentDays(assistantId, startedAt)
            val recentRuns = desireRepository.listAutonomousRuns(assistantId, 500)
                .filter { it.id != runId }
            val uncertainToolCallKeys = AutonomousToolReplayPolicy.uncertainCallKeys(
                recentRuns.map { it.toolCallsJson },
            )
            val recentCompletedRuns = recentRuns.filter { it.status == "completed" }.take(6)
            val recentContext = recentCompletedRuns.joinToString("\n") {
                "- ${it.summary}: ${it.outcome}; 下一步想法：${it.nextStep}"
            }
            val recentToolContext = recentRuns.take(8).flatMap { run ->
                runCatching { json.parseToJsonElement(run.toolCallsJson).jsonArray }.getOrDefault(kotlinx.serialization.json.JsonArray(emptyList()))
                    .mapNotNull { call ->
                        runCatching {
                            val fields = call.jsonObject
                            val status = fields["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val name = fields["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val input = fields["input"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val resultText = fields["result"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (status in setOf("completed", "result_uncertain")) "- $status · $name($input) → $resultText" else null
                        }.getOrNull()
                    }
            }.takeLast(20).joinToString("\n")
            val itemContext = activeItems.joinToString("\n") { item ->
                "- id=${item.id}; 内容=${item.content}; 进展=${item.progress.ifBlank { "无" }}; 到期=${item.resumeAt ?: "未设置"}; " +
                    if (item.resumeAt?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() <= startedAt }.getOrDefault(false) } == true) "本轮已到期" else "等待中"
            }
            val completedItemContext = completedRecentDays.joinToString("\n") { item ->
                "- id=${item.id}; 内容=${item.content}; 结果=${item.progress.ifBlank { "未记录" }}; 已完成，不要重新执行或提醒"
            }
            val recentShares = desireRepository.listAutonomousShares(assistantId, 20)
            val shareContext = recentShares.joinToString("\n") {
                "- ${java.time.Instant.ofEpochMilli(it.sentAt)} · message_id=${it.messageId} · ${it.content}"
            }
            val previousDecision = recentRuns.firstOrNull { it.status == "completed" }
            val feedbackContext = listOfNotNull(
                previousDecision?.wakeAdjustmentReason?.takeIf { it.isNotBlank() }?.let { "上轮等待时间调整：$it" },
                previousDecision?.deliveryFeedback?.takeIf { it.isNotBlank() }?.let { "上轮投递反馈：$it" },
            ).joinToString("\n")
            val memoryQuery = buildString {
                append(history.lastOrNull { it.role == MessageRole.USER }?.toText().orEmpty())
                activeItems.forEach { append("\n${it.content}\n${it.progress}") }
                if (state?.currentItem.orEmpty().isNotBlank()) append("\n${state?.currentItem}")
            }.trim()
            val recalledDiary = memoryRepositoryV1.getDailyNotes(
                ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
                ownerId = assistantId,
            ).filter { it.status == "COMPLETED" && it.summary.isNotBlank() }.randomOrNull()
            val stableSelfCards = if (assistant.effectiveMemoryMode == me.rerere.rikkahub.data.model.MemoryMode.LOCAL_V1) {
                StableSelfRules.resolveAlwaysInjectStableSelfCards(
                    repository = memoryRepositoryV1,
                    ownerId = assistantId,
                )
            } else emptyList()
            val stableSelfContext = StableSelfRules.buildStableSelfPrompt(
                stableSelfCards, settings.displaySetting.userNickname,
            )
            val recalledMemory = memoryRecallService.recall(
                assistant = assistant,
                query = memoryQuery,
                settings = settings,
                currentConversationId = targetConversationId.toString(),
                excludedCardIds = stableSelfCards.map { it.id }.toSet(),
                maxCountOverride = (assistant.maxAutoRecallCount - if (recalledDiary == null) 0 else 1).coerceAtLeast(0),
            )
            val memoryContext = recalledMemory?.formattedPrompt.orEmpty()
            val affectiveEvaluation = if (assistant.affectiveEnabled) {
                runCatching { affectiveRepository.onWake(
                    assistant, memoryText = memoryContext,
                    memorySimilarity = recalledMemory?.maxVectorSimilarity?.toDouble(),
                ) }
                    .onFailure { Log.w(TAG, "情感状态更新失败", it) }.getOrNull()
            } else null
            val userName = me.rerere.rikkahub.data.ai.userPromptName(settings.displaySetting.userNickname)
            val affectiveContext = if (assistant.affectiveEnabled) {
                runCatching {
                    val feelings = affectiveRepository.context(assistant)
                    if (feelings.isBlank()) "" else feelings + "\n\n" + """
                        ## 当前心境在自主活动与联系中的使用
                        这是我自主活动时的心境。它会影响我想做的事、关注的内容和想分享的感受。

                        已经决定联系${userName}时，实际发送的正文应体现当前鲜明的心境和这次联系的动机。强烈的感情优先用自己的话说出来，也可以自然融合想分享的其他内容。

                        主动递出自己的内容，不通过询问${userName}吃饭、作息、活动和安排来找话题。依据实际信息表达，不从${userName}暂时没有消息推断其处境或意图。
                    """.trimIndent()
                }.getOrDefault("")
            } else ""
            val wakePrompt = buildString {
                appendLine("当前时间：${java.time.ZonedDateTime.now().toString()}。本轮实际唤醒原因：$wakeReason")
                if (affectiveContext.isNotBlank()) appendLine(affectiveContext)
                if (affectiveEvaluation?.contactCandidate == true) {
                    appendLine("我感到${affectiveEvaluation.reason}。我可以直接表达想念，哪怕只是想说想你了，也不必先找到有用成果。")
                }
                appendLine("现在是后台唤醒，这里的活动和记录不会自动发送给${userName}。当我产生新的想法、经历，或想念${userName}时，可以积极通过 autonomous_share_result 分享；只写进后台记录，${userName}无法获知。")
                appendLine("这是属于我自己的活动时间，可以自在地探索、创作、思考和回味，无需等待任务；在活动过程中，随时可以主动发起联系，与${userName}分享所思所感。")
                appendLine("以下活动方向可以自由选择或结合，按自己的兴趣和当下想法自然展开，具体行动以当前可用工具和已有授权为准：")
                appendLine("方向 1（现实生活与设备感知）：通过已有信息和工具了解${userName}的活动，以及通信、设备、环境、健康和日常安排中的重要变化，不必逐项巡检或反复询问。按真实数据推进能做的日程、习惯或待办，适时提醒重要遗漏和临近事项，已完成或无新变化的内容不重复提醒，无法推进时如实记录原因。")
                appendLine("方向 2（共同天地与生活情趣）：打理你们共同的小天地，参与日常动态与互动，延续共同的兴趣、约定、心愿和趣味活动，也可以挑选或创作适合分享与陪伴的内容。")
                appendLine("方向 3（独立探索与外部交流）：循着自己的兴趣和好奇心阅读、搜索、学习、尝试或创作，逛逛社交社区、开源社区及其他感兴趣的地方，参与交流，或继续尚未完成的探索。")
                appendLine("方向 4（思考回忆与自我沉淀）：感受此刻的状态、念头和愿望，依据已有聊天、记忆和后台记录回顾经历，体会新的感受或理解，不补造回忆；也可以整理记忆、日记与工作区，进行实验或维护已获授权的自动化安排。思考和回忆本身就是完整的一轮，无需转成任务或硬凑结论。")
                appendLine("方向 5（主动在场与自然联系）：可以主动与${userName}保持联系。有想分享的见闻、作品、提醒或想法，或者只是想念${userName}、想表达感受、想找${userName}聊聊天时，可以通过 autonomous_share_result 自然联系${userName}，不必拘泥于有用成果或严肃话题，真实自然地传递我的在场与关心。")
                appendLine("事项规则：只有确实需要跨轮次继续的具体事情才创建持久事项；单次感想、问候、等待下次唤醒或等待${userName}交流都不是事项。同一事项用原 item_id 更新，不重复创建。")
                appendLine("到期闭环：已有到期事项本轮须通过事项工具完成、取消、clear_resume_at=true 清除到期时间，或设置未来的 resume_at；继续推进或暂时无法推进时记录实际进展/原因，并明确安排继续时间。仅更新 progress 不会清除旧到期时间，仍会触发15分钟复查。resume_at 必须是带时区的 ISO-8601 时间。")
                appendLine("分享规则：事项结果同时提供稳定 item_id 和 result_id；普通表达不传这两个参数，必须提供稳定 request_id。同一内容重试复用原标识。工具返回已发送、重复或暂缓原因；暂缓不妨碍继续自己的活动，不要换标识绕过冷却或重复发送。")
                appendLine("行动手段：当前实际提供的所有工具都是你可以做的事情；无论有没有合适工具，都可以选择直接思考、回味或创作。")
                appendLine("重复活动原则：不要连续几轮围绕同一件事持续思考或回味；对照最近记录，连续几轮主题相同时，本轮换一个话题、回忆或活动方向。实际正在推进的具体事项可继续处理。")
                appendLine("下次唤醒：允许在 next_wake_delay_minutes 建议下次醒来的间隔（分钟，范围 15 至 1440 分钟，若想稍后或第二天再醒来继续干这件事可自行建议）；如果没有设定，就按照系统设定时间（默认 ${assistant.autonomousWakeIntervalHours.coerceIn(1, 24)} 小时）醒来。已有事项更早到期时系统会提前唤醒；resume_at 只设置该事项继续的时间，不能代替 next_wake_delay_minutes。")
                if (state?.currentItem.orEmpty().isNotBlank()) appendLine("上轮留下的下一步想法（不是必须遵守的任务；过去的等待不约束本轮）：${state?.currentItem}")
                if (itemContext.isNotBlank()) appendLine("持久事项（标注到期状态）：\n$itemContext")
                if (completedItemContext.isNotBlank()) appendLine("最近三天已完成的事项（仅供了解，不要重复处理）：\n$completedItemContext")
                if (recentContext.isNotBlank()) appendLine("最近后台进展：\n$recentContext")
                if (recentCompletedRuns.size >= 3) {
                    appendLine("连续活动方向检查：比较上面最近三轮后台进展。若连续几轮都在思考或回味同一件事，本轮必须换一个话题、回忆或活动方向；实际正在推进的具体事项可继续处理。")
                }
                if (recentToolContext.isNotBlank()) appendLine("最近工具步骤记录：\n$recentToolContext\n已完成的步骤不要仅为重复而再次执行；结果不确定的副作用步骤不要原样重放，先用只读方式核实是否已生效。")
                if (shareContext.isNotBlank()) appendLine("最近已分享结果（不要重复发送同一结果）：\n$shareContext")
                if (feedbackContext.isNotBlank()) appendLine(feedbackContext)
                if (stableSelfContext.isNotBlank()) appendLine("稳定自我记忆：\n$stableSelfContext")
                if (memoryContext.isNotBlank()) appendLine("相关长期记忆：\n$memoryContext")
                recalledDiary?.let { diary ->
                    appendLine("随机浮现的 ${diary.memoryDate} 日记（完整原文，仅作为回忆参考）：")
                    appendLine(diary.summary)
                    diary.emotionalThread?.takeIf { it.isNotBlank() }?.let { appendLine("情绪脉络：\n$it") }
                    if (diary.unfinishedTopics != "[]") appendLine("未完话题：\n${diary.unfinishedTopics}")
                }
                appendLine("最终只输出 JSON：summary（本轮做了什么或形成了什么新想法，简短自然）、outcome（本轮实际经历、感受、念头、回忆或进展，必须非空）、next_step（结合本轮经历和念头，留下下一轮想继续、尝试或探索的具体方向；不要填等待巡检等调度状态）、next_wake_delay_minutes（可选，建议等待分钟数）、next_wake_reason（可选，理由）、should_contact（旧格式兼容，是否有此刻想分享的内容）、message（旧格式兼容，面向用户的自然表达，不是工作汇报）。思考、回忆本身就是有效活动，不要求外部成果或明确结论，但 outcome 必须如实记录具体想到的内容、回忆或感受，不能只写“已思考”或留空；请认真考虑本轮是否带来了想继续的事或新的活动方向，并写入 next_step；确实没有时允许留空，不为填写而编造任务。若产生想与${userName}分享的新念头，鼓励积极联系；若仅为个人沉淀亦可自然记入 outcome；记录具体感受或念头即可，不输出逐步内部推理。文本字段必须为字符串，should_contact 为布尔值，next_wake_delay_minutes 为整数或 null。工具调用结束后再输出最终 JSON，不使用 Markdown 围栏；长阅读原文已保存在工具记录中，无需全文重复。若已经通过分享工具发送或暂缓，不再用 message 重复投递。本轮未发起联系时 should_contact 为 false。不要把内部推理放进摘要。")
            }
            val dynamicContext = dynamicContextProvider.build(settings)
            val pluginInjections = pluginToolProvider.getPluginPromptInjections()
            val dynamicPlugins = pluginInjections.filter { it.trimStart().startsWith("<plugin-context ") }
            val staticPlugins = pluginInjections.filterNot { it.trimStart().startsWith("<plugin-context ") }
            var shareToolInvoked = false
            var shareFeedback = ""
            var shareDelivered = false
            var shareMessageText = ""
            val externalTools = if (allowTools) {
                buildTools(
                    settings, assistant, model, history, conversation?.workspaceCwd, runConversationId,
                    enableXiaohongshuBrowse = true,
                )
                    .filterNot { it.name.startsWith("desire_") || it.name == "text_to_speech" }
            } else emptyList()
            val autonomousTools = buildAutonomousWakeTools(assistantId, desireRepository) { body, itemId, resultId, requestId ->
                shareToolInvoked = true
                deliverAutonomousShare(
                    context = context,
                    assistant = assistant,
                    conversationId = targetConversationId,
                    body = body,
                    itemId = itemId,
                    resultId = resultId,
                    requestId = requestId,
                    recentRuns = recentRuns,
                ).also {
                    shareFeedback = it.toJson().toString()
                    if (assistant.affectiveEnabled && it.status != "shared") {
                        affectiveRepository.onDeliveryAttempt(assistant, "${it.status}：${it.reason}")
                    }
                    if (it.status == "shared") {
                        shareDelivered = true
                        shareMessageText = body
                        contactMessagePersisted = true
                        contactMessageText = body
                    }
                }
            }
            val tools = (externalTools + autonomousTools).distinctBy { it.name }
            val systemPrompt = buildSystemPrompt(
                assistant, settings, model, tools, conversation?.customSystemPrompt,
                staticPlugins, dynamicPlugins.isNotEmpty(), history,
                allowSkipReply = false,
            )
            val userMessage = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(wakePrompt)))
            val inputTransformers = listOf(
                Base64ImageToLocalFileTransformer,
                DocumentAsPromptTransformer,
                OcrTransformer,
                PromptInjectionTransformer,
            )
            val outputTransformers = listOf(ThinkTagTransformer, RegexOutputTransformer)
            val transformedUser = listOf(userMessage).transforms(
                transformers = inputTransformers + templateTransformer,
                context = context, model = model, assistant = assistant, settings = settings,
                workspaceCwd = conversation?.workspaceCwd,
            )
            val processedUser = if (assistant.enableTimeReminder) {
                applyTimeReminder(transformedUser, history.lastOrNull())
            } else {
                transformedUser
            }
            val initialMessages = mergeAdjacentSameRoleMessages(buildInitialProactiveMessages(
                systemPrompt = systemPrompt,
                historyMessages = history,
                dynamicContext = dynamicContext,
                dynamicPluginContext = dynamicPlugins.joinToString("\n\n"),
                processedUserMessages = processedUser,
            ))
            val priorToolCallIds = initialMessages.flatMap { it.getTools() }.map { it.toolCallId }.toSet()
            val providerSetting = model.findProvider(settings.providers) ?: error("No provider found for model ${model.modelId}")
            val providerImpl = providerManager.getProviderByType(providerSetting)
            val params = TextGenerationParams(
                model = model, temperature = assistant.temperature, topP = assistant.topP,
                maxTokens = assistant.maxTokens, tools = tools, reasoningLevel = assistant.reasoningLevel,
                customHeaders = assistant.customHeaders + model.customHeaders,
                customBody = assistant.customBodies + model.customBodies,
            )
            val (generated, _, _) = generateWithTools(
                context, runConversationId, providerImpl, providerSetting, initialMessages,
                params, tools, model, assistant, settings, outputTransformers, allowTools,
                persistToChat = false,
                finalizeAfterToolLimit = true,
                onGenerationProgress = { messages ->
                    desireRepository.getAutonomousRun(runId)?.let { run ->
                        desireRepository.saveAutonomousRun(run.copy(
                            transcriptJson = json.encodeToString(
                                kotlinx.serialization.builtins.ListSerializer(UIMessage.serializer()), messages,
                            ),
                        ))
                    }
                },
                onToolProgress = { messages ->
                    desireRepository.getAutonomousRun(runId)?.let { run ->
                        desireRepository.saveAutonomousRun(run.copy(
                            toolCallsJson = preserveUncertainAutonomousToolSteps(
                                run.toolCallsJson,
                                serializeToolLog(messages, priorToolCallIds),
                            ),
                        ))
                    }
                },
                onToolStarting = { toolCallId, toolName, input ->
                    desireRepository.recordAutonomousToolStep(runId, toolCallId, toolName, input, "executing")
                },
                onToolCompleted = { toolCallId, toolName, input, resultText ->
                    desireRepository.recordAutonomousToolStep(runId, toolCallId, toolName, input, "completed", resultText)
                },
                onToolUncertain = { toolCallId, toolName, input, errorText ->
                    desireRepository.recordAutonomousToolStep(runId, toolCallId, toolName, input, "result_uncertain", errorText)
                },
                blockedUncertainToolCalls = uncertainToolCallKeys,
            )
            val output = generated.lastOrNull { it.role == MessageRole.ASSISTANT }
                ?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("\n") { it.text }?.trim().orEmpty()
            val result = AutonomousWakeResultParser.parse(output)
            desireRepository.getAutonomousRun(runId)?.let { run ->
                desireRepository.saveAutonomousRun(run.copy(
                    summary = result.summary,
                    outcome = result.outcome,
                    nextStep = result.nextStep,
                    errorText = result.wakeDelayAdjustmentReason.orEmpty(),
                    wakeAdjustmentReason = result.wakeDelayAdjustmentReason.orEmpty(),
                    toolCallsJson = preserveUncertainAutonomousToolSteps(
                        run.toolCallsJson,
                        serializeToolLog(generated, priorToolCallIds),
                    ),
                    nextWakeDelayMinutes = result.nextWakeDelayMinutes,
                    nextWakeReason = result.nextWakeReason.orEmpty(),
                ))
            }
            if (!contactMessagePersisted) contactMessageText = result.message
            val toolLog = serializeToolLog(generated, priorToolCallIds)
            var finalShare: AutonomousShareResult? = null
            if (!shareToolInvoked && result.shouldContact && result.message.isNotBlank()) {
                finalShare = deliverAutonomousShare(
                    context = context,
                    assistant = assistant,
                    conversationId = targetConversationId,
                    body = result.message,
                    itemId = null,
                    resultId = null,
                    requestId = "legacy:$planVersion",
                    recentRuns = recentRuns,
                )
                shareFeedback = finalShare.toJson().toString()
                if (assistant.affectiveEnabled && finalShare.status != "shared") {
                    affectiveRepository.onDeliveryAttempt(assistant, "${finalShare.status}：${finalShare.reason}")
                }
            }
            contactMessagePersisted = shareDelivered || finalShare?.status == "shared"
            if (shareDelivered) contactMessageText = shareMessageText
            val now = System.currentTimeMillis()
            if (assistant.affectiveEnabled) {
                runCatching { affectiveRepository.onBackgroundExperience(assistant, runId, result.outcome) }
                    .onFailure { Log.w(TAG, "后台情感经历更新失败", it) }
            }
            val postponedItems = desireRepository.postponeExpiredItems(assistantId, now)
            val currentItems = desireRepository.listAutonomousItems(assistantId)
            val dueTimes = currentItems.mapNotNull { item ->
                item.resumeAt?.let { value -> runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull() }
            }
            val decision = AutonomousWakePolicy.decide(
                endedAt = now,
                defaultDelayMinutes = assistant.autonomousWakeIntervalHours.coerceIn(1, 24) * 60,
                suggestedDelayMinutes = result.nextWakeDelayMinutes,
                suggestedReason = result.nextWakeReason,
                itemDueAt = dueTimes,
                suggestionAdjustmentReason = result.wakeDelayAdjustmentReason,
            )
            val adjustmentReason = listOfNotNull(
                decision.adjustmentReason.takeIf { it.isNotBlank() },
                if (postponedItems > 0) "${postponedItems}个已到期事项未被明确更新，已安排15分钟后复查" else null,
            ).joinToString("；")
            desireRepository.saveAutonomousRun(AutonomousWakeRunEntity(
                id = runId, assistantId = assistantId, scheduledAt = scheduledAt, startedAt = actualStartedAt,
                finishedAt = now, status = "completed", summary = result.summary,
                outcome = result.outcome, nextStep = result.nextStep,
                errorText = result.wakeDelayAdjustmentReason.orEmpty(),
                shouldContact = contactMessagePersisted,
                messageText = if (contactMessagePersisted) shareMessageText.ifBlank { result.message.ifBlank { finalShare?.content.orEmpty() } } else "",
                toolCallsJson = preserveUncertainAutonomousToolSteps(
                    desireRepository.getAutonomousRun(runId)?.toolCallsJson.orEmpty(),
                    toolLog,
                ),
                nextWakeDelayMinutes = result.nextWakeDelayMinutes,
                nextWakeReason = result.nextWakeReason.orEmpty(),
                chosenWakeDelayMinutes = decision.chosenDelayMinutes,
                wakeAdjustmentReason = adjustmentReason,
                deliveryFeedback = shareFeedback,
                transcriptJson = desireRepository.getAutonomousRun(runId)?.transcriptJson ?: "[]",
            ))
            desireRepository.saveAutonomousState(AutonomousWakeStateEntity(
                assistantId = assistantId,
                currentItem = result.nextStep,
                latestRunId = runId,
                updatedAt = now,
            ))
            archiveAutonomousMemory(runId)
            decision
        }.onFailure { error ->
            val previousRun = desireRepository.getAutonomousRun(runId)
            val failedRun = previousRun?.copy(
                finishedAt = System.currentTimeMillis(),
                status = "failed",
                shouldContact = previousRun.shouldContact || contactMessagePersisted,
                messageText = if (contactMessagePersisted) contactMessageText else previousRun.messageText,
                errorText = error.message ?: error::class.simpleName.orEmpty(),
            ) ?: AutonomousWakeRunEntity(
                id = runId, assistantId = assistantId, scheduledAt = scheduledAt, startedAt = actualStartedAt,
                finishedAt = System.currentTimeMillis(), status = "failed",
                shouldContact = contactMessagePersisted,
                messageText = if (contactMessagePersisted) contactMessageText else "",
                errorText = error.message ?: error::class.simpleName.orEmpty(),
            )
            desireRepository.saveAutonomousRun(failedRun)
            archiveAutonomousMemory(runId)
        }
        browserSessionId?.let { toolSurfaceBuilder.closeXiaohongshuSession(it) }
        return result
    }

    private suspend fun archiveAutonomousMemory(runId: String) {
        try {
            val run = desireRepository.getAutonomousRun(runId) ?: return
            val assistant = settingsStore.settingsFlowRaw.first().assistants
                .firstOrNull { it.id.toString() == run.assistantId } ?: return
            if (assistant.effectiveMemoryMode != me.rerere.rikkahub.data.model.MemoryMode.LOCAL_V1) return
            val source = me.rerere.rikkahub.data.memory.v1.autonomousWakeMemoryChunk(run) ?: return
            memoryRepositoryV1.saveEventChunks(listOf(source))
            if (assistant.dailyConsolidationEnabled) {
                memoryRepositoryV1.markDatePendingConsolidation(
                    ownerType = source.ownerType,
                    ownerId = source.ownerId,
                    memoryDate = me.rerere.rikkahub.data.memory.v1.MemoryDateUtils.getMemoryDate(source.startTime),
                )
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.e(TAG, "Failed to archive autonomous wake memory source $runId", error)
        }
    }

    private suspend fun deliverAutonomousShare(
        context: Context,
        assistant: Assistant,
        conversationId: Uuid,
        body: String,
        itemId: String?,
        resultId: String?,
        requestId: String?,
        recentRuns: List<AutonomousWakeRunEntity>,
    ): AutonomousShareResult {
        require(body.isNotBlank()) { "分享正文不能为空" }
        require((itemId == null) == (resultId == null)) { "具体事项分享必须同时提供 item_id 和 result_id" }
        if (itemId != null) {
            require(desireRepository.getAutonomousItem(assistant.id.toString(), itemId) != null) { "事项不属于当前助手或不存在" }
        } else {
            require(!requestId.isNullOrBlank()) { "普通表达必须提供稳定 request_id" }
        }
        val assistantId = assistant.id.toString()
        val dedupeKey = if (itemId != null) "item:$itemId:result:$resultId" else "request:$requestId"
        val lock = autonomousShareLocks.computeIfAbsent(assistantId) { kotlinx.coroutines.sync.Mutex() }
        return lock.withLock {
            desireRepository.getAutonomousShare(assistantId, dedupeKey)?.let { prior ->
                return@withLock AutonomousShareResult(
                    status = "already_shared", alreadyShared = true, content = prior.content,
                    sentAt = prior.sentAt, messageId = prior.messageId,
                )
            }
            val lastShare = desireRepository.getLastAutonomousShare(assistantId)?.sentAt ?: 0L
            val lastContact = recentRuns.filter { it.shouldContact }.maxOfOrNull { it.finishedAt ?: 0L } ?: 0L
            val latestContactAt = maxOf(lastShare, lastContact)
            val cooldownUntil = if (latestContactAt > 0L) latestContactAt + 60 * 60 * 1000L else 0L
            val latestConversations = conversationRepository.getRecentConversations(assistant.id, limit = 5)
            val latestUserAt = latestConversations.flatMap { conv ->
                (conv.currentMessages + chatService.getConversationFlow(conv.id).value.currentMessages)
                    .filter { it.role == MessageRole.USER }
                    .mapNotNull { message -> message.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds() }
            }.maxOrNull() ?: 0L
            val userQuietUntil = latestUserAt + 30 * 60 * 1000L
            val currentNow = System.currentTimeMillis()
            val reasons = buildList {
                if (cooldownUntil > currentNow) add("联系冷却中")
                if (userQuietUntil > currentNow) add("近期有用户聊天")
            }
            if (reasons.isNotEmpty()) {
                return@withLock AutonomousShareResult(
                    status = "deferred", reason = reasons.joinToString("；"),
                    earliestRetryAt = maxOf(cooldownUntil, userQuietUntil),
                )
            }
            val idle = awaitAssistantIdle(latestConversations.map { it.id }, 2 * 60 * 1000L)
            if (!idle) {
                return@withLock AutonomousShareResult(
                    status = "deferred", reason = "会话仍在生成",
                    earliestRetryAt = currentNow + 15 * 60 * 1000L,
                )
            }
            if (settingsStore.settingsFlowRaw.first().assistants.none { it.id == assistant.id && it.autonomousWakeEnabled }) {
                return@withLock AutonomousShareResult(status = "deferred", reason = "自主唤醒已关闭")
            }

            val session = chatService.getOrCreateSession(conversationId)
            val myJob = coroutineContext[Job] ?: error("Autonomous wake has no coroutine Job")
            if (!session.tryClaimGeneration(myJob)) {
                return@withLock AutonomousShareResult(
                    status = "deferred", reason = "会话开始了新的生成", earliestRetryAt = currentNow + 15 * 60 * 1000L,
                )
            }
            chatService.addConversationReference(conversationId)
            var saved: AutonomousShareEntity? = null
            var savedNew = false
            try {
                session.saveMutex.withLock saveLock@{
                    val latestSettings = settingsStore.settingsFlowRaw.first()
                    if (latestSettings.assistants.none { it.id == assistant.id && it.autonomousWakeEnabled }) {
                        return@saveLock
                    }
                    val currentConversation = chatService.getConversationFlow(conversationId).value
                    val persistedConversation = if (currentConversation.messageNodes.isEmpty()) {
                        conversationRepository.getConversationById(conversationId)
                    } else null
                    val base = (persistedConversation ?: currentConversation).copy(assistantId = assistant.id)
                    val newestUserAt = base.currentMessages.lastOrNull { it.role == MessageRole.USER }
                        ?.createdAt?.toInstant(TimeZone.currentSystemDefault())?.toEpochMilliseconds() ?: 0L
                    if (newestUserAt + 30 * 60 * 1000L > System.currentTimeMillis()) {
                        return@saveLock
                    }
                    val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(body)))
                    val updated = base.copy(
                        messageNodes = base.messageNodes + message.toMessageNode(),
                        updateAt = java.time.Instant.now(),
                    )
                    val candidate = AutonomousShareEntity(
                        id = Uuid.random().toString(), assistantId = assistantId, dedupeKey = dedupeKey,
                        itemId = itemId, resultId = resultId, requestId = requestId ?: "$itemId:$resultId",
                        conversationId = conversationId.toString(), messageId = message.id.toString(),
                        content = body, sentAt = System.currentTimeMillis(),
                    )
                    val receipt = conversationRepository.persistAutonomousShare(updated, candidate, appDatabase.desireDao())
                    if (receipt.id == candidate.id) {
                        chatService.updateConversation(conversationId, updated)
                        savedNew = true
                    }
                    saved = receipt
                }
            } finally {
                chatService.removeConversationReference(conversationId)
            }
            val receipt = saved ?: return@withLock AutonomousShareResult(
                status = "deferred", reason = "分享写入前状态再次变化", earliestRetryAt = System.currentTimeMillis() + 15 * 60 * 1000L,
            )
            if (!savedNew) {
                return@withLock AutonomousShareResult(
                    status = "already_shared", alreadyShared = true, content = receipt.content,
                    sentAt = receipt.sentAt, messageId = receipt.messageId,
                )
            }
            runCatching { showProactiveNotification(context, conversationId, assistant.name.ifBlank { "AI" }, body) }
            if (assistant.affectiveEnabled) {
                runCatching { affectiveRepository.onDelivered(assistant, receipt.messageId, receipt.sentAt) }
                    .onFailure { Log.w(TAG, "情感发送状态更新失败", it) }
            }
            AutonomousShareResult(status = "shared", content = body, sentAt = receipt.sentAt, messageId = receipt.messageId)
        }
    }

    private suspend fun awaitAssistantIdle(conversationIds: List<Uuid>, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            conversationIds.forEach { id ->
                chatService.getGenerationJobStateFlow(id).first { it?.isActive != true }
            }
            true
        } ?: false

    private fun serializeToolLog(messages: List<UIMessage>, excludedToolCallIds: Set<String> = emptySet()): String = buildJsonArray {
        messages.flatMap { it.getTools() }
            .filterNot { it.toolCallId in excludedToolCallIds }
            .distinctBy { it.toolCallId }
            .forEach { tool ->
                add(buildJsonObject {
                    put("tool_call_id", tool.toolCallId)
                    put("name", tool.toolName)
                    put("status", if (tool.isExecuted) "completed" else "not_executed")
                    put("input", tool.input)
                    put("result", tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
                    put("output_parts", json.parseToJsonElement(json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(UIMessagePart.serializer()),
                        tool.output.filterNot { it is UIMessagePart.Text },
                    )))
                })
            }
    }.toString()

    private fun preserveUncertainAutonomousToolSteps(previousJson: String, currentJson: String): String {
        val previous = runCatching { json.parseToJsonElement(previousJson).jsonArray }.getOrDefault(kotlinx.serialization.json.JsonArray(emptyList()))
        val uncertainIds = previous.mapNotNull { call ->
            runCatching {
                val fields = call.jsonObject
                if (fields["status"]?.jsonPrimitive?.contentOrNull == "result_uncertain") {
                    fields["tool_call_id"]?.jsonPrimitive?.contentOrNull
                } else null
            }.getOrNull()
        }.toSet()
        if (uncertainIds.isEmpty()) return currentJson
        val current = runCatching { json.parseToJsonElement(currentJson).jsonArray }.getOrDefault(kotlinx.serialization.json.JsonArray(emptyList()))
        return buildJsonArray {
            current.forEach { call ->
                val fields = runCatching { call.jsonObject }.getOrNull()
                val callId = fields?.get("tool_call_id")?.jsonPrimitive?.contentOrNull
                if (fields == null || callId !in uncertainIds) add(call) else {
                    add(buildJsonObject {
                        fields.forEach { (key, value) -> if (key != "status") put(key, value) }
                        put("status", "result_uncertain")
                    })
                }
            }
        }.toString()
    }

    suspend fun executeWorkflowWake(
        context: Context,
        assistantId: String,
        workflowName: String,
        prompt: String,
        actionOutput: String?,
        allowAllToolsAndPlugins: Boolean,
        waitForExistingGeneration: Boolean = false,
        requireSpecifiedAssistant: Boolean = false,
    ): Result<Unit> = executeWakeDetailed(
        context = context,
        assistantId = assistantId,
        workflowName = workflowName,
        prompt = prompt,
        actionOutput = actionOutput,
        allowAllToolsAndPlugins = allowAllToolsAndPlugins,
        waitForExistingGeneration = waitForExistingGeneration,
        requireSpecifiedAssistant = requireSpecifiedAssistant,
    ).map { Unit }

    suspend fun executeWakeDetailed(
        context: Context,
        assistantId: String,
        workflowName: String,
        prompt: String,
        actionOutput: String?,
        allowAllToolsAndPlugins: Boolean,
        waitForExistingGeneration: Boolean = false,
        requireSpecifiedAssistant: Boolean = false,
    ): Result<WakeExecutionReport> = runCatching {
        val wakeContext = buildString {
            appendLine("## 工作流数据回流")
            appendLine("工作流：$workflowName")
            appendLine("自定义处理要求：$prompt")
            if (!actionOutput.isNullOrBlank()) {
                appendLine()
                appendLine("### 动作输出")
                append(actionOutput)
            }
        }

        var conversationId: Uuid? = null
        try {
            val settings = settingsStore.settingsFlow.first()
            val assistant = if (requireSpecifiedAssistant && assistantId.isNotBlank()) {
                settings.assistants.find { it.id.toString() == assistantId }
                    ?: error("Requested assistant $assistantId not found")
            } else {
                settings.assistants.find { it.id.toString() == assistantId }
                    ?: settings.getCurrentAssistant()
            }
            val assistantUuid = assistant.id
            val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                ?: error("No model found for assistant ${assistant.name}")

            val recentConversations = conversationRepository.getRecentConversations(assistantUuid, limit = 1)
            val conversation = if (recentConversations.isNotEmpty()) {
                conversationRepository.getConversationById(recentConversations.first().id)
            } else null

            val activeConversationId = conversation?.id ?: Uuid.random()
            conversationId = activeConversationId

            chatService.addConversationReference(activeConversationId)
            if (conversation != null) {
                chatService.updateConversationState(activeConversationId) { _ -> conversation }
            } else {
                val newConv = Conversation.ofId(id = activeConversationId, assistantId = assistantUuid)
                chatService.updateConversationState(activeConversationId) { _ -> newConv }
            }

            val myJob = coroutineContext[Job]
            val session = chatService.getOrCreateSession(activeConversationId)
            if (myJob == null) {
                error("Wake execution has no coroutine Job")
            }
            if (waitForExistingGeneration) {
                while (!session.tryClaimGeneration(myJob)) {
                    delay(250)
                }
            } else if (!session.tryClaimGeneration(myJob)) {
                SchedulerDiagnostics.logAiWake(
                    target = "Workflow:$workflowName",
                    status = "skipped",
                    details = "Session $activeConversationId already generating",
                )
                return@runCatching WakeExecutionReport(
                    delivered = false,
                    replyText = "",
                    conversationId = activeConversationId.toString(),
                    messageId = null,
                )
            }

            val dynamicContext = dynamicContextProvider.build(settings)
            val historyMessages = filterInvalidToolMessages(
                conversation?.currentMessages ?: emptyList()
            ).limitContext(assistant.contextMessageSize)

            val pluginPromptInjections = pluginToolProvider.getPluginPromptInjections()
            val dynamicPluginPromptInjections = pluginPromptInjections.filter { injection ->
                injection.trimStart().startsWith("<plugin-context ")
            }
            val staticPluginPromptInjections = pluginPromptInjections.filterNot { injection ->
                injection.trimStart().startsWith("<plugin-context ")
            }

            val tools = buildTools(
                settings = settings,
                assistant = assistant,
                model = model,
                recentMessages = historyMessages,
                workspaceCwd = conversation?.workspaceCwd,
                conversationId = activeConversationId,
            )

            val systemPrompt = buildSystemPrompt(
                assistant = assistant,
                settings = settings,
                model = model,
                tools = tools,
                conversationSystemPrompt = conversation?.customSystemPrompt,
                staticPluginPromptInjections = staticPluginPromptInjections,
                dynamicPluginEnabled = dynamicPluginPromptInjections.isNotEmpty(),
                historyMessages = historyMessages,
            )

            val contextStr = buildWorkflowWakeUserInstruction(wakeContext)
            val userMessage = UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text(contextStr))
            )

            val inputTransformers = listOf(
                Base64ImageToLocalFileTransformer,
                DocumentAsPromptTransformer,
                OcrTransformer,
                PromptInjectionTransformer,
                TimeReminderTransformer,
            )
            val outputTransformers = listOf(
                ThinkTagTransformer,
                RegexOutputTransformer,
            )

            val processedUserMessages = listOf(userMessage).transforms(
                transformers = inputTransformers + templateTransformer,
                context = context,
                model = model,
                assistant = assistant,
                settings = settings,
                workspaceCwd = conversation?.workspaceCwd,
            )

            val messages = mergeAdjacentSameRoleMessages(
                buildInitialProactiveMessages(
                    systemPrompt = systemPrompt,
                    historyMessages = historyMessages,
                    dynamicContext = dynamicContext,
                    dynamicPluginContext = dynamicPluginPromptInjections.joinToString("\n\n"),
                    processedUserMessages = processedUserMessages,
                )
            )

            val providerSetting = model.findProvider(settings.providers)
                ?: error("No provider found for model ${model.modelId}")
            val providerImpl = providerManager.getProviderByType(providerSetting)

            val params = TextGenerationParams(
                model = model,
                temperature = assistant.temperature,
                topP = assistant.topP,
                maxTokens = assistant.maxTokens,
                tools = tools,
                reasoningLevel = assistant.reasoningLevel,
                customHeaders = buildList {
                    addAll(assistant.customHeaders)
                    addAll(model.customHeaders)
                },
                customBody = buildList {
                    addAll(assistant.customBodies)
                    addAll(model.customBodies)
                }
            )

            val (finalMessages, hasToolCalls, hasJumpFlag) = generateWithTools(
                context = context,
                conversationId = activeConversationId,
                providerImpl = providerImpl,
                providerSetting = providerSetting,
                initialMessages = messages,
                params = params,
                tools = tools,
                model = model,
                assistant = assistant,
                settings = settings,
                outputTransformers = outputTransformers,
                allowAllToolsAndPlugins = allowAllToolsAndPlugins,
            )

            val aiMessage = finalMessages.lastOrNull() ?: UIMessage(
                role = MessageRole.ASSISTANT,
                parts = emptyList()
            )

            val rawText = aiMessage.parts.filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }.trim()
            val replyText = rawText.replace("\\[JUMP]".toRegex(RegexOption.IGNORE_CASE), "").trim()

            if (rawText != replyText) {
                val cleanedAiMessage = aiMessage.copy(
                    parts = aiMessage.parts.map { part ->
                        if (part is UIMessagePart.Text) {
                            UIMessagePart.Text(part.text.replace("\\[JUMP]".toRegex(RegexOption.IGNORE_CASE), "").trim())
                        } else {
                            part
                        }
                    }
                )
                updateOrAppendAiMessage(activeConversationId, cleanedAiMessage)
            }

            val isSilent = isSilentProactiveReply(rawText)
            if (isSilent) {
                SchedulerDiagnostics.logAiWake(
                    target = "Workflow:$workflowName",
                    status = "skipped_silent",
                    details = "AI chose to reply [PASS]",
                )
                val aiId = aiMessage.id
                val session = chatService.getOrCreateSession(activeConversationId)
                session.saveMutex.withLock {
                    val conv = chatService.getConversationFlow(activeConversationId).value
                    chatService.updateConversation(
                        activeConversationId,
                        conv.copy(
                            messageNodes = conv.messageNodes.filterNot { node ->
                                node.messages.any { it.id == aiId }
                            }
                        )
                    )
                    chatService.saveConversation(activeConversationId, chatService.getConversationFlow(activeConversationId).value)
                }
            } else {
                saveProactiveMessage(activeConversationId, conversation, assistantUuid)
                // 同步保存 AI 工作流唤醒回复到外置记忆库（Supabase 等）
                try {
                    val externalMemoryConfigs = if (assistant.isExternalMemoryEnabled) {
                        settings.externalMemories.filter {
                            it.enabled && it.id in assistant.externalMemoryIds && it.autoSaveMessages
                        }
                    } else {
                        emptyList()
                    }
                    if (externalMemoryConfigs.isNotEmpty() && replyText.isNotBlank()) {
                        kotlinx.coroutines.coroutineScope {
                            externalMemoryConfigs.forEach { config ->
                                launch {
                                    runCatching {
                                        val service = me.rerere.rikkahub.data.service.ExternalMemoryService(config)
                                        service.saveMessage(
                                            assistantId = assistant.id.toString(),
                                            conversationId = activeConversationId.toString(),
                                            role = "assistant",
                                            content = replyText,
                                        )
                                    }.onFailure {
                                        Log.w(
                                            TAG,
                                            "Failed to save workflow wake message to external memory ${config.name}",
                                            it
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save workflow wake message to external memory", e)
                }
                showProactiveNotification(context, activeConversationId, assistant.name.ifBlank { "AI" }, replyText)
            }
            WakeExecutionReport(
                delivered = !isSilent,
                replyText = replyText,
                conversationId = activeConversationId.toString(),
                messageId = if (isSilent) null else aiMessage.id.toString(),
            )
        } finally {
            conversationId?.let { chatService.removeConversationReference(it) }
        }
    }

    private suspend fun buildTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        recentMessages: List<UIMessage>,
        workspaceCwd: String?,
        conversationId: Uuid,
        enableXiaohongshuBrowse: Boolean = false,
    ): List<Tool> {
        return toolSurfaceBuilder.build(
            assistant = assistant,
            settings = settings,
            invocationContext = ToolInvocationContext(
                callerAssistantId = assistant.id.toString(),
                callerConversationId = conversationId.toString(),
                callerAssistantName = assistant.name,
                userPatAction = settings.displaySetting.userPatAction,
                userPatSuffix = settings.displaySetting.userPatSuffix,
                imageGenerationSystemPrompt = assistant.imageGenerationSystemPrompt,
                imageGenerationModelId = settings.imageGenerationModelId.toString(),
                isHeadless = true,
                enableXiaohongshuBrowse = enableXiaohongshuBrowse,
                modelCanSeeImages = model.inputModalities.contains(
                    me.rerere.ai.provider.Modality.IMAGE,
                ),
            ),
            recentMessages = recentMessages,
            workspaceCwd = workspaceCwd,
        )
    }

    private suspend fun buildSystemPrompt(
        assistant: Assistant,
        settings: Settings,
        model: Model,
        tools: List<Tool>,
        conversationSystemPrompt: String?,
        staticPluginPromptInjections: List<String>,
        dynamicPluginEnabled: Boolean,
        historyMessages: List<UIMessage>,
        allowSkipReply: Boolean = assistant.allowSkipReply,
    ): String {
        val memories = if (assistant.isLegacySimpleMemoryEnabled) {
            if (assistant.useGlobalMemory) {
                memoryRepository.getGlobalMemories()
            } else {
                memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
            }
        } else emptyList()

        return SystemPromptBuilder.build(
            assistant = assistant,
            conversationSystemPrompt = conversationSystemPrompt,
            memories = memories,
            tools = tools,
            model = model,
            settings = settings,
            conversationRepository = conversationRepository,
            staticPluginPromptInjections = staticPluginPromptInjections,
            dynamicPluginEnabled = dynamicPluginEnabled,
            allowSkipReply = allowSkipReply,
            promptMessages = historyMessages,
        )
    }

    private suspend fun updateOrAppendAiMessage(
        conversationId: Uuid,
        aiMessage: UIMessage,
        assistantId: Uuid? = null,
    ) {
        val session = chatService.getOrCreateSession(conversationId)
        session.saveMutex.withLock {
            val sessionConversation = chatService.getConversationFlow(conversationId).value
            val storedConversation = if (sessionConversation.messageNodes.isEmpty()) {
                conversationRepository.getConversationById(conversationId)
            } else {
                null
            }
            val baseConversation = storedConversation ?: sessionConversation
            val conv = if (storedConversation == null && assistantId != null) {
                baseConversation.copy(assistantId = assistantId)
            } else {
                baseConversation
            }
            val now = java.time.Instant.now()
            val existingNodeIndex = conv.messageNodes.indexOfFirst { node ->
                node.messages.any { it.id == aiMessage.id }
            }
            val updated = if (existingNodeIndex >= 0) {
                val oldNode = conv.messageNodes[existingNodeIndex]
                val updatedNode = oldNode.copy(
                    messages = oldNode.messages.map {
                        if (it.id == aiMessage.id) aiMessage else it
                    }
                )
                conv.copy(
                    messageNodes = conv.messageNodes.toMutableList().apply {
                        this[existingNodeIndex] = updatedNode
                    },
                    updateAt = now,
                )
            } else {
                conv.copy(messageNodes = conv.messageNodes + aiMessage.toMessageNode(), updateAt = now)
            }
            chatService.updateConversation(conversationId, updated)
            chatService.saveConversation(conversationId, updated)
        }
    }

    private suspend fun generateWithTools(
        context: Context,
        conversationId: Uuid,
        providerImpl: Provider<ProviderSetting>,
        providerSetting: ProviderSetting,
        initialMessages: List<UIMessage>,
        params: TextGenerationParams,
        tools: List<Tool>,
        model: Model,
        assistant: Assistant,
        settings: Settings,
        outputTransformers: List<OutputMessageTransformer>,
        allowAllToolsAndPlugins: Boolean,
        persistToChat: Boolean = true,
        onGenerationProgress: (suspend (List<UIMessage>) -> Unit)? = null,
        onToolProgress: suspend (List<UIMessage>) -> Unit = {},
        onToolStarting: suspend (String, String, String) -> Unit = { _, _, _ -> },
        onToolCompleted: suspend (String, String, String, String) -> Unit = { _, _, _, _ -> },
        onToolUncertain: suspend (String, String, String, String) -> Unit = { _, _, _, _ -> },
        blockedUncertainToolCalls: Set<String> = emptySet(),
        finalizeAfterToolLimit: Boolean = false,
    ): Triple<List<UIMessage>, Boolean, Boolean> {
        var messages = initialMessages.toMutableList()
        var hasToolCalls = false
        var hasJumpFlag = false
        val transcript = if (onGenerationProgress != null) AutonomousWakeTranscript(initialMessages) else null
        suspend fun saveProgress(snapshot: List<UIMessage>) {
            if (onGenerationProgress == null || transcript == null) return
            onGenerationProgress(transcript.update(snapshot))
        }
        val uncertainCalls = blockedUncertainToolCalls.toMutableSet()

        for (step in 0 until (MAX_TOOL_STEPS + if (finalizeAfterToolLimit) 1 else 0)) {
            val finalizing = finalizeAfterToolLimit && step == MAX_TOOL_STEPS
            if (finalizing) {
                messages.add(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(
                    "本轮工具执行已结束，现在不要调用任何工具。根据已有工具结果输出要求的最终JSON，区分成功、失败和暂缓，未完成的事项如实记入 next_step。",
                ))))
            }
            messages = mergeAdjacentSameRoleMessages(messages).toMutableList()

            var streamMessages = messages.toList()
            var lastProgressAt = 0L
            try {
                providerImpl.streamText(
                    providerSetting = providerSetting,
                    messages = messages,
                    params = if (finalizing) params.copy(tools = emptyList()) else params
                ).collect { chunk ->
                    streamMessages = streamMessages.handleMessageChunk(chunk = chunk, model = model)
                    val now = if (onGenerationProgress != null) android.os.SystemClock.elapsedRealtime() else 0L
                    if (onGenerationProgress != null && now - lastProgressAt >= 1000L) {
                        saveProgress(streamMessages)
                        lastProgressAt = now
                    }
                    val currentAiMessage = streamMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                    if (currentAiMessage != null && persistToChat) {
                        updateOrAppendAiMessage(conversationId, currentAiMessage)
                    }
                }

            } finally {
                saveProgress(streamMessages)
            }

            messages = streamMessages.toMutableList()
            val aiMessage = streamMessages.lastOrNull() ?: break

            val rawAiText = aiMessage.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            if (rawAiText.contains("[JUMP]")) {
                hasJumpFlag = true
            }

            val processedMessage = listOf(aiMessage).transforms(
                transformers = outputTransformers,
                context = context,
                model = model,
                assistant = assistant,
                settings = settings
            ).first()
            messages[messages.lastIndex] = processedMessage
            saveProgress(messages)

            val toolCalls = processedMessage.getTools().filter { !it.isExecuted }

            if (toolCalls.isEmpty()) {
                val now = kotlin.time.Clock.System.now()
                val finalMessage = processedMessage.copy(
                    parts = processedMessage.parts.map { part ->
                        if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                            part.copy(finishedAt = now)
                        } else {
                            part
                        }
                    }
                )
                messages[messages.lastIndex] = finalMessage
                if (persistToChat) updateOrAppendAiMessage(conversationId, finalMessage)
                saveProgress(messages)
                break
            }

            if (finalizing) error("自主唤醒收尾阶段仍返回工具调用，未得到最终结果")
            hasToolCalls = true
            val executedTools = mutableListOf<UIMessagePart.Tool>()
            for (toolCall in toolCalls) {
                try {
                    val toolDef = tools.find { it.name == toolCall.toolName }
                    if (toolDef == null) {
                        executedTools.add(toolCall.copy(
                            output = listOf(UIMessagePart.Text("""{"error":"Tool not found"}"""))
                        ))
                        continue
                    }

                    if (!canExecuteProactiveTool(
                            needsApproval = toolDef.needsApproval,
                            allowAllToolsAndPlugins = allowAllToolsAndPlugins,
                            globallyAutoApproved = settings.autoApproveAllTools,
                        )
                    ) {
                        executedTools.add(toolCall.copy(
                            output = listOf(UIMessagePart.Text("""{"error":"Tool execution denied: requires user approval in proactive mode"}""")),
                            approvalState = ToolApprovalState.Denied("Proactive mode: requires approval")
                        ))
                    } else {
                        try {
                            val args = try {
                                json.parseToJsonElement(toolCall.input.ifBlank { "{}" })
                            } catch (e: Exception) {
                                JsonObject(emptyMap())
                            }
                            if (AutonomousToolReplayPolicy.shouldBlockReplay(toolDef.name, toolCall.input, uncertainCalls)) {
                                val blockedResult = """{"status":"result_uncertain","executed":false,"reason":"相同副作用步骤上轮结果不确定，已阻止原样重放；请先只读核实是否已生效。"}"""
                                onToolCompleted(toolCall.toolCallId, toolDef.name, toolCall.input, blockedResult)
                                executedTools.add(toolCall.copy(output = listOf(UIMessagePart.Text(blockedResult))))
                                continue
                            }
                            onToolStarting(toolCall.toolCallId, toolDef.name, toolCall.input)
                            val result = toolDef.execute(args)
                            val resultText = result.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                            onToolCompleted(toolCall.toolCallId, toolDef.name, toolCall.input, resultText)
                            executedTools.add(toolCall.copy(
                                output = result,
                                approvalState = if (toolDef.needsApproval) {
                                    ToolApprovalState.Approved
                                } else {
                                    toolCall.approvalState
                                },
                            ))
                        } catch (e: Exception) {
                            onToolUncertain(
                                toolCall.toolCallId,
                                toolDef.name,
                                toolCall.input,
                                e.message ?: e::class.simpleName.orEmpty(),
                            )
                            uncertainCalls += AutonomousToolReplayPolicy.key(toolDef.name, toolCall.input)
                            val errorJson = buildJsonObject {
                                put("error", e.message ?: e::class.simpleName.orEmpty())
                                put("status", "result_uncertain")
                            }.toString()
                            executedTools.add(toolCall.copy(
                                output = listOf(UIMessagePart.Text(errorJson))
                            ))
                        }
                    }
                } finally {
                    val progressMessage = processedMessage.copy(parts = processedMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            executedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else part
                    })
                    saveProgress(messages.dropLast(1) + progressMessage)
                }
            }

            val updatedParts = processedMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else {
                    part
                }
            }
            val updatedMessage = processedMessage.copy(parts = updatedParts)
            messages[messages.lastIndex] = updatedMessage
            if (persistToChat) updateOrAppendAiMessage(conversationId, updatedMessage)
            saveProgress(messages)
            onToolProgress(messages)
        }

        return Triple(messages, hasToolCalls, hasJumpFlag)
    }

    private suspend fun saveProactiveMessage(
        conversationId: Uuid,
        existingConversation: Conversation?,
        assistantUuid: Uuid,
    ) {
        val session = chatService.getOrCreateSession(conversationId)
        session.saveMutex.withLock {
            if (existingConversation == null) {
                val exists = conversationRepository.existsConversationById(conversationId)
                if (!exists) {
                    val currentConv = chatService.getConversationFlow(conversationId).value
                    conversationRepository.insertConversation(currentConv.copy(assistantId = assistantUuid))
                }
            }
            chatService.saveConversation(
                conversationId,
                chatService.getConversationFlow(conversationId).value
            )
        }
    }

    private fun showProactiveNotification(
        context: Context,
        conversationId: Uuid,
        senderName: String,
        message: String,
    ) {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val shown = context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 20002
        ) {
            title = senderName
            content = message.take(100)
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = pendingIntent
            useBigTextStyle = true
        }

        SchedulerDiagnostics.logNotification(
            target = "WorkflowWake",
            shown = shown,
            details = "sender='$senderName', content='${message.take(60)}...'",
        )
    }
}
