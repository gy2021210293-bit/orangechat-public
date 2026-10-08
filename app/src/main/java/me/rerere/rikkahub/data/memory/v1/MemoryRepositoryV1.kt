/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryConsolidationRunEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryDailyNoteEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryExperienceCoordinateEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEProposalEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEmbeddingEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryEmbeddingProjection
import me.rerere.rikkahub.data.memory.v1.db.MemoryEventChunkEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryFactAuditEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryMaintenanceTaskEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryProposalEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryRelationEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryRevisionEntity
import me.rerere.rikkahub.data.memory.v1.db.MemorySourceEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryTimelineEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryTimelineMemberEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryV1Dao
import me.rerere.rikkahub.data.memory.v1.db.secondaryIdsList
import kotlin.uuid.Uuid

private const val TAG = "MemoryRepositoryV1"

private inline fun safeLogW(tag: String, message: String, throwable: Throwable? = null) {
    runCatching { Log.w(tag, message, throwable) }
}

/**
 * E 坐标 (Experience Signals) 权威著作授权契约。
 * 只有具备合法授权上下文的调用方才能确立 CURRENT 状态的经验坐标：
 * 1. [PrimaryAgent]: 主智能体在与用户的真实对话中亲历交互亲定；
 * 2. [UserReview]: 用户在管理界面显式人工审核采纳提案。
 * 严禁任意未授权字符串或后台 Worker 直接篡权写入 CURRENT E。
 */
sealed interface ExperienceAuthority {
    val authorIdentifier: String

    /**
     * 主智能体亲身经历确立。
     * @property assistantId 经历该事件的主智能体 ID
     * @property conversationId 发生该交互的真实会话 ID
     */
    data class PrimaryAgent(
        val assistantId: String,
        val conversationId: String,
    ) : ExperienceAuthority {
        override val authorIdentifier: String get() = "assistant:$assistantId:primary"
    }

    /**
     * 用户显式人工审查确认。
     * @property userId 操作者标识，默认为 USER
     */
    data class UserReview(
        val userId: String = MemoryConstants.ACTOR_USER,
    ) : ExperienceAuthority {
        override val authorIdentifier: String get() = userId
    }
}

object MemoryConstants {
    const val OWNER_TYPE_ASSISTANT = "assistant"
    const val OWNER_TYPE_GLOBAL = "global"
    const val GLOBAL_OWNER_ID = "global"

    const val STATUS_ACTIVE = "ACTIVE"
    const val STATUS_REVIEW = "REVIEW"
    const val STATUS_SUPERSEDED = "SUPERSEDED"
    const val STATUS_ARCHIVED = "ARCHIVED"

    const val CATEGORY_PREFERENCE = "preference"
    const val CATEGORY_PROFILE = "profile"
    const val CATEGORY_SELF = "self"
    const val CATEGORY_RELATIONSHIP = "relationship"
    const val CATEGORY_PLAN = "plan"
    const val CATEGORY_EXPERIENCE = "experience"
    const val CATEGORY_BOUNDARY = "boundary"
    const val CATEGORY_OTHER = "other"

    const val OP_ADD = "ADD"
    const val OP_UPDATE = "UPDATE"
    const val OP_MERGE = "MERGE"
    const val OP_SUPERSEDE = "SUPERSEDE"
    const val OP_ARCHIVE = "ARCHIVE"
    const val OP_RESTORE = "RESTORE"

    const val ACTOR_TOOL = "TOOL"
    const val ACTOR_NIGHTLY_MODEL = "NIGHTLY_MODEL"
    const val ACTOR_MANUAL = "MANUAL"
    const val ACTOR_SYSTEM = "SYSTEM"
    const val ACTOR_USER = "USER"

    const val RELATION_STATUS_ACTIVE = "ACTIVE"
    const val RELATION_STATUS_REVIEW = "REVIEW"
    const val RELATION_STATUS_ARCHIVED = "ARCHIVED"

    const val RUN_STATUS_PENDING = "PENDING"
    const val RUN_STATUS_RUNNING = "RUNNING"
    const val RUN_STATUS_SUCCEEDED = "SUCCEEDED"
    const val RUN_STATUS_FAILED = "FAILED"
    const val RUN_STATUS_NEEDS_REVIEW = "NEEDS_REVIEW"

    val SAFE_RELATION_TYPES = setOf(
        "same_event",
        "same_topic",
        "temporal_sequence",
        "derived_from",
        "same_person",
        "in_timeline",
        "relates_to",
        "part_of",
        "precedes",
        "subtopic_of",
        "depends_on",
    )

    fun isSafeRelationType(type: String): Boolean = type.trim().lowercase() in SAFE_RELATION_TYPES

    val HIGH_RISK_RELATION_TYPES = setOf(
        "supports",
        "contradicts",
        "cause_effect",
    )

    const val AUDIT_SUGGESTION_SUPERSEDE = "SUPERSEDE"
    const val AUDIT_SUGGESTION_BOTH_VALID = "BOTH_VALID"
    const val AUDIT_SUGGESTION_UNCERTAIN = "UNCERTAIN"

    const val AUDIT_STATUS_PENDING = "PENDING"
    const val AUDIT_STATUS_RESOLVED_SUPERSEDE = "RESOLVED_SUPERSEDE"
    const val AUDIT_STATUS_RESOLVED_KEEP_BOTH = "RESOLVED_KEEP_BOTH"
    const val AUDIT_STATUS_REJECTED = "REJECTED"

    const val AUDIT_RESOLUTION_SUPERSEDE = "SUPERSEDE"
    const val AUDIT_RESOLUTION_KEEP_BOTH = "KEEP_BOTH"
    const val AUDIT_RESOLUTION_REJECT = "REJECT"
}

