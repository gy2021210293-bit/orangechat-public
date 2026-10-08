/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MemoryDateUtilsTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun testMemoryDateBoundary() {
        // 2026-09-09 03:59:59 should belong to memory date 2026-09-08
        val beforeBoundary = LocalDateTime.of(2026, 9, 9, 3, 59, 59)
            .atZone(zoneId).toInstant().toEpochMilli()
        assertEquals("2026-09-08", MemoryDateUtils.getMemoryDate(beforeBoundary, zoneId))

        // 2026-09-09 04:00:00 should belong to memory date 2026-09-09
        val atBoundary = LocalDateTime.of(2026, 9, 9, 4, 0, 0)
            .atZone(zoneId).toInstant().toEpochMilli()
        assertEquals("2026-09-09", MemoryDateUtils.getMemoryDate(atBoundary, zoneId))

        // 2026-09-09 23:59:59 should belong to memory date 2026-09-09
        val nightTime = LocalDateTime.of(2026, 9, 9, 23, 59, 59)
            .atZone(zoneId).toInstant().toEpochMilli()
        assertEquals("2026-09-09", MemoryDateUtils.getMemoryDate(nightTime, zoneId))
    }

    @Test
    fun testMemoryDateRange() {
        val (start, end) = MemoryDateUtils.getMemoryDateRange("2026-09-08", zoneId)
        val expectedStart = LocalDateTime.of(2026, 9, 8, 4, 0, 0)
            .atZone(zoneId).toInstant().toEpochMilli()
        val expectedEnd = LocalDateTime.of(2026, 9, 9, 3, 59, 59, 999_000_000)
            .atZone(zoneId).toInstant().toEpochMilli()

        assertEquals(expectedStart, start)
        assertEquals(expectedEnd, end)
    }

    @Test
    fun testYesterdayMemoryDate() {
        val timestamp = LocalDateTime.of(2026, 9, 9, 10, 0, 0)
            .atZone(zoneId).toInstant().toEpochMilli()
        assertEquals("2026-09-09", MemoryDateUtils.getTodayMemoryDate(timestamp, zoneId))
        assertEquals("2026-09-08", MemoryDateUtils.getYesterdayMemoryDate(timestamp, zoneId))
    }
}
