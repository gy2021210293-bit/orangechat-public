/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 本地长期记忆日时间工具：
 * 规定“记忆日”边界为本地时间 04:00 到次日 03:59:59.999。
 */
object MemoryDateUtils {
    private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE

    fun getMemoryDate(
        timestamp: Long = System.currentTimeMillis(),
        boundaryHour: Int = 4,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val instant = Instant.ofEpochMilli(timestamp)
        val zonedDateTime = instant.atZone(zoneId)
        val memoryDate = zonedDateTime.minusHours(boundaryHour.toLong()).toLocalDate()
        return memoryDate.format(DATE_FORMATTER)
    }

    fun getMemoryDate(
        timestamp: Long,
        zoneId: ZoneId,
    ): String = getMemoryDate(timestamp, 4, zoneId)

    fun getMemoryDateRange(
        memoryDate: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Pair<Long, Long> {
        val localDate = LocalDate.parse(memoryDate, DATE_FORMATTER)
        val startInstant = localDate.atTime(4, 0, 0, 0).atZone(zoneId).toInstant()
        val endInstant = localDate.plusDays(1).atTime(3, 59, 59, 999_000_000).atZone(zoneId).toInstant()
        return Pair(startInstant.toEpochMilli(), endInstant.toEpochMilli())
    }

    fun getTodayMemoryDate(
        timestamp: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String = getMemoryDate(timestamp, zoneId)

    fun getYesterdayMemoryDate(
        timestamp: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val today = getMemoryDate(timestamp, zoneId)
        val localDate = LocalDate.parse(today, DATE_FORMATTER)
        return localDate.minusDays(1).format(DATE_FORMATTER)
    }
}