open class MemoryRepositoryV1(
    database: AppDatabase? = null,
    dao: MemoryV1Dao? = null,
) {
    private val _database: AppDatabase? = database
    private val _dao: MemoryV1Dao? = dao

    private val database: AppDatabase get() = _database ?: error("Database not initialized")
    private val dao: MemoryV1Dao get() = _dao ?: error("DAO not initialized")
    private val db get() = _database?.openHelper?.writableDatabase

    private suspend fun <T> runTransaction(block: suspend () -> T): T {
        val db = _database
        return if (db != null) {
            db.withTransaction { block() }
        } else {
            block()
        }
    }

    open suspend fun createCard(
        ownerType: String,
        ownerId: String,
        content: String,
        category: String = MemoryConstants.CATEGORY_OTHER,
        factKey: String? = null,
        confidence: Float = 1.0f,
        importance: Float = 0.5f,
        validFrom: Long? = null,
        validTo: Long? = null,
        actor: String = MemoryConstants.ACTOR_MANUAL,
        reason: String? = null,
        sourceConversationId: String? = null,
        sourceNodeId: String? = null,
        sourceMessageId: String? = null,
        sourceRole: String? = null,
        sourceKind: String = "MANUAL",
        sourceHash: String? = null,
        status: String = MemoryConstants.STATUS_ACTIVE,
        alwaysInject: Boolean = false,
    ): MemoryCardEntity {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "Memory content cannot be blank" }

        val effectiveAlwaysInject = if (status == MemoryConstants.STATUS_ACTIVE && category == MemoryConstants.CATEGORY_SELF) {
            alwaysInject
        } else {
            false
        }

        val now = System.currentTimeMillis()
        val cardId = Uuid.random().toString()
        val card = MemoryCardEntity(
            id = cardId,
            ownerType = ownerType,
            ownerId = ownerId,
            content = trimmed,
            category = category,
            factKey = factKey?.trim()?.ifEmpty { null },
            status = status,
            confidence = confidence.coerceIn(0f, 1f),
            importance = importance.coerceIn(0f, 1f),
            validFrom = validFrom,
            validTo = validTo,
            supersededBy = null,
            createdAt = now,
            updatedAt = now,
            revision = 1,
            createdBy = actor,
            updatedBy = actor,
            alwaysInject = effectiveAlwaysInject,
        )

        val revision = MemoryRevisionEntity(
            id = Uuid.random().toString(),
            memoryId = cardId,
            revision = 1,
            operation = MemoryConstants.OP_ADD,
            beforeContent = null,
            afterContent = trimmed,
            beforeStatus = null,
            afterStatus = status,
            reason = reason,
            actor = actor,
            createdAt = now,
        )

        val source = if (sourceConversationId != null || sourceMessageId != null || sourceHash != null) {
            MemorySourceEntity(
                id = Uuid.random().toString(),
                memoryId = cardId,
                conversationId = sourceConversationId,
                nodeId = sourceNodeId,
                messageId = sourceMessageId,
                role = sourceRole,
                sourceKind = sourceKind,
                sourceHash = sourceHash,
                createdAt = now,
            )
        } else null

        runTransaction {
            dao.insertCard(card)
            dao.insertRevision(revision)
            if (source != null) {
                dao.insertSource(source)
            }
            indexFts(card)
        }

        return card
    }

    open suspend fun updateCard(
        id: String,
        expectedRevision: Int,
        content: String,
        category: String? = null,
        factKey: String? = null,
        confidence: Float? = null,
        importance: Float? = null,
        validFrom: Long? = null,
        validTo: Long? = null,
        actor: String = MemoryConstants.ACTOR_MANUAL,
        reason: String? = null,
        sourceConversationId: String? = null,
        sourceNodeId: String? = null,
        sourceMessageId: String? = null,
        sourceRole: String? = null,
        sourceKind: String = "MANUAL",
        sourceHash: String? = null,
    ): MemoryCardEntity {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "Memory content cannot be blank" }

        return runTransaction {
            val existing = dao.getCardById(id) ?: throw IllegalArgumentException("Memory card $id not found")
            if (existing.revision != expectedRevision) {
                throw RevisionConflictException(id, expectedRevision, existing.revision)
            }

            val now = System.currentTimeMillis()
            val nextRevision = existing.revision + 1
            val updatedCategory = category ?: existing.category
            val effectiveAlwaysInject = if (existing.status == MemoryConstants.STATUS_ACTIVE && updatedCategory == MemoryConstants.CATEGORY_SELF) {
                existing.alwaysInject
            } else {
                false
            }
            val updated = existing.copy(
                content = trimmed,
                category = updatedCategory,
                factKey = factKey ?: existing.factKey,
                confidence = confidence?.coerceIn(0f, 1f) ?: existing.confidence,
                importance = importance?.coerceIn(0f, 1f) ?: existing.importance,
                validFrom = validFrom ?: existing.validFrom,
                validTo = validTo ?: existing.validTo,
                alwaysInject = effectiveAlwaysInject,
                updatedAt = now,
                revision = nextRevision,
                updatedBy = actor,
            )

            val revision = MemoryRevisionEntity(
                id = Uuid.random().toString(),
                memoryId = id,
                revision = nextRevision,
                operation = MemoryConstants.OP_UPDATE,
                beforeContent = existing.content,
                afterContent = trimmed,
                beforeStatus = existing.status,
                afterStatus = updated.status,
                reason = reason,
                actor = actor,
                createdAt = now,
            )

            val source = if (sourceConversationId != null || sourceMessageId != null || sourceHash != null) {
                MemorySourceEntity(
                    id = Uuid.random().toString(),
                    memoryId = id,
                    conversationId = sourceConversationId,
                    nodeId = sourceNodeId,
                    messageId = sourceMessageId,
                    role = sourceRole,
                    sourceKind = sourceKind,
                    sourceHash = sourceHash,
                    createdAt = now,
                )
            } else null

            if (trimmed != existing.content) {
                // 内容变更立即在单事务内清理旧向量，防止新内容命中旧向量导致语义错位
                dao.deleteEmbeddingByMemoryId(id)
            }
            dao.updateCard(updated)
            dao.insertRevision(revision)
            if (source != null) {
                dao.insertSource(source)
            }
            indexFts(updated)

            updated
        }
    }

    open suspend fun archiveCard(
        id: String,
        expectedRevision: Int,
        actor: String = MemoryConstants.ACTOR_MANUAL,
        reason: String? = null,
    ): MemoryCardEntity {
        return runTransaction {
            val existing = dao.getCardById(id) ?: throw IllegalArgumentException("Memory card $id not found")
            if (existing.revision != expectedRevision) {
                throw RevisionConflictException(id, expectedRevision, existing.revision)
            }
            if (existing.status == MemoryConstants.STATUS_ARCHIVED) {
                return@runTransaction existing
            }

            val now = System.currentTimeMillis()
            val nextRevision = existing.revision + 1
            val updated = existing.copy(
                status = MemoryConstants.STATUS_ARCHIVED,
                alwaysInject = false,
                updatedAt = now,
                revision = nextRevision,
                updatedBy = actor,
            )

            val revision = MemoryRevisionEntity(
                id = Uuid.random().toString(),
                memoryId = id,
                revision = nextRevision,
                operation = MemoryConstants.OP_ARCHIVE,
                beforeContent = existing.content,
                afterContent = existing.content,
                beforeStatus = existing.status,
                afterStatus = MemoryConstants.STATUS_ARCHIVED,
                reason = reason,
                actor = actor,
                createdAt = now,
            )

            dao.updateCard(updated)
            dao.insertRevision(revision)
            dao.deleteEmbeddingByMemoryId(id)
            indexFts(updated)

            updated
        }
    }

    open suspend fun restoreCard(
        id: String,
        expectedRevision: Int,
        actor: String = MemoryConstants.ACTOR_MANUAL,
        reason: String? = null,
    ): MemoryCardEntity {
        return runTransaction {
            val existing = dao.getCardById(id) ?: throw IllegalArgumentException("Memory card $id not found")
            if (existing.revision != expectedRevision) {
                throw RevisionConflictException(id, expectedRevision, existing.revision)
            }
            restoreArchivedCardInTransaction(existing, actor, reason)
        }
    }

    private suspend fun restoreArchivedCardInTransaction(
        existing: MemoryCardEntity,
        actor: String,
        reason: String?,
    ): MemoryCardEntity {
        if (existing.status != MemoryConstants.STATUS_ARCHIVED) return existing

        val now = System.currentTimeMillis()
        val nextRevision = existing.revision + 1
        val updated = existing.copy(
            status = MemoryConstants.STATUS_ACTIVE,
            updatedAt = now,
            revision = nextRevision,
            updatedBy = actor,
        )
        val revision = MemoryRevisionEntity(
            id = Uuid.random().toString(),
            memoryId = existing.id,
            revision = nextRevision,
            operation = MemoryConstants.OP_RESTORE,
            beforeContent = existing.content,
            afterContent = existing.content,
            beforeStatus = existing.status,
            afterStatus = MemoryConstants.STATUS_ACTIVE,
            reason = reason,
            actor = actor,
            createdAt = now,
        )
        dao.updateCard(updated)
        dao.insertRevision(revision)
        indexFts(updated)
        return updated
    }

    open suspend fun supersedeCard(
        oldCardId: String,
        expectedRevision: Int,
        newContent: String,
        category: String? = null,
        factKey: String? = null,
        confidence: Float? = null,
        importance: Float? = null,
        actor: String = MemoryConstants.ACTOR_MANUAL,
        reason: String? = null,
        sourceConversationId: String? = null,
        sourceNodeId: String? = null,
        sourceMessageId: String? = null,
        sourceRole: String? = null,
        sourceKind: String = "MANUAL",
        sourceHash: String? = null,
    ): Pair<MemoryCardEntity, MemoryCardEntity> {
        val trimmed = newContent.trim()
        require(trimmed.isNotEmpty()) { "Memory content cannot be blank" }

        return runTransaction {
            val oldCard = dao.getCardById(oldCardId) ?: throw IllegalArgumentException("Memory card $oldCardId not found")
            if (oldCard.revision != expectedRevision) {
                throw RevisionConflictException(oldCardId, expectedRevision, oldCard.revision)
            }

            val now = System.currentTimeMillis()
            val newCardId = Uuid.random().toString()
            val inheritedFactKey = factKey ?: oldCard.factKey
            val newCategory = category ?: oldCard.category
            val isSelfFromNonManual = newCategory == MemoryConstants.CATEGORY_SELF && actor != MemoryConstants.ACTOR_MANUAL
            val targetStatus = if (isSelfFromNonManual) MemoryConstants.STATUS_REVIEW else MemoryConstants.STATUS_ACTIVE
            val targetConfidence = if (isSelfFromNonManual) {
                minOf(confidence?.coerceIn(0f, 1f) ?: oldCard.confidence, 0.60f)
            } else {
                confidence?.coerceIn(0f, 1f) ?: oldCard.confidence
            }

            val newCard = MemoryCardEntity(
                id = newCardId,
                ownerType = oldCard.ownerType,
                ownerId = oldCard.ownerId,
                content = trimmed,
                category = newCategory,
                factKey = inheritedFactKey,
                status = targetStatus,
                confidence = targetConfidence,
                importance = importance?.coerceIn(0f, 1f) ?: oldCard.importance,
                alwaysInject = false,
                validFrom = now,
                validTo = null,
                supersededBy = null,
                createdAt = now,
                updatedAt = now,
                revision = 1,
                createdBy = actor,
                updatedBy = actor,
            )

            val newCardRevision = MemoryRevisionEntity(
                id = Uuid.random().toString(),
                memoryId = newCardId,
                revision = 1,
                operation = MemoryConstants.OP_ADD,
                beforeContent = null,
                afterContent = trimmed,
                beforeStatus = null,
                afterStatus = MemoryConstants.STATUS_ACTIVE,
                reason = "Supersedes $oldCardId" + if (reason != null) ": $reason" else "",
                actor = actor,
                createdAt = now,
            )

            val source = if (sourceConversationId != null || sourceMessageId != null || sourceHash != null) {
                MemorySourceEntity(
                    id = Uuid.random().toString(),
                    memoryId = newCardId,
                    conversationId = sourceConversationId,
                    nodeId = sourceNodeId,
                    messageId = sourceMessageId,
                    role = sourceRole,
                    sourceKind = sourceKind,
                    sourceHash = sourceHash,
                    createdAt = now,
                )
            } else null

            val nextOldRevision = oldCard.revision + 1
            val updatedOldCard = oldCard.copy(
                status = MemoryConstants.STATUS_SUPERSEDED,
                alwaysInject = false,
                supersededBy = newCardId,
                validTo = now,
                updatedAt = now,
                revision = nextOldRevision,
                updatedBy = actor,
            )

            val oldCardRevision = MemoryRevisionEntity(
                id = Uuid.random().toString(),
                memoryId = oldCardId,
                revision = nextOldRevision,
                operation = MemoryConstants.OP_SUPERSEDE,
                beforeContent = oldCard.content,
                afterContent = oldCard.content,
                beforeStatus = oldCard.status,
                afterStatus = MemoryConstants.STATUS_SUPERSEDED,
                reason = "Superseded by $newCardId" + if (reason != null) ": $reason" else "",
                actor = actor,
                createdAt = now,
            )

            dao.insertCard(newCard)
            dao.insertRevision(newCardRevision)
            if (source != null) {
                dao.insertSource(source)
            }
            dao.updateCard(updatedOldCard)
            dao.insertRevision(oldCardRevision)
            dao.deleteEmbeddingByMemoryId(oldCardId)

            indexFts(newCard)
            indexFts(updatedOldCard)

            Pair(newCard, updatedOldCard)
        }
    }

    open suspend fun setSelfAlwaysInject(cardId: String, enabled: Boolean): MemoryCardEntity {
        return runTransaction {
            val card = dao.getCardById(cardId) ?: throw IllegalArgumentException("Memory card $cardId not found")
            if (enabled) {
                require(card.category == MemoryConstants.CATEGORY_SELF) {
                    "Only self category cards can be configured as always inject, but got '${card.category}'"
                }
                require(card.status == MemoryConstants.STATUS_ACTIVE) {
                    "Only ACTIVE cards can be configured as always inject, but got '${card.status}'"
                }
            }
            if (card.alwaysInject == enabled) {
                return@runTransaction card
            }
            val now = System.currentTimeMillis()
            val nextRevision = card.revision + 1
            val updated = card.copy(
                alwaysInject = enabled,
                updatedAt = now,
                revision = nextRevision,
                updatedBy = MemoryConstants.ACTOR_MANUAL,
            )
            val revision = MemoryRevisionEntity(
                id = Uuid.random().toString(),
                memoryId = cardId,
                revision = nextRevision,
                operation = MemoryConstants.OP_UPDATE,
                beforeContent = card.content,
                afterContent = card.content,
                beforeStatus = card.status,
                afterStatus = card.status,
                reason = if (enabled) "Enable always inject" else "Disable always inject",
                actor = MemoryConstants.ACTOR_MANUAL,
                createdAt = now,
            )
            dao.updateCard(updated)
            dao.insertRevision(revision)
            updated
        }
    }

    open suspend fun approveReviewCard(
        cardId: String,
        actor: String = MemoryConstants.ACTOR_MANUAL,
        reason: String? = null,
    ): MemoryCardEntity {
        return runTransaction {
            val card = dao.getCardById(cardId) ?: throw IllegalArgumentException("Memory card $cardId not found")
            if (card.status == MemoryConstants.STATUS_ACTIVE) {
                return@runTransaction card
            }
            require(card.status == MemoryConstants.STATUS_REVIEW) {
                "Only REVIEW cards can be approved, but card $cardId has status '${card.status}'"
            }
            val now = System.currentTimeMillis()
            val inheritAlwaysInject = if (card.category == MemoryConstants.CATEGORY_SELF && !card.factKey.isNullOrBlank()) {
                val existingActiveCards = dao.getCardsByFactKey(card.ownerType, card.ownerId, card.factKey)
                    .filter { it.id != cardId && it.status == MemoryConstants.STATUS_ACTIVE }
                val anyAlwaysInject = existingActiveCards.any { it.alwaysInject }
                for (oldActive in existingActiveCards) {
                    val nextOldRevision = oldActive.revision + 1
                    val supersededOldCard = oldActive.copy(
                        status = MemoryConstants.STATUS_SUPERSEDED,
                        alwaysInject = false,
                        supersededBy = cardId,
                        validTo = now,
                        updatedAt = now,
                        revision = nextOldRevision,
                        updatedBy = actor,
                    )
                    val oldCardRevision = MemoryRevisionEntity(
                        id = Uuid.random().toString(),
                        memoryId = oldActive.id,
                        revision = nextOldRevision,
                        operation = MemoryConstants.OP_SUPERSEDE,
                        beforeContent = oldActive.content,
                        afterContent = oldActive.content,
                        beforeStatus = oldActive.status,
                        afterStatus = MemoryConstants.STATUS_SUPERSEDED,
                        reason = "Manually superseded by approved self card $cardId",
                        actor = actor,
                        createdAt = now,
                    )
                    dao.updateCard(supersededOldCard)
                    dao.insertRevision(oldCardRevision)
                    dao.deleteEmbeddingByMemoryId(oldActive.id)
                }
                anyAlwaysInject
            } else {
                card.alwaysInject
            }
            val nextRevision = card.revision + 1
            val updated = card.copy(
                status = MemoryConstants.STATUS_ACTIVE,
                alwaysInject = inheritAlwaysInject,
                updatedAt = now,
                revision = nextRevision,
                updatedBy = actor,
            )
            val revision = MemoryRevisionEntity(
                id = Uuid.random().toString(),
                memoryId = cardId,
                revision = nextRevision,
                operation = MemoryConstants.OP_UPDATE,
                beforeContent = card.content,
                afterContent = card.content,
                beforeStatus = MemoryConstants.STATUS_REVIEW,
                afterStatus = MemoryConstants.STATUS_ACTIVE,
                reason = reason ?: "Manual approval from REVIEW to ACTIVE",
                actor = actor,
                createdAt = now,
            )
            dao.updateCard(updated)
            dao.insertRevision(revision)
            indexFts(updated)
            updated
        }
    }

    open suspend fun getAlwaysInjectSelfCards(ownerType: String, ownerId: String): List<MemoryCardEntity> {
        return dao.getAlwaysInjectSelfCards(ownerType, ownerId)
    }

    open suspend fun deletePermanently(id: String) {
        runTransaction {
            dao.deleteCardById(id)
            dao.deleteSourcesByMemoryId(id)
            dao.deleteRevisionsByMemoryId(id)
            dao.deleteEmbeddingByMemoryId(id)
            dao.deleteRelationsByCardId(id)
            dao.deleteTimelineMembersByCardId(id)
            try {
                db?.execSQL("DELETE FROM memory_card_fts WHERE card_id = ?", arrayOf(id))
            } catch (e: Exception) {
                safeLogW(TAG, "Failed to delete FTS entry for card $id", e)
            }
        }
    }

    open suspend fun getCardById(id: String): MemoryCardEntity? = dao.getCardById(id)

    open suspend fun getCardsByIds(ids: List<String>): List<MemoryCardEntity> =
        if (ids.isEmpty()) emptyList() else dao.getCardsByIds(ids)

    open fun getCardsByIdsFlow(ids: List<String>): Flow<List<MemoryCardEntity>> =
        if (ids.isEmpty()) flowOf(emptyList()) else dao.getCardsByIdsFlow(ids)

    open suspend fun getRevisions(memoryId: String): List<MemoryRevisionEntity> = dao.getRevisionsByMemoryId(memoryId)

    open suspend fun getSources(memoryId: String): List<MemorySourceEntity> = dao.getSourcesByMemoryId(memoryId)

    open suspend fun addSource(source: MemorySourceEntity) {
        dao.insertSource(source)
    }

    open suspend fun getMemoryIdsWithSources(memoryIds: List<String>): Set<String> {
        if (memoryIds.isEmpty()) return emptySet()
        return dao.getMemoryIdsWithSources(memoryIds).toSet()
    }

    open suspend fun getSourcesForMemories(memoryIds: List<String>): List<MemorySourceEntity> {
        if (memoryIds.isEmpty()) return emptyList()
        return dao.getSourcesForMemories(memoryIds)
    }

    open suspend fun getCardsByStatus(
        ownerType: String,
        ownerId: String,
        status: String,
    ): List<MemoryCardEntity> {
        return dao.getCardsByStatus(ownerType, ownerId, status)
    }

    open suspend fun getCardsByStatusPaged(
        ownerType: String,
        ownerId: String,
        status: String,
        limit: Int,
        offset: Int,
    ): List<MemoryCardEntity> {
        return dao.getCardsByStatusPaged(ownerType, ownerId, status, limit, offset)
    }

    open suspend fun countCardsByStatus(
        ownerType: String,
        ownerId: String,
        status: String,
    ): Int {
        return dao.countCardsByStatus(ownerType, ownerId, status)
    }

    open suspend fun getAllCards(ownerType: String, ownerId: String): List<MemoryCardEntity> {
        val d = _dao ?: return emptyList()
        return d.getAllCards(ownerType, ownerId)
    }

    open fun getCardsFlow(
        ownerType: String,
        ownerId: String,
        status: String? = null,
    ): Flow<List<MemoryCardEntity>> {
        val d = _dao ?: return kotlinx.coroutines.flow.emptyFlow()
        return if (status == null) {
            d.getAllCardsFlow(ownerType, ownerId)
        } else {
            d.getCardsByStatusFlow(ownerType, ownerId, status)
        }
    }

    fun getCardsByStatusListFlow(
        ownerType: String,
        ownerId: String,
        statusList: List<String>,
    ): Flow<List<MemoryCardEntity>> {
        return dao.getCardsByStatusListFlow(ownerType, ownerId, statusList)
    }

    open suspend fun search(
        query: String,
        ownerType: String,
        ownerId: String,
        statusList: List<String>? = null,
        limit: Int = 20,
        includeGlobal: Boolean = false,
    ): List<MemoryCardEntity> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        val queryOwnerCondition = if (includeGlobal) {
            "((f.owner_type = ? AND f.owner_id = ?) OR f.owner_type = '${MemoryConstants.OWNER_TYPE_GLOBAL}')"
        } else {
            "(f.owner_type = ? AND f.owner_id = ?)"
        }

        if (trimmed.isEmpty()) {
            val baseList = if (includeGlobal) {
                if (statusList != null) {
                    dao.getCardsByStatusList(ownerType, ownerId, statusList) +
                        dao.getCardsByStatusList(MemoryConstants.OWNER_TYPE_GLOBAL, MemoryConstants.GLOBAL_OWNER_ID, statusList)
                } else {
                    dao.getAllCards(ownerType, ownerId) +
                        dao.getAllCards(MemoryConstants.OWNER_TYPE_GLOBAL, MemoryConstants.GLOBAL_OWNER_ID)
                }
            } else {
                if (statusList != null) {
                    dao.getCardsByStatusList(ownerType, ownerId, statusList)
                } else {
                    dao.getAllCards(ownerType, ownerId)
                }
            }
            return@withContext baseList.sortedByDescending { it.updatedAt }.take(limit)
        }

        val cardIds = mutableListOf<String>()
        try {
            val (sql, args) = if (statusList.isNullOrEmpty()) {
                val querySql = """
                    SELECT f.card_id
                    FROM memory_card_fts f
                    JOIN memory_cards c ON f.card_id = c.id
                    WHERE f.content MATCH jieba_query(?) AND $queryOwnerCondition
                    ORDER BY f.rank
                    LIMIT ?
                """.trimIndent()
                querySql to arrayOf(trimmed, ownerType, ownerId, limit.toString())
            } else {
                val placeholders = statusList.joinToString(",") { "?" }
                val querySql = """
                    SELECT f.card_id
                    FROM memory_card_fts f
                    JOIN memory_cards c ON f.card_id = c.id
                    WHERE f.content MATCH jieba_query(?) AND $queryOwnerCondition
                      AND c.status IN ($placeholders)
                    ORDER BY f.rank
                    LIMIT ?
                """.trimIndent()
                val queryArgs = (listOf(trimmed, ownerType, ownerId) + statusList + listOf(limit.toString())).toTypedArray()
                querySql to queryArgs
            }
            val writableDb = db ?: error("Database not initialized")
            val cursor = writableDb.query(sql, args)
            cursor.use {
                while (it.moveToNext()) {
                    cardIds.add(it.getString(0))
                }
            }
        } catch (e: Exception) {
            safeLogW(TAG, "FTS search failed (query len=${trimmed.length}), falling back to in-memory LIKE filter", e)
            val all = if (includeGlobal) {
                if (statusList != null) {
                    dao.getCardsByStatusList(ownerType, ownerId, statusList) +
                        dao.getCardsByStatusList(MemoryConstants.OWNER_TYPE_GLOBAL, MemoryConstants.GLOBAL_OWNER_ID, statusList)
                } else {
                    dao.getAllCards(ownerType, ownerId) +
                        dao.getAllCards(MemoryConstants.OWNER_TYPE_GLOBAL, MemoryConstants.GLOBAL_OWNER_ID)
                }
            } else {
                if (statusList != null) {
                    dao.getCardsByStatusList(ownerType, ownerId, statusList)
                } else {
                    dao.getAllCards(ownerType, ownerId)
                }
            }
            return@withContext all.filter { it.content.contains(trimmed, ignoreCase = true) }
                .sortedByDescending { it.updatedAt }
                .take(limit)
        }

        if (cardIds.isEmpty()) return@withContext emptyList()

        val cards = dao.getCardsByIds(cardIds).associateBy { it.id }
        cardIds.mapNotNull { cards[it] }
    }

    // --- Memory Embeddings ---

    open suspend fun conditionalInsertEmbedding(
        memoryId: String,
        expectedRevision: Int,
        expectedStatus: String,
        expectedContentHash: String,
        embedding: MemoryEmbeddingEntity,
    ): Boolean {
        val block: suspend () -> Boolean = {
            val card = dao.getCardById(memoryId)
            if (card == null ||
                card.revision != expectedRevision ||
                card.status != expectedStatus ||
                computeContentHash(card.content) != expectedContentHash
            ) {
                false
            } else {
                dao.deleteEmbedding(memoryId, embedding.embeddingProvider, embedding.embeddingModel)
                dao.insertOrReplaceEmbedding(embedding)
                true
            }
        }
        val db = _database
        return if (db != null) {
            db.withTransaction { block() }
        } else {
            block()
        }
    }

    open suspend fun insertOrReplaceEmbedding(embedding: MemoryEmbeddingEntity) {
        val block: suspend () -> Unit = {
            dao.deleteEmbedding(embedding.memoryId, embedding.embeddingProvider, embedding.embeddingModel)
            dao.insertOrReplaceEmbedding(embedding)
        }
        val db = _database
        if (db != null) {
            db.withTransaction { block() }
        } else {
            block()
        }
    }

    open suspend fun getEmbedding(
        memoryId: String,
        embeddingProvider: String,
        embeddingModel: String,
    ): MemoryEmbeddingEntity? {
        return dao.getEmbedding(memoryId, embeddingProvider, embeddingModel)
    }

    open suspend fun getEmbeddingByMemoryId(memoryId: String): MemoryEmbeddingEntity? {
        return dao.getEmbeddingByMemoryId(memoryId)
    }

    open suspend fun getEmbeddingByContentHash(contentHash: String, embeddingModel: String): MemoryEmbeddingEntity? {
        return dao.getEmbeddingByContentHash(contentHash, embeddingModel)
    }

    open suspend fun getActiveEmbeddingProjections(
        ownerType: String,
        ownerId: String,
        embeddingProvider: String,
        embeddingModel: String,
        includeGlobal: Boolean,
        limit: Int,
        offset: Int,
    ): List<MemoryEmbeddingProjection> {
        return dao.getActiveEmbeddingProjections(
            ownerType = ownerType,
            ownerId = ownerId,
            embeddingProvider = embeddingProvider,
            embeddingModel = embeddingModel,
            includeGlobal = includeGlobal,
            limit = limit,
            offset = offset,
        )
    }

    open suspend fun deleteEmbedding(memoryId: String, embeddingProvider: String, embeddingModel: String) {
        dao.deleteEmbedding(memoryId, embeddingProvider, embeddingModel)
    }

    open suspend fun deleteEmbeddingByMemoryId(memoryId: String) {
        dao.deleteEmbeddingByMemoryId(memoryId)
    }

    private fun computeContentHash(text: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(text.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun indexFts(card: MemoryCardEntity) {
        val writableDb = db ?: return
        try {
            writableDb.execSQL("DELETE FROM memory_card_fts WHERE card_id = ?", arrayOf(card.id))
            writableDb.execSQL(
                """
                INSERT INTO memory_card_fts(content, card_id, owner_type, owner_id, category, fact_key)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    card.content,
                    card.id,
                    card.ownerType,
                    card.ownerId,
                    card.category,
                    card.factKey ?: "",
                )
            )
        } catch (e: Exception) {
            safeLogW(TAG, "indexFts failed for card ${card.id}", e)
        }
    }

    // --- Daily Notes & Consolidation ---

    open suspend fun getDailyNotes(ownerType: String, ownerId: String): List<MemoryDailyNoteEntity> =
        dao.getDailyNotes(ownerType, ownerId)

    open fun getDailyNotesFlow(ownerType: String, ownerId: String): Flow<List<MemoryDailyNoteEntity>> =
        _dao?.getDailyNotesFlow(ownerType, ownerId) ?: kotlinx.coroutines.flow.emptyFlow()

    open suspend fun getDailyNote(ownerId: String, memoryDate: String): MemoryDailyNoteEntity? =
        dao.getDailyNote(ownerId, memoryDate)

    open suspend fun searchDailyNotes(
        ownerType: String,
        ownerId: String,
        query: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        limit: Int = 3,
    ): List<MemoryDailyNoteEntity> = dao.searchDailyNotes(
        ownerType = ownerType,
        ownerId = ownerId,
        query = query?.trim()?.takeIf { it.isNotBlank() },
        dateFrom = dateFrom?.trim()?.takeIf { it.isNotBlank() },
        dateTo = dateTo?.trim()?.takeIf { it.isNotBlank() },
        limit = limit.coerceIn(1, 5),
    )

    open suspend fun insertOrUpdateDailyNote(note: MemoryDailyNoteEntity) =
        dao.insertOrUpdateDailyNote(note)

    open suspend fun deleteDailyNote(ownerId: String, memoryDate: String) {
        val block: suspend () -> Unit = {
            dao.deleteDailyNote(ownerId, memoryDate)
            dao.deleteRun(ownerId, memoryDate)
        }
        val db = _database
        if (db != null) {
            db.withTransaction { block() }
        } else {
            block()
        }
    }

    open suspend fun getConsolidationRun(ownerId: String, memoryDate: String): MemoryConsolidationRunEntity? =
        dao.getRun(ownerId, memoryDate)

    open suspend fun getPendingRuns(ownerType: String, ownerId: String): List<MemoryConsolidationRunEntity> =
        dao.getPendingRuns(ownerType, ownerId)

    open suspend fun getAllPendingRuns(): List<MemoryConsolidationRunEntity> =
        dao.getAllPendingRuns()

    open suspend fun markDatePendingConsolidation(
        ownerType: String,
        ownerId: String,
        memoryDate: String,
    ) {
        val block: suspend () -> Unit = {
            val existing = dao.getRun(ownerId, memoryDate)
            val now = System.currentTimeMillis()
            val isRunning = existing != null &&
                existing.status == MemoryConstants.RUN_STATUS_RUNNING &&
                (existing.leaseUntil ?: 0L) > now

            if (!isRunning) {
                val run = existing?.copy(
                    status = MemoryConstants.RUN_STATUS_PENDING,
                    errorCode = null,
                ) ?: MemoryConsolidationRunEntity(
                    id = Uuid.random().toString(),
                    ownerType = ownerType,
                    ownerId = ownerId,
                    memoryDate = memoryDate,
                    status = MemoryConstants.RUN_STATUS_PENDING,
                )
                dao.insertOrReplaceRun(run)
            }
        }
        val db = _database
        if (db != null) {
            db.withTransaction { block() }
        } else {
            block()
        }
    }

    open suspend fun acquireConsolidationLease(
        ownerType: String,
        ownerId: String,
        memoryDate: String,
        modelId: String? = null,
        leaseDurationMs: Long = 10 * 60 * 1000L,
    ): String? {
        val block: suspend () -> String? = {
            val existing = dao.getRun(ownerId, memoryDate)
            val now = System.currentTimeMillis()
            val isLeaseActive = existing != null &&
                existing.status == MemoryConstants.RUN_STATUS_RUNNING &&
                (existing.leaseUntil ?: 0L) > now

            if (isLeaseActive) {
                null
            } else {
                val newToken = Uuid.random().toString()
                val run = existing?.copy(
                    status = MemoryConstants.RUN_STATUS_RUNNING,
                    startedAt = now,
                    leaseUntil = now + leaseDurationMs,
                    attemptCount = existing.attemptCount + 1,
                    modelId = modelId ?: existing.modelId,
                    errorCode = null,
                    runToken = newToken,
                ) ?: MemoryConsolidationRunEntity(
                    id = Uuid.random().toString(),
                    ownerType = ownerType,
                    ownerId = ownerId,
                    memoryDate = memoryDate,
                    status = MemoryConstants.RUN_STATUS_RUNNING,
                    startedAt = now,
                    leaseUntil = now + leaseDurationMs,
                    attemptCount = 1,
                    modelId = modelId,
                    runToken = newToken,
                )
                dao.insertOrReplaceRun(run)
                newToken
            }
        }
        val db = _database
        return if (db != null) {
            db.withTransaction { block() }
        } else {
            block()
        }
    }

    open suspend fun renewConsolidationLease(
        ownerType: String,
        ownerId: String,
        memoryDate: String,
        runToken: String,
        leaseDurationMs: Long = 10 * 60 * 1000L,
    ): Boolean {
        val block: suspend () -> Boolean = {
            val existing = dao.getRun(ownerId, memoryDate)
            val now = System.currentTimeMillis()
            if (existing != null &&
                existing.status == MemoryConstants.RUN_STATUS_RUNNING &&
                existing.runToken == runToken &&
                (existing.leaseUntil ?: 0L) > now
            ) {
                val updated = existing.copy(
                    leaseUntil = now + leaseDurationMs,
                )
                dao.insertOrReplaceRun(updated)
                true
            } else {
                false
            }
        }
        val db = _database
        return if (db != null) {
            db.withTransaction { block() }
        } else {
            block()
        }
    }

    open suspend fun finishConsolidationRun(
        ownerType: String,
        ownerId: String,
        memoryDate: String,
        status: String,
        fingerprint: String? = null,
        clearFingerprint: Boolean = false,
        errorCode: String? = null,
        runToken: String? = null,
    ) {
        val block: suspend () -> Unit = {
            val existing = dao.getRun(ownerId, memoryDate)
            val now = System.currentTimeMillis()
            if (existing != null && runToken != null && existing.runToken != runToken) {
                safeLogW(TAG, "Cannot finish run for $ownerId on $memoryDate: runToken mismatch (expected ${existing.runToken}, got $runToken)")
                throw IllegalStateException("Cannot finish consolidation run: token mismatch (expected ${existing.runToken}, got $runToken)")
            } else {
                val finalFingerprint = if (clearFingerprint) null else (fingerprint ?: existing?.sourceFingerprint)
                val run = existing?.copy(
                    status = status,
                    sourceFingerprint = finalFingerprint,
                    finishedAt = now,
                    errorCode = errorCode,
                    leaseUntil = null,
                ) ?: MemoryConsolidationRunEntity(
                    id = Uuid.random().toString(),
                    ownerType = ownerType,
                    ownerId = ownerId,
                    memoryDate = memoryDate,
                    status = status,
                    sourceFingerprint = finalFingerprint,
                    finishedAt = now,
                    errorCode = errorCode,
                    leaseUntil = null,
                    runToken = runToken,
                )
                dao.insertOrReplaceRun(run)
            }
        }
        val db = _database
        if (db != null) {
            db.withTransaction { block() }
        } else {
            block()
        }
    }

    open suspend fun applyConsolidationBatch(
        ownerType: String,
        ownerId: String,
        memoryDate: String,
        dailyNoteResult: DailyNoteResult,
        patches: List<MemoryPatch>,
        sourceReferences: Map<String, MemorySourceReference> = emptyMap(),
        sourceFingerprint: String? = null,
        runToken: String? = null,
    ): ConsolidationBatchResult {
        val block: suspend () -> ConsolidationBatchResult = {
            val now = System.currentTimeMillis()
            if (runToken != null) {
                val currentRun = dao.getRun(ownerId, memoryDate)
                if (currentRun == null ||
                    currentRun.runToken != runToken ||
                    currentRun.status != MemoryConstants.RUN_STATUS_RUNNING ||
                    (currentRun.leaseUntil ?: 0L) < now
                ) {
                    throw IllegalStateException("Consolidation lease expired or token mismatch for $ownerId on $memoryDate")
                }
            }
            val existingNote = dao.getDailyNote(ownerId, memoryDate)
            val unfinishedTopicsJson = if (dailyNoteResult.unfinishedTopics.isNotEmpty()) {
                Json.encodeToString(dailyNoteResult.unfinishedTopics)
            } else {
                "[]"
            }
            val noteEntity = MemoryDailyNoteEntity(
                id = existingNote?.id ?: Uuid.random().toString(),
                ownerType = ownerType,
                ownerId = ownerId,
                memoryDate = memoryDate,
                summary = dailyNoteResult.summary.trim(),
                emotionalThread = dailyNoteResult.emotionalThread?.trim(),
                unfinishedTopics = unfinishedTopicsJson,
                sourceFingerprint = sourceFingerprint ?: existingNote?.sourceFingerprint,
                status = "COMPLETED",
                createdAt = existingNote?.createdAt ?: now,
                updatedAt = now,
                revision = (existingNote?.revision ?: 0) + 1,
            )
            dao.insertOrUpdateDailyNote(noteEntity)

            var appliedCount = 0
            var conflictedCount = 0
            val affectedCards = mutableListOf<MemoryCardEntity>()
            val patchAppliedCardMap = mutableMapOf<Int, MemoryCardEntity>()

            for ((index, patch) in patches.withIndex()) {
                try {
                    when (patch.action) {
                        MemoryPatchAction.ADD -> {
                            val content = patch.content?.trim()
                            if (content.isNullOrEmpty()) {
                                conflictedCount++
                                continue
                            }

                            val isSelfCategory = (patch.category ?: MemoryConstants.CATEGORY_OTHER) == MemoryConstants.CATEGORY_SELF
                            if (isSelfCategory) {
                                if (patch.factKey == null || !StableSelfRules.isValidSelfFactKey(patch.factKey)) {
                                    conflictedCount++
                                    continue
                                }
                                val existingCards = dao.getCardsByFactKey(ownerType, ownerId, patch.factKey)
                                val existingSelfCandidate = existingCards.firstOrNull { it.status == MemoryConstants.STATUS_ACTIVE }
                                    ?: existingCards.firstOrNull { it.status == MemoryConstants.STATUS_REVIEW }
                                if (existingSelfCandidate != null) {
                                    val updatedCard = applySelfCardEvidence(
                                        targetCard = existingSelfCandidate,
                                        newContent = content.take(1000),
                                        newConfidence = patch.confidence,
                                        patchReason = patch.reason,
                                        sourceIds = patch.sourceIds,
                                        sourceReferences = sourceReferences,
                                        memoryDate = memoryDate,
                                        now = now,
                                    )
                                    if (updatedCard != null) {
                                        affectedCards.add(updatedCard)
                                        patchAppliedCardMap[index] = updatedCard
                                        appliedCount++
                                    } else {
                                        conflictedCount++
                                    }
                                    continue
                                }

                                val episode = StableSelfRules.validateSelfEpisode(
                                    patch.sourceIds.mapNotNull { sourceReferences[it] },
                                    ownerId,
                                    memoryDate,
                                )
                                if (episode == null) {
                                    conflictedCount++
                                    continue
                                }
                                val episodeSourceHash = episode.sourceHash
                                val initialCap = StableSelfRules.calculateSelfConfidenceCap(1)
                                val initialConfidence = minOf(patch.confidence ?: 0.9f, initialCap)
                                val card = createCard(
                                    ownerType = ownerType,
                                    ownerId = ownerId,
                                    content = content.take(1000),
                                    category = MemoryConstants.CATEGORY_SELF,
                                    factKey = patch.factKey,
                                    confidence = initialConfidence,
                                    importance = patch.importance ?: 0.5f,
                                    actor = MemoryConstants.ACTOR_NIGHTLY_MODEL,
                                    reason = patch.reason,
                                    sourceKind = "NIGHTLY_MODEL",
                                    status = MemoryConstants.STATUS_REVIEW,
                                    alwaysInject = false,
                                )
                                for (srcId in patch.sourceIds) {
                                    val sourceRef = sourceReferences[srcId]
                                    dao.insertSource(
                                        MemorySourceEntity(
                                            id = Uuid.random().toString(),
                                            memoryId = card.id,
                                            conversationId = sourceRef?.conversationId,
                                            nodeId = sourceRef?.nodeId,
                                            messageId = srcId,
                                            role = sourceRef?.role,
                                            messageCreatedAt = sourceRef?.messageCreatedAt,
                                            sourceKind = "NIGHTLY_MODEL",
                                            sourceHash = episodeSourceHash,
                                            createdAt = now,
                                        )
                                    )
                                }
                                affectedCards.add(card)
                                patchAppliedCardMap[index] = card
                                appliedCount++
                                continue
                            }

                            val existingFactCard = if (patch.factKey != null) {
                                dao.getCardByFactKey(ownerType, ownerId, patch.factKey, MemoryConstants.STATUS_ACTIVE)
                            } else null

                            if (existingFactCard != null) {
                                if (existingFactCard.content.trim() == content) {
                                    // 内容完全一致，幂等处理
                                    affectedCards.add(existingFactCard)
                                    patchAppliedCardMap[index] = existingFactCard
                                    appliedCount++
                                } else {
                                    // 事实冲突/潜在替代：严禁静默覆写已有卡片！记录事实审计表待人工确认（去重避免重复创建）
                                    val existingAudits = dao.getPendingFactAuditsByFactKeys(
                                        ownerType,
                                        ownerId,
                                        listOf(patch.factKey ?: "")
                                    )
                                    val alreadyPending = existingAudits.any {
                                        it.existingCardId == existingFactCard.id &&
                                        it.factKey == (patch.factKey ?: "") &&
                                        it.candidateContent.trim() == content.trim()
                                    }
                                    if (!alreadyPending) {
                                        dao.insertFactAudit(
                                            MemoryFactAuditEntity(
                                                id = Uuid.random().toString(),
                                                ownerType = ownerType,
                                                ownerId = ownerId,
                                                factKey = patch.factKey ?: "",
                                                existingCardId = existingFactCard.id,
                                                candidateCardId = null,
                                                candidateContent = content,
                                                modelSuggestion = MemoryConstants.AUDIT_SUGGESTION_SUPERSEDE,
                                                reason = "ADD with existing factKey attempted to mutate fact: ${patch.reason.orEmpty()}",
                                                evidenceSnippet = patch.evidenceSnippet,
                                                status = MemoryConstants.AUDIT_STATUS_PENDING,
                                                createdAt = now,
                                            )
                                        )
                                    }
                                    conflictedCount++
                                }
                                continue
                            }

                            val card = createCard(
                                ownerType = ownerType,
                                ownerId = ownerId,
                                content = content.take(1000),
                                category = patch.category ?: MemoryConstants.CATEGORY_OTHER,
                                factKey = patch.factKey,
                                confidence = patch.confidence ?: 0.9f,
                                importance = patch.importance ?: 0.5f,
                                actor = MemoryConstants.ACTOR_NIGHTLY_MODEL,
                                reason = patch.reason,
                                sourceKind = "NIGHTLY_MODEL",
                            )
                            for (srcId in patch.sourceIds) {
                                val sourceRef = sourceReferences[srcId]
                                dao.insertSource(
                                    MemorySourceEntity(
                                        id = Uuid.random().toString(),
                                        memoryId = card.id,
                                        conversationId = sourceRef?.conversationId,
                                        nodeId = sourceRef?.nodeId,
                                        messageId = srcId,
                                        role = sourceRef?.role,
                                        messageCreatedAt = sourceRef?.messageCreatedAt,
                                        sourceKind = "NIGHTLY_MODEL",
                                        sourceHash = null,
                                        createdAt = now,
                                    )
                                )
                            }
                            affectedCards.add(card)
                            patchAppliedCardMap[index] = card
                            appliedCount++
                        }
                        MemoryPatchAction.UPDATE -> {
                            val targetId = patch.targetId
                            val expectedRev = patch.expectedRevision
                            val content = patch.content?.trim()
                            if (targetId == null || expectedRev == null || content.isNullOrEmpty()) {
                                conflictedCount++
                                continue
                            }
                            val existingCard = dao.getCardById(targetId)
                            if (existingCard == null || existingCard.ownerId != ownerId || existingCard.revision != expectedRev) {
                                conflictedCount++
                                continue
                            }
                            if (existingCard.category == MemoryConstants.CATEGORY_SELF) {
                                val updatedCard = applySelfCardEvidence(
                                    targetCard = existingCard,
                                    newContent = content.take(1000),
                                    newConfidence = patch.confidence,
                                    patchReason = patch.reason,
                                    sourceIds = patch.sourceIds,
                                    sourceReferences = sourceReferences,
                                    memoryDate = memoryDate,
                                    now = now,
                                )
                                if (updatedCard != null) {
                                    affectedCards.add(updatedCard)
                                    patchAppliedCardMap[index] = updatedCard
                                    appliedCount++
                                } else {
                                    conflictedCount++
                                }
                                continue
                            }
                            val updatedCard = updateCard(
                                id = targetId,
                                expectedRevision = expectedRev,
                                content = content.take(1000),
                                category = patch.category,
                                factKey = patch.factKey,
                                confidence = patch.confidence,
                                importance = patch.importance,
                                actor = MemoryConstants.ACTOR_NIGHTLY_MODEL,
                                reason = patch.reason,
                                sourceKind = "NIGHTLY_MODEL",
                            )
                            for (srcId in patch.sourceIds) {
                                val sourceRef = sourceReferences[srcId]
                                dao.insertSource(
                                    MemorySourceEntity(
                                        id = Uuid.random().toString(),
                                        memoryId = updatedCard.id,
                                        conversationId = sourceRef?.conversationId,
                                        nodeId = sourceRef?.nodeId,
                                        messageId = srcId,
                                        role = sourceRef?.role,
                                        messageCreatedAt = sourceRef?.messageCreatedAt,
                                        sourceKind = "NIGHTLY_MODEL",
                                        sourceHash = null,
                                        createdAt = now,
                                    )
                                )
                            }
                            affectedCards.add(updatedCard)
                            patchAppliedCardMap[index] = updatedCard
                            appliedCount++
                        }
                        MemoryPatchAction.MERGE -> {
                            val targetId = patch.targetId
                            val expectedRev = patch.expectedRevision
                            val content = patch.content?.trim()
                            if (targetId == null || expectedRev == null || content.isNullOrEmpty()) {
                                conflictedCount++
                                continue
                            }
                            val targetCard = dao.getCardById(targetId)
                            if (targetCard == null || targetCard.ownerId != ownerId || targetCard.revision != expectedRev) {
                                conflictedCount++
                                continue
                            }
                            val mergedCard = updateCard(
                                id = targetId,
                                expectedRevision = expectedRev,
                                content = content.take(1000),
                                category = patch.category,
                                factKey = patch.factKey,
                                confidence = patch.confidence,
                                importance = patch.importance,
                                actor = MemoryConstants.ACTOR_NIGHTLY_MODEL,
                                reason = "Merged into $targetId: ${patch.reason.orEmpty()}",
                                sourceKind = "NIGHTLY_MODEL",
                            )
                            for (secId in patch.secondaryIds) {
                                val secCard = dao.getCardById(secId)
                                if (secCard != null && secCard.ownerId == ownerId && secCard.status != MemoryConstants.STATUS_ARCHIVED) {
                                    archiveCard(
                                        id = secId,
                                        expectedRevision = secCard.revision,
                                        actor = MemoryConstants.ACTOR_NIGHTLY_MODEL,
                                        reason = "Merged into $targetId",
                                    )
                                }
                            }
                            affectedCards.add(mergedCard)
                            patchAppliedCardMap[index] = mergedCard
                            appliedCount++
                        }
                        MemoryPatchAction.SUPERSEDE -> {
                            val targetId = patch.targetId
                            val expectedRev = patch.expectedRevision
                            val content = patch.content?.trim()
                            if (targetId == null || expectedRev == null || content.isNullOrEmpty()) {
                                conflictedCount++
                                continue
                            }
                            val targetCard = dao.getCardById(targetId)
                            if (targetCard == null || targetCard.ownerId != ownerId || targetCard.revision != expectedRev) {
                                conflictedCount++
                                continue
                            }
                            val (_, newCard) = supersedeCard(
                                oldCardId = targetId,
                                expectedRevision = expectedRev,
                                newContent = content.take(1000),
                                category = patch.category,
                                factKey = patch.factKey,
                                confidence = patch.confidence,
                                importance = patch.importance,
                                actor = MemoryConstants.ACTOR_NIGHTLY_MODEL,
                                reason = patch.reason,
                                sourceKind = "NIGHTLY_MODEL",
                            )
                            for (srcId in patch.sourceIds) {
                                val sourceRef = sourceReferences[srcId]
                                dao.insertSource(
                                    MemorySourceEntity(
                                        id = Uuid.random().toString(),
                                        memoryId = newCard.id,
                                        conversationId = sourceRef?.conversationId,
                                        nodeId = sourceRef?.nodeId,
                                        messageId = srcId,
                                        role = sourceRef?.role,
                                        messageCreatedAt = sourceRef?.messageCreatedAt,
                                        sourceKind = "NIGHTLY_MODEL",
                                        sourceHash = null,
                                        createdAt = now,
                                    )
                                )
                            }
                            affectedCards.add(newCard)
                            patchAppliedCardMap[index] = newCard
                            appliedCount++
                        }
                        MemoryPatchAction.ARCHIVE -> {
                            val targetId = patch.targetId
                            val expectedRev = patch.expectedRevision
                            if (targetId == null || expectedRev == null) {
                                conflictedCount++
                                continue
                            }
                            val targetCard = dao.getCardById(targetId)
                            if (targetCard == null || targetCard.ownerId != ownerId || targetCard.revision != expectedRev) {
                                conflictedCount++
                                continue
                            }
                            archiveCard(
                                id = targetId,
                                expectedRevision = expectedRev,
                                actor = MemoryConstants.ACTOR_NIGHTLY_MODEL,
                                reason = patch.reason,
                            )
                            patchAppliedCardMap[index] = targetCard
                            appliedCount++
                        }
                        MemoryPatchAction.NOOP -> {
                            // no-op
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    safeLogW(TAG, "Failed to apply patch: action=${patch.action}, targetId=${patch.targetId}", e)
                    conflictedCount++
                }
            }

            ConsolidationBatchResult(
                dailyNote = noteEntity,
                appliedPatches = appliedCount,
                conflictedPatches = conflictedCount,
                affectedCards = affectedCards,
                patchAppliedCardMap = patchAppliedCardMap,
            )
        }
        val db = _database
        return if (db != null) {
            db.withTransaction { block() }
        } else {
            block()
        }
    }

    private suspend fun applySelfCardEvidence(
        targetCard: MemoryCardEntity,
        newContent: String,
        newConfidence: Float?,
        patchReason: String?,
        sourceIds: List<String>,
        sourceReferences: Map<String, MemorySourceReference>,
        memoryDate: String,
        now: Long,
    ): MemoryCardEntity? {
        val validatedEpisode = StableSelfRules.validateSelfEpisode(
            sourceIds.mapNotNull { sourceReferences[it] },
            targetCard.ownerId,
            memoryDate,
        ) ?: return null // 无效事件停止自动落卡，不写入来源，不修改卡片

        val episodeSourceHash = validatedEpisode.sourceHash
        val factKey = targetCard.factKey

        // 1. 若目标卡为 ACTIVE 成熟自我，严禁一次新互动直接改写内容
        if (targetCard.status == MemoryConstants.STATUS_ACTIVE) {
            if (newContent.trim() == targetCard.content.trim()) {
                // 内容一致：印证已有自我，仅追加来源
                val existingSources = dao.getSourcesByMemoryId(targetCard.id)
                val existingMsgIds = existingSources.mapNotNull { it.messageId }.toSet()
                var insertedAny = false
                for (srcId in sourceIds) {
                    if (srcId !in existingMsgIds) {
                        val sourceRef = sourceReferences[srcId]
                        dao.insertSource(
                            MemorySourceEntity(
                                id = Uuid.random().toString(),
                                memoryId = targetCard.id,
                                conversationId = sourceRef?.conversationId,
                                nodeId = sourceRef?.nodeId,
                                messageId = srcId,
                                role = sourceRef?.role,
                                messageCreatedAt = sourceRef?.messageCreatedAt,
                                sourceKind = "NIGHTLY_MODEL",
                                sourceHash = episodeSourceHash,
                                createdAt = now,
                            )
                        )
                        insertedAny = true
                    }
                }
                if (!insertedAny) return targetCard
                val nextRevision = targetCard.revision + 1
                val updated = targetCard.copy(
                    updatedAt = now,
                    revision = nextRevision,
                    updatedBy = MemoryConstants.ACTOR_NIGHTLY_MODEL,
                )
                val revision = MemoryRevisionEntity(
                    id = Uuid.random().toString(),
                    memoryId = targetCard.id,
                    revision = nextRevision,
                    operation = MemoryConstants.OP_UPDATE,
                    beforeContent = targetCard.content,
                    afterContent = targetCard.content,
                    beforeStatus = targetCard.status,
                    afterStatus = targetCard.status,
                    reason = patchReason ?: "Confirmed active self card with new episode evidence",
                    actor = MemoryConstants.ACTOR_NIGHTLY_MODEL,
                    createdAt = now,
                )
                dao.updateCard(updated)
                dao.insertRevision(revision)
                return updated
            } else {
                // 内容不一致：绝不覆盖 ACTIVE，而是寻找或创建 REVIEW 变更候选卡
                val existingReviewCandidates = if (!factKey.isNullOrBlank()) {
                    dao.getCardsByFactKey(targetCard.ownerType, targetCard.ownerId, factKey)
                        .filter { it.id != targetCard.id && it.status == MemoryConstants.STATUS_REVIEW }
                } else emptyList()

                val matchingCandidate = existingReviewCandidates.firstOrNull {
                    it.content.trim() == newContent.trim()
                }

                if (matchingCandidate != null) {
                    return applySelfCardEvidence(
                        targetCard = matchingCandidate,
                        newContent = newContent,
                        newConfidence = newConfidence,
                        patchReason = patchReason,
                        sourceIds = sourceIds,
                        sourceReferences = sourceReferences,
                        memoryDate = memoryDate,
                        now = now,
                    )
                } else if (existingReviewCandidates.isNotEmpty()) {
                    // 已存在同 factKey 的变更候选卡但内容不匹配，出现反证/冲突：创建审计并阻止成熟（去重避免重复创建）
                    val existingReview = existingReviewCandidates.first()
                    val existingAudits = dao.getPendingFactAuditsByFactKeys(
                        targetCard.ownerType,
                        targetCard.ownerId,
                        listOf(factKey ?: "")
                    )
                    val alreadyPending = existingAudits.any {
                        it.existingCardId == existingReview.id &&
                        it.factKey == (factKey ?: "") &&
                        it.candidateContent.trim() == newContent.trim()
                    }
                    if (!alreadyPending) {
                        dao.insertFactAudit(
                            MemoryFactAuditEntity(
                                id = Uuid.random().toString(),
                                ownerType = targetCard.ownerType,
                                ownerId = targetCard.ownerId,
                                factKey = factKey ?: "",
                                existingCardId = existingReview.id,
                                candidateCardId = null,
                                candidateContent = newContent.trim(),
                                modelSuggestion = MemoryConstants.AUDIT_SUGGESTION_UNCERTAIN,
                                reason = "Contradictory self evidence for $factKey: active='${targetCard.content}', candidate='${existingReview.content}', incoming='$newContent'",
                                evidenceSnippet = patchReason ?: "Episode: ${validatedEpisode.episodeKey}",
                                status = MemoryConstants.AUDIT_STATUS_PENDING,
                                createdAt = now,
                            )
                        )
                    }
                    return null
                } else {
                    val initialCap = StableSelfRules.calculateSelfConfidenceCap(1)
                    val initialConfidence = minOf(newConfidence ?: targetCard.confidence, initialCap)
                    val candidateCard = createCard(
                        ownerType = targetCard.ownerType,
                        ownerId = targetCard.ownerId,
                        content = newContent,
                        category = MemoryConstants.CATEGORY_SELF,
                        factKey = factKey,
                        confidence = initialConfidence,
                        importance = targetCard.importance,
                        actor = MemoryConstants.ACTOR_NIGHTLY_MODEL,
                        reason = patchReason ?: "Change candidate for active self factKey: $factKey",
                        sourceKind = "NIGHTLY_MODEL",
                        status = MemoryConstants.STATUS_REVIEW,
                        alwaysInject = false,
                    )
                    for (srcId in sourceIds) {
                        val sourceRef = sourceReferences[srcId]
                        dao.insertSource(
                            MemorySourceEntity(
                                id = Uuid.random().toString(),
                                memoryId = candidateCard.id,
                                conversationId = sourceRef?.conversationId,
                                nodeId = sourceRef?.nodeId,
                                messageId = srcId,
                                role = sourceRef?.role,
                                messageCreatedAt = sourceRef?.messageCreatedAt,
                                sourceKind = "NIGHTLY_MODEL",
                                sourceHash = episodeSourceHash,
                                createdAt = now,
                            )
                        )
                    }
                    return candidateCard
                }
            }
        }

        // 2. 目标卡为 REVIEW 候选：只累计支持同一候选命题的证据；出现反证或无法判定一致性时创建审计并阻止成熟（去重避免重复创建）
        if (newContent.trim() != targetCard.content.trim()) {
            val existingAudits = if (!factKey.isNullOrBlank()) {
                dao.getPendingFactAuditsByFactKeys(
                    targetCard.ownerType,
                    targetCard.ownerId,
                    listOf(factKey)
                )
            } else emptyList()
            val alreadyPending = existingAudits.any {
                it.existingCardId == targetCard.id &&
                it.factKey == (factKey ?: "") &&
                it.candidateContent.trim() == newContent.trim()
            }
            if (!alreadyPending) {
                dao.insertFactAudit(
                    MemoryFactAuditEntity(
                        id = Uuid.random().toString(),
                        ownerType = targetCard.ownerType,
                        ownerId = targetCard.ownerId,
                        factKey = factKey ?: "",
                        existingCardId = targetCard.id,
                        candidateCardId = null,
                        candidateContent = newContent.trim(),
                        modelSuggestion = MemoryConstants.AUDIT_SUGGESTION_UNCERTAIN,
                        reason = "Contradictory self evidence for $factKey: candidate='${targetCard.content}', incoming='$newContent'",
                        evidenceSnippet = patchReason ?: "Episode: ${validatedEpisode.episodeKey}",
                        status = MemoryConstants.AUDIT_STATUS_PENDING,
                        createdAt = now,
                    )
                )
            }
            return null
        }

        val existingSources = dao.getSourcesByMemoryId(targetCard.id)
        val existingMsgIds = existingSources.mapNotNull { it.messageId }.toSet()

        for (srcId in sourceIds) {
            if (srcId !in existingMsgIds) {
                val sourceRef = sourceReferences[srcId]
                dao.insertSource(
                    MemorySourceEntity(
                        id = Uuid.random().toString(),
                        memoryId = targetCard.id,
                        conversationId = sourceRef?.conversationId,
                        nodeId = sourceRef?.nodeId,
                        messageId = srcId,
                        role = sourceRef?.role,
                        messageCreatedAt = sourceRef?.messageCreatedAt,
                        sourceKind = "NIGHTLY_MODEL",
                        sourceHash = episodeSourceHash,
                        createdAt = now,
                    )
                )
            }
        }

        val allSources = dao.getSourcesByMemoryId(targetCard.id)
        val stats = StableSelfRules.computeEvidenceStats(allSources)

        // 成长判断前：清扫该主体与 factKey 下的废弃悬空 PENDING 审计与孤立 REVIEW 候选卡
        if (!factKey.isNullOrBlank()) {
            sweepObsoletePendingAudits(targetCard.ownerType, targetCard.ownerId, listOf(factKey))
        }

        // 真实查询同 factKey 的未决冲突（未决审计或其他多余 REVIEW 卡片）
        val pendingAudits = if (!factKey.isNullOrBlank()) {
            dao.getPendingFactAuditsByFactKeys(targetCard.ownerType, targetCard.ownerId, listOf(factKey))
        } else emptyList()
        val duplicateReviewCards = if (!factKey.isNullOrBlank()) {
            dao.getCardsByFactKey(targetCard.ownerType, targetCard.ownerId, factKey)
                .filter { it.id != targetCard.id && it.status == MemoryConstants.STATUS_REVIEW }
        } else emptyList()
        val hasPendingConflict = pendingAudits.isNotEmpty() || duplicateReviewCards.isNotEmpty()

        val cap = if (hasPendingConflict) {
            minOf(targetCard.confidence, 0.60f)
        } else {
            StableSelfRules.calculateSelfConfidenceCap(stats.distinctEpisodeCount)
        }
        val candidateConfidence = newConfidence ?: targetCard.confidence
        val finalConfidence = minOf(candidateConfidence, cap)

        val isMature = StableSelfRules.isMatureSelfCandidate(stats, hasPendingConflict = hasPendingConflict)
        val shouldPromote = isMature
        val targetStatus = if (shouldPromote) MemoryConstants.STATUS_ACTIVE else targetCard.status

        // 若候选成熟晋升为 ACTIVE，建立 SUPERSEDED/supersededBy 演化关系并继承 alwaysInject
        var inheritedAlwaysInject = false
        if (shouldPromote && !factKey.isNullOrBlank()) {
            val existingActiveCards = dao.getCardsByFactKey(targetCard.ownerType, targetCard.ownerId, factKey)
                .filter { it.id != targetCard.id && it.status == MemoryConstants.STATUS_ACTIVE }
            if (existingActiveCards.any { it.alwaysInject }) {
                inheritedAlwaysInject = true
            }
            for (oldActive in existingActiveCards) {
                val nextOldRevision = oldActive.revision + 1
                val supersededOldCard = oldActive.copy(
                    status = MemoryConstants.STATUS_SUPERSEDED,
                    alwaysInject = false,
                    supersededBy = targetCard.id,
                    validTo = now,
                    updatedAt = now,
                    revision = nextOldRevision,
                    updatedBy = MemoryConstants.ACTOR_NIGHTLY_MODEL,
                )
                val oldCardRevision = MemoryRevisionEntity(
                    id = Uuid.random().toString(),
                    memoryId = oldActive.id,
                    revision = nextOldRevision,
                    operation = MemoryConstants.OP_SUPERSEDE,
                    beforeContent = oldActive.content,
                    afterContent = oldActive.content,
                    beforeStatus = oldActive.status,
                    afterStatus = MemoryConstants.STATUS_SUPERSEDED,
                    reason = "Superseded by mature self candidate ${targetCard.id}",
                    actor = MemoryConstants.ACTOR_NIGHTLY_MODEL,
                    createdAt = now,
                )
                dao.updateCard(supersededOldCard)
                dao.insertRevision(oldCardRevision)
                dao.deleteEmbeddingByMemoryId(oldActive.id)
            }
        }

        val nextRevision = targetCard.revision + 1
        val updated = targetCard.copy(
            content = targetCard.content,
            confidence = finalConfidence,
            status = targetStatus,
            alwaysInject = if (targetStatus == MemoryConstants.STATUS_ACTIVE) (inheritedAlwaysInject || targetCard.alwaysInject) else false,
            updatedAt = now,
            revision = nextRevision,
            updatedBy = MemoryConstants.ACTOR_NIGHTLY_MODEL,
        )

        val revisionReason = if (shouldPromote) {
            "Promoted self candidate to ACTIVE based on evidence maturity (episodes=${stats.distinctEpisodeCount}, days=${stats.distinctMemoryDateCount})"
        } else if (hasPendingConflict) {
            "Updated self card evidence under pending conflict (episodes=${stats.distinctEpisodeCount}, days=${stats.distinctMemoryDateCount})"
        } else {
            patchReason ?: "Updated self card evidence (episodes=${stats.distinctEpisodeCount}, days=${stats.distinctMemoryDateCount})"
        }

        val revision = MemoryRevisionEntity(
            id = Uuid.random().toString(),
            memoryId = targetCard.id,
            revision = nextRevision,
            operation = MemoryConstants.OP_UPDATE,
            beforeContent = targetCard.content,
            afterContent = newContent,
            beforeStatus = targetCard.status,
            afterStatus = targetStatus,
            reason = revisionReason,
            actor = MemoryConstants.ACTOR_NIGHTLY_MODEL,
            createdAt = now,
        )

        if (newContent != targetCard.content) {
            dao.deleteEmbeddingByMemoryId(targetCard.id)
        }
        dao.updateCard(updated)
        dao.insertRevision(revision)
        indexFts(updated)

        return updated
    }

    /**
     * 试行期数据保守复核：
     * 针对由旧算法自动晋升且从未经过人工确认（无 MANUAL 审计记录）的 ACTIVE self 卡，
     * 保守降级为 REVIEW 并关闭常驻，等待新的合格 episode 重新成熟或用户手动确认。
     */
    open suspend fun reconcileLegacyTrialSelfCards(
        ownerType: String,
        ownerId: String,
    ): List<MemoryCardEntity> {
        val block: suspend () -> List<MemoryCardEntity> = {
            val activeSelfCards = dao.getAllCards(ownerType, ownerId)
                .filter { it.category == MemoryConstants.CATEGORY_SELF && it.status == MemoryConstants.STATUS_ACTIVE }
            val demoted = mutableListOf<MemoryCardEntity>()
            val now = System.currentTimeMillis()

            for (card in activeSelfCards) {
                val revisions = dao.getRevisionsByMemoryId(card.id)
                val hasManualConfirmation = card.createdBy in setOf(MemoryConstants.ACTOR_MANUAL, MemoryConstants.ACTOR_USER) ||
                    revisions.any { r ->
                        (r.actor in setOf(MemoryConstants.ACTOR_MANUAL, MemoryConstants.ACTOR_USER)) &&
                            (r.afterStatus == MemoryConstants.STATUS_ACTIVE || r.operation == MemoryConstants.OP_ADD)
                    }
                if (!hasManualConfirmation) {
                    val nextRev = card.revision + 1
                    val updated = card.copy(
                        status = MemoryConstants.STATUS_REVIEW,
                        alwaysInject = false,
                        revision = nextRev,
                        updatedAt = now,
                        updatedBy = MemoryConstants.ACTOR_SYSTEM,
                    )
                    val rev = MemoryRevisionEntity(
                        id = Uuid.random().toString(),
                        memoryId = card.id,
                        revision = nextRev,
                        operation = MemoryConstants.OP_UPDATE,
                        beforeContent = card.content,
                        afterContent = card.content,
                        beforeStatus = card.status,
                        afterStatus = MemoryConstants.STATUS_REVIEW,
                        reason = "Re-evaluate after companion self episode model correction",
                        actor = MemoryConstants.ACTOR_SYSTEM,
                        createdAt = now,
                    )
                    dao.updateCard(updated)
                    dao.insertRevision(rev)
                    demoted.add(updated)
                }
            }
            demoted
        }
        val db = _database
        return if (db != null) {
            db.withTransaction { block() }
        } else {
            block()
        }
    }

    /**
     * 全量试行期数据保守复核生产入口：
     * 遍历所有拥有 ACTIVE self 卡片的 owner，执行保守降级复核。
     */
    open suspend fun reconcileAllLegacyTrialSelfCards(): List<MemoryCardEntity> {
        val activeSelfCards = dao.getCardsByCategoryAndStatus(
            MemoryConstants.CATEGORY_SELF,
            MemoryConstants.STATUS_ACTIVE,
        )
        val owners = activeSelfCards.map { it.ownerType to it.ownerId }.distinct()
        val allDemoted = mutableListOf<MemoryCardEntity>()
        for ((ownerType, ownerId) in owners) {
            val demoted = reconcileLegacyTrialSelfCards(ownerType, ownerId)
            allDemoted.addAll(demoted)
        }
        return allDemoted
    }

    open suspend fun getFailedRuns(ownerType: String, ownerId: String, maxAttempts: Int = 3): List<MemoryConsolidationRunEntity> =
        dao.getFailedRuns(ownerType, ownerId, maxAttempts)

    // --- Event Chunks ---

    open suspend fun getEventChunkById(id: String): MemoryEventChunkEntity? = dao.getEventChunkById(id)

    open suspend fun saveEventChunks(chunks: List<MemoryEventChunkEntity>) {
        if (chunks.isNotEmpty()) {
            dao.insertEventChunks(chunks)
        }
    }

    open suspend fun getEventChunks(
        ownerType: String,
        ownerId: String,
        startMs: Long,
        endMs: Long,
    ): List<MemoryEventChunkEntity> = dao.getEventChunks(ownerType, ownerId, startMs, endMs)

    open suspend fun deleteEventChunksByDateRange(
        ownerType: String,
        ownerId: String,
        startMs: Long,
        endMs: Long,
    ) {
        dao.deleteEventChunksByDateRange(ownerType, ownerId, startMs, endMs)
    }

    // --- Proposals ---

    open suspend fun saveProposals(proposals: List<MemoryProposalEntity>) {
        if (proposals.isNotEmpty()) {
            dao.insertProposals(proposals)
        }
    }

    open suspend fun getProposalsByStatus(
        ownerType: String,
        ownerId: String,
        status: String,
    ): List<MemoryProposalEntity> = dao.getProposalsByStatus(ownerType, ownerId, status)

    open suspend fun getPendingProposals(
        ownerType: String,
        ownerId: String,
    ): List<MemoryProposalEntity> = dao.getProposalsByStatus(ownerType, ownerId, "REVIEW")

    open suspend fun updateProposalStatus(
        id: String,
        status: String,
        resolvedBy: String? = null,
        cardId: String? = null,
    ): Boolean {
        val now = System.currentTimeMillis()
        dao.updateProposalStatus(id, status, now, resolvedBy, cardId)
        return true
    }

    open suspend fun approveProposal(
        proposalId: String,
        actor: String = MemoryConstants.ACTOR_MANUAL,
    ): MemoryCardEntity? {
        val proposal = dao.getProposalById(proposalId) ?: return null
        if (proposal.status != "REVIEW") return null

        val now = System.currentTimeMillis()
        val action = proposal.action.uppercase()
        return runTransaction {
            val card: MemoryCardEntity? = when (action) {
                MemoryConstants.OP_UPDATE -> {
                    val targetId = proposal.targetId
                    if (targetId.isNullOrBlank()) {
                        dao.updateProposal(proposal.copy(risk = "missing_target", resolvedBy = "target memory is missing"))
                        return@runTransaction null
                    }
                    val existing = dao.getCardById(targetId)
                    if (existing == null) {
                        dao.updateProposal(proposal.copy(risk = "missing_target", resolvedBy = "target memory $targetId is missing"))
                        return@runTransaction null
                    }
                    val current = restoreArchivedCardInTransaction(existing, actor, "Restored before manual proposal approval")
                    updateCard(
                        id = targetId,
                        expectedRevision = current.revision,
                        content = proposal.content,
                        category = proposal.type,
                        factKey = proposal.factKey ?: current.factKey,
                        actor = actor,
                        reason = "Approved proposal UPDATE: ${proposal.title}",
                        sourceKind = "NIGHTLY_MODEL",
                    )
                }
                MemoryConstants.OP_SUPERSEDE -> {
                    val targetId = proposal.targetId
                    if (targetId.isNullOrBlank()) {
                        dao.updateProposal(proposal.copy(risk = "missing_target", resolvedBy = "target memory is missing"))
                        return@runTransaction null
                    }
                    val existing = dao.getCardById(targetId)
                    if (existing == null) {
                        dao.updateProposal(proposal.copy(risk = "missing_target", resolvedBy = "target memory $targetId is missing"))
                        return@runTransaction null
                    }
                    val current = restoreArchivedCardInTransaction(existing, actor, "Restored before manual proposal approval")
                    val (newCard, _) = supersedeCard(
                        oldCardId = targetId,
                        expectedRevision = current.revision,
                        newContent = proposal.content,
                        category = proposal.type,
                        factKey = proposal.factKey ?: existing.factKey,
                        actor = actor,
                        reason = "Approved proposal SUPERSEDE: ${proposal.title}",
                        sourceKind = "NIGHTLY_MODEL",
                    )
                    newCard
                }
                MemoryConstants.OP_MERGE -> {
                    val targetId = proposal.targetId
                    if (targetId.isNullOrBlank()) {
                        dao.updateProposal(proposal.copy(risk = "missing_target", resolvedBy = "target memory is missing"))
                        return@runTransaction null
                    }
                    val targetCard = dao.getCardById(targetId)
                    if (targetCard == null) {
                        dao.updateProposal(proposal.copy(risk = "missing_target", resolvedBy = "target memory $targetId is missing"))
                        return@runTransaction null
                    }
                    val currentTarget = restoreArchivedCardInTransaction(targetCard, actor, "Restored before manual proposal approval")
                    val mergedCard = updateCard(
                        id = targetId,
                        expectedRevision = currentTarget.revision,
                        content = proposal.content,
                        category = proposal.type,
                        factKey = proposal.factKey ?: currentTarget.factKey,
                        actor = actor,
                        reason = "Approved proposal MERGE into $targetId: ${proposal.title}",
                        sourceKind = "NIGHTLY_MODEL",
                    )
                    for (secId in proposal.secondaryIdsList) {
                        val secCard = dao.getCardById(secId)
                        if (secCard != null) {
                            val currentSecondary = restoreArchivedCardInTransaction(secCard, actor, "Restored before manual merge approval")
                            archiveCard(
                                id = secId,
                                expectedRevision = currentSecondary.revision,
                                actor = actor,
                                reason = "Merged into $targetId via proposal approval",
                            )
                        }
                    }
                    mergedCard
                }
                MemoryConstants.OP_ARCHIVE -> {
                    val targetId = proposal.targetId
                    if (targetId.isNullOrBlank()) {
                        dao.updateProposal(proposal.copy(risk = "missing_target", resolvedBy = "target memory is missing"))
                        return@runTransaction null
                    }
                    val existing = dao.getCardById(targetId)
                    if (existing == null) {
                        dao.updateProposal(proposal.copy(risk = "missing_target", resolvedBy = "target memory $targetId is missing"))
                        return@runTransaction null
                    }
                    val current = restoreArchivedCardInTransaction(existing, actor, "Restored before manual proposal approval")
                    archiveCard(
                        id = targetId,
                        expectedRevision = current.revision,
                        actor = actor,
                        reason = "Approved proposal ARCHIVE: ${proposal.title}",
                    )
                }
                MemoryConstants.OP_ADD -> {
                    val factKey = proposal.factKey
                    val existingFactCard = if (!factKey.isNullOrBlank()) {
                        dao.getCardByFactKey(proposal.ownerType, proposal.ownerId, factKey, MemoryConstants.STATUS_ACTIVE)
                    } else null
                    if (existingFactCard != null && existingFactCard.content.trim() != proposal.content.trim()) {
                        // 人工审批该 ADD 意味着采纳新事实，通过 supersedeCard 演化旧卡，保持同 factKey 唯一活跃事实！
                        val (newCard, _) = supersedeCard(
                            oldCardId = existingFactCard.id,
                            expectedRevision = existingFactCard.revision,
                            newContent = proposal.content,
                            category = proposal.type,
                            factKey = factKey,
                            actor = actor,
                            reason = "Approved proposal ADD superseding existing fact: ${proposal.title}",
                            sourceKind = "NIGHTLY_MODEL",
                        )
                        newCard
                    } else if (existingFactCard != null) {
                        existingFactCard
                    } else {
                        createCardFromProposal(proposal, actor)
                    }
                }
                else -> {
                    createCardFromProposal(proposal, actor)
                }
            }

            if (card != null) {
                val sourceIds = runCatching {
                    Json.decodeFromString<List<String>>(proposal.sourceMessageIds)
                }.getOrDefault(emptyList()).filter { it.isNotBlank() }

                if (sourceIds.isNotEmpty()) {
                    attachSourcesToCard(card.id, sourceIds, proposal.chunkId, now)
                }

                dao.updateProposalStatus(proposalId, "ACCEPTED", now, actor, card.id)
            }
            card
        }
    }

    private suspend fun attachSourcesToCard(
        cardId: String,
        sourceMessageIds: List<String>,
        chunkId: String?,
        now: Long,
    ) {
        val dbRef = _database?.openHelper?.readableDatabase
        val nodeMessagesCache = mutableMapOf<String, List<UIMessage>>()
        for (srcId in sourceMessageIds.distinct()) {
            var convId: String? = null
            var nodeId: String? = null
            var msgCreatedAt: Long? = null
            var msgRole: String? = null
            if (dbRef != null) {
                try {
                    val cursor = dbRef.query(
                        "SELECT node_id, conversation_id, update_at FROM message_fts WHERE message_id = ? LIMIT 1",
                        arrayOf(srcId)
                    )
                    cursor.use { c ->
                        if (c.moveToNext()) {
                            nodeId = c.getString(0)
                            convId = c.getString(1)
                            msgCreatedAt = c.getLong(2)
                        }
                    }
                } catch (e: Exception) {
                    safeLogW(TAG, "Failed to query message_fts for source message $srcId", e)
                }

                if (nodeId != null) {
                    try {
                        val messages = nodeMessagesCache.getOrPut(nodeId) {
                            var list: List<UIMessage> = emptyList()
                            val nodeCursor = dbRef.query(
                                "SELECT messages FROM message_node WHERE id = ? LIMIT 1",
                                arrayOf(nodeId)
                            )
                            nodeCursor.use { nc ->
                                if (nc.moveToNext()) {
                                    val messagesJson = nc.getString(0)
                                    if (!messagesJson.isNullOrBlank()) {
                                        list = JsonInstant.decodeFromString<List<UIMessage>>(messagesJson)
                                    }
                                }
                            }
                            list
                        }
                        val matched = messages.find { it.id.toString() == srcId }
                        if (matched != null) {
                            msgRole = matched.role.name
                        }
                    } catch (e: Exception) {
                        safeLogW(TAG, "Failed to query message_node for role of message $srcId", e)
                    }
                }
            }
            dao.insertSource(
                MemorySourceEntity(
                    id = Uuid.random().toString(),
                    memoryId = cardId,
                    conversationId = convId,
                    nodeId = nodeId,
                    messageId = srcId,
                    role = msgRole,
                    messageCreatedAt = msgCreatedAt,
                    sourceKind = "NIGHTLY_MODEL",
                    sourceHash = null,
                    createdAt = now,
                )
            )
        }
    }

    private suspend fun createCardFromProposal(
        proposal: MemoryProposalEntity,
        actor: String,
    ): MemoryCardEntity {
        return createCard(
            ownerType = proposal.ownerType,
            ownerId = proposal.ownerId,
            content = proposal.content,
            category = proposal.type,
            factKey = proposal.factKey,
            confidence = 1.0f,
            importance = (proposal.importance.toFloat() / 10f).coerceIn(0.1f, 1.0f),
            actor = actor,
            reason = "Approved proposal: ${proposal.title}",
            sourceKind = "NIGHTLY_MODEL",
        )
    }

    open suspend fun rejectProposal(
        proposalId: String,
        reason: String? = null,
    ): Boolean {
        val proposal = dao.getProposalById(proposalId) ?: return false
        val now = System.currentTimeMillis()
        dao.updateProposalStatus(proposalId, "REJECTED", now, reason ?: "manual", null)
        return true
    }

    // --- Timelines & Members ---

    open suspend fun createTimeline(
        ownerType: String,
        ownerId: String,
        title: String,
        summary: String = "",
        startTime: Long? = null,
        endTime: Long? = null,
        status: String = "ACTIVE",
    ): MemoryTimelineEntity {
        val now = System.currentTimeMillis()
        val timeline = MemoryTimelineEntity(
            id = Uuid.random().toString(),
            ownerType = ownerType,
            ownerId = ownerId,
            title = title.trim(),
            summary = summary.trim(),
            startTime = startTime ?: now,
            endTime = endTime,
            status = status,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertTimeline(timeline)
        return timeline
    }

    open suspend fun getTimeline(id: String): MemoryTimelineEntity? = dao.getTimelineById(id)

    open suspend fun getTimelineByTitle(ownerType: String, ownerId: String, title: String): MemoryTimelineEntity? =
        dao.getTimelineByTitle(ownerType, ownerId, title.trim())

    open suspend fun getActiveTimelines(ownerType: String, ownerId: String): List<MemoryTimelineEntity> =
        dao.getTimelinesByStatus(ownerType, ownerId, "ACTIVE")

    open suspend fun deleteTimeline(id: String) {
        dao.deleteTimelineMembersByTimeline(id)
        dao.deleteTimelineById(id)
    }

    open suspend fun removeCardFromTimeline(timelineId: String, cardId: String) {
        dao.deleteTimelineMember(timelineId, cardId)
    }

    open suspend fun addCardToTimeline(
        timelineId: String,
        cardId: String,
        startTime: Long? = null,
        endTime: Long? = null,
        confidence: Float = 1.0f,
    ): MemoryTimelineMemberEntity {
        val timeline = dao.getTimelineById(timelineId)
            ?: throw IllegalArgumentException("Timeline not found: $timelineId")
        val card = dao.getCardById(cardId)
            ?: throw IllegalArgumentException("Memory card not found: $cardId")
        require(timeline.ownerType == card.ownerType && timeline.ownerId == card.ownerId) {
            "Timeline and memory card must have the same owner"
        }
        val existing = dao.getTimelineMembers(timelineId).find { it.cardId == cardId }
        if (existing != null) {
            return existing
        }
        val sources = dao.getSourcesByMemoryId(cardId)
        val earliestSourceTime = sources.mapNotNull { it.messageCreatedAt ?: it.createdAt }.minOrNull()
        val latestSourceTime = sources.mapNotNull { it.messageCreatedAt ?: it.createdAt }.maxOrNull()

        val effectiveStartTime = startTime ?: card.validFrom ?: earliestSourceTime ?: card.createdAt
        val effectiveEndTime = endTime ?: card.validTo ?: if (sources.size > 1) latestSourceTime else null

        val member = MemoryTimelineMemberEntity(
            id = Uuid.random().toString(),
            timelineId = timelineId,
            cardId = cardId,
            startTime = effectiveStartTime,
            endTime = effectiveEndTime,
            confidence = confidence,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertTimelineMember(member)
        return member
    }

    open suspend fun getTimelineCards(timelineId: String): List<MemoryCardEntity> {
        val members = dao.getTimelineMembers(timelineId)
        if (members.isEmpty()) return emptyList()
        val cardIds = members.map { it.cardId }
        val cardsMap = dao.getCardsByIds(cardIds).associateBy { it.id }
        return members.mapNotNull { cardsMap[it.cardId] }
    }

    /**
     * X 轴时间线扩展：仅从已命中的种子卡所属的有效时间线补充少量进展。
     */
    open suspend fun expandTimelines(
        seedCardIds: List<String>,
        ownerType: String,
        ownerId: String,
        includeGlobal: Boolean,
        limit: Int = 2,
    ): List<MemoryCardEntity> {
        if (seedCardIds.isEmpty() || limit <= 0) return emptyList()

        val seedIds = seedCardIds.toSet()
        val timelineIds = seedIds.flatMap { dao.getTimelineMembersByCardId(it) }
            .map { it.timelineId }
            .distinct()
        if (timelineIds.isEmpty()) return emptyList()

        val candidates = mutableListOf<Pair<MemoryTimelineMemberEntity, MemoryCardEntity>>()
        for (timelineId in timelineIds) {
            val timeline = dao.getTimelineById(timelineId) ?: continue
            val isOwnerAllowed = timeline.ownerType == ownerType && timeline.ownerId == ownerId
            val isGlobalAllowed = includeGlobal &&
                timeline.ownerType == MemoryConstants.OWNER_TYPE_GLOBAL &&
                timeline.ownerId == MemoryConstants.GLOBAL_OWNER_ID
            if (timeline.status != MemoryConstants.STATUS_ACTIVE || (!isOwnerAllowed && !isGlobalAllowed)) continue

            val members = dao.getTimelineMembers(timelineId)
            val cards = dao.getCardsByIds(members.map { it.cardId }).associateBy { it.id }
            members.forEach { member ->
                val card = cards[member.cardId] ?: return@forEach
                if (member.cardId !in seedIds && card.status == MemoryConstants.STATUS_ACTIVE &&
                    card.ownerType == timeline.ownerType && card.ownerId == timeline.ownerId) {
                    candidates += member to card
                }
            }
        }

        return candidates
            .sortedWith(
                compareByDescending<Pair<MemoryTimelineMemberEntity, MemoryCardEntity>> { (member, card) ->
                    member.endTime ?: member.startTime ?: card.validTo ?: card.validFrom ?: card.updatedAt
                }.thenByDescending { (_, card) -> card.updatedAt }
                 .thenByDescending { (member, _) -> member.createdAt }
            )
            .map { it.second }
            .distinctBy { it.id }
            .take(limit)
    }

    // --- Memory Relations (Y-Axis) ---

    open suspend fun addRelation(
        ownerType: String,
        ownerId: String,
        sourceCardId: String,
        targetCardId: String,
        relationType: String,
        weight: Float = 1.0f,
        strength: Float = weight,
        reason: String? = null,
        evidenceSourceIds: String? = null,
        validFrom: Long? = null,
        validUntil: Long? = null,
        status: String? = null,
        source: String = "AUTO",
        createdBy: String = source,
    ): MemoryRelationEntity {
        val trimmedType = relationType.trim().lowercase()
        val effectiveStatus = status ?: if (MemoryConstants.isSafeRelationType(trimmedType)) "ACTIVE" else "REVIEW"
        val existing = dao.findRelation(ownerType, ownerId, sourceCardId, targetCardId, trimmedType)
        val relation = if (existing != null) {
            existing.copy(
                weight = weight,
                strength = strength,
                reason = reason ?: existing.reason,
                evidenceSourceIds = evidenceSourceIds ?: existing.evidenceSourceIds,
                validFrom = validFrom ?: existing.validFrom,
                validUntil = validUntil ?: existing.validUntil,
                status = effectiveStatus,
                source = source,
                createdBy = createdBy,
            )
        } else {
            MemoryRelationEntity(
                id = Uuid.random().toString(),
                ownerType = ownerType,
                ownerId = ownerId,
                sourceCardId = sourceCardId,
                targetCardId = targetCardId,
                relationType = trimmedType,
                weight = weight,
                strength = strength,
                reason = reason,
                evidenceSourceIds = evidenceSourceIds,
                validFrom = validFrom,
                validUntil = validUntil,
                status = effectiveStatus,
                source = source,
                createdBy = createdBy,
                createdAt = System.currentTimeMillis(),
            )
        }
        dao.insertRelation(relation)
        return relation
    }

    open suspend fun deleteConsolidationRelationsByDate(ownerType: String, ownerId: String, memoryDate: String) {
        dao.deleteRelationsBySource(ownerType, ownerId, "CONSOLIDATION:$memoryDate")
    }

    open suspend fun addRelations(relations: List<MemoryRelationEntity>) {
        if (relations.isEmpty()) return
        val normalized = relations.map { it.copy(relationType = it.relationType.trim().lowercase()) }
        dao.insertRelations(normalized)
    }

    open suspend fun getCardRelations(cardId: String): List<MemoryRelationEntity> {
        return dao.getRelationsByCardId(cardId)
    }

    open suspend fun getAllRelations(ownerType: String, ownerId: String): List<MemoryRelationEntity> {
        return dao.getAllRelations(ownerType, ownerId)
    }

    open suspend fun deleteRelation(id: String) {
        dao.deleteRelationById(id)
    }

    open fun getPendingRelationsFlow(ownerType: String, ownerId: String): Flow<List<MemoryRelationEntity>> {
        return dao.getPendingRelationsFlow(ownerType, ownerId)
    }

    open suspend fun getPendingRelations(ownerType: String, ownerId: String): List<MemoryRelationEntity> {
        return dao.getPendingRelations(ownerType, ownerId)
    }

    open suspend fun approveRelation(relationId: String): Boolean {
        val rel = dao.getRelationById(relationId) ?: return false
        dao.updateRelationStatus(relationId, MemoryConstants.RELATION_STATUS_ACTIVE)
        return true
    }

    open suspend fun rejectRelation(relationId: String): Boolean {
        val rel = dao.getRelationById(relationId) ?: return false
        dao.deleteRelationById(relationId)
        return true
    }

    /**
     * 图扩展算法 (Graph Expand - LMC-5 Y-Axis):
     * 给定一组种子卡片 ID，沿 memory_relations 边进行广度优先搜索 (BFS) 遍历。
     * 第一期严格约束：
     * 1. 只展开一跳安全关系 (SAFE_RELATION_TYPES)；
     * 2. 仅展开 ACTIVE 状态边，过滤已失效/已过期边；
     * 3. 过滤低于 0.5 强度的弱边；
     * 4. 过滤高连接度 Hub 节点，单节点最多展开 8 条边；
     * 5. 过滤掉种子卡片自身以及非 ACTIVE 状态的目标卡片。
     */
    open suspend fun graphExpand(
        seedCardIds: List<String>,
        maxHops: Int = 1,
        limit: Int = 3,
        minStrength: Float = 0.5f,
        ownerType: String? = null,
        ownerId: String? = null,
        includeGlobal: Boolean = true,
    ): List<MemoryCardEntity> {
        if (seedCardIds.isEmpty() || maxHops < 1 || limit <= 0) return emptyList()

        val visitedCardIds = seedCardIds.toMutableSet()
        val candidateCardIds = LinkedHashSet<String>()
        val now = System.currentTimeMillis()

        var currentFrontier = seedCardIds.toSet()

        val safeTypesExpanded = MemoryConstants.SAFE_RELATION_TYPES.flatMap {
            listOf(it.lowercase(), it.uppercase())
        }.distinct()

        for (hop in 1..maxHops) {
            if (currentFrontier.isEmpty()) break
            val relations = dao.getActiveSafeRelationsByCardIds(
                cardIds = currentFrontier.toList(),
                safeTypes = safeTypesExpanded,
                minStrength = minStrength,
                now = now,
            )
            val nextFrontier = mutableSetOf<String>()

            // 按照边强度降序优先排序
            val sortedRelations = relations.sortedByDescending { it.strength }
            // 单个种子节点展开上限防发散 (Hub node guard)
            val expansionCountPerSeed = mutableMapOf<String, Int>()

            for (rel in sortedRelations) {
                val (seedId, neighborId) = if (currentFrontier.contains(rel.sourceCardId)) {
                    rel.sourceCardId to rel.targetCardId
                } else if (currentFrontier.contains(rel.targetCardId)) {
                    rel.targetCardId to rel.sourceCardId
                } else (null to null)

                if (seedId != null && neighborId != null) {
                    val count = expansionCountPerSeed.getOrDefault(seedId, 0)
                    if (count >= 8) continue // 限制中心枢纽节点发散
                    expansionCountPerSeed[seedId] = count + 1

                    if (visitedCardIds.add(neighborId)) {
                        nextFrontier.add(neighborId)
                        candidateCardIds.add(neighborId)
                        if (candidateCardIds.size >= limit * 2) {
                            break
                        }
                    }
                }
            }
            currentFrontier = nextFrontier
        }

        if (candidateCardIds.isEmpty()) return emptyList()

        // 仅保留 ACTIVE 状态且满足所属权隔离约束的卡片并截断
        val cards = dao.getCardsByIds(candidateCardIds.toList())
            .filter { card ->
                card.status == MemoryConstants.STATUS_ACTIVE &&
                    (ownerType == null || (card.ownerType == ownerType && (ownerId == null || card.ownerId == ownerId)) ||
                        (includeGlobal && card.ownerType == MemoryConstants.OWNER_TYPE_GLOBAL))
            }
            .take(limit)

        return cards
    }

    /**
     * Z 轴事实演化闭环审计 (Fact Evolution Audit - LMC-5 Z-Axis):
     * 遵循“冲突发现与事实变更分离”原则：
     * 模型与夜间审计只能发现并提出事实演化审计建议，绝对不能自动将旧事实删除或替代；
     * 只有明确规则或本人在 UI 确认后才执行 SUPERSEDE。
     *
     * 针对指定 owner 的所有 ACTIVE 卡片进行 fact_key 唯一性审计：
     * 若同一 fact_key 出现多个活跃卡片，判定为事实冲突，生成 PENDING 状态的 MemoryFactAuditEntity。
     */
    open suspend fun auditFactEvolution(
        ownerType: String,
        ownerId: String,
    ): FactAuditResult {
        return runTransaction {
            sweepObsoletePendingAudits(ownerType, ownerId)
            val activeCards = dao.getActiveCardsWithFactKey(ownerType, ownerId)
            val groupedByFactKey = activeCards.filter { !it.factKey.isNullOrBlank() }.groupBy { it.factKey!! }

            var conflictedKeysCount = 0
            val pendingAudits = mutableListOf<MemoryFactAuditEntity>()
            val existingPendingAudits = dao.getPendingFactAudits(ownerType, ownerId)
            val existingAuditPairs = existingPendingAudits.map { "${it.factKey}#${it.existingCardId}#${it.candidateCardId}" }.toSet()
            val now = System.currentTimeMillis()

            for ((factKey, cards) in groupedByFactKey) {
                if (cards.size <= 1) continue

                conflictedKeysCount++
                // 排序规则：updatedAt DESC, revision DESC, importance DESC, createdAt DESC
                // 排在首位者为建议保留的权威候选卡片 (candidateCardId)
                val sorted = cards.sortedWith(
                    compareByDescending<MemoryCardEntity> { it.updatedAt }
                        .thenByDescending { it.revision }
                        .thenByDescending { it.importance }
                        .thenByDescending { it.createdAt }
                )

                val candidateCard = sorted.first()
                val staleCards = sorted.drop(1)

                for (stale in staleCards) {
                    val pairKey = "$factKey#${stale.id}#${candidateCard.id}"
                    if (pairKey in existingAuditPairs) {
                        continue // 已有待处理的相同审计记录，避免夜间任务重复创建
                    }

                    val auditEntity = MemoryFactAuditEntity(
                        id = Uuid.random().toString(),
                        ownerType = ownerType,
                        ownerId = ownerId,
                        factKey = factKey,
                        existingCardId = stale.id,
                        candidateCardId = candidateCard.id,
                        candidateContent = candidateCard.content,
                        modelSuggestion = MemoryConstants.AUDIT_SUGGESTION_SUPERSEDE,
                        reason = "Fact collision detected for key '$factKey': candidate card ${candidateCard.id} conflicts with existing card ${stale.id}",
                        evidenceSnippet = "Existing: \"${stale.content.take(100)}\" vs Candidate: \"${candidateCard.content.take(100)}\"",
                        status = MemoryConstants.AUDIT_STATUS_PENDING,
                        createdAt = now,
                    )
                    dao.insertFactAudit(auditEntity)
                    pendingAudits.add(auditEntity)
                }
            }

            FactAuditResult(
                auditedKeysCount = groupedByFactKey.size,
                conflictedKeysCount = conflictedKeysCount,
                pendingAuditsCount = pendingAudits.size,
                pendingAudits = pendingAudits,
                supersededCardsCount = 0,
                resolvedCollisions = emptyList(),
            )
        }
    }

    open suspend fun getPendingFactAudits(
        ownerType: String,
        ownerId: String,
    ): List<MemoryFactAuditEntity> {
        return dao.getPendingFactAudits(ownerType, ownerId)
    }

    /**
     * 幂等清扫失效或悬空的 PENDING 事实审计记录，并归档其关联的独立 REVIEW 候选卡。
     *
     * 识别准则：
     * 1. 现有卡片不存在，或者已处于 SUPERSEDED / ARCHIVED 等非活跃状态
     * 2. 或候选卡片 ID 不为空但卡片不存在，或已处于 SUPERSEDED / ARCHIVED 状态
     *
     * 处理逻辑：
     * - 若关联的候选卡处于 REVIEW 状态，将其原子归档为 ARCHIVED 并记录修订历史、移除 Embedding、更新 FTS
     * - 将该悬空审计更新为 REJECTED，标明失效原因
     *
     * @param ownerType 主体类型（可选；为空时清扫全库）
     * @param ownerId 主体 ID（可选；为空时清扫全库）
     * @param factKeys 限制的 factKey 集合（可选；为空时不限制）
     * @return 成功清扫并关闭的废弃审计数量
     */
    open suspend fun sweepObsoletePendingAudits(
        ownerType: String? = null,
        ownerId: String? = null,
        factKeys: Collection<String>? = null,
    ): Int {
        val nonBlankKeys = factKeys?.filter { it.isNotBlank() }?.distinct()
        if (factKeys != null && nonBlankKeys.isNullOrEmpty()) return 0

        val daoInstance = _dao ?: return 0

        val pendingAudits = when {
            ownerType != null && ownerId != null && nonBlankKeys != null -> {
                daoInstance.getPendingFactAuditsByFactKeys(ownerType, ownerId, nonBlankKeys)
            }
            ownerType != null && ownerId != null -> {
                daoInstance.getPendingFactAudits(ownerType, ownerId)
            }
            else -> {
                daoInstance.getAllPendingFactAudits().let { all ->
                    if (nonBlankKeys != null) all.filter { it.factKey in nonBlankKeys } else all
                }
            }
        }

        if (pendingAudits.isEmpty()) return 0

        var sweptCount = 0
        runTransaction {
            val now = System.currentTimeMillis()
            val validStatuses = setOf(MemoryConstants.STATUS_ACTIVE, MemoryConstants.STATUS_REVIEW)

            for (audit in pendingAudits) {
                val currentAudit = daoInstance.getFactAuditById(audit.id) ?: continue
                if (currentAudit.status != MemoryConstants.AUDIT_STATUS_PENDING) continue

                val existingCard = daoInstance.getCardById(currentAudit.existingCardId)
                val candidateCard = currentAudit.candidateCardId?.let { daoInstance.getCardById(it) }

                val isExistingInvalid = existingCard == null || existingCard.status !in validStatuses
                val isCandidateInvalid = currentAudit.candidateCardId != null && (candidateCard == null || candidateCard.status !in validStatuses)

                if (isExistingInvalid || isCandidateInvalid) {
                    if (candidateCard != null && candidateCard.status == MemoryConstants.STATUS_REVIEW) {
                        val nextCandRev = candidateCard.revision + 1
                        val archivedCand = candidateCard.copy(
                            status = MemoryConstants.STATUS_ARCHIVED,
                            validTo = now,
                            updatedAt = now,
                            revision = nextCandRev,
                            updatedBy = MemoryConstants.ACTOR_SYSTEM,
                        )
                        val candRev = MemoryRevisionEntity(
                            id = Uuid.random().toString(),
                            memoryId = candidateCard.id,
                            revision = nextCandRev,
                            operation = MemoryConstants.OP_ARCHIVE,
                            beforeContent = candidateCard.content,
                            afterContent = candidateCard.content,
                            beforeStatus = candidateCard.status,
                            afterStatus = MemoryConstants.STATUS_ARCHIVED,
                            reason = "Auto-archived: candidate obsolete due to cleanup of dangling audit ${currentAudit.id}",
                            actor = MemoryConstants.ACTOR_SYSTEM,
                            createdAt = now,
                        )
                        daoInstance.updateCard(archivedCand)
                        daoInstance.insertRevision(candRev)
                        daoInstance.deleteEmbeddingByMemoryId(candidateCard.id)
                        indexFts(archivedCand)
                    }

                    val closedAudit = currentAudit.copy(
                        status = MemoryConstants.AUDIT_STATUS_REJECTED,
                        reason = "${currentAudit.reason} (Auto-closed: dangling audit targeting invalid/superseded/archived cards)",
                        resolvedAt = now,
                        resolvedBy = MemoryConstants.ACTOR_SYSTEM,
                    )
                    daoInstance.updateFactAudit(closedAudit)
                    sweptCount++
                }
            }
        }

        return sweptCount
    }

    open suspend fun resolveFactAudit(
        auditId: String,
        resolution: String,
        resolvedBy: String = MemoryConstants.ACTOR_USER,
    ): Boolean {
        return runTransaction {
            val audit = dao.getFactAuditById(auditId) ?: return@runTransaction false
            if (audit.status != MemoryConstants.AUDIT_STATUS_PENDING) return@runTransaction false
            val now = System.currentTimeMillis()

            val normalizedResolution = when (resolution) {
                MemoryConstants.AUDIT_RESOLUTION_SUPERSEDE, MemoryConstants.AUDIT_STATUS_RESOLVED_SUPERSEDE ->
                    MemoryConstants.AUDIT_STATUS_RESOLVED_SUPERSEDE
                MemoryConstants.AUDIT_RESOLUTION_KEEP_BOTH, MemoryConstants.AUDIT_STATUS_RESOLVED_KEEP_BOTH ->
                    MemoryConstants.AUDIT_STATUS_RESOLVED_KEEP_BOTH
                MemoryConstants.AUDIT_RESOLUTION_REJECT, MemoryConstants.AUDIT_STATUS_REJECTED ->
                    MemoryConstants.AUDIT_STATUS_REJECTED
                else -> return@runTransaction false
            }

            val existing = dao.getCardById(audit.existingCardId)
            val candidateSnapshot = audit.candidateCardId?.let { dao.getCardById(it) }
            if (candidateSnapshot == null && audit.candidateContent.isBlank()) {
                if (normalizedResolution == MemoryConstants.AUDIT_STATUS_REJECTED) {
                    dao.updateFactAudit(audit.copy(
                        status = normalizedResolution,
                        resolvedAt = now,
                        resolvedBy = resolvedBy,
                    ))
                    return@runTransaction true
                }
                return@runTransaction false
            }
            if (existing == null) {
                if (normalizedResolution == MemoryConstants.AUDIT_STATUS_REJECTED) {
                    candidateSnapshot?.let { candidate ->
                        if (candidate.status != MemoryConstants.STATUS_ARCHIVED) {
                            archiveCard(
                                id = candidate.id,
                                expectedRevision = candidate.revision,
                                actor = resolvedBy,
                                reason = "Fact audit manually rejected after existing card disappeared",
                            )
                        }
                    }
                    dao.updateFactAudit(audit.copy(
                        status = normalizedResolution,
                        resolvedAt = now,
                        resolvedBy = resolvedBy,
                    ))
                    return@runTransaction true
                }
                return@runTransaction false
            }
            val existingCard = restoreArchivedCardInTransaction(existing, resolvedBy, "Restored before manual fact audit resolution")
            val candidateCard = candidateSnapshot?.let {
                restoreArchivedCardInTransaction(it, resolvedBy, "Restored before manual fact audit resolution")
            }

            when (normalizedResolution) {
                MemoryConstants.AUDIT_STATUS_RESOLVED_SUPERSEDE -> {
                    // 若 candidateCard 为 null 且 candidateContent 为空，无法替代
                    if (candidateCard == null && audit.candidateContent.isBlank()) {
                        return@runTransaction false
                    }

                    // 预先查询同 factKey 下除 candidateCard 和 existingCard 以外的其他 ACTIVE 卡片
                    val otherActives = if (audit.factKey.isNotBlank()) {
                        dao.getCardsByFactKey(audit.ownerType, audit.ownerId, audit.factKey)
                            .filter { it.id != (candidateCard?.id ?: "") && it.id != existingCard.id && it.status == MemoryConstants.STATUS_ACTIVE }
                    } else emptyList()
                    val hasActiveAlwaysInject = existingCard.alwaysInject || otherActives.any { it.alwaysInject }

                    // 确定或生成生效的替代卡片
                    val effectiveCandidateCard = if (candidateCard != null) {
                        val shouldPromote = candidateCard.status != MemoryConstants.STATUS_ACTIVE
                        val targetAlwaysInject = candidateCard.alwaysInject || hasActiveAlwaysInject
                        if (shouldPromote || targetAlwaysInject != candidateCard.alwaysInject) {
                            val nextCandRev = candidateCard.revision + 1
                            val updatedCandidate = candidateCard.copy(
                                status = MemoryConstants.STATUS_ACTIVE,
                                alwaysInject = targetAlwaysInject,
                                validFrom = candidateCard.validFrom ?: now,
                                updatedAt = now,
                                revision = nextCandRev,
                                updatedBy = resolvedBy,
                            )
                            val candRev = MemoryRevisionEntity(
                                id = Uuid.random().toString(),
                                memoryId = candidateCard.id,
                                revision = nextCandRev,
                                operation = MemoryConstants.OP_UPDATE,
                                beforeContent = candidateCard.content,
                                afterContent = candidateCard.content,
                                beforeStatus = candidateCard.status,
                                afterStatus = MemoryConstants.STATUS_ACTIVE,
                                reason = "Fact audit resolved as SUPERSEDE by $resolvedBy: promoted to ACTIVE",
                                actor = resolvedBy,
                                createdAt = now,
                            )
                            dao.updateCard(updatedCandidate)
                            dao.insertRevision(candRev)
                            indexFts(updatedCandidate)
                            updatedCandidate
                        } else {
                            candidateCard
                        }
                    } else {
                        createCard(
                            ownerType = audit.ownerType,
                            ownerId = audit.ownerId,
                            content = audit.candidateContent,
                            category = existingCard.category,
                            factKey = audit.factKey.ifBlank { null },
                            confidence = existingCard.confidence,
                            importance = existingCard.importance,
                            actor = resolvedBy,
                            reason = "Fact audit resolved as SUPERSEDE by $resolvedBy: materialized candidate content",
                            sourceKind = "FACT_AUDIT",
                            status = MemoryConstants.STATUS_ACTIVE,
                            alwaysInject = hasActiveAlwaysInject,
                        )
                    }

                    val nextRev = existingCard.revision + 1
                    val updatedOld = existingCard.copy(
                        status = MemoryConstants.STATUS_SUPERSEDED,
                        alwaysInject = false,
                        supersededBy = effectiveCandidateCard.id,
                        validTo = now,
                        updatedAt = now,
                        revision = nextRev,
                        updatedBy = resolvedBy,
                    )
                    val rev = MemoryRevisionEntity(
                        id = Uuid.random().toString(),
                        memoryId = existingCard.id,
                        revision = nextRev,
                        operation = MemoryConstants.OP_SUPERSEDE,
                        beforeContent = existingCard.content,
                        afterContent = existingCard.content,
                        beforeStatus = existingCard.status,
                        afterStatus = MemoryConstants.STATUS_SUPERSEDED,
                        reason = "Fact audit resolved as SUPERSEDE by $resolvedBy: superseded by ${effectiveCandidateCard.id}",
                        actor = resolvedBy,
                        createdAt = now,
                    )
                    dao.updateCard(updatedOld)
                    dao.insertRevision(rev)
                    dao.deleteEmbeddingByMemoryId(existingCard.id)
                    indexFts(updatedOld)

                    // 若还有其它同 factKey 的 ACTIVE 卡片，一并置为 SUPERSEDED
                    val supersededCardIds = mutableSetOf(existingCard.id)
                    if (audit.factKey.isNotBlank()) {
                        for (other in otherActives) {
                            supersededCardIds.add(other.id)
                            val nextOtherRev = other.revision + 1
                            val updatedOther = other.copy(
                                status = MemoryConstants.STATUS_SUPERSEDED,
                                alwaysInject = false,
                                supersededBy = effectiveCandidateCard.id,
                                validTo = now,
                                updatedAt = now,
                                revision = nextOtherRev,
                                updatedBy = resolvedBy,
                            )
                            val otherRev = MemoryRevisionEntity(
                                id = Uuid.random().toString(),
                                memoryId = other.id,
                                revision = nextOtherRev,
                                operation = MemoryConstants.OP_SUPERSEDE,
                                beforeContent = other.content,
                                afterContent = other.content,
                                beforeStatus = other.status,
                                afterStatus = MemoryConstants.STATUS_SUPERSEDED,
                                reason = "Fact audit resolved as SUPERSEDE by $resolvedBy: superseded by ${effectiveCandidateCard.id}",
                                actor = resolvedBy,
                                createdAt = now,
                            )
                            dao.updateCard(updatedOther)
                            dao.insertRevision(otherRev)
                            dao.deleteEmbeddingByMemoryId(other.id)
                            indexFts(updatedOther)
                        }

                        // SUPERSEDE 成功时，原子关闭同 factKey、涉及被替代卡片的所有其他 PENDING 审计，标记为已失效/拒绝并记录原因
                        val otherPendingAudits = dao.getPendingFactAuditsByFactKeys(audit.ownerType, audit.ownerId, listOf(audit.factKey))
                            .filter { it.id != audit.id }
                        for (obsoleteAudit in otherPendingAudits) {
                            val existingOfObsolete = dao.getCardById(obsoleteAudit.existingCardId)
                            val isTargetSuperseded = obsoleteAudit.existingCardId in supersededCardIds ||
                                obsoleteAudit.candidateCardId in supersededCardIds ||
                                existingOfObsolete?.status == MemoryConstants.STATUS_SUPERSEDED

                            if (isTargetSuperseded) {
                                obsoleteAudit.candidateCardId?.let { candId ->
                                    if (candId != effectiveCandidateCard.id) {
                                        val candCard = dao.getCardById(candId)
                                        if (candCard != null && candCard.status == MemoryConstants.STATUS_REVIEW) {
                                            val nextCandRev = candCard.revision + 1
                                            val archivedCand = candCard.copy(
                                                status = MemoryConstants.STATUS_ARCHIVED,
                                                validTo = now,
                                                updatedAt = now,
                                                revision = nextCandRev,
                                                updatedBy = resolvedBy,
                                            )
                                            val candRev = MemoryRevisionEntity(
                                                id = Uuid.random().toString(),
                                                memoryId = candCard.id,
                                                revision = nextCandRev,
                                                operation = MemoryConstants.OP_ARCHIVE,
                                                beforeContent = candCard.content,
                                                afterContent = candCard.content,
                                                beforeStatus = candCard.status,
                                                afterStatus = MemoryConstants.STATUS_ARCHIVED,
                                                reason = "Auto-archived: candidate obsolete due to SUPERSEDE resolution of audit ${audit.id} by $resolvedBy",
                                                actor = resolvedBy,
                                                createdAt = now,
                                            )
                                            dao.updateCard(archivedCand)
                                            dao.insertRevision(candRev)
                                            dao.deleteEmbeddingByMemoryId(candCard.id)
                                            indexFts(archivedCand)
                                        }
                                    }
                                }
                                val closedAudit = obsoleteAudit.copy(
                                    status = MemoryConstants.AUDIT_STATUS_REJECTED,
                                    reason = "${obsoleteAudit.reason} (Auto-closed: obsolete due to SUPERSEDE of card ${obsoleteAudit.existingCardId} by audit ${audit.id})",
                                    resolvedAt = now,
                                    resolvedBy = resolvedBy,
                                )
                                dao.updateFactAudit(closedAudit)
                            }
                        }
                    }

                    // 建立演化关系边 (可追溯)
                    val relation = MemoryRelationEntity(
                        id = Uuid.random().toString(),
                        ownerType = audit.ownerType,
                        ownerId = audit.ownerId,
                        sourceCardId = effectiveCandidateCard.id,
                        targetCardId = existingCard.id,
                        relationType = "SUPERSEDES",
                        strength = 1.0f,
                        weight = 1.0f,
                        reason = "Fact audit resolved as SUPERSEDE",
                        source = "AUDIT",
                        status = MemoryConstants.RELATION_STATUS_ACTIVE,
                        createdBy = resolvedBy,
                        createdAt = now,
                    )
                    dao.insertRelation(relation)
                }
                MemoryConstants.AUDIT_STATUS_RESOLVED_KEEP_BOTH -> {
                    if (candidateCard != null) {
                        val nextRev = candidateCard.revision + 1
                        val updatedCandidate = candidateCard.copy(
                            factKey = null,
                            updatedAt = now,
                            revision = nextRev,
                            updatedBy = resolvedBy,
                        )
                        val rev = MemoryRevisionEntity(
                            id = Uuid.random().toString(),
                            memoryId = candidateCard.id,
                            revision = nextRev,
                            operation = MemoryConstants.OP_UPDATE,
                            beforeContent = candidateCard.content,
                            afterContent = candidateCard.content,
                            beforeStatus = candidateCard.status,
                            afterStatus = candidateCard.status,
                            reason = "Fact audit resolved as KEEP_BOTH by $resolvedBy: cleared factKey",
                            actor = resolvedBy,
                            createdAt = now,
                        )
                        dao.updateCard(updatedCandidate)
                        dao.insertRevision(rev)
                        indexFts(updatedCandidate)
                    } else if (audit.candidateContent.isNotBlank()) {
                        createCard(
                            ownerType = audit.ownerType,
                            ownerId = audit.ownerId,
                            content = audit.candidateContent,
                            category = existingCard.category,
                            factKey = null,
                            confidence = existingCard.confidence,
                            importance = existingCard.importance,
                            actor = resolvedBy,
                            reason = "Fact audit resolved as KEEP_BOTH by $resolvedBy: preserved candidate content without factKey",
                            sourceKind = "FACT_AUDIT",
                            status = existingCard.status,
                            alwaysInject = false,
                        )
                    } else {
                        return@runTransaction false
                    }
                }
                MemoryConstants.AUDIT_STATUS_REJECTED -> {
                    if (candidateCard != null) {
                        val nextRev = candidateCard.revision + 1
                        val updatedCandidate = candidateCard.copy(
                            status = MemoryConstants.STATUS_ARCHIVED,
                            validTo = now,
                            updatedAt = now,
                            revision = nextRev,
                            updatedBy = resolvedBy,
                        )
                        val rev = MemoryRevisionEntity(
                            id = Uuid.random().toString(),
                            memoryId = candidateCard.id,
                            revision = nextRev,
                            operation = MemoryConstants.OP_ARCHIVE,
                            beforeContent = candidateCard.content,
                            afterContent = candidateCard.content,
                            beforeStatus = candidateCard.status,
                            afterStatus = MemoryConstants.STATUS_ARCHIVED,
                            reason = "Fact audit resolved as REJECT by $resolvedBy: rejected candidate card",
                            actor = resolvedBy,
                            createdAt = now,
                            )
                        dao.updateCard(updatedCandidate)
                        dao.insertRevision(rev)
                        dao.deleteEmbeddingByMemoryId(candidateCard.id)
                        indexFts(updatedCandidate)
                    }
                }
            }

            val updatedAudit = audit.copy(
                status = normalizedResolution,
                resolvedAt = now,
                resolvedBy = resolvedBy,
            )
            dao.updateFactAudit(updatedAudit)
            true
        }
    }

    // --- Memory Metabolism (M-Axis) ---

    /**
     * 记录卡片被成功召回的代谢事件：
     * 递增 hitCount，更新 lastRecalledAt，并按 7 天半衰期衰减模型累加 heat 热度。
     */
    open suspend fun recordCardRecalls(cardIds: List<String>, recallTimeMs: Long = System.currentTimeMillis()) {
        if (cardIds.isEmpty()) return
        val now = recallTimeMs
        runTransaction {
            for (id in cardIds.distinct()) {
                val card = dao.getCardById(id) ?: continue
                val lastTime = card.lastRecalledAt ?: card.createdAt
                val deltaDays = (now - lastTime).coerceAtLeast(0L).toDouble() / (7.0 * 86400000.0)
                val decay = Math.pow(0.5, deltaDays).toFloat()
                val newHeat = (card.heat * decay) + 1.0f
                dao.updateCardMetabolism(card.id, card.hitCount + 1, now, newHeat)
            }
        }
    }

    /**
     * 标记或取消卡片的保护豁免状态（受保护卡片豁免冷记忆自动归档建议）。
     */
    open suspend fun setCardProtection(cardId: String, isProtected: Boolean) {
        dao.updateCardProtection(cardId, isProtected)
    }

    /**
     * 记录卡片完成人工/系统巡检。
     */
    open suspend fun recordCardReviewed(cardId: String, reviewedAt: Long = System.currentTimeMillis()) {
        dao.updateCardLastReviewedAt(cardId, reviewedAt)
    }

    // --- Memory Maintenance Tasks ---

    open suspend fun createMaintenanceTask(
        ownerType: String,
        ownerId: String,
        taskType: String,
        targetType: String,
        targetId: String,
        title: String,
        suggestion: String,
        reason: String,
        severity: String = "INFO",
        metadata: String? = null,
    ): MemoryMaintenanceTaskEntity {
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
            createdAt = System.currentTimeMillis(),
        )
        dao.insertMaintenanceTask(task)
        return task
    }

    open suspend fun getPendingMaintenanceTasks(ownerType: String, ownerId: String): List<MemoryMaintenanceTaskEntity> {
        return dao.getMaintenanceTasksByStatus(ownerType, ownerId, "PENDING")
    }

    open suspend fun getAllMaintenanceTasks(ownerType: String, ownerId: String): List<MemoryMaintenanceTaskEntity> {
        return dao.getAllMaintenanceTasks(ownerType, ownerId)
    }

    open suspend fun getPendingMaintenanceTaskByTarget(ownerType: String, ownerId: String, targetType: String, targetId: String): MemoryMaintenanceTaskEntity? {
        return dao.getPendingMaintenanceTaskByTarget(ownerType, ownerId, targetType, targetId)
    }

    open suspend fun resolveMaintenanceTask(id: String, status: String, resolvedBy: String? = null) {
        dao.updateMaintenanceTaskStatus(id, status, System.currentTimeMillis(), resolvedBy)
    }

    open suspend fun deleteMaintenanceTask(id: String) {
        dao.deleteMaintenanceTaskById(id)
    }

    open fun getPendingProposalsFlow(ownerType: String, ownerId: String): Flow<List<MemoryProposalEntity>> =
        dao.getProposalsByStatusFlow(ownerType, ownerId, "REVIEW")

    open fun getPendingFactAuditsFlow(ownerType: String, ownerId: String): Flow<List<MemoryFactAuditEntity>> =
        dao.getFactAuditsByStatusFlow(ownerType, ownerId, "PENDING")

    open fun getPendingMaintenanceTasksFlow(ownerType: String, ownerId: String): Flow<List<MemoryMaintenanceTaskEntity>> =
        dao.getMaintenanceTasksByStatusFlow(ownerType, ownerId, "PENDING")

    open suspend fun expirePendingProposalsByDate(ownerType: String, ownerId: String, memoryDate: String, now: Long = System.currentTimeMillis()) {
        dao.expirePendingProposalsByDate(ownerType, ownerId, memoryDate, now)
    }

    open suspend fun getAuthoritativeFactStates(
        ownerType: String,
        ownerId: String,
        includeGlobal: Boolean,
        factKeys: Collection<String>,
    ): Map<String, FactKeyAuthoritativeState> {
        val nonBlankKeys = factKeys.filter { it.isNotBlank() }.distinct()
        if (nonBlankKeys.isEmpty()) return emptyMap()

        val daoInstance = _dao ?: return nonBlankKeys.associateWith { FactKeyAuthoritativeState() }

        val isPrimaryGlobal = ownerType == MemoryConstants.OWNER_TYPE_GLOBAL && ownerId == MemoryConstants.GLOBAL_OWNER_ID

        // 1. 查询当前主体（私有空间）活跃卡片与未决审核前，清扫非活跃或悬空的 PENDING 审计
        sweepObsoletePendingAudits(ownerType, ownerId, nonBlankKeys)
        val privateActiveCards = daoInstance.getActiveCardsByFactKeys(ownerType, ownerId, nonBlankKeys)
        val privatePendingAudits = daoInstance.getPendingFactAuditsByFactKeys(ownerType, ownerId, nonBlankKeys)

        val privateCardsByFactKey = privateActiveCards.groupBy { it.factKey ?: "" }
        val privateAuditsByFactKey = privatePendingAudits.groupBy { it.factKey }

        // 2. 若 includeGlobal 且主体非 GLOBAL，独立查询全局空间活跃卡片与未决审核
        val queryGlobal = includeGlobal && !isPrimaryGlobal
        if (queryGlobal) {
            sweepObsoletePendingAudits(MemoryConstants.OWNER_TYPE_GLOBAL, MemoryConstants.GLOBAL_OWNER_ID, nonBlankKeys)
        }
        val globalActiveCards = if (queryGlobal) {
            daoInstance.getActiveCardsByFactKeys(MemoryConstants.OWNER_TYPE_GLOBAL, MemoryConstants.GLOBAL_OWNER_ID, nonBlankKeys)
        } else {
            emptyList()
        }
        val globalPendingAudits = if (queryGlobal) {
            daoInstance.getPendingFactAuditsByFactKeys(MemoryConstants.OWNER_TYPE_GLOBAL, MemoryConstants.GLOBAL_OWNER_ID, nonBlankKeys)
        } else {
            emptyList()
        }

        val globalCardsByFactKey = globalActiveCards.groupBy { it.factKey ?: "" }
        val globalAuditsByFactKey = globalPendingAudits.groupBy { it.factKey }

        return nonBlankKeys.associateWith { fk ->
            val privCards = privateCardsByFactKey[fk].orEmpty().sortedByDescending { it.updatedAt }
            val privLatest = privCards.firstOrNull()
            val privAudits = privateAuditsByFactKey[fk].orEmpty()

            val globCards = globalCardsByFactKey[fk].orEmpty().sortedByDescending { it.updatedAt }
            val globLatest = globCards.firstOrNull()
            val globAudits = globalAuditsByFactKey[fk].orEmpty()

            if (isPrimaryGlobal) {
                FactKeyAuthoritativeState(
                    globalLatestActiveCardId = privLatest?.id,
                    globalLatestUpdatedAt = privLatest?.updatedAt,
                    globalHasPendingAudit = privAudits.isNotEmpty(),
                )
            } else {
                FactKeyAuthoritativeState(
                    privateLatestActiveCardId = privLatest?.id,
                    privateLatestUpdatedAt = privLatest?.updatedAt,
                    privateHasPendingAudit = privAudits.isNotEmpty(),
                    globalLatestActiveCardId = globLatest?.id,
                    globalLatestUpdatedAt = globLatest?.updatedAt,
                    globalHasPendingAudit = globAudits.isNotEmpty(),
                )
            }
        }
    }

    // =========================================================================
    // E 轴 (Experience Signals) 权威写入与不可变不可篡改契约 (LMC-5)
    // =========================================================================

    companion object {
        fun parseConversationIdFromEpisodeKey(key: String): String? {
            val regex = Regex("""(?:conv[:_])([a-zA-Z0-9_-]+)""")
            return regex.find(key)?.groupValues?.get(1)
        }

        fun parseMessageIdFromEpisodeKey(key: String): String? {
            val regex = Regex("""(?:msg[:_])([a-zA-Z0-9_-]+)""")
            return regex.find(key)?.groupValues?.get(1)
        }
    }

    /**
     * 校验 E 坐标写入参数、授权上下文与真实经历血缘。
     */
    private suspend fun validateExperienceCoordinate(
        coord: MemoryExperienceCoordinateEntity,
        card: MemoryCardEntity,
        authority: ExperienceAuthority,
    ) {
        require(coord.eAuthoredBy.isNotBlank()) { "e_authored_by must not be blank" }
        require(coord.meaning.isNotBlank()) { "meaning must not be blank" }
        require(coord.meaning.contains("我") || authority is ExperienceAuthority.UserReview) {
            "meaning must be in AI first-person perspective containing '我'"
        }
        require(coord.eInitialPriority in 1..100) { "e_initial_priority must be in 1..100, got ${coord.eInitialPriority}" }
        require(!coord.valence.isNaN() && !coord.valence.isInfinite() && coord.valence in -1.0..1.0) {
            "valence must be between -1.0 and 1.0, got ${coord.valence}"
        }
        require(!coord.arousal.isNaN() && !coord.arousal.isInfinite() && coord.arousal in 0.0..1.0) {
            "arousal must be between 0.0 and 1.0, got ${coord.arousal}"
        }
        require(!coord.tension.isNaN() && !coord.tension.isInfinite() && coord.tension in 0.0..1.0) {
            "tension must be between 0.0 and 1.0, got ${coord.tension}"
        }
        require(!coord.emotionConfidence.isNaN() && !coord.emotionConfidence.isInfinite() && coord.emotionConfidence in 0.0..1.0) {
            "emotion_confidence must be between 0.0 and 1.0, got ${coord.emotionConfidence}"
        }
        require(coord.responseTendency in MemoryExperienceCoordinateEntity.ALLOWED_TENDENCIES) {
            "Invalid response_tendency '${coord.responseTendency}', allowed: ${MemoryExperienceCoordinateEntity.ALLOWED_TENDENCIES}"
        }
        require(coord.sourceEpisodeKey.isNotBlank()) { "source_episode_key must not be blank" }

        // 权威 E 作者身份严格校验：绝不允许未授权身份或后台 Worker 随意伪造
        when (authority) {
            is ExperienceAuthority.PrimaryAgent -> {
                val expectedPrefix = "assistant:${authority.assistantId}"
                val isMatch = coord.eAuthoredBy == authority.authorIdentifier ||
                    coord.eAuthoredBy == "primary-agent" ||
                    coord.eAuthoredBy.startsWith(expectedPrefix)
                require(isMatch) {
                    "e_authored_by '${coord.eAuthoredBy}' does not match PrimaryAgent authority ($authority)"
                }
                if (card.ownerType == MemoryConstants.OWNER_TYPE_ASSISTANT) {
                    require(card.ownerId == authority.assistantId) {
                        "Assistant mismatch: card owned by ${card.ownerId}, but authority is for ${authority.assistantId}"
                    }
                }
            }
            is ExperienceAuthority.UserReview -> {
                require(coord.eAuthoredBy == authority.authorIdentifier || coord.eAuthoredBy == MemoryConstants.ACTOR_USER) {
                    "e_authored_by '${coord.eAuthoredBy}' does not match UserReview authority ($authority)"
                }
            }
        }

        // 真实经历约束校验：严禁 consolidation_、proposal: 等占位符
        val episodeKey = coord.sourceEpisodeKey.trim()
        val isManualReview = authority is ExperienceAuthority.UserReview && episodeKey.startsWith("manual-review:")
        require(!episodeKey.startsWith("consolidation_")) {
            "source_episode_key cannot be a consolidation placeholder, got: $episodeKey"
        }
        require(!episodeKey.startsWith("proposal:") && !episodeKey.startsWith("proposal_")) {
            "source_episode_key cannot be a proposal placeholder, got: $episodeKey"
        }

        // 证据真实性验证：必须能解析到卡片关联的真实 memory_sources 或持久化会话消息记录
        val cardSources = dao.getSourcesByMemoryId(card.id)
        val validCardSource = cardSources.firstOrNull { src ->
            (src.role.equals("user", ignoreCase = true) || src.role.equals("assistant", ignoreCase = true)) &&
                (!src.messageId.isNullOrBlank() || !src.conversationId.isNullOrBlank() || !src.sourceHash.isNullOrBlank())
        }

        val parsedConvId = parseConversationIdFromEpisodeKey(episodeKey)
        val parsedMsgId = parseMessageIdFromEpisodeKey(episodeKey)

        var verified = false
        if (validCardSource != null) {
            val match = cardSources.any { src ->
                (parsedMsgId != null && src.messageId == parsedMsgId) ||
                    (parsedConvId != null && src.conversationId == parsedConvId) ||
                    (src.messageId != null && episodeKey.contains(src.messageId!!)) ||
                    (src.sourceHash != null && episodeKey.contains(src.sourceHash!!))
            }
            if (match) {
                verified = true
            }
        }

        // 尝试从 AppDatabase 验证消息记录真实性
        if (!verified && _database != null && parsedConvId != null && parsedMsgId != null) {
            val conv = _database.conversationDao().getConversationById(parsedConvId)
            if (conv != null) {
                if (card.ownerType == MemoryConstants.OWNER_TYPE_ASSISTANT) {
                    require(conv.assistantId == card.ownerId) {
                        "Conversation ${conv.id} belongs to assistant ${conv.assistantId}, not card owner ${card.ownerId}"
                    }
                }
                val nodes = _database.messageNodeDao().getNodesOfConversation(parsedConvId)
                val foundMsg = nodes.asSequence().flatMap { nodeEntity ->
                    runCatching { JsonInstant.decodeFromString<List<UIMessage>>(nodeEntity.messages) }.getOrDefault(emptyList())
                }.firstOrNull { it.id.toString() == parsedMsgId }
                if (foundMsg != null && (foundMsg.role == me.rerere.ai.core.MessageRole.USER || foundMsg.role == me.rerere.ai.core.MessageRole.ASSISTANT)) {
                    verified = true
                }
            }
        }

        require(verified || isManualReview) {
            "source_episode_key '$episodeKey' cannot be verified against card dialogue sources or persistent conversation messages (cardId=${card.id})"
        }

        require(card.ownerType == coord.ownerType && card.ownerId == coord.ownerId) {
            "Card owner mismatch: card(${card.ownerType}, ${card.ownerId}) != coord(${coord.ownerType}, ${coord.ownerId})"
        }
        require(card.status != MemoryConstants.STATUS_ARCHIVED && card.status != MemoryConstants.STATUS_SUPERSEDED) {
            "Cannot attach experience coordinate to card in status ${card.status}"
        }
    }

    /**
     * 主 AI 权威写入经验坐标。
     * 若该卡片已存在 CURRENT 状态的 E 坐标，则禁止普通 ADD，必须调用 [addExperienceSuccessor] 进行修正。
     */
    suspend fun addExperienceCoordinate(
        coord: MemoryExperienceCoordinateEntity,
        authority: ExperienceAuthority,
    ): MemoryExperienceCoordinateEntity = runTransaction {
        val card = dao.getCardById(coord.memoryCardId)
            ?: error("Memory card not found: ${coord.memoryCardId}")

        validateExperienceCoordinate(coord, card, authority)

        val existingCurrent = dao.getCurrentExperienceForCard(coord.memoryCardId)
        if (existingCurrent != null) {
            error("Card ${coord.memoryCardId} already has a CURRENT experience coordinate (id=${existingCurrent.id}); modification must use successor.")
        }

        val insertedId = dao.insertExperienceCoordinate(coord.copy(status = MemoryExperienceCoordinateEntity.STATUS_CURRENT))
        coord.copy(id = insertedId, status = MemoryExperienceCoordinateEntity.STATUS_CURRENT)
    }

    @Deprecated("Use overload with explicit ExperienceAuthority", ReplaceWith("addExperienceCoordinate(coord, authority)"))
    suspend fun addExperienceCoordinate(
        coord: MemoryExperienceCoordinateEntity,
    ): MemoryExperienceCoordinateEntity {
        val authority = when {
            coord.eAuthoredBy == "primary-agent" || coord.eAuthoredBy.startsWith("assistant:") -> {
                val convId = parseConversationIdFromEpisodeKey(coord.sourceEpisodeKey) ?: "unknown"
                ExperienceAuthority.PrimaryAgent(coord.ownerId, convId)
            }
            coord.eAuthoredBy == MemoryConstants.ACTOR_USER -> {
                ExperienceAuthority.UserReview(coord.eAuthoredBy)
            }
            else -> {
                throw IllegalArgumentException("Cannot author authoritative CURRENT experience coordinate with untrusted author '${coord.eAuthoredBy}' without explicit ExperienceAuthority")
            }
        }
        return addExperienceCoordinate(coord, authority)
    }

    /**
     * 经验坐标修正原子操作：创建 successor 记录，将原 CURRENT 记录标记为 SUPERSEDED。
     */
    suspend fun addExperienceSuccessor(
        oldExperienceId: Long,
        newCoordinate: MemoryExperienceCoordinateEntity,
        authority: ExperienceAuthority,
    ): MemoryExperienceCoordinateEntity = runTransaction {
        val oldCoord = dao.getExperiencesByCardId(newCoordinate.memoryCardId).find { it.id == oldExperienceId }
            ?: error("Existing experience coordinate $oldExperienceId not found for card ${newCoordinate.memoryCardId}")

        require(oldCoord.status == MemoryExperienceCoordinateEntity.STATUS_CURRENT) {
            "Only CURRENT experience coordinate can be superseded; old status is ${oldCoord.status}"
        }

        val card = dao.getCardById(newCoordinate.memoryCardId)
            ?: error("Memory card not found: ${newCoordinate.memoryCardId}")

        validateExperienceCoordinate(newCoordinate, card, authority)

        val newInsertedId = dao.insertExperienceCoordinate(
            newCoordinate.copy(
                id = 0,
                status = MemoryExperienceCoordinateEntity.STATUS_CURRENT,
                supersededBy = null,
                createdAt = System.currentTimeMillis(),
            )
        )

        dao.updateExperienceStatus(
            id = oldExperienceId,
            status = MemoryExperienceCoordinateEntity.STATUS_SUPERSEDED,
            supersededBy = newInsertedId,
        )

        newCoordinate.copy(
            id = newInsertedId,
            status = MemoryExperienceCoordinateEntity.STATUS_CURRENT,
        )
    }

    @Deprecated("Use overload with explicit ExperienceAuthority", ReplaceWith("addExperienceSuccessor(oldExperienceId, newCoordinate, authority)"))
    suspend fun addExperienceSuccessor(
        oldExperienceId: Long,
        newCoordinate: MemoryExperienceCoordinateEntity,
    ): MemoryExperienceCoordinateEntity {
        val authority = when {
            newCoordinate.eAuthoredBy == "primary-agent" || newCoordinate.eAuthoredBy.startsWith("assistant:") -> {
                val convId = parseConversationIdFromEpisodeKey(newCoordinate.sourceEpisodeKey) ?: "unknown"
                ExperienceAuthority.PrimaryAgent(newCoordinate.ownerId, convId)
            }
            newCoordinate.eAuthoredBy == MemoryConstants.ACTOR_USER -> {
                ExperienceAuthority.UserReview(newCoordinate.eAuthoredBy)
            }
            else -> {
                throw IllegalArgumentException("Cannot author authoritative CURRENT experience coordinate with untrusted author '${newCoordinate.eAuthoredBy}' without explicit ExperienceAuthority")
            }
        }
        return addExperienceSuccessor(oldExperienceId, newCoordinate, authority)
    }

    /**
     * 提交非主 AI (如整理器、建议规则) 的 E 提案。
     */
    suspend fun addEProposal(
        proposal: MemoryEProposalEntity,
    ): MemoryEProposalEntity = runTransaction {
        val card = dao.getCardById(proposal.memoryCardId)
            ?: error("Memory card not found: ${proposal.memoryCardId}")
        require(card.ownerType == proposal.ownerType && card.ownerId == proposal.ownerId) {
            "Card owner mismatch for proposal"
        }
        val insertedId = dao.insertEProposal(proposal)
        proposal.copy(id = insertedId)
    }

    /**
     * 接受 E 提案：根据 LMC-5 契约，必须由主 AI 重新第一人称写下 meaning 和初始优先级。
     */
    open suspend fun acceptEProposal(
        proposalId: Long,
        eAuthoredBy: String = MemoryConstants.ACTOR_USER,
        meaning: String? = null,
        eInitialPriority: Int? = null,
        growthDelta: String? = null,
        responseTendency: String? = null,
    ): MemoryExperienceCoordinateEntity {
        return acceptEProposal(
            proposalId = proposalId,
            eAuthoredBy = eAuthoredBy,
            meaning = meaning,
            eInitialPriority = eInitialPriority,
            growthDelta = growthDelta,
            responseTendency = responseTendency,
            authority = ExperienceAuthority.UserReview(eAuthoredBy),
        )
    }

    open suspend fun acceptEProposal(
        proposalId: Long,
        eAuthoredBy: String = MemoryConstants.ACTOR_USER,
        meaning: String? = null,
        eInitialPriority: Int? = null,
        growthDelta: String? = null,
        responseTendency: String? = null,
        authority: ExperienceAuthority = ExperienceAuthority.UserReview(eAuthoredBy),
    ): MemoryExperienceCoordinateEntity = runTransaction {
        val proposal = dao.getEProposalById(proposalId)
            ?: error("Proposal $proposalId not found")
        require(proposal.status == MemoryEProposalEntity.STATUS_PENDING) {
            "Proposal $proposalId is not in pending status"
        }
        val card = dao.getCardById(proposal.memoryCardId)
            ?: error("Memory card ${proposal.memoryCardId} not found")

        val existingCurrent = dao.getCurrentExperienceForCard(proposal.memoryCardId)
        val now = System.currentTimeMillis()

        val candidateMeaning = meaning?.trim()?.ifEmpty { null }
            ?: proposal.meaning?.trim()?.ifEmpty { null }
            ?: proposal.reason?.takeIf { it.contains("我") }
            ?: card.content.takeIf { it.contains("我") }
            ?: error("Cannot accept E proposal without first-person meaning containing '我'")

        require(candidateMeaning.contains("我") || authority is ExperienceAuthority.UserReview) {
            "E meaning must be written in first person containing '我', got: $candidateMeaning"
        }

        val resolvedPriority = (eInitialPriority ?: proposal.initialPriority).coerceIn(1, 100)
        val resolvedTendency = MemoryExperienceCoordinateEntity.normalizeTendency(responseTendency ?: proposal.responseTendency)

        // 真实经历解析：坚决禁止 proposal:id 退化
        // 优先从提案 sourceEpisodeKey 中解析，若为空或为 proposal/consolidation 占位符，则从卡片的真实 memory_sources 解析
        val cardSources = dao.getSourcesByMemoryId(card.id)
        val validCardSource = cardSources.firstOrNull { src ->
            (src.role.equals("user", ignoreCase = true) || src.role.equals("assistant", ignoreCase = true)) &&
                (!src.messageId.isNullOrBlank() || !src.conversationId.isNullOrBlank() || !src.sourceHash.isNullOrBlank())
        }

        val candidateKey = proposal.sourceEpisodeKey?.trim()?.ifEmpty { null }
        val isCandidateValid = candidateKey != null &&
            !candidateKey.startsWith("proposal:") &&
            !candidateKey.startsWith("proposal_") &&
            !candidateKey.startsWith("consolidation_") &&
            cardSources.any { src ->
                (src.role.equals("user", ignoreCase = true) || src.role.equals("assistant", ignoreCase = true)) &&
                    ((src.messageId != null && candidateKey.contains(src.messageId)) ||
                        (src.conversationId != null && candidateKey.contains(src.conversationId)) ||
                        (src.sourceHash != null && candidateKey.contains(src.sourceHash)))
            }

        val resolvedEpisodeKey = if (isCandidateValid) {
            candidateKey!!
        } else if (validCardSource != null) {
            val convPart = validCardSource.conversationId ?: "conv"
            val msgPart = validCardSource.messageId ?: validCardSource.sourceHash ?: "msg"
            "conv:$convPart:msg:$msgPart"
        } else {
            "manual-review:$proposalId"
        }

        val coord = MemoryExperienceCoordinateEntity(
            ownerType = proposal.ownerType,
            ownerId = proposal.ownerId,
            memoryCardId = proposal.memoryCardId,
            meaning = candidateMeaning,
            eAuthoredBy = authority.authorIdentifier,
            eInitialPriority = resolvedPriority,
            valence = (proposal.valence ?: 0.0).coerceIn(-1.0, 1.0),
            arousal = (proposal.arousal ?: 0.5).coerceIn(0.0, 1.0),
            tension = (proposal.tension ?: 0.0).coerceIn(0.0, 1.0),
            emotionConfidence = (proposal.confidence ?: 1.0).coerceIn(0.0, 1.0),
            responseTendency = resolvedTendency,
            growthDelta = growthDelta ?: proposal.growthDelta ?: MemoryExperienceCoordinateEntity.GROWTH_STABLE,
            sourceEpisodeKey = resolvedEpisodeKey,
            status = MemoryExperienceCoordinateEntity.STATUS_CURRENT,
            createdAt = now,
        )

        val result = if (existingCurrent != null) {
            addExperienceSuccessor(existingCurrent.id, coord, authority)
        } else {
            addExperienceCoordinate(coord, authority)
        }

        dao.updateEProposalStatus(proposalId, MemoryEProposalEntity.STATUS_ACCEPTED, now)
        result
    }

    /**
     * 拒绝 E 提案。
     */
    open suspend fun rejectEProposal(proposalId: Long): Unit = runTransaction {
        dao.updateEProposalStatus(proposalId, MemoryEProposalEntity.STATUS_REJECTED, System.currentTimeMillis())
    }

    // --- E 轴查询封装 ---

    open suspend fun getCurrentExperienceForCard(cardId: String): MemoryExperienceCoordinateEntity? {
        return dao.getCurrentExperienceForCard(cardId)
    }

    open suspend fun getCurrentExperiencesForCards(cardIds: List<String>): List<MemoryExperienceCoordinateEntity> {
        if (cardIds.isEmpty()) return emptyList()
        return dao.getCurrentExperiencesForCards(cardIds)
    }

    open suspend fun getCurrentExperiencesForOwner(ownerType: String, ownerId: String): List<MemoryExperienceCoordinateEntity> {
        return dao.getCurrentExperiencesForOwner(ownerType, ownerId)
    }

    open suspend fun getExperiencesByCardId(cardId: String): List<MemoryExperienceCoordinateEntity> {
        return dao.getExperiencesByCardId(cardId)
    }

    open suspend fun getCurrentExperienceCoordinate(cardId: String): MemoryExperienceCoordinateEntity? {
        return getCurrentExperienceForCard(cardId)
    }

    suspend fun addExperienceCoordinate(
        memoryCardId: String,
        ownerType: String,
        ownerId: String,
        meaning: String,
        eInitialPriority: Int,
        eAuthoredBy: String,
        sourceEpisodeKey: String,
        rubricVersion: String = "v1",
        responseTendency: String = MemoryExperienceCoordinateEntity.TENDENCY_ENGAGE,
        valence: Float? = null,
        arousal: Float? = null,
        tension: Float? = null,
        growthDelta: String = MemoryExperienceCoordinateEntity.GROWTH_STABLE,
        emotionConfidence: Float = 1.0f,
        authority: ExperienceAuthority? = null,
    ): MemoryExperienceCoordinateEntity {
        val effectiveAuthority = authority ?: when {
            eAuthoredBy == "primary-agent" || eAuthoredBy.startsWith("assistant:") -> {
                val convId = parseConversationIdFromEpisodeKey(sourceEpisodeKey) ?: "unknown"
                ExperienceAuthority.PrimaryAgent(ownerId, convId)
            }
            eAuthoredBy == MemoryConstants.ACTOR_USER -> {
                ExperienceAuthority.UserReview(eAuthoredBy)
            }
            else -> {
                throw IllegalArgumentException("Cannot author authoritative CURRENT experience coordinate with untrusted author '$eAuthoredBy' without explicit ExperienceAuthority")
            }
        }
        val coord = MemoryExperienceCoordinateEntity(
            memoryCardId = memoryCardId,
            ownerType = ownerType,
            ownerId = ownerId,
            meaning = meaning,
            eInitialPriority = eInitialPriority,
            eAuthoredBy = effectiveAuthority.authorIdentifier,
            sourceEpisodeKey = sourceEpisodeKey,
            responseTendency = MemoryExperienceCoordinateEntity.normalizeTendency(responseTendency),
            valence = valence?.toDouble() ?: 0.0,
            arousal = arousal?.toDouble()?.coerceIn(0.0, 1.0) ?: 0.5,
            tension = tension?.toDouble()?.coerceIn(0.0, 1.0) ?: 0.0,
            emotionConfidence = emotionConfidence.toDouble().coerceIn(0.0, 1.0),
            growthDelta = growthDelta,
            status = MemoryExperienceCoordinateEntity.STATUS_CURRENT,
            createdAt = System.currentTimeMillis(),
        )
        return addExperienceCoordinate(coord, effectiveAuthority)
    }

    suspend fun addExperienceSuccessor(
        supersededId: Long,
        meaning: String,
        eInitialPriority: Int,
        eAuthoredBy: String,
        sourceEpisodeKey: String,
        rubricVersion: String = "v1",
        responseTendency: String = MemoryExperienceCoordinateEntity.TENDENCY_ENGAGE,
        valence: Float? = null,
        arousal: Float? = null,
        tension: Float? = null,
        growthDelta: String = MemoryExperienceCoordinateEntity.GROWTH_STABLE,
        emotionConfidence: Float = 1.0f,
        revisionReason: String? = null,
        authority: ExperienceAuthority? = null,
    ): MemoryExperienceCoordinateEntity {
        val old = dao.getExperienceById(supersededId)
            ?: error("Existing experience coordinate $supersededId not found")
        val effectiveAuthority = authority ?: when {
            eAuthoredBy == "primary-agent" || eAuthoredBy.startsWith("assistant:") -> {
                val convId = parseConversationIdFromEpisodeKey(sourceEpisodeKey) ?: "unknown"
                ExperienceAuthority.PrimaryAgent(old.ownerId, convId)
            }
            eAuthoredBy == MemoryConstants.ACTOR_USER -> {
                ExperienceAuthority.UserReview(eAuthoredBy)
            }
            else -> {
                throw IllegalArgumentException("Cannot author authoritative CURRENT experience coordinate with untrusted author '$eAuthoredBy' without explicit ExperienceAuthority")
            }
        }
        val coord = MemoryExperienceCoordinateEntity(
            memoryCardId = old.memoryCardId,
            ownerType = old.ownerType,
            ownerId = old.ownerId,
            meaning = meaning,
            eInitialPriority = eInitialPriority,
            eAuthoredBy = effectiveAuthority.authorIdentifier,
            sourceEpisodeKey = sourceEpisodeKey,
            responseTendency = MemoryExperienceCoordinateEntity.normalizeTendency(responseTendency),
            valence = valence?.toDouble() ?: old.valence,
            arousal = arousal?.toDouble()?.coerceIn(0.0, 1.0) ?: old.arousal,
            tension = tension?.toDouble()?.coerceIn(0.0, 1.0) ?: old.tension,
            emotionConfidence = emotionConfidence.toDouble().coerceIn(0.0, 1.0),
            growthDelta = growthDelta,
            status = MemoryExperienceCoordinateEntity.STATUS_CURRENT,
            createdAt = System.currentTimeMillis(),
        )
        return addExperienceSuccessor(supersededId, coord, effectiveAuthority)
    }

    suspend fun addEProposal(
        memoryCardId: String,
        ownerType: String,
        ownerId: String,
        sourceEpisodeKey: String?,
        proposer: String,
        meaning: String? = null,
        initialPriority: Int? = null,
        responseTendency: String? = null,
        valence: Float? = null,
        arousal: Float? = null,
        tension: Float? = null,
        growthDelta: String? = null,
        reason: String? = null,
        confidence: Float? = null,
    ): MemoryEProposalEntity {
        val proposal = MemoryEProposalEntity(
            memoryCardId = memoryCardId,
            ownerType = ownerType,
            ownerId = ownerId,
            sourceEpisodeKey = sourceEpisodeKey,
            proposer = proposer,
            meaning = meaning?.trim()?.ifEmpty { null },
            initialPriority = (initialPriority ?: 50).coerceIn(1, 100),
            responseTendency = MemoryExperienceCoordinateEntity.normalizeTendency(responseTendency),
            valence = valence?.toDouble(),
            arousal = arousal?.toDouble(),
            tension = tension?.toDouble(),
            growthDelta = growthDelta,
            reason = reason,
            confidence = confidence?.toDouble(),
            status = MemoryEProposalEntity.STATUS_PENDING,
            createdAt = System.currentTimeMillis(),
        )
        return addEProposal(proposal)
    }

    open suspend fun getPendingEProposals(ownerType: String, ownerId: String): List<MemoryEProposalEntity> {
        return dao.getPendingEProposals(ownerType, ownerId)
    }

    open fun getPendingEProposalsFlow(ownerType: String, ownerId: String): Flow<List<MemoryEProposalEntity>> {
        return dao.getPendingEProposalsFlow(ownerType, ownerId)
    }
}


data class ConsolidationBatchResult(
    val dailyNote: MemoryDailyNoteEntity,
    val appliedPatches: Int,
    val conflictedPatches: Int,
    val affectedCards: List<MemoryCardEntity>,
    val patchAppliedCardMap: Map<Int, MemoryCardEntity> = emptyMap(),
)

data class FactAuditResult(
    val auditedKeysCount: Int,
    val conflictedKeysCount: Int,
    val pendingAuditsCount: Int = 0,
    val pendingAudits: List<MemoryFactAuditEntity> = emptyList(),
    val supersededCardsCount: Int = 0,
    val resolvedCollisions: List<FactCollisionResolution> = emptyList(),
)

data class FactCollisionResolution(
    val factKey: String,
    val survivingCardId: String,
    val supersededCardIds: List<String>,
)

data class FactKeyAuthoritativeState(
    val privateLatestActiveCardId: String? = null,
    val privateLatestUpdatedAt: Long? = null,
    val privateHasPendingAudit: Boolean = false,
    val globalLatestActiveCardId: String? = null,
    val globalLatestUpdatedAt: Long? = null,
    val globalHasPendingAudit: Boolean = false,
) {
    /**
     * 判断指定卡片在该 factKey 下是否权威有效且允许被召回。
     *
     * 核心规则：
     * 1. 助手私有事实严格优先于 GLOBAL；
     * 2. 只有私有空间没有该 factKey 的活跃事实时，才采用 GLOBAL；
     * 3. 审核状态按事实实际所属空间隔离判定（GLOBAL 的未决审核不影响私有事实召回）。
     */
    fun isAuthoritativeAndValid(card: MemoryCardEntity): Boolean {
        val isPrivate = card.ownerType != MemoryConstants.OWNER_TYPE_GLOBAL

        if (isPrivate) {
            // 私有卡片：只受私有空间内的未决审核与最新卡片约束
            if (privateHasPendingAudit) return false
            if (privateLatestActiveCardId != null && privateLatestActiveCardId != card.id) {
                // 私有空间中存在更新的活跃事实，当前私有卡已被淘汰
                return false
            }
            return true
        } else {
            // 全局卡片：
            // 1. 若当前助手私有空间已有该 factKey 的活跃事实，私有事实优先，全局事实直接被屏蔽
            if (privateLatestActiveCardId != null) return false
            // 2. 若当前助手私有空间存在针对该 factKey 的未决审核，私有事实正在演化中，全局事实不得插足
            if (privateHasPendingAudit) return false
            // 3. 私有空间无该事实时，回退采用 GLOBAL 事实，只受全局空间未决审核与全局最新卡片约束
            if (globalHasPendingAudit) return false
            if (globalLatestActiveCardId != null && globalLatestActiveCardId != card.id) {
                // 全局空间中存在更新的活跃全局事实，当前全局卡已被淘汰
                return false
            }
            return true
        }
    }
}

