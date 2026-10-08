/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DesireEvolutionEngineTest {

    @Test
    fun testSteppingConsistency_1HourSingleVsMultipleSteps() {
        val initialDrives = createDefaultDrives()

        // 1 次结算 1 小时（用户已离开 1 小时）
        val singleStep = DesireEvolutionEngine.evolve(
            drives = initialDrives,
            elapsedHours = 1.0,
            absentHoursStart = 1.0,
        )

        // 4 次每次 15 分钟累积结算
        var multiStep = initialDrives
        var absent = 1.0
        for (i in 0 until 4) {
            multiStep = DesireEvolutionEngine.evolve(
                drives = multiStep,
                elapsedHours = 0.25,
                absentHoursStart = absent,
            )
            absent += 0.25
        }

        // 验证各维度数值一致（浮点误差 < 1e-6）
        for (key in initialDrives.keys) {
            val v1 = singleStep[key]?.value ?: 0.0
            val v2 = multiStep[key]?.value ?: 0.0
            assertEquals("Stepping consistency mismatch for drive: $key", v1, v2, 1e-5)
        }
    }

    @Test
    fun testClockRollback_noChange() {
        val initialDrives = createDefaultDrives()
        val result = DesireEvolutionEngine.evolve(
            drives = initialDrives,
            elapsedHours = -1.5,
            absentHoursStart = 2.0,
        )
        for (key in initialDrives.keys) {
            assertEquals(initialDrives[key]?.value ?: 0.0, result[key]?.value ?: 0.0, 1e-6)
        }
    }

    @Test
    fun testAttachment_doesNotGrowWithinHalfHour() {
        val initialDrives = createDefaultDrives()
        val result = DesireEvolutionEngine.evolve(
            drives = initialDrives,
            elapsedHours = 0.4,
            absentHoursStart = 0.0,
        )
        assertEquals(40.0, result["attachment"]?.value ?: 0.0, 1e-6)
        assertEquals(40.0, result["duty"]?.value ?: 0.0, 1e-6)
    }

    @Test
    fun testDuty_crossesDefaultActionThresholdAt8_0Hours() {
        val initialDrives = createDefaultDrives()
        // 8.0 小时离开：前 0.5h 不长，后 7.5h 以 +2.0/h 增长 15 -> 40 + 15 = 55.0
        val result = DesireEvolutionEngine.evolve(
            drives = initialDrives,
            elapsedHours = 8.0,
            absentHoursStart = 0.0,
        )
        val dutyVal = result["duty"]?.value ?: 0.0
        assertEquals(55.0, dutyVal, 1e-5)
    }

    @Test
    fun testAttachment_crosses60At3Hours() {
        val initialDrives = createDefaultDrives()
        // 3.0 小时离开：前 0.5h 不长，后 2.5h 以 +8.0/h 增长 20 -> 40 + 20 = 60.0
        val result = DesireEvolutionEngine.evolve(
            drives = initialDrives,
            elapsedHours = 3.0,
            absentHoursStart = 0.0,
        )
        val attachmentVal = result["attachment"]?.value ?: 0.0
        assertEquals(60.0, attachmentVal, 1e-5)
    }

    @Test
    fun testIntimacy_crosses65At12_5Hours() {
        val initialDrives = createDefaultDrives()
        // 初始 40.0，以 +2.0/h 增长，到达 65.0 需要 (65 - 40) / 2.0 = 12.5 小时
        val result = DesireEvolutionEngine.evolve(
            drives = initialDrives,
            elapsedHours = 12.5,
            absentHoursStart = 0.0,
        )
        val intimacyVal = result["intimacy"]?.value ?: 0.0
        assertEquals(65.0, intimacyVal, 1e-5)
    }

    @Test
    fun testDecayingDrives_halfLifeTrajectory() {
        // 设置初始高疲劳 (80.0, 基线 20.0, 半衰期 6h)，其他驱动力处于基线
        val customFatigue = createDefaultDrives().toMutableMap().apply {
            this["fatigue"] = this["fatigue"]!!.copy(value = 80.0)
        }
        val after6h = DesireEvolutionEngine.evolve(
            drives = customFatigue,
            elapsedHours = 6.0,
            absentHoursStart = 0.0,
        )
        assertEquals(50.0, after6h["fatigue"]?.value ?: 0.0, 1e-5)

        // 设置初始高压力 (60.0, 基线 20.0, 半衰期 8h)，喜悦处于基线 50.0，想念感设为 0 避免 6.5h 后顶峰溢出干扰纯衰减
        val customStress = createDefaultDrives().toMutableMap().apply {
            this["stress"] = this["stress"]!!.copy(value = 60.0)
            this["attachment"] = this["attachment"]!!.copy(value = 0.0)
        }
        val after8h = DesireEvolutionEngine.evolve(
            drives = customStress,
            elapsedHours = 8.0,
            absentHoursStart = 0.0,
        )
        assertEquals(40.0, after8h["stress"]?.value ?: 0.0, 1e-5)

        // 设置初始高喜悦 (90.0, 基线 50.0, 半衰期 3h)
        val customJoy = createDefaultDrives().toMutableMap().apply {
            this["joy"] = this["joy"]!!.copy(value = 90.0)
        }
        val after3h = DesireEvolutionEngine.evolve(
            drives = customJoy,
            elapsedHours = 3.0,
            absentHoursStart = 0.0,
        )
        assertEquals(70.0, after3h["joy"]?.value ?: 0.0, 1e-5)
    }

    @Test
    fun testCoupling_joyAcceleratesStressDecayAndBoostsCuriosity() {
        // 1. 高喜悦温和加速压力衰减（在 4 小时内测试，此时想念感尚未达到 100）
        val drivesDefault = createDefaultDrives().toMutableMap().apply {
            this["stress"] = this["stress"]!!.copy(value = 60.0)
        }
        val drivesWithHighJoy = createDefaultDrives().toMutableMap().apply {
            this["joy"] = this["joy"]!!.copy(value = 90.0) // > 60.0
            this["stress"] = this["stress"]!!.copy(value = 60.0)
        }
        val resNormalStress = DesireEvolutionEngine.evolve(drivesDefault, 4.0, 0.0)["stress"]?.value ?: 0.0
        val resHighJoyStress = DesireEvolutionEngine.evolve(drivesWithHighJoy, 4.0, 0.0)["stress"]?.value ?: 0.0
        // 高喜悦下半衰期从 8h 缩短为 6h，因此衰减后压力显著低于普通喜悦
        assertTrue(
            "Stress under high joy ($resHighJoyStress) should decay faster than normal ($resNormalStress)",
            resHighJoyStress < resNormalStress
        )

        // 2. 高喜悦滋养好奇心
        val drivesDefaultJoy = createDefaultDrives()
        val resNormal = DesireEvolutionEngine.evolve(drivesDefaultJoy, 2.0, 0.0)
        val resBoosted = DesireEvolutionEngine.evolve(drivesWithHighJoy, 2.0, 0.0)
        val curiosityNormal = resNormal["curiosity"]?.value ?: 0.0
        val curiosityBoosted = resBoosted["curiosity"]?.value ?: 0.0
        assertTrue(
            "Curiosity with high joy should be higher ($curiosityBoosted > $curiosityNormal)",
            curiosityBoosted > curiosityNormal
        )
    }

    @Test
    fun testCoupling_saturationToStressAndFatigueScheme1() {
        val initialDrives = createDefaultDrives()

        // 离线 14.0h (顶峰持续 6h)：想念感满 100，压力升至约 43，疲劳仍在基线 20 (未达 68 门槛)
        val after14_0h = DesireEvolutionEngine.evolve(initialDrives, 14.0, 0.0)
        assertEquals(100.0, after14_0h["attachment"]?.value ?: 0.0, 1e-5)
        val stress14_0 = after14_0h["stress"]?.value ?: 0.0
        val fatigue14_0 = after14_0h["fatigue"]?.value ?: 0.0
        assertTrue("Stress at 14.0h should be ~43 (got $stress14_0)", stress14_0 in 40.0..46.0)
        assertEquals("Fatigue should stay at baseline 20.0 (got $fatigue14_0)", 20.0, fatigue14_0, 1e-4)

        // 离线 20.0h (顶峰持续 12h)：压力升至约 57，疲劳依然在基线 20 (未达 68 门槛)
        val after20_0h = DesireEvolutionEngine.evolve(initialDrives, 20.0, 0.0)
        val stress20_0 = after20_0h["stress"]?.value ?: 0.0
        val fatigue20_0 = after20_0h["fatigue"]?.value ?: 0.0
        assertTrue("Stress at 20.0h should be ~57 (got $stress20_0)", stress20_0 in 55.0..60.0)
        assertEquals("Fatigue should stay at baseline 20.0 (got $fatigue20_0)", 20.0, fatigue20_0, 1e-4)

        // 离线 32.0h (顶峰持续 24h，整整一天顶峰)：压力超过 68，疲劳开始露头至约 38
        val after32_0h = DesireEvolutionEngine.evolve(initialDrives, 32.0, 0.0)
        val stress32_0 = after32_0h["stress"]?.value ?: 0.0
        val fatigue32_0 = after32_0h["fatigue"]?.value ?: 0.0
        assertTrue("Stress at 32.0h should be ~70 (got $stress32_0)", stress32_0 in 68.0..73.0)
        assertTrue("Fatigue at 32.0h should start showing ~38 (got $fatigue32_0)", fatigue32_0 in 34.0..42.0)
    }

    @Test
    fun testCoupling_fatigueHighFreezesCuriosityAndSocial() {
        val highFatigueDrives = createDefaultDrives().toMutableMap().apply {
            this["fatigue"] = this["fatigue"]!!.copy(value = 80.0) // >= 70.0
        }
        val result = DesireEvolutionEngine.evolve(highFatigueDrives, 4.0, 0.0)
        val curiosityVal = result["curiosity"]?.value ?: 0.0
        val socialVal = result["social"]?.value ?: 0.0
        // 初始值分别为 40.0 和 30.0，在高疲劳下完全冻结不增长
        assertEquals("Curiosity should be frozen at 40.0", 40.0, curiosityVal, 1e-5)
        assertEquals("Social should be frozen at 30.0", 30.0, socialVal, 1e-5)
    }

    @Test
    fun testLongTermTrajectory_24h_3d_7d_bounded() {
        val initialDrives = createDefaultDrives()

        val after24h = DesireEvolutionEngine.evolve(initialDrives, 24.0, 0.0)
        val after3d = DesireEvolutionEngine.evolve(initialDrives, 72.0, 0.0)
        val after7d = DesireEvolutionEngine.evolve(initialDrives, 168.0, 0.0) // 超过 72h 上限截断

        // 验证所有值均在 [0, 100] 之间
        for (key in initialDrives.keys) {
            val v24 = after24h[key]?.value ?: 0.0
            val v72 = after3d[key]?.value ?: 0.0
            val v168 = after7d[key]?.value ?: 0.0
            assertTrue("$key v24 in range: $v24", v24 in 0.0..100.0)
            assertTrue("$key v72 in range: $v72", v72 in 0.0..100.0)
            assertTrue("$key v168 in range: $v168", v168 in 0.0..100.0)
            // 7d 与 3d 应该相同（72h 截断）
            assertEquals("$key 7d should be clamped to 72h", v72, v168, 1e-5)
        }
    }
}
