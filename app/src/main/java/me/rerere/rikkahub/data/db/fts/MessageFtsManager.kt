/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.db.fts

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import java.time.Instant
import kotlinx.datetime.toInstant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

private const val TAG = "MessageFtsManager"

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    /**
     * 全量重建某个对话的 FTS 索引。用于新建对话（insertConversation）场景，
     * 此时对话本来就是从零开始，全量写入就是唯一需要做的事，没有"多余重建"的问题。
     *
     * 注意：不在这里自行 withContext(Dispatchers.IO)，因为 database.withTransaction 本身
     * 是可重入(reentrant)的挂起 API，会自动处理线程调度；如果这里额外包一层 withContext，
     * 在被 ConversationRepository.updateConversation 的外层事务嵌套调用时反而容易引入
     * 不必要的调度切换。调用方（ConversationRepository）已经是 suspend 函数，天然运行在
     * 非主线程的协程里，不需要这里再兜底。
     */
    suspend fun indexConversation(conversation: Conversation) {
        try {
            database.withTransaction {
                val conversationId = conversation.id.toString()
                db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
                conversation.messageNodes.forEach { node ->
                    node.messages.forEach { message ->
                        val text = message.extractFtsText()
                        if (text.isNotBlank()) {
                            val msgCreatedAt = runCatching {
                                message.createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds()
                            }.getOrDefault(conversation.updateAt.toEpochMilli())
                            db.execSQL(
                                "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                                arrayOf(
                                    text,
                                    node.id.toString(),
                                    message.id.toString(),
                                    conversationId,
                                    conversation.title,
                                    msgCreatedAt.toString(),
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "indexConversation failed, conversationId=${conversation.id}", e)
        }
    }

    /**
     * 增量重建 FTS 索引：只处理指定的 node id 集合，而不是整个对话的全部历史消息。
     * 用于 ConversationRepository.updateConversation 场景 —— 每次保存对话时，
     * 只有极少数 node 真正发生变化，没必要把这个对话全部历史消息的索引都删光重建。
     *
     * @param changedNodeIds 本次发生变化（新增/修改/删除）的 node id 集合
     * @param currentNodes 当前完整的 messageNodes 列表，用于取出仍然存在的 node 的最新内容；
     *                     如果某个 id 不在这个列表里，说明它已被删除，只清索引、不重建
     */
    suspend fun reindexNodes(
        conversationId: String,
        conversationTitle: String,
        updateAt: Instant,
        changedNodeIds: Set<String>,
        currentNodes: List<MessageNode>,
    ) {
        if (changedNodeIds.isEmpty()) return
        try {
            database.withTransaction {
                val currentNodesById = currentNodes.associateBy { it.id.toString() }
                changedNodeIds.forEach { nodeId ->
                    db.execSQL("DELETE FROM message_fts WHERE node_id = ?", arrayOf(nodeId))
                    val node = currentNodesById[nodeId] ?: return@forEach
                    node.messages.forEach { message ->
                        val text = message.extractFtsText()
                        if (text.isNotBlank()) {
                            val msgCreatedAt = runCatching {
                                message.createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds()
                            }.getOrDefault(updateAt.toEpochMilli())
                            db.execSQL(
                                "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                                arrayOf(
                                    text,
                                    nodeId,
                                    message.id.toString(),
                                    conversationId,
                                    conversationTitle,
                                    msgCreatedAt.toString(),
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "reindexNodes failed, conversationId=$conversationId, changedNodeIds=$changedNodeIds",
                e
            )
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        try {
            database.withTransaction {
                db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteConversation failed, conversationId=$conversationId", e)
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        try {
            database.withTransaction {
                db.execSQL("DELETE FROM message_fts")
            }
        } catch (e: Exception) {
            Log.e(TAG, "deleteAll failed", e)
        }
    }

    suspend fun search(keyword: String): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        try {
            val cursor = db.query(
                """
                SELECT node_id, message_id, conversation_id, title, update_at,
                       simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
                FROM message_fts
                WHERE text MATCH jieba_query(?)
                ORDER BY rank, update_at DESC
                LIMIT 50
                """.trimIndent(),
                arrayOf(keyword)
            )
            Log.i(TAG, "search: $keyword")
            cursor.use {
                while (it.moveToNext()) {
                    results.add(
                        MessageSearchResult(
                            nodeId = it.getString(0),
                            messageId = it.getString(1),
                            conversationId = it.getString(2),
                            title = it.getString(3),
                            updateAt = Instant.ofEpochMilli(it.getLong(4)),
                            snippet = it.getString(5),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "search failed, keyword=$keyword", e)
        }
        results
    }

    /**
     * 按助手与时间窗口检索聊天原文证据（用于长期记忆辅助核对与导航）。
     *
     * @param query 搜索词或短语
     * @param assistantId 所属助手 ID
     * @param sinceEpochMilli 起始时间戳（如近 90 天）
     * @param excludeMessageId 需排除的消息 ID（如当前轮次输入）
     * @param limit 返回条数上限
     */
    suspend fun searchEvidence(
        query: String,
        assistantId: String,
        sinceEpochMilli: Long,
        excludeMessageId: String? = null,
        limit: Int = 10,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val results = mutableListOf<MessageSearchResult>()
        try {
            val sql = if (excludeMessageId != null) {
                """
                SELECT message_fts.node_id, message_fts.message_id, message_fts.conversation_id, message_fts.title, message_fts.update_at,
                       simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
                FROM message_fts
                INNER JOIN conversationentity ON conversationentity.id = message_fts.conversation_id
                WHERE message_fts.text MATCH jieba_query(?)
                  AND conversationentity.assistant_id = ?
                  AND CAST(message_fts.update_at AS INTEGER) >= ?
                  AND message_fts.message_id != ?
                ORDER BY message_fts.rank, message_fts.update_at DESC
                LIMIT ?
                """.trimIndent()
            } else {
                """
                SELECT message_fts.node_id, message_fts.message_id, message_fts.conversation_id, message_fts.title, message_fts.update_at,
                       simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
                FROM message_fts
                INNER JOIN conversationentity ON conversationentity.id = message_fts.conversation_id
                WHERE message_fts.text MATCH jieba_query(?)
                  AND conversationentity.assistant_id = ?
                  AND CAST(message_fts.update_at AS INTEGER) >= ?
                ORDER BY message_fts.rank, message_fts.update_at DESC
                LIMIT ?
                """.trimIndent()
            }
            val args = if (excludeMessageId != null) {
                arrayOf(trimmed, assistantId, sinceEpochMilli.toString(), excludeMessageId, limit.toString())
            } else {
                arrayOf(trimmed, assistantId, sinceEpochMilli.toString(), limit.toString())
            }

            val cursor = db.query(sql, args)
            cursor.use {
                while (it.moveToNext()) {
                    results.add(
                        MessageSearchResult(
                            nodeId = it.getString(0),
                            messageId = it.getString(1),
                            conversationId = it.getString(2),
                            title = it.getString(3),
                            updateAt = Instant.ofEpochMilli(it.getLong(4)),
                            snippet = it.getString(5),
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "searchEvidence failed, query=$trimmed, assistantId=$assistantId", e)
        }
        results
    }
}

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)