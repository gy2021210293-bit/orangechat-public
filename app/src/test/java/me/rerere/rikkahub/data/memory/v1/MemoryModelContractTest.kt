/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemoryRevisionEntity
import me.rerere.rikkahub.data.memory.v1.db.MemorySourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class MemoryModelContractTest {

    @Test
    fun memoryCard_defaultsAndFields() {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val card = MemoryCardEntity(
            id = id,
            ownerType = MemoryConstants.OWNER_TYPE_ASSISTANT,
            ownerId = "test-assistant",
            content = "小雨喜欢看书",
            category = MemoryConstants.CATEGORY_PREFERENCE,
            factKey = "person.hobby",
            status = MemoryConstants.STATUS_ACTIVE,
            confidence = 0.95f,
            importance = 0.8f,
            validFrom = now,
            validTo = null,
            supersededBy = null,
            createdAt = now,
            updatedAt = now,
            revision = 1,
            createdBy = MemoryConstants.ACTOR_MANUAL,
            updatedBy = MemoryConstants.ACTOR_MANUAL,
        )

        assertEquals(id, card.id)
        assertEquals(MemoryConstants.OWNER_TYPE_ASSISTANT, card.ownerType)
        assertEquals("test-assistant", card.ownerId)
        assertEquals("小雨喜欢看书", card.content)
        assertEquals(MemoryConstants.CATEGORY_PREFERENCE, card.category)
        assertEquals("person.hobby", card.factKey)
        assertEquals(MemoryConstants.STATUS_ACTIVE, card.status)
        assertEquals(0.95f, card.confidence)
        assertEquals(0.8f, card.importance)
        assertEquals(1, card.revision)
        assertNull(card.supersededBy)
    }

    @Test
    fun memoryRevision_recordsAuditTrail() {
        val id = Uuid.random().toString()
        val memoryId = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val rev = MemoryRevisionEntity(
            id = id,
            memoryId = memoryId,
            revision = 2,
            operation = MemoryConstants.OP_UPDATE,
            beforeContent = "旧内容",
            afterContent = "新内容",
            beforeStatus = MemoryConstants.STATUS_ACTIVE,
            afterStatus = MemoryConstants.STATUS_ACTIVE,
            reason = "纠正拼写",
            actor = MemoryConstants.ACTOR_MANUAL,
            createdAt = now,
        )

        assertEquals(2, rev.revision)
        assertEquals(MemoryConstants.OP_UPDATE, rev.operation)
        assertEquals("旧内容", rev.beforeContent)
        assertEquals("新内容", rev.afterContent)
        assertEquals("纠正拼写", rev.reason)
    }

    @Test
    fun memorySource_recordsTraceability() {
        val id = Uuid.random().toString()
        val memoryId = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val source = MemorySourceEntity(
            id = id,
            memoryId = memoryId,
            conversationId = "conv-1",
            nodeId = "node-1",
            messageId = "msg-1",
            role = "user",
            sourceKind = "CONVERSATION",
            sourceHash = "hash-123",
            createdAt = now,
        )

        assertEquals("conv-1", source.conversationId)
        assertEquals("node-1", source.nodeId)
        assertEquals("msg-1", source.messageId)
        assertEquals("hash-123", source.sourceHash)
    }
}
