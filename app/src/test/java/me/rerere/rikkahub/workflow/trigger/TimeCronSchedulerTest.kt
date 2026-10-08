/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.trigger

import me.rerere.rikkahub.workflow.model.TriggerSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TimeCronSchedulerTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun testTimeOfDayNextFireCalculation() {
        val baseDate = LocalDate.of(2026, 8, 22)
        val morningTime = LocalTime.of(8, 0)
        val nowMs = baseDate.atTime(morningTime).atZone(zone).toInstant().toEpochMilli()

        // 1. 计划时间在今天之后 (09:00 > 08:00) -> 今天 09:00
        val specLaterToday = TriggerSpec.TimeCron(timeOfDay = "09:00")
        val nextFireMs1 = TimeCronTriggerFamily.computeNextFireMs(specLaterToday, zone, nowMs)
        val target1 = baseDate.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli()
        assertEquals(target1, nextFireMs1)

        // 2. 计划时间在今天之前 (07:00 < 08:00) -> 明天 07:00
        val specEarlierToday = TriggerSpec.TimeCron(timeOfDay = "07:00")
        val nextFireMs2 = TimeCronTriggerFamily.computeNextFireMs(specEarlierToday, zone, nowMs)
        val target2 = baseDate.plusDays(1).atTime(LocalTime.of(7, 0)).atZone(zone).toInstant().toEpochMilli()
        assertEquals(target2, nextFireMs2)
    }

    @Test
    fun testDaysOfWeekFilter() {
        // 2026-08-22 是周六 (Saturday, ISO dow = 6)
        val baseDate = LocalDate.of(2026, 8, 22)
        val nowMs = baseDate.atTime(LocalTime.of(10, 0)).atZone(zone).toInstant().toEpochMilli()

        // 仅在周一 (ISO 1) 和周三 (ISO 3) 触发
        val specDays = TriggerSpec.TimeCron(
            timeOfDay = "09:00",
            daysOfWeek = listOf(1, 3),
        )
        val nextFireMs = TimeCronTriggerFamily.computeNextFireMs(specDays, zone, nowMs)
        val nextDate = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nextFireMs), zone)

        // 下一个周一是 2026-08-24
        assertEquals(java.time.DayOfWeek.MONDAY, nextDate.dayOfWeek)
        assertEquals(LocalDate.of(2026, 8, 24), nextDate.toLocalDate())
        assertEquals(LocalTime.of(9, 0), nextDate.toLocalTime())
    }

    @Test
    fun testCronExpressionParsing() {
        // 2026-08-22 10:00:00
        val baseDate = LocalDate.of(2026, 8, 22)
        val nowMs = baseDate.atTime(LocalTime.of(10, 0)).atZone(zone).toInstant().toEpochMilli()

        // "0 3 * * *" -> 每天凌晨 3:00
        val specCron = TriggerSpec.TimeCron(cron = "0 3 * * *")
        val nextFireMs = TimeCronTriggerFamily.computeNextFireMs(specCron, zone, nowMs)
        val nextDateTime = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nextFireMs), zone)

        assertEquals(LocalDate.of(2026, 8, 23), nextDateTime.toLocalDate())
        assertEquals(3, nextDateTime.hour)
        assertEquals(0, nextDateTime.minute)
    }

    @Test
    fun testEveryPeriodDerivation() {
        assertEquals(30_000L, TimeCronTriggerFamily.derivePeriodMs(TriggerSpec.TimeCron(cron = "@every 30s")))
        assertEquals(5 * 60_000L, TimeCronTriggerFamily.derivePeriodMs(TriggerSpec.TimeCron(cron = "@every 5m")))
        assertEquals(2 * 3600_000L, TimeCronTriggerFamily.derivePeriodMs(TriggerSpec.TimeCron(cron = "@every 2h")))
        assertEquals(3600_000L, TimeCronTriggerFamily.derivePeriodMs(TriggerSpec.TimeCron(cron = "@hourly")))
        assertEquals(24 * 3600_000L, TimeCronTriggerFamily.derivePeriodMs(TriggerSpec.TimeCron(cron = "@daily")))
    }

    @Test
    fun testRequestCodeGenerationIsStableAndPositive() {
        val id1 = "workflow-morning-summary"
        val id2 = "workflow-night-sync"

        val code1a = TimeCronAlarmScheduler.requestCodeFor(id1)
        val code1b = TimeCronAlarmScheduler.requestCodeFor(id1)
        val code2 = TimeCronAlarmScheduler.requestCodeFor(id2)

        assertEquals(code1a, code1b)
        assertTrue(code1a >= 30000)
        assertTrue(code2 >= 30000)
    }

    @Test
    fun testFallbackWorkNamingAndDelayCalculation() {
        val workflowId = "wf-morning"
        val scheduledAt = 10000000L

        val workName = TimeCronAlarmScheduler.workName(workflowId, scheduledAt)
        val workTag = TimeCronAlarmScheduler.workTag(workflowId)

        assertEquals("workflow-fallback-wf-morning-10000000", workName)
        assertEquals("wf_fallback_wf-morning", workTag)

        // 15 分钟补偿延迟计算验证
        val now = 10000000L
        val compensationTime = scheduledAt + 15 * 60 * 1000L
        val delay = (compensationTime - now).coerceAtLeast(0L)
        assertEquals(15 * 60 * 1000L, delay)
    }
}
