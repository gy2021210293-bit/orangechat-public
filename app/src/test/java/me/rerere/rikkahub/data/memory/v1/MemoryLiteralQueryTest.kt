/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryLiteralQueryTest {

    @Test
    fun `detects quotes and book titles`() {
        assertTrue(MemoryLiteralQuery.shouldRunLiteralSearch("你记得《三体》里面讲了什么吗"))
        assertTrue(MemoryLiteralQuery.shouldRunLiteralSearch("我上次说的“红烧牛肉面”"))
        assertTrue(MemoryLiteralQuery.shouldRunLiteralSearch("关于「量子纠缠」的想法"))
        assertTrue(MemoryLiteralQuery.shouldRunLiteralSearch("叫'橘瓣'的那个项目"))

        val terms1 = MemoryLiteralQuery.extractLiteralQueryTerms("你记得《三体》里面讲了什么吗")
        assertTrue(terms1.contains("三体"))

        val terms2 = MemoryLiteralQuery.extractLiteralQueryTerms("我上次说的“红烧牛肉面”")
        assertTrue(terms2.contains("红烧牛肉面"))
    }

    @Test
    fun `detects trigger words with proper nouns`() {
        assertTrue(MemoryLiteralQuery.shouldRunLiteralSearch("搜一下蘸水菜"))
        assertTrue(MemoryLiteralQuery.shouldRunLiteralSearch("查一下邦德"))
        assertTrue(MemoryLiteralQuery.shouldRunLiteralSearch("你记得小明吗"))
        assertTrue(MemoryLiteralQuery.shouldRunLiteralSearch("提到过猫咪"))

        val terms = MemoryLiteralQuery.extractLiteralQueryTerms("搜一下蘸水菜")
        assertTrue(terms.contains("蘸水菜"))
    }

    @Test
    fun `detects english codenames`() {
        assertTrue(MemoryLiteralQuery.shouldRunLiteralSearch("那个deepseek-v3怎么样"))
        assertTrue(MemoryLiteralQuery.shouldRunLiteralSearch("关于claude-3-7-sonnet"))

        val terms = MemoryLiteralQuery.extractLiteralQueryTerms("关于claude-3-7-sonnet")
        assertTrue(terms.any { it.contains("claude") })
    }

    @Test
    fun `filters out generic cjk stops and short chats`() {
        assertFalse(MemoryLiteralQuery.shouldRunLiteralSearch("今天"))
        assertFalse(MemoryLiteralQuery.shouldRunLiteralSearch("刚才"))
        assertFalse(MemoryLiteralQuery.shouldRunLiteralSearch("为什么"))
        assertFalse(MemoryLiteralQuery.shouldRunLiteralSearch("好的"))
        assertFalse(MemoryLiteralQuery.shouldRunLiteralSearch("哈哈"))
    }

    @Test
    fun `detects short specific cjk queries`() {
        assertTrue(MemoryLiteralQuery.shouldRunLiteralSearch("生煎包"))
        assertTrue(MemoryLiteralQuery.shouldRunLiteralSearch("羽毛球拍"))

        val terms = MemoryLiteralQuery.extractLiteralQueryTerms("生煎包")
        assertTrue(terms.contains("生煎包"))
    }
}
