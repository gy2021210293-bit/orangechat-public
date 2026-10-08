/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.tools

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.memory.v1.ExperienceAuthority
import me.rerere.rikkahub.data.memory.v1.MemoryConstants
import me.rerere.rikkahub.data.memory.v1.MemoryRepositoryV1
import me.rerere.rikkahub.data.memory.v1.RevisionConflictException
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity
import me.rerere.rikkahub.data.memory.v1.db.MemorySourceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.ai.userPromptName

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.memory.v1.MemoryEmbeddingService
import me.rerere.rikkahub.data.model.MemoryMode
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

private const val MAX_CONTENT_LENGTH = 1000

/**
 * 构建本地长期记忆 V1 工具集：
 * - memory_search: 显式全库检索
 * - memory_manage: 记忆的新增、细化、修正/演进、软归档与恢复
 */
fun buildMemoryV1Tools(
    repository: MemoryRepositoryV1,
    assistant: Assistant,
    callerConversationId: String? = null,
    embeddingService: MemoryEmbeddingService? = null,
    settings: Settings? = null,
    conversationRepository: ConversationRepository? = null,
    conversationProvider: (suspend (Uuid) -> Conversation?)? = null,
    json: Json = Json { ignoreUnknownKeys = true },
): List<Tool> {
    val userName = userPromptName(settings?.displaySetting?.userNickname.orEmpty())
    val convProvider: (suspend (Uuid) -> Conversation?)? = conversationProvider ?: conversationRepository?.let { repo -> { repo.getConversationById(it) } }
    val (defaultOwnerType, defaultOwnerId) = if (assistant.useGlobalMemory) {
        MemoryConstants.OWNER_TYPE_GLOBAL to MemoryConstants.GLOBAL_OWNER_ID
    } else {
        MemoryConstants.OWNER_TYPE_ASSISTANT to assistant.id.toString()
    }

    fun triggerSyncEmbedding(card: MemoryCardEntity) {
        if (embeddingService == null || settings == null) return
        if (card.ownerType == MemoryConstants.OWNER_TYPE_GLOBAL) {
            val modelIds = settings.assistants
                .filter { it.effectiveMemoryMode == MemoryMode.LOCAL_V1 && it.useGlobalMemory && it.memoryEmbeddingModelId != null }
                .mapNotNull { it.memoryEmbeddingModelId }
                .distinct()
            modelIds.forEach { modelId ->
                embeddingService.enqueueSyncCard(card, modelId, settings)
            }
        } else {
            val modelId = assistant.memoryEmbeddingModelId
            if (modelId != null) {
                embeddingService.enqueueSyncCard(card, modelId, settings)
            }
        }
    }

    fun errorResponse(code: String, message: String): List<UIMessagePart> {
        val payload = buildJsonObject {
            put("success", false)
            put("error", code)
            put("message", message)
        }
        return listOf(UIMessagePart.Text(payload.toString()))
    }

    fun revisionConflictResponse(e: RevisionConflictException): List<UIMessagePart> {
        val payload = buildJsonObject {
            put("success", false)
            put("error", "REVISION_CONFLICT")
            put(
                "message",
                "Revision conflict on card '${e.cardId}': expected revision ${e.expectedRevision}, but current revision is ${e.actualRevision}. Please run memory_search again to get the latest state."
            )
            put("card_id", e.cardId)
            put("expected_revision", e.expectedRevision)
            put("current_revision", e.actualRevision)
        }
        return listOf(UIMessagePart.Text(payload.toString()))
    }

    suspend fun checkCardAuth(cardId: String): Pair<MemoryCardEntity?, JsonObject?> {
        val card = runCatching { repository.getCardById(cardId) }.getOrNull()
        if (card == null) {
            return null to buildJsonObject {
                put("success", false)
                put("error", "CARD_NOT_FOUND")
                put("message", "Memory card with id '$cardId' was not found.")
            }
        }
        val isOwned = if (card.ownerType == MemoryConstants.OWNER_TYPE_GLOBAL) {
            assistant.useGlobalMemory
        } else {
            card.ownerType == defaultOwnerType && card.ownerId == defaultOwnerId
        }
        if (!isOwned) {
            return null to buildJsonObject {
                put("success", false)
                put("error", "UNAUTHORIZED")
                put("message", "Memory card '$cardId' does not belong to the current assistant.")
            }
        }
        return card to null
    }

    val searchTool = Tool(
        name = "memory_search",
        description = """
            搜索我的长期记忆（基于本地全文关键词检索），用于回忆我的感受、思考、自我认识、与${userName}的共同经历、关系理解、约定，以及我对${userName}形成的重要认识。
            在执行 memory_manage 之前，建议先通过 memory_search 查找是否已有相关记忆，以避免重复添加同类卡片。
            参数：
            - query (必填): 搜索关键词或短语
            - limit (可选): 返回的最大记忆条数，范围 1~20，默认 8
            - scope (可选): 搜索范围 ("assistant" 或 "global")
            - include_archived (可选): 是否包含已归档/失效的记忆，默认为 false
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "搜索关键词或短语")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "返回结果上限 (1-20)，默认 8")
                    })
                    put("scope", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add(MemoryConstants.OWNER_TYPE_ASSISTANT)
                                add(MemoryConstants.OWNER_TYPE_GLOBAL)
                            }
                        )
                        put("description", "记忆作用域：assistant (专属记忆) 或 global (全局共享记忆)")
                    })
                    put("include_archived", buildJsonObject {
                        put("type", "boolean")
                        put("description", "是否包含已归档的记忆，默认为 false")
                    })
                },
                required = listOf("query")
            )
        },
        execute = execute@ { element ->
            val params = runCatching { element.jsonObject }.getOrNull()
                ?: return@execute errorResponse("INVALID_PARAMS", "Parameters must be a JSON object")

            val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim()
            if (query.isNullOrEmpty()) {
                return@execute errorResponse("MISSING_QUERY", "'query' parameter is required and cannot be empty")
            }

            val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 8).coerceIn(1, 20)
            val includeArchived = params["include_archived"]?.jsonPrimitive?.booleanOrNull ?: false
            val scope = params["scope"]?.jsonPrimitive?.contentOrNull?.trim()

            if (scope == MemoryConstants.OWNER_TYPE_GLOBAL && !assistant.useGlobalMemory) {
                return@execute errorResponse(
                    "UNAUTHORIZED_SCOPE",
                    "Global memory scope is disabled for this assistant. Only 'assistant' scope is allowed."
                )
            }
            if (scope != null && scope != MemoryConstants.OWNER_TYPE_GLOBAL && scope != MemoryConstants.OWNER_TYPE_ASSISTANT) {
                return@execute errorResponse(
                    "INVALID_SCOPE",
                    "Unknown scope '$scope'. Allowed values are: '${MemoryConstants.OWNER_TYPE_ASSISTANT}', '${MemoryConstants.OWNER_TYPE_GLOBAL}'"
                )
            }

            val (searchOwnerType, searchOwnerId) = if (scope == MemoryConstants.OWNER_TYPE_GLOBAL) {
                MemoryConstants.OWNER_TYPE_GLOBAL to MemoryConstants.GLOBAL_OWNER_ID
            } else if (scope == MemoryConstants.OWNER_TYPE_ASSISTANT) {
                MemoryConstants.OWNER_TYPE_ASSISTANT to assistant.id.toString()
            } else {
                defaultOwnerType to defaultOwnerId
            }

            val statusList = if (includeArchived) {
                listOf(
                    MemoryConstants.STATUS_ACTIVE,
                    MemoryConstants.STATUS_REVIEW,
                    MemoryConstants.STATUS_SUPERSEDED,
                    MemoryConstants.STATUS_ARCHIVED
                )
            } else {
                listOf(MemoryConstants.STATUS_ACTIVE, MemoryConstants.STATUS_REVIEW)
            }

            val cards = repository.search(
                query = query,
                ownerType = searchOwnerType,
                ownerId = searchOwnerId,
                statusList = statusList,
                limit = limit,
            )

            if (cards.isNotEmpty()) {
                runCatching {
                    repository.recordCardRecalls(cards.map { it.id })
                }
            }

            val sourceCardIds = repository.getMemoryIdsWithSources(cards.map { it.id })

            val memoriesJson = buildJsonArray {
                cards.forEach { card ->
                    add(buildJsonObject {
                        put("id", card.id)
                        put("status", card.status)
                        put("revision", card.revision)
                        put("category", card.category)
                        card.factKey?.let { put("fact_key", it) }
                        put("content", card.content)
                        put("updated_at", card.updatedAt)
                        put("has_source", card.id in sourceCardIds)
                    })
                }
            }

            val resultObj = buildJsonObject {
                put("success", true)
                put("count", cards.size)
                put("memories", memoriesJson)
            }
            listOf(UIMessagePart.Text(resultObj.toString()))
        }
    )

    val enableExperience = assistant.enableExperienceMemory
    val experienceActionDesc = if (enableExperience) {
        "\n- add_experience: 为已有长期记忆附加/演进第一人称经验坐标 (E 轴)。必须提供 id (cardId), experience_meaning；可选 response_tendency, e_initial_priority, valence, arousal, tension, growth_delta"
    } else ""

    val allowedActions = buildList {
        add("create")
        add("update")
        add("supersede")
        add("archive")
        add("restore")
        add("create_timeline")
        add("add_to_timeline")
        if (enableExperience) {
            add("add_experience")
        }
    }

    val manageTool = Tool(
        name = "memory_manage",
        description = """
            管理我的长期记忆。用于保存我值得长期记住的感受, 思考, 自我认识, 关系理解, 约定, 共同经历, 以及我对${userName}形成的重要认识。记忆正文以第一人称“我”书写。
            注意：
            1. 永久删除已被禁止（保护${userName}隐私与记忆资产）。若某条记忆不再适用, 请使用 action='archive' 进行软归档。
            2. 修改或归档已有记忆时, 必须先通过 memory_search 获取该卡片当前的 expected_revision（乐观锁版本号）, 防止并发冲突。
            3. 若事实被明确纠正或演化（例如搬家, 换工作, 口味改变）, 请使用 action='supersede', 将废弃旧卡片并自动创建新卡片。
            4. category='self' 仅限记录我在长期相处中逐渐形成的自我感受, 喜好, 性格与回应倾向。self 不是用户资料, 不是关系画像, 不是一次性情绪, 也不是任务执行守则。禁止为了塑造理想人格而主动创建 self。即使用户明确要求形成自我认识, 创建后也保持待复核 (REVIEW), 需人工确认才能成熟。
            支持的 action：
            - create: 新增记忆。必须提供 content；可选 category, fact_key, reason
            - update: 补充/细化已有记忆。必须提供 id, expected_revision, content；可选 category, fact_key, reason
            - supersede: 替代旧事实。必须提供 id, expected_revision, content；可选 category, fact_key, reason
            - archive: 软归档记忆。必须提供 id, expected_revision；可选 reason
            - restore: 恢复已归档记忆。必须提供 id, expected_revision；可选 reason
            - create_timeline: 创建新的时间线。必须提供 timeline_title；可选 reason 作为时间线概述
            - add_to_timeline: 将已有记忆卡片关联到时间线。必须提供 id (cardId), 以及 timeline_title 或 timeline_id$experienceActionDesc
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                allowedActions.forEach { add(it) }
                            }
                        )
                        put("description", "执行的操作：${allowedActions.joinToString(", ")}")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "目标记忆卡片 ID (UUID)。update/supersede/archive/restore/add_to_timeline/add_experience 时必填")
                    })
                    put("expected_revision", buildJsonObject {
                        put("type", "integer")
                        put("description", "预期的卡片当前版本号 (revision)。update/supersede/archive/restore 时必填")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "以第一人称“我”书写的长期记忆。内容应独立, 完整, 有持续价值, 建议不超过300字。create/update/supersede 时必填")
                    })
                    put("category", buildJsonObject {
                        put("type", "string")
                        put("description", "记忆分类（可选）：preference(偏好习惯), profile(人物画像/基本信息), self(稳定自我认识), relationship(关系与约定), plan(规划计划), experience(共同经历事件), boundary(边界与禁忌), other(其他)")
                    })
                    put("fact_key", buildJsonObject {
                        put("type", "string")
                        put("description", "稳定事实键（可选）。用于标记同一演化事实。例如 self.*（自身倾向）, person.preference.*, relationship.*。同 key 的旧卡片会被自动演化替换")
                    })
                    put("timeline_title", buildJsonObject {
                        put("type", "string")
                        put("description", "时间线标题。create_timeline 时必填；add_to_timeline 时可作为新时间线标题或已有标题索引；create/update 时可选关联")
                    })
                    put("timeline_id", buildJsonObject {
                        put("type", "string")
                        put("description", "已有时间线 ID (UUID)。add_to_timeline 时可选提供；create/update 时可选关联")
                    })
                    put("confidence", buildJsonObject {
                        put("type", "number")
                        put("description", "置信度 (0.0..1.0), 默认 1.0。注意：category='self' 时系统将自动上限至 0.60 待复核")
                    })
                    put("importance", buildJsonObject {
                        put("type", "number")
                        put("description", "重要度 (0.0..1.0), 默认 0.5")
                    })
                    put("reason", buildJsonObject {
                        put("type", "string")
                        put("description", "操作理由或变更背景（可选, 建议提供以供审计溯源）")
                    })
                    if (enableExperience) {
                        put("experience_meaning", buildJsonObject {
                            put("type", "string")
                            put("description", "以第一人称“我”书写的主观经历意义理解（必须包含“我”, 如：在${userName}难过时, 我先陪伴比出建议更重要）。add_experience 必填, create 可选")
                        })
                        put("response_tendency", buildJsonObject {
                            put("type", "string")
                            put("description", "回应倾向标签：COMFORT, ENGAGE, WITHDRAW, ALERT, GIVE_SPACE, REPAIR, CELEBRATE, CLARIFY, OBSERVE 等。")
                        })
                        put("e_initial_priority", buildJsonObject {
                            put("type", "integer")
                            put("description", "初始优先级 (1..100), 默认 50。")
                        })
                        put("valence", buildJsonObject {
                            put("type", "number")
                            put("description", "情绪愉悦度 (-1.0..1.0)")
                        })
                        put("arousal", buildJsonObject {
                            put("type", "number")
                            put("description", "情绪唤醒度 (0.0..1.0)")
                        })
                        put("tension", buildJsonObject {
                            put("type", "number")
                            put("description", "关系紧张度 (0.0..1.0)")
                        })
                        put("growth_delta", buildJsonObject {
                            put("type", "string")
                            put("description", "成长变化：STATIC, GROWTH, SETBACK, 默认 STATIC")
                        })
                        put("source_episode_key", buildJsonObject {
                            put("type", "string")
                            put("description", "来源 episode 引用键（可选）")
                        })
                    }
                },
                required = listOf("action")
            )
        },
        execute = execute@ { element ->
            val params = runCatching { element.jsonObject }.getOrNull()
                ?: return@execute errorResponse("INVALID_PARAMS", "Parameters must be a JSON object")

            val action = params["action"]?.jsonPrimitive?.contentOrNull?.trim()
            if (action.isNullOrEmpty()) {
                return@execute errorResponse("MISSING_ACTION", "'action' is required")
            }

            if (action.equals("delete", ignoreCase = true) ||
                action.equals("drop", ignoreCase = true) ||
                action.equals("remove", ignoreCase = true)
            ) {
                return@execute errorResponse(
                    "PERMANENT_DELETE_FORBIDDEN",
                    "Permanent deletion is prohibited for AI tools to protect memories. Use action='archive' to soft-delete memories."
                )
            }

            val validActions = allowedActions
            if (action !in validActions) {
                if (action == "add_experience" && !enableExperience) {
                    return@execute errorResponse(
                        "EXPERIENCE_DISABLED",
                        "Experience memory (E-axis) is disabled for this assistant."
                    )
                }
                return@execute errorResponse(
                    "UNKNOWN_ACTION",
                    "Unknown action '$action'. Allowed actions are: ${validActions.joinToString(", ")}"
                )
            }

            val id = params["id"]?.jsonPrimitive?.contentOrNull?.trim()
            val expectedRevision = params["expected_revision"]?.jsonPrimitive?.intOrNull
            val content = params["content"]?.jsonPrimitive?.contentOrNull?.trim()
            val category = params["category"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            val factKey = params["fact_key"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            val timelineTitle = params["timeline_title"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            val timelineId = params["timeline_id"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            val confidence = params["confidence"]?.jsonPrimitive?.floatOrNull?.coerceIn(0f, 1f)
            val importance = params["importance"]?.jsonPrimitive?.floatOrNull?.coerceIn(0f, 1f)
            val reason = params["reason"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            val expMeaning = params["experience_meaning"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            val expTendency = params["response_tendency"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
            val expPriority = params["e_initial_priority"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100) ?: 50
            val expValence = params["valence"]?.jsonPrimitive?.floatOrNull
            val expArousal = params["arousal"]?.jsonPrimitive?.floatOrNull
            val expTension = params["tension"]?.jsonPrimitive?.floatOrNull
            val expGrowthDelta = params["growth_delta"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null } ?: "STATIC"
            val rawEpisodeKey = params["source_episode_key"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }

            val primaryAuthority = ExperienceAuthority.PrimaryAgent(
                assistantId = assistant.id.toString(),
                conversationId = callerConversationId ?: "unknown",
            )

            suspend fun resolveRealEpisodeAndEnsureEvidence(
                card: MemoryCardEntity,
                rawKey: String?,
            ): String? {
                val convUuid = callerConversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                val conv = convUuid?.let { convProvider?.invoke(it) }
                val lastRealMsg = conv?.currentMessages?.lastOrNull {
                    it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT
                }

                val effectiveEpisodeKey: String? = if (!rawKey.isNullOrBlank() &&
                    !rawKey.startsWith("consolidation_") &&
                    !rawKey.startsWith("proposal:") &&
                    !rawKey.startsWith("proposal_")
                ) {
                    rawKey
                } else if (callerConversationId != null && lastRealMsg != null) {
                    "conv:$callerConversationId:msg:${lastRealMsg.id}"
                } else {
                    null
                }

                if (effectiveEpisodeKey == null) return null

                // 确保目标卡片拥有真实经历的 memory_sources 证据
                val existingSources = repository.getSources(card.id)
                val hasEvidence = existingSources.any { src ->
                    (src.role.equals("user", ignoreCase = true) || src.role.equals("assistant", ignoreCase = true)) &&
                        (!src.messageId.isNullOrBlank() || !src.conversationId.isNullOrBlank() || !src.sourceHash.isNullOrBlank())
                }
                if (!hasEvidence && callerConversationId != null && lastRealMsg != null) {
                    val sourceEntity = MemorySourceEntity(
                        id = Uuid.random().toString(),
                        memoryId = card.id,
                        conversationId = callerConversationId,
                        nodeId = conv.getMessageNodeByMessageId(lastRealMsg.id)?.id?.toString(),
                        messageId = lastRealMsg.id.toString(),
                        role = lastRealMsg.role.name.lowercase(),
                        messageCreatedAt = lastRealMsg.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds(),
                        sourceKind = "TOOL_EXPERIENCE",
                        sourceHash = effectiveEpisodeKey,
                        createdAt = System.currentTimeMillis(),
                    )
                    runCatching { repository.addSource(sourceEntity) }
                }

                return effectiveEpisodeKey
            }

            suspend fun resolveOrCreateTimeline(title: String?, tid: String?): String? {
                if (!tid.isNullOrBlank()) {
                    val tl = repository.getTimeline(tid)
                    if (tl != null) return tl.id
                }
                if (!title.isNullOrBlank()) {
                    val existing = repository.getTimelineByTitle(defaultOwnerType, defaultOwnerId, title)
                    if (existing != null) return existing.id
                    val created = repository.createTimeline(
                        ownerType = defaultOwnerType,
                        ownerId = defaultOwnerId,
                        title = title,
                        summary = reason ?: "",
                    )
                    return created.id
                }
                return null
            }

            fun checkRevision(card: MemoryCardEntity, expected: Int?): JsonObject? {
                if (expected == null || expected < 1) {
                    return buildJsonObject {
                        put("success", false)
                        put("error", "MISSING_EXPECTED_REVISION")
                        put("message", "'expected_revision' is required for action '$action' and must be >= 1.")
                    }
                }
                if (card.revision != expected) {
                    return buildJsonObject {
                        put("success", false)
                        put("error", "REVISION_CONFLICT")
                        put(
                            "message",
                            "Revision conflict on card '${card.id}': expected revision $expected, but current revision is ${card.revision}. Please run memory_search again to get the latest state."
                        )
                        put("card_id", card.id)
                        put("expected_revision", expected)
                        put("current_revision", card.revision)
                    }
                }
                return null
            }

            when (action) {
                "create" -> {
                    if (content.isNullOrEmpty()) {
                        return@execute errorResponse("MISSING_CONTENT", "'content' is required for creating a memory")
                    }
                    if (content.length > MAX_CONTENT_LENGTH) {
                        return@execute errorResponse("CONTENT_TOO_LONG", "Memory content exceeds maximum length ($MAX_CONTENT_LENGTH)")
                    }

                    val targetCategory = category ?: MemoryConstants.CATEGORY_OTHER
                    val isSelf = targetCategory == MemoryConstants.CATEGORY_SELF
                    val initialStatus = if (isSelf) MemoryConstants.STATUS_REVIEW else MemoryConstants.STATUS_ACTIVE
                    val initialConfidence = if (isSelf) minOf(confidence ?: 1.0f, 0.60f) else (confidence ?: 1.0f)

                    val card = try {
                        repository.createCard(
                            ownerType = defaultOwnerType,
                            ownerId = defaultOwnerId,
                            content = content,
                            category = targetCategory,
                            factKey = factKey,
                            confidence = initialConfidence,
                            importance = importance ?: 0.5f,
                            status = initialStatus,
                            alwaysInject = false,
                            actor = MemoryConstants.ACTOR_TOOL,
                            reason = reason,
                            sourceConversationId = callerConversationId,
                            sourceKind = "TOOL",
                        )
                    } catch (e: Exception) {
                        return@execute errorResponse("CREATE_FAILED", e.message ?: "Failed to create memory card")
                    }

                    triggerSyncEmbedding(card)

                    val resolvedTlId = resolveOrCreateTimeline(timelineTitle, timelineId)
                    if (resolvedTlId != null) {
                        runCatching { repository.addCardToTimeline(resolvedTlId, card.id) }
                    }

                    var createdECoordId: Long? = null
                    if (enableExperience && !expMeaning.isNullOrBlank() && expMeaning.contains("我")) {
                        val resolvedEpisodeKey = resolveRealEpisodeAndEnsureEvidence(card, rawEpisodeKey)
                        if (resolvedEpisodeKey != null) {
                            runCatching {
                                val eCoord = repository.addExperienceCoordinate(
                                    memoryCardId = card.id,
                                    ownerType = defaultOwnerType,
                                    ownerId = defaultOwnerId,
                                    meaning = expMeaning,
                                    eInitialPriority = expPriority,
                                    eAuthoredBy = primaryAuthority.authorIdentifier,
                                    sourceEpisodeKey = resolvedEpisodeKey,
                                    rubricVersion = "v1",
                                    responseTendency = MemoryExperienceCoordinateEntity.normalizeTendency(expTendency),
                                    valence = expValence,
                                    arousal = expArousal,
                                    tension = expTension,
                                    growthDelta = expGrowthDelta,
                                    authority = primaryAuthority,
                                )
                                createdECoordId = eCoord.id
                            }
                        }
                    }

                    val result = buildJsonObject {
                        put("success", true)
                        put("action", "create")
                        put("card", buildJsonObject {
                            put("id", card.id)
                            put("revision", card.revision)
                            put("status", card.status)
                            put("category", card.category)
                            card.factKey?.let { put("fact_key", it) }
                            put("content", card.content)
                            put("updated_at", card.updatedAt)
                            resolvedTlId?.let { put("timeline_id", it) }
                            createdECoordId?.let { put("experience_id", it) }
                        })
                    }
                    listOf(UIMessagePart.Text(result.toString()))
                }

                "update" -> {
                    if (id.isNullOrEmpty()) {
                        return@execute errorResponse("MISSING_ID", "'id' is required for updating a memory")
                    }
                    if (content.isNullOrEmpty()) {
                        return@execute errorResponse("MISSING_CONTENT", "'content' is required for updating a memory")
                    }
                    if (content.length > MAX_CONTENT_LENGTH) {
                        return@execute errorResponse("CONTENT_TOO_LONG", "Memory content exceeds maximum length ($MAX_CONTENT_LENGTH)")
                    }

                    val (card, authError) = checkCardAuth(id)
                    if (authError != null) return@execute listOf(UIMessagePart.Text(authError.toString()))

                    val revisionError = checkRevision(card!!, expectedRevision)
                    if (revisionError != null) return@execute listOf(UIMessagePart.Text(revisionError.toString()))

                    val updated = try {
                        repository.updateCard(
                            id = id,
                            expectedRevision = expectedRevision!!,
                            content = content,
                            category = category,
                            factKey = factKey,
                            confidence = confidence,
                            importance = importance,
                            actor = MemoryConstants.ACTOR_TOOL,
                            reason = reason,
                            sourceConversationId = callerConversationId,
                            sourceKind = "TOOL",
                        )
                    } catch (e: RevisionConflictException) {
                        return@execute revisionConflictResponse(e)
                    } catch (e: Exception) {
                        return@execute errorResponse("UPDATE_FAILED", e.message ?: "Failed to update memory card")
                    }

                    triggerSyncEmbedding(updated)

                    val resolvedTlId = resolveOrCreateTimeline(timelineTitle, timelineId)
                    if (resolvedTlId != null) {
                        runCatching { repository.addCardToTimeline(resolvedTlId, updated.id) }
                    }

                    val result = buildJsonObject {
                        put("success", true)
                        put("action", "update")
                        put("card", buildJsonObject {
                            put("id", updated.id)
                            put("revision", updated.revision)
                            put("status", updated.status)
                            put("category", updated.category)
                            updated.factKey?.let { put("fact_key", it) }
                            put("content", updated.content)
                            put("updated_at", updated.updatedAt)
                            resolvedTlId?.let { put("timeline_id", it) }
                        })
                    }
                    listOf(UIMessagePart.Text(result.toString()))
                }

                "supersede" -> {
                    if (id.isNullOrEmpty()) {
                        return@execute errorResponse("MISSING_ID", "'id' is required for superseding a memory")
                    }
                    if (content.isNullOrEmpty()) {
                        return@execute errorResponse("MISSING_CONTENT", "'content' is required for superseding a memory")
                    }
                    if (content.length > MAX_CONTENT_LENGTH) {
                        return@execute errorResponse("CONTENT_TOO_LONG", "Memory content exceeds maximum length ($MAX_CONTENT_LENGTH)")
                    }

                    val (card, authError) = checkCardAuth(id)
                    if (authError != null) return@execute listOf(UIMessagePart.Text(authError.toString()))

                    val revisionError = checkRevision(card!!, expectedRevision)
                    if (revisionError != null) return@execute listOf(UIMessagePart.Text(revisionError.toString()))

                    val (newCard, supersededCard) = try {
                        repository.supersedeCard(
                            oldCardId = id,
                            expectedRevision = expectedRevision!!,
                            newContent = content,
                            category = category,
                            factKey = factKey,
                            confidence = confidence,
                            importance = importance,
                            actor = MemoryConstants.ACTOR_TOOL,
                            reason = reason,
                            sourceConversationId = callerConversationId,
                            sourceKind = "TOOL",
                        )
                    } catch (e: RevisionConflictException) {
                        return@execute revisionConflictResponse(e)
                    } catch (e: Exception) {
                        return@execute errorResponse("SUPERSEDE_FAILED", e.message ?: "Failed to supersede memory card")
                    }

                    triggerSyncEmbedding(newCard)
                    embeddingService?.cancelSyncJob(id)

                    val result = buildJsonObject {
                        put("success", true)
                        put("action", "supersede")
                        put("new_card", buildJsonObject {
                            put("id", newCard.id)
                            put("revision", newCard.revision)
                            put("status", newCard.status)
                            put("category", newCard.category)
                            newCard.factKey?.let { put("fact_key", it) }
                            put("content", newCard.content)
                            put("updated_at", newCard.updatedAt)
                        })
                        put("superseded_card", buildJsonObject {
                            put("id", supersededCard.id)
                            put("revision", supersededCard.revision)
                            put("status", supersededCard.status)
                        })
                    }
                    listOf(UIMessagePart.Text(result.toString()))
                }

                "archive" -> {
                    if (id.isNullOrEmpty()) {
                        return@execute errorResponse("MISSING_ID", "'id' is required for archiving a memory")
                    }

                    val (card, authError) = checkCardAuth(id)
                    if (authError != null) return@execute listOf(UIMessagePart.Text(authError.toString()))

                    val revisionError = checkRevision(card!!, expectedRevision)
                    if (revisionError != null) return@execute listOf(UIMessagePart.Text(revisionError.toString()))

                    val updated = try {
                        val archived = repository.archiveCard(
                            id = id,
                            expectedRevision = expectedRevision!!,
                            actor = MemoryConstants.ACTOR_TOOL,
                            reason = reason,
                        )
                        embeddingService?.cancelSyncJob(id)
                        archived
                    } catch (e: RevisionConflictException) {
                        return@execute revisionConflictResponse(e)
                    } catch (e: Exception) {
                        return@execute errorResponse("ARCHIVE_FAILED", e.message ?: "Failed to archive memory card")
                    }

                    val result = buildJsonObject {
                        put("success", true)
                        put("action", "archive")
                        put("card", buildJsonObject {
                            put("id", updated.id)
                            put("revision", updated.revision)
                            put("status", updated.status)
                        })
                    }
                    listOf(UIMessagePart.Text(result.toString()))
                }

                "restore" -> {
                    if (id.isNullOrEmpty()) {
                        return@execute errorResponse("MISSING_ID", "'id' is required for restoring a memory")
                    }

                    val (card, authError) = checkCardAuth(id)
                    if (authError != null) return@execute listOf(UIMessagePart.Text(authError.toString()))

                    val revisionError = checkRevision(card!!, expectedRevision)
                    if (revisionError != null) return@execute listOf(UIMessagePart.Text(revisionError.toString()))

                    val updated = try {
                        repository.restoreCard(
                            id = id,
                            expectedRevision = expectedRevision!!,
                            actor = MemoryConstants.ACTOR_TOOL,
                            reason = reason,
                        )
                    } catch (e: RevisionConflictException) {
                        return@execute revisionConflictResponse(e)
                    } catch (e: Exception) {
                        return@execute errorResponse("RESTORE_FAILED", e.message ?: "Failed to restore memory card")
                    }

                    triggerSyncEmbedding(updated)

                    val result = buildJsonObject {
                        put("success", true)
                        put("action", "restore")
                        put("card", buildJsonObject {
                            put("id", updated.id)
                            put("revision", updated.revision)
                            put("status", updated.status)
                        })
                    }
                    listOf(UIMessagePart.Text(result.toString()))
                }

                "create_timeline" -> {
                    if (timelineTitle.isNullOrBlank()) {
                        return@execute errorResponse("MISSING_TIMELINE_TITLE", "'timeline_title' is required for creating a timeline")
                    }
                    val existing = repository.getTimelineByTitle(defaultOwnerType, defaultOwnerId, timelineTitle)
                    val tl = existing ?: repository.createTimeline(
                        ownerType = defaultOwnerType,
                        ownerId = defaultOwnerId,
                        title = timelineTitle,
                        summary = reason ?: "",
                    )
                    val result = buildJsonObject {
                        put("success", true)
                        put("action", "create_timeline")
                        put("timeline", buildJsonObject {
                            put("id", tl.id)
                            put("title", tl.title)
                            put("summary", tl.summary)
                            put("status", tl.status)
                        })
                    }
                    listOf(UIMessagePart.Text(result.toString()))
                }

                "add_to_timeline" -> {
                    if (id.isNullOrBlank()) {
                        return@execute errorResponse("MISSING_ID", "'id' (cardId) is required for adding to timeline")
                    }
                    val targetTlId = resolveOrCreateTimeline(timelineTitle, timelineId)
                        ?: return@execute errorResponse("MISSING_TIMELINE", "Either 'timeline_title' or 'timeline_id' must be provided")
                    val member = try {
                        repository.addCardToTimeline(targetTlId, id)
                    } catch (e: Exception) {
                        return@execute errorResponse("ADD_TO_TIMELINE_FAILED", e.message ?: "Failed to add card to timeline")
                    }
                    val result = buildJsonObject {
                        put("success", true)
                        put("action", "add_to_timeline")
                        put("card_id", id)
                        put("timeline_id", targetTlId)
                        put("member_id", member.id)
                    }
                    listOf(UIMessagePart.Text(result.toString()))
                }

                "add_experience" -> {
                    if (!enableExperience) {
                        return@execute errorResponse("EXPERIENCE_DISABLED", "Experience memory (E-axis) is not enabled for this assistant.")
                    }
                    if (id.isNullOrEmpty()) {
                        return@execute errorResponse("MISSING_ID", "'id' (cardId) is required for adding experience")
                    }
                    if (expMeaning.isNullOrEmpty()) {
                        return@execute errorResponse("MISSING_EXPERIENCE_MEANING", "'experience_meaning' is required for adding experience")
                    }
                    if (!expMeaning.contains("我")) {
                        return@execute errorResponse("INVALID_MEANING", "'experience_meaning' must be written in first person and contain '我'")
                    }
                    val (card, authError) = checkCardAuth(id)
                    if (authError != null) return@execute listOf(UIMessagePart.Text(authError.toString()))
                    if (card!!.status == MemoryConstants.STATUS_ARCHIVED || card.status == MemoryConstants.STATUS_SUPERSEDED) {
                        return@execute errorResponse("CARD_NOT_ACTIVE", "Cannot attach experience coordinate to archived or superseded card.")
                    }
                    val resolvedEpisodeKey = resolveRealEpisodeAndEnsureEvidence(card, rawEpisodeKey)
                    if (resolvedEpisodeKey == null) {
                        return@execute errorResponse(
                            "NO_REAL_EPISODE",
                            "Cannot attach experience coordinate without a verified persistent dialogue message. Please provide a valid source_episode_key or invoke within a real conversation."
                        )
                    }
                    val tendency = MemoryExperienceCoordinateEntity.normalizeTendency(expTendency)
                    val eCoord = try {
                        val currentE = repository.getCurrentExperienceCoordinate(card.id)
                        if (currentE != null) {
                            // 已有 CURRENT E，演进为 successor
                            repository.addExperienceSuccessor(
                                supersededId = currentE.id,
                                meaning = expMeaning,
                                eInitialPriority = expPriority,
                                eAuthoredBy = primaryAuthority.authorIdentifier,
                                sourceEpisodeKey = resolvedEpisodeKey,
                                rubricVersion = "v1",
                                responseTendency = tendency,
                                valence = expValence,
                                arousal = expArousal,
                                tension = expTension,
                                growthDelta = expGrowthDelta,
                                revisionReason = reason ?: "Updated via tool",
                                authority = primaryAuthority,
                            )
                        } else {
                            repository.addExperienceCoordinate(
                                memoryCardId = card.id,
                                ownerType = defaultOwnerType,
                                ownerId = defaultOwnerId,
                                meaning = expMeaning,
                                eInitialPriority = expPriority,
                                eAuthoredBy = primaryAuthority.authorIdentifier,
                                sourceEpisodeKey = resolvedEpisodeKey,
                                rubricVersion = "v1",
                                responseTendency = tendency,
                                valence = expValence,
                                arousal = expArousal,
                                tension = expTension,
                                growthDelta = expGrowthDelta,
                                authority = primaryAuthority,
                            )
                        }
                    } catch (e: Exception) {
                        return@execute errorResponse("ADD_EXPERIENCE_FAILED", e.message ?: "Failed to add experience coordinate")
                    }
                    val result = buildJsonObject {
                        put("success", true)
                        put("action", "add_experience")
                        put("experience_coordinate", buildJsonObject {
                            put("id", eCoord.id)
                            put("memory_card_id", eCoord.memoryCardId)
                            put("meaning", eCoord.meaning)
                            put("initial_priority", eCoord.eInitialPriority)
                            put("response_tendency", eCoord.responseTendency)
                            put("status", eCoord.status)
                            eCoord.valence?.let { put("valence", it) }
                            eCoord.arousal?.let { put("arousal", it) }
                        })
                    }
                    listOf(UIMessagePart.Text(result.toString()))
                }

                else -> errorResponse("UNKNOWN_ACTION", "Unknown action '$action'")
            }
        }
    )

    val evidenceTool = if (assistant.allowMemoryEvidenceRead && convProvider != null) {
        Tool(
            name = "memory_read_evidence",
            description = "读取 memory_search 已命中记忆卡的有限原话证据。原话只用于核对和导航，不能当作当前长期事实。",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("memory_id", buildJsonObject { put("type", "string") })
                        put("limit", buildJsonObject { put("type", "integer") })
                    },
                    required = listOf("memory_id"),
                )
            },
            execute = execute@ { element ->
                val params = runCatching { element.jsonObject }.getOrNull()
                    ?: return@execute errorResponse("INVALID_PARAMS", "Parameters must be a JSON object")
                val memoryId = params["memory_id"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return@execute errorResponse("MISSING_MEMORY_ID", "'memory_id' is required")
                val (card, authError) = checkCardAuth(memoryId)
                if (authError != null) return@execute listOf(UIMessagePart.Text(authError.toString()))
                val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 2).coerceIn(1, 3)
                val sources = repository.getSources(memoryId).take(limit)
                val evidence = buildJsonArray {
                    sources.forEach { source ->
                        if (source.messageId?.startsWith("autonomous:") == true) {
                            val chunk = repository.getEventChunkById(source.messageId) ?: return@forEach
                            if (chunk.ownerType != card!!.ownerType || chunk.ownerId != card.ownerId ||
                                chunk.status != "AUTONOMOUS_SOURCE"
                            ) return@forEach
                            add(buildJsonObject {
                                put("source_kind", "AUTONOMOUS_WAKE")
                                put("source_id", chunk.id)
                                put("role", "AUTONOMOUS")
                                put("matched", chunk.summary)
                            })
                            return@forEach
                        }
                        val conversationId = source.conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                            ?: return@forEach
                        val conversation = convProvider(conversationId) ?: return@forEach
                        val messages = conversation.currentMessages
                        val index = messages.indexOfFirst { it.id.toString() == source.messageId }
                        val (matchedMsg, beforeMsg, afterMsg) = if (index >= 0) {
                            Triple(messages[index], messages.getOrNull(index - 1), messages.getOrNull(index + 1))
                        } else {
                            val node = conversation.messageNodes.find { n ->
                                n.messages.any { m -> m.id.toString() == source.messageId }
                            } ?: return@forEach
                            val msg = node.messages.find { it.id.toString() == source.messageId } ?: return@forEach
                            val nodeIndex = conversation.messageNodes.indexOf(node)
                            val before = if (nodeIndex > 0) {
                                val prevNode = conversation.messageNodes[nodeIndex - 1]
                                prevNode.messages.getOrNull(prevNode.selectIndex)
                            } else null
                            val after = if (nodeIndex in 0 until conversation.messageNodes.size - 1) {
                                val nextNode = conversation.messageNodes[nodeIndex + 1]
                                nextNode.messages.getOrNull(nextNode.selectIndex)
                            } else null
                            Triple(msg, before, after)
                        }
                        add(buildJsonObject {
                            put("conversation_id", source.conversationId)
                            put("message_id", source.messageId.orEmpty())
                            put("role", source.role.orEmpty().ifEmpty { matchedMsg.role.name.lowercase() })
                            put("matched", matchedMsg.toText().take(600))
                            beforeMsg?.toText()?.take(300)?.let { put("before", it) }
                            afterMsg?.toText()?.take(300)?.let { put("after", it) }
                        })
                    }
                }
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", true)
                    put("memory_id", memoryId)
                    put("evidence", evidence)
                    put("notice", "原话是可变证据，不是当前长期事实。")
                }.toString()))
            }
        )
    } else null

    val searchDailyNotesTool = Tool(
        name = "memory_search_daily_notes",
        description = "检索助手专属日记（每日回顾与情绪脉络）。支持关键词、单日或日期区间过滤。仅在明确需要回顾历史日记时调用。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "搜索关键词（匹配日记正文 summary 或情绪脉络 emotional_thread，可选）")
                    })
                    put("date_from", buildJsonObject {
                        put("type", "string")
                        put("description", "起始日期（格式 YYYY-MM-DD，包含该日，可选）")
                    })
                    put("date_to", buildJsonObject {
                        put("type", "string")
                        put("description", "结束日期（格式 YYYY-MM-DD，包含该日，可选）")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "最大返回条数（1-5，默认 3，可选）")
                    })
                }
            )
        },
        execute = execute@ { args ->
            val params = runCatching { args.jsonObject }.getOrNull() ?: return@execute emptyList()
            val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim()
            val dateFrom = params["date_from"]?.jsonPrimitive?.contentOrNull?.trim()
            val dateTo = params["date_to"]?.jsonPrimitive?.contentOrNull?.trim()
            val limit = (params["limit"]?.jsonPrimitive?.intOrNull ?: 3).coerceIn(1, 5)

            val notes = repository.searchDailyNotes(
                ownerType = defaultOwnerType,
                ownerId = defaultOwnerId,
                query = query,
                dateFrom = dateFrom,
                dateTo = dateTo,
                limit = limit,
            )

            val notesJson = buildJsonArray {
                notes.forEach { note ->
                    add(buildJsonObject {
                        put("memory_date", note.memoryDate)
                        put("summary", note.summary)
                        note.emotionalThread?.let { put("emotional_thread", it) }
                    })
                }
            }

            listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("count", notes.size)
                put("notes", notesJson)
            }.toString()))
        }
    )

    return listOfNotNull(searchTool, evidenceTool, manageTool, searchDailyNotesTool)
}
