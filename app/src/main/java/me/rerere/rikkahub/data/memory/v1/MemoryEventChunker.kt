/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.memory.v1.db.MemoryEventChunkEntity
import java.security.MessageDigest
import kotlin.uuid.Uuid

object MemoryEventChunker {

    /**
     * 相邻消息时间间隔超过 2 小时视为新事件块
     */
    const val INACTIVITY_THRESHOLD_MS = 2 * 60 * 60 * 1000L

    /**
     * 单个事件块的最大字符数，超出则切分以避免块过大
     */
    const val MAX_CHUNK_CHARS = 8_000

    /**
     * 将清洗后的对话消息流按会话边界与静默间隔切分为事件块。
     */
    fun chunkMessages(
        ownerType: String,
        ownerId: String,
        messages: List<CleanedDialogueMessage>,
    ): List<MemoryEventChunkEntity> {
        if (messages.isEmpty()) return emptyList()

        val sorted = messages.sortedBy { it.timestamp }
        val chunks = mutableListOf<List<CleanedDialogueMessage>>()
        var current = mutableListOf<CleanedDialogueMessage>()
        var currentChars = 0

        for (msg in sorted) {
            val shouldSplit = if (current.isEmpty()) {
                false
            } else {
                val lastMsg = current.last()
                val convChanged = msg.conversationId != lastMsg.conversationId
                val timeGap = (msg.timestamp - lastMsg.timestamp) > INACTIVITY_THRESHOLD_MS
                val charsOverflow = (currentChars + msg.content.length) > MAX_CHUNK_CHARS
                convChanged || timeGap || charsOverflow
            }

            if (shouldSplit) {
                chunks.add(current)
                current = mutableListOf()
                currentChars = 0
            }

            current.add(msg)
            currentChars += msg.content.length
        }

        if (current.isNotEmpty()) {
            chunks.add(current)
        }

        val now = System.currentTimeMillis()
        return chunks.map { chunkMsgs ->
            val first = chunkMsgs.first()
            val last = chunkMsgs.last()
            val summary = buildSummary(chunkMsgs)
            val fingerprint = computeChunkFingerprint(chunkMsgs)

            val msgIdsJson = kotlinx.serialization.json.Json.encodeToString(chunkMsgs.map { it.messageId })

            MemoryEventChunkEntity(
                id = "chunk_" + fingerprint.take(24),
                ownerType = ownerType,
                ownerId = ownerId,
                conversationId = first.conversationId,
                summary = summary,
                startTime = first.timestamp,
                endTime = last.timestamp,
                messageIds = msgIdsJson,
                status = "PROCESSED",
                fingerprint = fingerprint,
                createdAt = now,
            )
        }
    }

    private fun buildSummary(messages: List<CleanedDialogueMessage>): String {
        val userCount = messages.count { it.role.equals("USER", ignoreCase = true) }
        val assistantCount = messages.count { it.role.equals("ASSISTANT", ignoreCase = true) }
        val otherCount = messages.size - userCount - assistantCount
        val totalChars = messages.sumOf { it.content.length }
        return if (otherCount > 0) {
            "对话事件块 (${messages.size} 条消息: $userCount 用户, $assistantCount 助手, $otherCount 其他, 共 $totalChars 字符)"
        } else {
            "对话事件块 (${messages.size} 条消息: $userCount 用户, $assistantCount 助手, 共 $totalChars 字符)"
        }
    }

    private fun computeChunkFingerprint(messages: List<CleanedDialogueMessage>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (m in messages) {
            md.update(m.conversationId.toByteArray())
            md.update(m.messageId.toByteArray())
            md.update(m.content.toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
