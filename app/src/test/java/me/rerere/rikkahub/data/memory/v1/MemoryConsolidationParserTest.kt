/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MemoryConsolidationParserTest {

    @Test
    fun testParsePureJson() {
        val json = """
            {
              "daily_note": {
                "summary": "小雨今天讨论了关于长期记忆的重构方案，重点关注了日记与Patch机制。",
                "emotional_thread": "态度专注且严谨",
                "unfinished_topics": ["WorkManager调度验证"]
              },
              "patches": [
                {
                  "action": "ADD",
                  "category": "plan",
                  "content": "小雨正在推进长期记忆V1的Phase 5开发",
                  "confidence": 0.95,
                  "importance": 0.8,
                  "reason": "新计划"
                }
              ]
            }
        """.trimIndent()

        val result = MemoryConsolidationParser.parse(json)
        assertEquals("小雨今天讨论了关于长期记忆的重构方案，重点关注了日记与Patch机制。", result.dailyNote.summary)
        assertEquals("态度专注且严谨", result.dailyNote.emotionalThread)
        assertEquals(listOf("WorkManager调度验证"), result.dailyNote.unfinishedTopics)
        assertEquals(1, result.patches.size)
        val patch = result.patches[0]
        assertEquals(MemoryPatchAction.ADD, patch.action)
        assertEquals("plan", patch.category)
        assertEquals("小雨正在推进长期记忆V1的Phase 5开发", patch.content)
        assertEquals(0.95f, patch.confidence ?: 0f, 0.01f)
    }

    @Test
    fun testParseMarkdownCodeBlockWithSurroundingText() {
        val raw = """
            好的，我已经完成了今天的记忆整理，以下是整理结果：
            ```json
            {
              "daily_note": {
                "summary": "今天进行了多轮对话，小雨表达了对代码简洁性的偏好。",
                "emotional_thread": "愉快轻松",
                "unfinished_topics": []
              },
              "patches": [
                {
                  "action": "UPDATE",
                  "target_id": "card_123",
                  "expected_revision": 2,
                  "category": "preference",
                  "content": "小雨偏好极简且针对性的重构方案",
                  "reason": "细化了偏好"
                },
                {
                  "action": "NOOP"
                }
              ]
            }
            ```
            请查收。
        """.trimIndent()

        val result = MemoryConsolidationParser.parse(raw)
        assertEquals("今天进行了多轮对话，小雨表达了对代码简洁性的偏好。", result.dailyNote.summary)
        assertEquals(2, result.patches.size)
        assertEquals(MemoryPatchAction.UPDATE, result.patches[0].action)
        assertEquals("card_123", result.patches[0].targetId)
        assertEquals(2, result.patches[0].expectedRevision)
        assertEquals(MemoryPatchAction.NOOP, result.patches[1].action)
    }

    @Test
    fun testParseAllActions() {
        val json = """
            {
              "daily_note": { "summary": "测试各种Action" },
              "patches": [
                { "action": "ADD", "content": "内容1" },
                { "action": "UPDATE", "target_id": "c1", "expected_revision": 1, "content": "更新1" },
                { "action": "MERGE", "target_id": "c1", "secondary_ids": ["c2", "c3"], "expected_revision": 2, "content": "合并后内容" },
                { "action": "SUPERSEDE", "target_id": "c1", "expected_revision": 3, "content": "纠正后新事实" },
                { "action": "ARCHIVE", "target_id": "c4", "expected_revision": 1, "reason": "过期" },
                { "action": "NOOP" }
              ]
            }
        """.trimIndent()

        val result = MemoryConsolidationParser.parse(json)
        assertEquals(6, result.patches.size)
        assertEquals(MemoryPatchAction.ADD, result.patches[0].action)
        assertEquals(MemoryPatchAction.UPDATE, result.patches[1].action)
        assertEquals(MemoryPatchAction.MERGE, result.patches[2].action)
        assertEquals(listOf("c2", "c3"), result.patches[2].secondaryIds)
        assertEquals(MemoryPatchAction.SUPERSEDE, result.patches[3].action)
        assertEquals(MemoryPatchAction.ARCHIVE, result.patches[4].action)
        assertEquals(MemoryPatchAction.NOOP, result.patches[5].action)
    }

    @Test
    fun testParseWithThinkTag() {
        val raw = """
            <think>
            分析上下文：
            { "temp_scratchpad": { "step": 1 } }
            得出结论。
            </think>
            ```json
            {
              "daily_note": {
                "summary": "小雨今天测试了带思考标签的解析能力。",
                "emotional_thread": "严密",
                "unfinished_topics": []
              },
              "patches": [
                {
                  "action": "ADD",
                  "content": "成功剥离思考标签"
                }
              ]
            }
            ```
        """.trimIndent()

        val result = MemoryConsolidationParser.parse(raw)
        assertEquals("小雨今天测试了带思考标签的解析能力。", result.dailyNote.summary)
        assertEquals(1, result.patches.size)
        assertEquals(MemoryPatchAction.ADD, result.patches[0].action)
        assertEquals("成功剥离思考标签", result.patches[0].content)
    }
}

