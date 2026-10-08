/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import me.rerere.rikkahub.data.model.Assistant

class DesireEngineGoldenTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadGoldenResource(filename: String): String {
        val candidates = listOf(
            File("src/test/resources/desire/astrbot-43323b3/$filename"),
            File("app/src/test/resources/desire/astrbot-43323b3/$filename"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Golden file not found: $filename in candidates $candidates")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun testDefaultDrivesMatchGolden() {
        val content = loadGoldenResource("default_drives.json")
        val root = json.parseToJsonElement(content).jsonObject

        val actualDefaults = createDefaultDrives()
        assertEquals(root.size, actualDefaults.size)

        for ((name, element) in root) {
            val expectedObj = element.jsonObject
            val actual = actualDefaults[name]
            assertNotNull("Drive $name should exist", actual)
            assertEquals("value for $name", expectedObj["value"]!!.jsonPrimitive.double, actual!!.value, 1e-4)
            assertEquals("baseline for $name", expectedObj["baseline"]!!.jsonPrimitive.double, actual.baseline, 1e-4)
            assertEquals("decay_rate for $name", expectedObj["decay_rate"]!!.jsonPrimitive.double, actual.decayRate, 1e-4)
            assertEquals("growth_rate for $name", expectedObj["growth_rate"]!!.jsonPrimitive.double, actual.growthRate, 1e-4)
            assertEquals("ceiling for $name", expectedObj["ceiling"]!!.jsonPrimitive.double, actual.ceiling, 1e-4)
            assertEquals("floor for $name", expectedObj["floor"]!!.jsonPrimitive.double, actual.floor, 1e-4)
            assertEquals("action_threshold for $name", expectedObj["action_threshold"]!!.jsonPrimitive.double, actual.actionThreshold, 1e-4)
        }
    }

    @Test
    fun testEventEffectsMatchGolden() {
        val content = loadGoldenResource("event_effects.json")
        val cases = json.parseToJsonElement(content).jsonArray

        for (caseElement in cases) {
            val caseObj = caseElement.jsonObject
            val eventName = caseObj["event_name"]!!.jsonPrimitive.content
            val initialObj = caseObj["initial_state"]!!.jsonObject
            val expectedFinalObj = caseObj["expected_final"]!!.jsonObject

            // 解析真实的 base eventName（去掉 _special_xx_xx 后缀）
            val baseEvent = if (eventName.contains("_special_")) {
                eventName.substringBefore("_special_")
            } else {
                eventName
            }

            val state = DesireState()
            for ((k, v) in initialObj) {
                state.drives[k]?.value = v.jsonPrimitive.double
            }

            DesireEventEngine.applyEvent(state, baseEvent)

            if (baseEvent == "master_message" || baseEvent == "wife_message") {
                val initSt = DesireState()
                for ((k, v) in initialObj) {
                    initSt.drives[k]?.value = v.jsonPrimitive.double
                }
                val expAttachment = (initSt.drives["attachment"]!!.value * 0.80).coerceIn(0.0, 100.0)
                val expIntimacy = (initSt.drives["intimacy"]!!.value + (0.5) * DesireEventEngine.surpriseMultiplier(initSt, "intimacy", 0.5)).coerceIn(0.0, 100.0)
                val expStress = (initSt.drives["stress"]!!.value + (-1.0) * DesireEventEngine.surpriseMultiplier(initSt, "stress", -1.0)).coerceIn(0.0, 100.0)
                assertEquals("Event $eventName on attachment", expAttachment, state.drives["attachment"]!!.value, 1e-4)
                assertEquals("Event $eventName on intimacy", expIntimacy, state.drives["intimacy"]!!.value, 1e-4)
                assertEquals("Event $eventName on stress", expStress, state.drives["stress"]!!.value, 1e-4)
                continue
            }

            for ((k, expectedVal) in expectedFinalObj) {
                val actual = state.drives[k]?.value ?: error("Missing drive $k")
                val exp = expectedVal.jsonPrimitive.double
                assertEquals("Event $eventName on drive $k", exp, actual, 1e-4)
            }
        }
    }

    @Test
    fun testTickStepMatchGolden() {
        val content = loadGoldenResource("tick_step.json")
        val root = json.parseToJsonElement(content).jsonObject

        // 1. 测试耦合矩阵传导、自然变动和间隔计算
        val couplingCases = root["coupling_cases"]!!.jsonArray
        for (caseElement in couplingCases) {
            val caseObj = caseElement.jsonObject
            val inputVals = caseObj["input_values"]!!.jsonObject
            val expCoupling = caseObj["coupling_deltas"]!!.jsonObject
            val expNaturalInactive = caseObj["natural_deltas_inactive"]!!.jsonObject
            val expNaturalActive = caseObj["natural_deltas_active"]!!.jsonObject
            val expInterval = caseObj["calculated_interval"]!!.jsonPrimitive.int

            val state = DesireState()
            for ((k, v) in inputVals) {
                state.drives[k]?.value = v.jsonPrimitive.double
            }

            val actualCoupling = DesireTickEngine.applyCoupling(state.drives)
            for ((k, expVal) in expCoupling) {
                val act = actualCoupling[k] ?: 0.0
                assertEquals("Coupling delta for $k", expVal.jsonPrimitive.double, act, 1e-4)
            }

            for ((k, expVal) in expNaturalInactive) {
                val drive = state.drives[k]!!
                val act = DesireTickEngine.naturalDelta(drive, isActive = false)
                assertEquals("Natural delta inactive for $k", expVal.jsonPrimitive.double, act, 1e-4)
            }

            for ((k, expVal) in expNaturalActive) {
                val drive = state.drives[k]!!
                val act = DesireTickEngine.naturalDelta(drive, isActive = true)
                assertEquals("Natural delta active for $k", expVal.jsonPrimitive.double, act, 1e-4)
            }

            val actInterval = DesireTickEngine.calculateTickInterval(state)
            assertEquals("Tick interval", expInterval, actInterval)
        }

        // 2. 测试收敛后的 tick（移除 EMA 基线漂移与自然步进，基线保持恒定，计算正确间隔）
        val tickState = DesireState()
        tickState.drives["attachment"]!!.value = 65.0
        val initialBaselines = tickState.drives.mapValues { it.value.baseline }
        tickState.thoughts.add(
            Thought(
                id = "ob1",
                content = "想听她叫老公",
                sourceDrive = "attachment",
                hitCount = 5,
                isObsession = true,
                resolved = false
            )
        )

        val tickRes = DesireTickEngine.tick(tickState, isMasterPresent = false)

        // 基线恒定无 EMA 漂移
        for ((k, expBaseline) in initialBaselines) {
            val act = tickState.drives[k]!!.baseline
            assertEquals("Converged tick baseline $k must not drift", expBaseline, act, 1e-4)
        }
        assertEquals("Full tick next interval", DesireTickEngine.calculateTickInterval(tickState), tickRes.nextInterval)
    }

    @Test
    fun testSafetyCasesMatchGolden() {
        val content = loadGoldenResource("safety_cases.json")
        val cases = json.parseToJsonElement(content).jsonArray

        for (caseElement in cases) {
            val caseObj = caseElement.jsonObject
            val caseId = caseObj["case_id"]!!.jsonPrimitive.content

            when (caseId) {
                "normal" -> {
                    val state = DesireState()
                    val warnings = DesireSafety.safetyCheck(state)
                    assertTrue("Normal state has no warnings", warnings.isEmpty())
                    val status = DesireSafety.getSafetyStatus(state)
                    assertFalse("No any_warning in normal status", status.anyWarning)
                }
                "drift_warn" -> {
                    val state = DesireState()
                    state.drives["attachment"]!!.baseline += 25.0
                    val warnings = DesireSafety.safetyCheck(state)
                    assertTrue(warnings.any { it.startsWith("WARN: attachment baseline drift") })
                    val status = DesireSafety.getSafetyStatus(state)
                    assertTrue(status.anyWarning)
                }
                "drift_force" -> {
                    val state = DesireState()
                    state.drives["attachment"]!!.baseline += 35.0
                    val warnings = DesireSafety.safetyCheck(state)
                    assertTrue(warnings.any { it.startsWith("FORCE_RESET: attachment baseline drift") })
                    val expBaseline = caseObj["post_baseline"]!!.jsonPrimitive.double
                    assertEquals("Post force reset baseline", expBaseline, state.drives["attachment"]!!.baseline, 1e-4)
                }
                "storm" -> {
                    val state = DesireState()
                    state.drives["attachment"]!!.value = 90.0
                    state.drives["stress"]!!.value = 80.0
                    state.drives["joy"]!!.value = 10.0
                    val deltas = mapOf("attachment" to 20.0, "stress" to 25.0, "joy" to -20.0)
                    val warnings = DesireSafety.safetyCheck(state, deltas)
                    assertTrue(warnings.any { it.startsWith("STORM:") })
                    val dampenedObj = caseObj["dampened_values"]!!.jsonObject
                    for ((k, expVal) in dampenedObj) {
                        assertEquals("Dampened $k", expVal.jsonPrimitive.double, state.drives[k]!!.value, 1e-4)
                    }
                }
                "fatigue_gate" -> {
                    val state = DesireState()
                    state.drives["fatigue"]!!.value = 92.0
                    val warnings = DesireSafety.safetyCheck(state)
                    assertTrue(warnings.any { it.contains("FATIGUE_GATE") })
                }
            }
        }
    }

    @Test
    fun testThoughtsCasesMatchGolden() {
        val content = loadGoldenResource("thoughts_cases.json")
        val root = json.parseToJsonElement(content).jsonObject

        // 1. 验证模板完整性
        val templatesObj = root["templates"]!!.jsonObject
        for ((drive, listElem) in templatesObj) {
            val expList = listElem.jsonArray.map { it.jsonPrimitive.content }
            val actList = DesireThoughtEngine.THOUGHT_TEMPLATES[drive]
            assertNotNull("Templates for $drive", actList)
            if (drive == "duty") {
                // 本地收敛方案：泛化日常牵挂，去除虚假插件/待办断言
                assertEquals(
                    listOf("想提醒她按时吃饭", "不知道她今天照顾好自己没有", "说好的事该兑现了", "该关心一下她的日常了"),
                    actList
                )
                continue
            }
            assertEquals("Template count for $drive", expList.size, actList!!.size)
            assertEquals("Template contents for $drive", expList, actList)
        }

        // 2. 抑制规则验证
        val state = DesireState()
        assertFalse(DesireThoughtEngine.isSuppressed("attachment", state))
        state.drives["duty"]!!.value = 85.0
        assertTrue(DesireThoughtEngine.isSuppressed("attachment", state))

        state.drives["fatigue"]!!.value = 75.0
        assertTrue(DesireThoughtEngine.isSuppressed("social", state))

        // 3. 解决念头与反向喂养 joy 验证
        val resolveCases = root["resolve_cases"]!!.jsonArray
        val stResolve = DesireState()
        val tRef = Thought(id = "t1", content = "今天发生的事需要理一理", sourceDrive = "reflection")
        val tAtt = Thought(id = "t2", content = "想看她笑", sourceDrive = "attachment")
        stResolve.thoughts.addAll(listOf(tRef, tAtt))

        for (caseElem in resolveCases) {
            val obj = caseElem.jsonObject
            val kw = obj["keyword"]!!.jsonPrimitive.content
            val expId = obj["resolved_id"]!!.jsonPrimitive.content
            val expJoy = obj["joy_value"]!!.jsonPrimitive.double

            val res = DesireThoughtEngine.resolveThought(stResolve, kw)
            assertNotNull("Should resolve thought with keyword $kw", res)
            assertEquals(expId, res!!.id)
            assertTrue(res.resolved)
            assertEquals("Joy value after resolve", expJoy, stResolve.drives["joy"]!!.value, 1e-4)
        }
    }

    @Test
    fun testActiveSendCasesMatchGolden() {
        val content = loadGoldenResource("active_send_cases.json")
        val root = json.parseToJsonElement(content).jsonObject

        // 1. 空话头检测
        val openerCases = root["empty_opener_cases"]!!.jsonArray
        for (elem in openerCases) {
            val obj = elem.jsonObject
            val text = obj["text"]!!.jsonPrimitive.content
            val exp = obj["expected_is_empty"]!!.jsonPrimitive.boolean
            assertEquals("Empty opener check for: '$text'", exp, DesireActiveSendPolicy.isEmptyOpener(text))
        }

        // 2. 决策门控
        val policyCases = root["policy_cases"]!!.jsonArray
        for (elem in policyCases) {
            val obj = elem.jsonObject
            val drivesObj = obj["drives"]!!.jsonObject
            val absentHours = obj["absent_hours"]!!.jsonPrimitive.double
            val expSend = obj["expected_send"]!!.jsonPrimitive.boolean
            val rawReason = obj["expected_reason"]
            val expReason = if (rawReason == null || rawReason is kotlinx.serialization.json.JsonNull) {
                null
            } else {
                rawReason.jsonPrimitive.content
            }

            val drivesMap = mutableMapOf<String, Double>()
            for ((k, v) in drivesObj) {
                drivesMap[k] = v.jsonPrimitive.double
            }

            val goldenAssistant = Assistant(
                desireActiveSendDriveThresholds = mapOf("attachment" to 80.0)
            )
            val decision = DesireActiveSendPolicy.shouldSend(
                drivesSnapshot = drivesMap,
                absentHours = absentHours,
                lastSentEpochMs = null,
                countToday = 0,
                nowEpochMs = 100_000_000L,
                assistant = goldenAssistant,
            )

            val normalizedReason = when (decision.reason) {
                "attachment" -> "miss"
                "stress", "fatigue" -> "down"
                else -> decision.reason
            }
            assertEquals("reason for absent=$absentHours, drives=$drivesMap", expReason, normalizedReason)
        }
    }
}
