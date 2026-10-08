/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRecallGateTest {

    @Test
    fun `null or blank query should be intercepted as low information`() {
        assertTrue(MemoryRecallGate.isLowInformation(null))
        assertTrue(MemoryRecallGate.isLowInformation(""))
        assertTrue(MemoryRecallGate.isLowInformation("   \t\n "))
    }

    @Test
    fun `pure punctuation or emoji should be intercepted`() {
        assertTrue(MemoryRecallGate.isLowInformation("..."))
        assertTrue(MemoryRecallGate.isLowInformation("？？？"))
        assertTrue(MemoryRecallGate.isLowInformation("！！！"))
        assertTrue(MemoryRecallGate.isLowInformation("，。、；："))
        assertTrue(MemoryRecallGate.isLowInformation("~~~"))
    }

    @Test
    fun `common single-word modal and greetings should be intercepted`() {
        assertTrue(MemoryRecallGate.isLowInformation("嗯"))
        assertTrue(MemoryRecallGate.isLowInformation("嗯嗯"))
        assertTrue(MemoryRecallGate.isLowInformation("哦"))
        assertTrue(MemoryRecallGate.isLowInformation("好"))
        assertTrue(MemoryRecallGate.isLowInformation("好的"))
        assertTrue(MemoryRecallGate.isLowInformation("行"))
        assertTrue(MemoryRecallGate.isLowInformation("哈哈"))
        assertTrue(MemoryRecallGate.isLowInformation("哈哈哈"))
        assertTrue(MemoryRecallGate.isLowInformation("收到"))
        assertTrue(MemoryRecallGate.isLowInformation("1"))
        assertTrue(MemoryRecallGate.isLowInformation("ok"))
        assertTrue(MemoryRecallGate.isLowInformation("OK"))
        assertTrue(MemoryRecallGate.isLowInformation("你好"))
        assertTrue(MemoryRecallGate.isLowInformation("在吗？"))
        assertTrue(MemoryRecallGate.isLowInformation("早安"))
        assertTrue(MemoryRecallGate.isLowInformation("晚安！"))
        assertTrue(MemoryRecallGate.isLowInformation("谢谢呀"))
    }

    @Test
    fun `entity or preference keywords must pass gate`() {
        assertFalse(MemoryRecallGate.isLowInformation("喜欢"))
        assertFalse(MemoryRecallGate.isLowInformation("小雨喜欢喝什么奶茶"))
        assertFalse(MemoryRecallGate.isLowInformation("我的猫叫什么名字"))
        assertFalse(MemoryRecallGate.isLowInformation("生日是哪天"))
        assertFalse(MemoryRecallGate.isLowInformation("电话号码是多少"))
        assertFalse(MemoryRecallGate.isLowInformation("家庭住址在哪"))
        assertFalse(MemoryRecallGate.isLowInformation("你还记得之前的约定吗"))
        assertFalse(MemoryRecallGate.isLowInformation("明天的计划安排"))
    }

    @Test
    fun `question pronouns and temporal followups must pass gate`() {
        assertFalse(MemoryRecallGate.isLowInformation("谁？"))
        assertFalse(MemoryRecallGate.isLowInformation("哪儿？"))
        assertFalse(MemoryRecallGate.isLowInformation("为什么？"))
        assertFalse(MemoryRecallGate.isLowInformation("何时？"))
        assertFalse(MemoryRecallGate.isLowInformation("上次那个朋友是谁"))
        assertFalse(MemoryRecallGate.isLowInformation("昨天说了什么"))
        assertFalse(MemoryRecallGate.isLowInformation("刚才提到的公司叫什么"))
    }

    @Test
    fun `ordinary content queries must pass gate`() {
        assertFalse(MemoryRecallGate.isLowInformation("今天天气真好，去公园散散步"))
        assertFalse(MemoryRecallGate.isLowInformation("帮我写一段 Kotlin 代码"))
    }
}
