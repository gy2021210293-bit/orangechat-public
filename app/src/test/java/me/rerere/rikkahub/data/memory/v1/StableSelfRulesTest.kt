/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import me.rerere.rikkahub.data.memory.v1.db.MemoryCardEntity
import me.rerere.rikkahub.data.memory.v1.db.MemorySourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class StableSelfRulesTest {

    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `isValidSelfFactKey validates companion self keys correctly`() {
        // 陪伴型合法 key 示例
        assertTrue(StableSelfRules.isValidSelfFactKey("self.preference.enjoy_small_daily_stories"))
        assertTrue(StableSelfRules.isValidSelfFactKey("self.comfort.prefer_listening_before_advice"))
        assertTrue(StableSelfRules.isValidSelfFactKey("self.expression.enjoy_playful_tension_relief"))
        assertTrue(StableSelfRules.isValidSelfFactKey("self.attachment.value_continuity_after_pause"))
        assertTrue(StableSelfRules.isValidSelfFactKey("self.boundary.avoid_exaggerated_promises"))
        assertTrue(StableSelfRules.isValidSelfFactKey("self.a.b"))
        assertTrue(StableSelfRules.isValidSelfFactKey("self.sub_domain_1.trait_2"))

        // 非法 key: 空值与格式
        assertFalse(StableSelfRules.isValidSelfFactKey(null))
        assertFalse(StableSelfRules.isValidSelfFactKey(""))
        assertFalse(StableSelfRules.isValidSelfFactKey("   "))
        assertFalse(StableSelfRules.isValidSelfFactKey("preference.enjoy_stories"))
        assertFalse(StableSelfRules.isValidSelfFactKey("self."))
        assertFalse(StableSelfRules.isValidSelfFactKey("self.preference")) // 只有两段
        assertFalse(StableSelfRules.isValidSelfFactKey("self.Comfort.Listen")) // 大写字母
        assertFalse(StableSelfRules.isValidSelfFactKey("self.comfort-listen.advice")) // 连字符

        // 非法 key: 包含日期
        assertFalse(StableSelfRules.isValidSelfFactKey("self.preference.action_2026_09_11"))
        assertFalse(StableSelfRules.isValidSelfFactKey("self.preference.20260911"))
        assertFalse(StableSelfRules.isValidSelfFactKey("self.event_2026.action"))
        assertFalse(StableSelfRules.isValidSelfFactKey("self.trait.year1999"))
    }

    // 1. 同一 conversation 内三个不同 assistant anchor = 三个 episode
    @Test
    fun `validateSelfEpisode recognizes multiple distinct assistant anchors in same conversation`() {
        val baseTime = 1700000000000L
        val ep1 = StableSelfRules.validateSelfEpisode(
            sources = listOf(
                MemorySourceReference("c1", null, "u1", "user", baseTime),
                MemorySourceReference("c1", null, "a1", "assistant", baseTime + 1000)
            ),
            ownerId = "ast-1",
            memoryDate = "2026-09-11",
        )
        val ep2 = StableSelfRules.validateSelfEpisode(
            sources = listOf(
                MemorySourceReference("c1", null, "u2", "user", baseTime + 2000),
                MemorySourceReference("c1", null, "a2", "assistant", baseTime + 3000)
            ),
            ownerId = "ast-1",
            memoryDate = "2026-09-11",
        )
        val ep3 = StableSelfRules.validateSelfEpisode(
            sources = listOf(
                MemorySourceReference("c1", null, "u3", "user", baseTime + 4000),
                MemorySourceReference("c1", null, "a3", "assistant", baseTime + 5000)
            ),
            ownerId = "ast-1",
            memoryDate = "2026-09-11",
        )

        assertNotNull(ep1)
        assertNotNull(ep2)
        assertNotNull(ep3)
        assertEquals("a1", ep1!!.assistantMessageId)
        assertEquals("a2", ep2!!.assistantMessageId)
        assertEquals("a3", ep3!!.assistantMessageId)

        // 三个不同的 anchor 得到三个不同的 episodeKey
        val distinctKeys = setOf(ep1.episodeKey, ep2.episodeKey, ep3.episodeKey)
        assertEquals(3, distinctKeys.size)
    }

    // 2. 相同 anchor 生成确定性 key
    @Test
    fun `validateSelfEpisode produces deterministic episodeKey for same anchor`() {
        val baseTime = 1700000000000L
        val sources = listOf(
            MemorySourceReference("c1", null, "u1", "user", baseTime),
            MemorySourceReference("c1", null, "a1", "assistant", baseTime + 1000)
        )

        val epA = StableSelfRules.validateSelfEpisode(sources, "ast-1", "2026-09-11")
        val epB = StableSelfRules.validateSelfEpisode(sources, "ast-1", "2026-09-11")

        assertNotNull(epA)
        assertNotNull(epB)
        assertEquals(epA!!.episodeKey, epB!!.episodeKey)
    }

    // 3. 只有 USER 来源不计数
    @Test
    fun `validateSelfEpisode fails when only user source exists`() {
        val baseTime = 1700000000000L
        val sources = listOf(
            MemorySourceReference("c1", null, "u1", "user", baseTime),
            MemorySourceReference("c1", null, "u2", "user", baseTime + 1000)
        )
        val ep = StableSelfRules.validateSelfEpisode(sources, "ast-1", "2026-09-11")
        assertNull("只有 USER 来源不能构成有效 episode", ep)
    }

    // 4. 只有 ASSISTANT 来源不计数（防止 AI 独白自证）
    @Test
    fun `validateSelfEpisode fails when only assistant source exists`() {
        val baseTime = 1700000000000L
        val sources = listOf(
            MemorySourceReference("c1", null, "a1", "assistant", baseTime),
            MemorySourceReference("c1", null, "a2", "assistant", baseTime + 1000)
        )
        val ep = StableSelfRules.validateSelfEpisode(sources, "ast-1", "2026-09-11")
        assertNull("只有 ASSISTANT 独白不能构成有效 episode", ep)
    }

    // 5. USER+ASSISTANT 不同 conversation 不计数
    @Test
    fun `validateSelfEpisode fails when messages belong to different conversations`() {
        val baseTime = 1700000000000L
        val sources = listOf(
            MemorySourceReference("c1", null, "u1", "user", baseTime),
            MemorySourceReference("c2", null, "a1", "assistant", baseTime + 1000)
        )
        val ep = StableSelfRules.validateSelfEpisode(sources, "ast-1", "2026-09-11")
        assertNull("跨会话配对必须失败", ep)
    }

    // 6. USER 在 ASSISTANT 之后或无法确认顺序不计数
    @Test
    fun `validateSelfEpisode fails when user message is after assistant`() {
        val baseTime = 1700000000000L

        // 用户消息在 assistant 之后
        val reversedSources = listOf(
            MemorySourceReference("c1", null, "a1", "assistant", baseTime),
            MemorySourceReference("c1", null, "u1", "user", baseTime + 1000)
        )
        val epReversed = StableSelfRules.validateSelfEpisode(reversedSources, "ast-1", "2026-09-11")
        assertNull("USER 晚于 ASSISTANT 不构成有效前置语境", epReversed)
    }

    // 7. 重跑同一 source set 不重复计数
    @Test
    fun `computeEvidenceStats deduplicates same episodeKey`() {
        val baseTime = ZonedDateTime.of(2026, 9, 10, 10, 0, 0, 0, zoneId).toInstant().toEpochMilli()
        val key1 = "self_episode_v2:abc123"

        val sources = listOf(
            createEpisodeSource(convId = "c1", msgId = "m1", time = baseTime, episodeKey = key1),
            createEpisodeSource(convId = "c1", msgId = "m2", time = baseTime + 1000, episodeKey = key1), // 同一 episode 的另一条消息
            createEpisodeSource(convId = "c1", msgId = "m1", time = baseTime, episodeKey = key1), // 重复插入
        )

        val stats = StableSelfRules.computeEvidenceStats(sources, boundaryHour = 4, zoneId = zoneId)
        assertEquals(1, stats.distinctEpisodeCount)
        assertEquals(1, stats.distinctMemoryDateCount)
    }

    // 8. Daily Note、stable_self、memory_context 以及旧版本未带 v2 hash 的来源永不计数
    @Test
    fun `computeEvidenceStats ignores derived and legacy non-v2 sources`() {
        val baseTime = ZonedDateTime.of(2026, 9, 10, 10, 0, 0, 0, zoneId).toInstant().toEpochMilli()
        val nextDayTime = ZonedDateTime.of(2026, 9, 11, 14, 0, 0, 0, zoneId).toInstant().toEpochMilli()

        val sources = listOf(
            // 合法 v2 episode 来源
            createEpisodeSource(convId = "c1", msgId = "m1", time = baseTime, episodeKey = "self_episode_v2:ep1"),
            // 旧试行版本无 sourceHash 或 legacy hash
            createEpisodeSource(convId = "c2", msgId = "m2", time = baseTime, episodeKey = null),
            createEpisodeSource(convId = "c3", msgId = "m3", time = nextDayTime, episodeKey = "legacy_conv_hash"),
            // 派生与注入类型
            createEpisodeSource(convId = "c4", msgId = "m4", time = nextDayTime, episodeKey = "self_episode_v2:ep2", kind = "DAILY_NOTE"),
            createEpisodeSource(convId = "c5", msgId = "m5", time = nextDayTime, episodeKey = "self_episode_v2:ep3", kind = "STABLE_SELF"),
            createEpisodeSource(convId = "c6", msgId = "m6", time = nextDayTime, episodeKey = "self_episode_v2:ep4", kind = "MEMORY_CONTEXT"),
            createEpisodeSource(convId = "c7", msgId = "m7", time = nextDayTime, episodeKey = "self_episode_v2:ep5", kind = "SUMMARY"),
            createEpisodeSource(convId = "c8", msgId = "m8", time = nextDayTime, episodeKey = "self_episode_v2:ep6", kind = "SCHEMA"),
            createEpisodeSource(convId = "c9", msgId = "m9", time = nextDayTime, episodeKey = "self_episode_v2:ep7", kind = "NARRATIVE"),
            // 缺失时间戳或会话 ID
            createEpisodeSource(convId = null, msgId = "m10", time = nextDayTime, episodeKey = "self_episode_v2:ep8"),
            createEpisodeSource(convId = "c10", msgId = "m11", time = null, episodeKey = "self_episode_v2:ep9"),
        )

        val stats = StableSelfRules.computeEvidenceStats(sources, boundaryHour = 4, zoneId = zoneId)
        // 只有 ep1 是合法计数的 v2 互动来源
        assertEquals(1, stats.distinctEpisodeCount)
        assertEquals(1, stats.distinctMemoryDateCount)
    }

    // 9. 三 episode、两天、无冲突才能成熟
    @Test
    fun `isMatureSelfCandidate requires 3 episodes and 2 dates without conflict`() {
        val baseTime = ZonedDateTime.of(2026, 9, 10, 10, 0, 0, 0, zoneId).toInstant().toEpochMilli()
        val nextDayTime = ZonedDateTime.of(2026, 9, 11, 14, 0, 0, 0, zoneId).toInstant().toEpochMilli()

        // 3 个 episode 但在同一天
        val sameDaySources = listOf(
            createEpisodeSource("c1", "m1", baseTime, "self_episode_v2:ep1"),
            createEpisodeSource("c1", "m2", baseTime + 1000, "self_episode_v2:ep2"),
            createEpisodeSource("c1", "m3", baseTime + 2000, "self_episode_v2:ep3"),
        )
        val statsSameDay = StableSelfRules.computeEvidenceStats(sameDaySources, boundaryHour = 4, zoneId = zoneId)
        assertEquals(3, statsSameDay.distinctEpisodeCount)
        assertEquals(1, statsSameDay.distinctMemoryDateCount)
        assertFalse("单日即使有3个episode也不成熟", StableSelfRules.isMatureSelfCandidate(statsSameDay, hasPendingConflict = false))

        // 2 个 episode 跨两天
        val twoEpisodesCrossDays = listOf(
            createEpisodeSource("c1", "m1", baseTime, "self_episode_v2:ep1"),
            createEpisodeSource("c1", "m2", nextDayTime, "self_episode_v2:ep2"),
        )
        val statsTwoEpisodes = StableSelfRules.computeEvidenceStats(twoEpisodesCrossDays, boundaryHour = 4, zoneId = zoneId)
        assertEquals(2, statsTwoEpisodes.distinctEpisodeCount)
        assertEquals(2, statsTwoEpisodes.distinctMemoryDateCount)
        assertFalse("仅2个episode未达到成熟门槛", StableSelfRules.isMatureSelfCandidate(statsTwoEpisodes, hasPendingConflict = false))

        // 3 个 episode 跨两天且无冲突 -> 成熟！
        val matureSources = listOf(
            createEpisodeSource("c1", "m1", baseTime, "self_episode_v2:ep1"),
            createEpisodeSource("c1", "m2", baseTime + 3600_000, "self_episode_v2:ep2"),
            createEpisodeSource("c1", "m3", nextDayTime, "self_episode_v2:ep3"),
        )
        val statsMature = StableSelfRules.computeEvidenceStats(matureSources, boundaryHour = 4, zoneId = zoneId)
        assertEquals(3, statsMature.distinctEpisodeCount)
        assertEquals(2, statsMature.distinctMemoryDateCount)
        assertTrue("跨两天3个episode且无冲突应成熟", StableSelfRules.isMatureSelfCandidate(statsMature, hasPendingConflict = false))
    }

    // 10. 同条件但存在 pending conflict 必须保持 REVIEW
    @Test
    fun `isMatureSelfCandidate blocked when pending conflict exists`() {
        val baseTime = ZonedDateTime.of(2026, 9, 10, 10, 0, 0, 0, zoneId).toInstant().toEpochMilli()
        val nextDayTime = ZonedDateTime.of(2026, 9, 11, 14, 0, 0, 0, zoneId).toInstant().toEpochMilli()

        val matureSources = listOf(
            createEpisodeSource("c1", "m1", baseTime, "self_episode_v2:ep1"),
            createEpisodeSource("c1", "m2", baseTime + 3600_000, "self_episode_v2:ep2"),
            createEpisodeSource("c1", "m3", nextDayTime, "self_episode_v2:ep3"),
        )
        val statsMature = StableSelfRules.computeEvidenceStats(matureSources, boundaryHour = 4, zoneId = zoneId)
        assertFalse("存在未决冲突时严禁自动成熟", StableSelfRules.isMatureSelfCandidate(statsMature, hasPendingConflict = true))
    }

    // 11. 跨 04:00 边界事件按 episode 聚合只算作一个记忆日
    @Test
    fun `single episode spanning memory day boundary only counts as one memory date`() {
        // 03:59 (属于前一日) 与 04:01 (属于次日) 的两条消息属于同一个互动事件
        val userTime = ZonedDateTime.of(2026, 9, 10, 3, 59, 0, 0, zoneId).toInstant().toEpochMilli()
        val assistantTime = ZonedDateTime.of(2026, 9, 10, 4, 1, 0, 0, zoneId).toInstant().toEpochMilli()
        val episodeKey = "self_episode_v2:cross_day_boundary"

        val sources = listOf(
            createEpisodeSource("c1", "u1", userTime, episodeKey, role = "user"),
            createEpisodeSource("c1", "a1", assistantTime, episodeKey, role = "assistant"),
        )

        val stats = StableSelfRules.computeEvidenceStats(sources, boundaryHour = 4, zoneId = zoneId)
        assertEquals("同一跨界事件只能计为1个episode", 1, stats.distinctEpisodeCount)
        assertEquals("同一跨界事件只能按ASSISTANT锚点计为1个记忆日", 1, stats.distinctMemoryDateCount)
    }

    @Test
    fun `confidence caps and effective calculation respect episode count limits`() {
        // 1 个有效 episode: 上限 0.60
        assertEquals(0.60f, StableSelfRules.calculateSelfConfidenceCap(1), 0.001f)
        assertEquals(0.60f, StableSelfRules.effectiveSelfConfidence(1.0f, 1), 0.001f)
        assertEquals(0.50f, StableSelfRules.effectiveSelfConfidence(0.50f, 1), 0.001f)

        // 2 个有效 episode: 上限 0.75
        assertEquals(0.75f, StableSelfRules.calculateSelfConfidenceCap(2), 0.001f)
        assertEquals(0.75f, StableSelfRules.effectiveSelfConfidence(0.95f, 2), 0.001f)
        assertEquals(0.70f, StableSelfRules.effectiveSelfConfidence(0.70f, 2), 0.001f)

        // >= 3 个有效 episode: 上限 0.90 (不能自动拉到 1.0)
        assertEquals(0.90f, StableSelfRules.calculateSelfConfidenceCap(3), 0.001f)
        assertEquals(0.90f, StableSelfRules.effectiveSelfConfidence(1.0f, 3), 0.001f)
        assertEquals(0.90f, StableSelfRules.calculateSelfConfidenceCap(5), 0.001f)
        assertEquals(0.90f, StableSelfRules.effectiveSelfConfidence(1.0f, 5), 0.001f)
    }

    @Test
    fun `buildStableSelfPrompt formats companion cards according to specification`() {
        assertTrue(StableSelfRules.buildStableSelfPrompt(emptyList()).isEmpty())

        val cards = listOf(
            MemoryCardEntity(
                id = "card-1",
                ownerType = "assistant",
                ownerId = "ast-1",
                content = "面对低落情绪时，我自然更喜欢先听对方把感受说完，而不是马上给出解决办法。",
                category = "self",
                factKey = "self.comfort.prefer_listening_before_advice",
                status = "ACTIVE",
                createdAt = 1000L,
                updatedAt = 1000L,
                createdBy = "NIGHTLY_MODEL",
                updatedBy = "NIGHTLY_MODEL",
                alwaysInject = true,
            ),
            MemoryCardEntity(
                id = "card-2",
                ownerType = "assistant",
                ownerId = "ast-1",
                content = "我喜欢听别人向我分享细小而具体的日常，这会让我感到亲近和踏实。",
                category = "self",
                factKey = "self.preference.enjoy_small_daily_stories",
                status = "ACTIVE",
                createdAt = 2000L,
                updatedAt = 2000L,
                createdBy = "NIGHTLY_MODEL",
                updatedBy = "NIGHTLY_MODEL",
                alwaysInject = true,
            )
        )

        val prompt = StableSelfRules.buildStableSelfPrompt(cards, "小雨")
        assertTrue(prompt.startsWith("<stable_self>"))
        assertTrue(prompt.endsWith("</stable_self>"))
        assertTrue(prompt.contains("以下是我在长期真实经历中逐渐形成、并由小雨选择常驻的稳定自我认识。"))
        assertTrue(prompt.contains("它们描述我自己的感受、喜好、性格与回应倾向，不是小雨或关系的画像，也不是必须表演的角色命令。"))
        assertTrue(prompt.contains("我可以自然保持连续性，也可以在新的真实相处经历中被修正。"))
        assertTrue(prompt.contains("1. [memory_id=card-1] 面对低落情绪时，我自然更喜欢先听对方把感受说完，而不是马上给出解决办法。"))
        assertTrue(prompt.contains("2. [memory_id=card-2] 我喜欢听别人向我分享细小而具体的日常，这会让我感到亲近和踏实。"))
    }

    private fun createEpisodeSource(
        convId: String?,
        msgId: String?,
        time: Long?,
        episodeKey: String?,
        kind: String = "NIGHTLY_MODEL",
        role: String = "assistant",
    ): MemorySourceEntity {
        return MemorySourceEntity(
            id = "src-${msgId ?: java.util.UUID.randomUUID()}",
            memoryId = "card-test",
            conversationId = convId,
            nodeId = null,
            messageId = msgId,
            role = role,
            messageCreatedAt = time,
            sourceKind = kind,
            sourceHash = episodeKey,
            createdAt = System.currentTimeMillis(),
        )
    }
}
