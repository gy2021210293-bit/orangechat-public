/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.memory

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryDailyNoteDisplayTest {

    @Test
    fun `unfinished topics JSON is displayed as individual topics`() {
        assertEquals(
            listOf("确认答辩时间", "完善实验图"),
            parseUnfinishedTopics("[\"确认答辩时间\",\"完善实验图\"]"),
        )
    }

    @Test
    fun `malformed unfinished topics is displayed as one topic`() {
        assertEquals(
            listOf("旧格式待跟进话题"),
            parseUnfinishedTopics("旧格式待跟进话题"),
        )
    }

    @Test
    fun `empty or blank unfinished topics returns empty list`() {
        assertEquals(emptyList<String>(), parseUnfinishedTopics(""))
        assertEquals(emptyList<String>(), parseUnfinishedTopics("   "))
        assertEquals(emptyList<String>(), parseUnfinishedTopics("[]"))
        assertEquals(emptyList<String>(), parseUnfinishedTopics("null"))
        assertEquals(emptyList<String>(), parseUnfinishedTopics("NULL"))
    }

    @Test
    fun `bracketed unquoted unfinished topics falls back to list items rather than char iteration`() {
        assertEquals(
            listOf("确认答辩时间", "完善实验图"),
            parseUnfinishedTopics("[确认答辩时间, 完善实验图]"),
        )
    }
}
