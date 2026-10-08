package me.rerere.rikkahub.data.ai.affect

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Kotlin adaptation of affective-longing@1b47e237 and revive-companion@7d11386b.
 * Original projects are MIT licensed. Their VAD, relationship transitions, OU process and
 * Poisson probability are preserved here; Android storage and the contact handoff are local.
 */
@Serializable
enum class AffectiveMode { FRIEND, LOVER }

@Serializable
enum class AffectiveStage { COURTING, SWEET, PASSIONATE, STABLE, COLD, REPAIRING }

@Serializable
enum class AffectiveEvent { REPLY_FAST, REPLY_SLOW, NO_REPLY, LONG_SILENCE, AFFECTION, FIGHT, APOLOGY, INITIATE, REJECT }

@Serializable
data class AffectiveState(
    val mode: AffectiveMode = AffectiveMode.FRIEND,
    val stage: AffectiveStage = AffectiveStage.STABLE,
    val intimacy: Double = 0.7,
    val conflict: Double = 0.1,
    val valence: Double = 0.5,
    val arousal: Double = 0.55,
    val dominance: Double = 0.5,
    val socialDesire: Double = 0.45,
    val belief: List<Double> = listOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0),
    val userBelief: List<Double> = listOf(.1, .2, .3, .1, .2, .1),
    val longing: Double = 1 - exp(-0.3 * 0.5),
    val missedTicks: Int = 0,
    val lastAdvancedAt: Long = 0L,
    val lastUserAt: Long = 0L,
    val lastSentAt: Long = 0L,
    val unansweredMessageId: String? = null,
    val unansweredAt: Long = 0L,
    val noReplyRecorded: Boolean = false,
    val unansweredCount: Int = 0,
    val lastSilenceDay: Long = 0L,
    val randomState: Long = 0x4d595df4d0f33173L,
    val socialRandomState: Long = 0x6a09e667f3bcc909L,
    val lastReason: String = "",
    val lastEventSource: String = "本地规则",
    val lastMemory: String = "",
    val lastCandidateReason: String = "",
    val lastDeliveryStatus: String = "",
)

data class AffectiveEvaluation(val state: AffectiveState, val contactCandidate: Boolean, val reason: String)

object AffectiveEngine {
    private const val HALF_HOUR_MS = 30 * 60 * 1000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val BASE_PROBABILITY = 0.1392920235749422 // 1 - exp(-0.3 * 0.5)

    private val transitions = mapOf(
        AffectiveStage.COURTING to doubleArrayOf(.6, .3, 0.0, 0.0, .1, 0.0),
        AffectiveStage.SWEET to doubleArrayOf(.05, .5, .35, 0.0, .1, 0.0),
        AffectiveStage.PASSIONATE to doubleArrayOf(0.0, .1, .4, .4, .1, 0.0),
        AffectiveStage.STABLE to doubleArrayOf(0.0, .05, .05, .7, .15, .05),
        AffectiveStage.COLD to doubleArrayOf(0.0, 0.0, 0.0, .1, .6, .3),
        AffectiveStage.REPAIRING to doubleArrayOf(0.0, .15, 0.0, .35, .2, .3),
    )
    private val bumps = mapOf(
        AffectiveEvent.REPLY_FAST to listOf(.05, -.02, .08, .05, .03),
        AffectiveEvent.REPLY_SLOW to listOf(-.02, .01, -.03, .02, -.02),
        AffectiveEvent.NO_REPLY to listOf(-.05, .03, -.06, .08, -.05),
        AffectiveEvent.LONG_SILENCE to listOf(-.10, .05, -.10, .10, -.08),
        AffectiveEvent.AFFECTION to listOf(.10, -.05, .12, .08, .02),
        AffectiveEvent.FIGHT to listOf(-.15, .20, -.15, .15, -.05),
        AffectiveEvent.APOLOGY to listOf(.05, -.15, .05, -.05, .03),
        AffectiveEvent.INITIATE to listOf(.08, -.02, .10, .06, .05),
        AffectiveEvent.REJECT to listOf(-.12, .10, -.12, .10, -.10),
    )

    fun initial(mode: AffectiveMode, now: Long): AffectiveState = AffectiveState(
        mode = mode, intimacy = if (mode == AffectiveMode.FRIEND) .7 else .8,
        lastAdvancedAt = now, lastUserAt = now,
    )

    fun switchMode(state: AffectiveState, mode: AffectiveMode): AffectiveState = state.copy(mode = mode)

    fun advance(state: AffectiveState, now: Long): AffectiveState {
        if (now <= state.lastAdvancedAt) return state
        val hours = (now - state.lastAdvancedAt) / 3_600_000.0
        val baseline = if (state.mode == AffectiveMode.FRIEND) .7 else .8
        var seed = state.randomState
        var socialSeed = state.socialRandomState
        fun normal(): Double {
            val a = draw(seed); seed = a.second
            val b = draw(seed); seed = b.second
            return sqrt(-2.0 * ln(a.first.coerceAtLeast(1e-12))) * cos(2.0 * PI * b.first)
        }
        fun socialNormal(): Double {
            val a = draw(socialSeed); socialSeed = a.second
            val b = draw(socialSeed); socialSeed = b.second
            return sqrt(-2.0 * ln(a.first.coerceAtLeast(1e-12))) * cos(2.0 * PI * b.first)
        }
        fun ou(value: Double, mean: Double, speed: Double, volatility: Double, lo: Double = 0.0, noise: Double = normal()): Double {
            val decay = exp(-speed * hours)
            val deviation = volatility * sqrt((1 - decay * decay) / (2 * speed)) * noise
            return (mean + (value - mean) * decay + deviation).coerceIn(lo, 1.0)
        }
        val stageBase = when (state.stage) {
            AffectiveStage.COURTING -> listOf(.2, .6, .3)
            AffectiveStage.SWEET -> listOf(.7, .55, .5)
            AffectiveStage.PASSIONATE -> listOf(.8, .7, .6)
            AffectiveStage.STABLE -> listOf(.5, .55, .5)
            AffectiveStage.COLD -> listOf(-.5, .5, .3)
            AffectiveStage.REPAIRING -> listOf(0.0, .5, .4)
        }
        val slots = ((now / HALF_HOUR_MS) - (state.lastAdvancedAt / HALF_HOUR_MS)).coerceAtLeast(0L)
        var result = state.copy(
            intimacy = ou(state.intimacy, baseline, .05, .02),
            conflict = ou(state.conflict, .1, .08, .03),
            valence = ou(state.valence, stageBase[0], .06, .02, -1.0),
            arousal = ou(state.arousal, stageBase[1], .10, .03),
            dominance = ou(state.dominance, stageBase[2], .08, .02),
            socialDesire = ou(state.socialDesire, .45, .08, .09, noise = socialNormal()),
            longing = (state.longing + slots.coerceAtMost(100L) * .08).coerceAtMost(.95),
            missedTicks = (state.missedTicks.toLong() + slots).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            lastAdvancedAt = now,
            randomState = seed,
            socialRandomState = socialSeed,
        )
        val transitionRoll = draw(result.randomState)
        result = result.copy(randomState = transitionRoll.second)
        if (result.conflict > .7 && result.stage in listOf(AffectiveStage.SWEET, AffectiveStage.PASSIONATE, AffectiveStage.STABLE) && transitionRoll.first < result.conflict) {
            result = result.copy(stage = AffectiveStage.COLD, belief = oneHot(AffectiveStage.COLD))
        } else if (result.stage == AffectiveStage.COLD && result.conflict < .3 && result.intimacy > .5 && transitionRoll.first < .3) {
            result = result.copy(stage = AffectiveStage.REPAIRING, belief = oneHot(AffectiveStage.REPAIRING))
        }
        val silenceDay = if (result.lastUserAt > 0) (now - result.lastUserAt).coerceAtLeast(0L) / DAY_MS else 0L
        if (silenceDay > result.lastSilenceDay) {
            repeat((silenceDay - result.lastSilenceDay).coerceAtMost(30L).toInt()) {
                result = observe(result, AffectiveEvent.LONG_SILENCE, "时间演化")
            }
            result = result.copy(lastSilenceDay = silenceDay)
        }
        if (result.unansweredMessageId != null && !result.noReplyRecorded && now - result.unansweredAt >= 3_600_000L) {
            result = observe(result, AffectiveEvent.NO_REPLY, "时间演化").copy(noReplyRecorded = true)
        }
        return result
    }

    fun observe(state: AffectiveState, event: AffectiveEvent, source: String = "本地规则"): AffectiveState {
        val b = bumps.getValue(event)
        val values = transitions.getValue(state.stage).copyOf()
        val nextIntimacy = (state.intimacy + b[0]).coerceIn(0.0, 1.0)
        val nextConflict = (state.conflict + b[1]).coerceIn(0.0, 1.0)
        if (nextIntimacy > .7) { values[1] *= 2; values[2] *= 2; values[4] *= .3 }
        if (nextIntimacy < .3) { values[4] *= 2; values[1] *= .3; values[2] *= .2 }
        if (nextConflict > .5) { values[4] *= 3; values[5] *= 2; values[1] *= .2; values[2] *= .1 }
        if (nextConflict < .2) values[4] *= .2
        when (event) {
            AffectiveEvent.FIGHT -> { values[4] *= 5; values[state.stage.ordinal] *= .5 }
            AffectiveEvent.APOLOGY -> { values[5] *= 3; values[4] *= .3 }
            AffectiveEvent.AFFECTION -> if (nextIntimacy > .6) {
                values[2] *= 3; values[1] *= 2; values[state.stage.ordinal] *= .5
            }
            AffectiveEvent.LONG_SILENCE -> values[4] *= 2
            else -> Unit
        }
        val sum = values.sum().coerceAtLeast(1e-12)
        val belief = values.map { it / sum }
        val stage = AffectiveStage.entries[belief.indices.maxBy { belief[it] }]
        return state.copy(
            intimacy = nextIntimacy,
            conflict = nextConflict,
            valence = (state.valence + b[2]).coerceIn(-1.0, 1.0),
            arousal = (state.arousal + b[3]).coerceIn(0.0, 1.0),
            dominance = (state.dominance + b[4]).coerceIn(0.0, 1.0),
            stage = stage, belief = belief,
            lastReason = event.name, lastEventSource = source,
        )
    }

    fun evaluate(state: AffectiveState, now: Long, memorySimilarity: Double? = null): AffectiveEvaluation {
        val base = state.longing
        val boost = memorySimilarity?.takeIf { it >= .68 }?.let { min(1.0, it * 2.5) } ?: 0.0
        val probability = (base * (1 + boost)).coerceAtMost(1.0)
        if (state.lastSentAt > 0 && now - state.lastSentAt < 3_600_000L) {
            return AffectiveEvaluation(state, false, "联系冷却中")
        }
        val first = draw(state.randomState)
        val hit = first.first < probability
        val second = draw(first.second)
        val silenceHours = (now - state.lastUserAt).coerceAtLeast(0L) / 3_600_000.0
        val unanswered = state.unansweredCount
        // revive-companion's SilenceDuration and ConversationFlow information sources.
        val silenceEntropy = min(1.0, silenceHours / 24.0)
        val silenceResolution = if (silenceHours < 1.0) .2 else .7
        val flowEntropy = if (silenceHours < 1.0) .2 else if (unanswered > 0) .4 else .7
        val flowResolution = when {
            silenceHours < 1.0 -> .3
            unanswered >= 3 -> .1
            unanswered > 0 -> .3
            else -> .7
        }
        val gain = silenceEntropy * silenceResolution + flowEntropy * flowResolution
        val ratio = gain / (silenceEntropy + flowEntropy).coerceAtLeast(.001)
        val worthContacting = (ratio >= .2 && gain >= .1) || base >= .7
        // The Bayesian states are hypotheses, not knowledge of the user's activity. No time is a hard ban.
        val hours = silenceHours.coerceAtMost(48.0)
        val means = doubleArrayOf(.5, 2.0, 4.0, 8.0, 12.0, 24.0)
        val deviations = doubleArrayOf(1.0, 2.0, 3.0, 3.0, 6.0, 12.0)
        val prior = state.userBelief
        val weights = means.indices.map { i ->
            val transition = prior.getOrElse(i) { 1.0 / 6.0 }
            transition * exp(-.5 * ((hours - means[i]) / deviations[i]).let { it * it })
        }
        val weightSum = weights.sum().coerceAtLeast(1e-12)
        val posterior = weights.map { it / weightSum }
        val utility = posterior.zip(listOf(.2, .7, .1, 0.0, .3, .9)).sumOf { (p, u) -> p * u }
        val send = hit && second.first < .7 && worthContacting && (utility >= .45 || base >= .7)
        val next = if (send) state.copy(randomState = second.second) else state.copy(
            randomState = second.second, longing = (base + .08).coerceAtMost(.95), missedTicks = state.missedTicks + 1,
        )
        return AffectiveEvaluation(next.copy(userBelief = posterior), send, if (send) "积累的想念形成了联系契机" else "牵挂继续积累")
    }

    fun userMessage(state: AffectiveState): AffectiveState = state.copy(
        longing = BASE_PROBABILITY, missedTicks = 0,
    )

    fun delivered(state: AffectiveState, messageId: String, now: Long): AffectiveState = state.copy(
        lastSentAt = now,
        unansweredMessageId = messageId, unansweredAt = now, noReplyRecorded = false,
        unansweredCount = (state.unansweredCount + 1).coerceAtMost(3),
    )

    fun stageLabel(state: AffectiveState): String = when (state.mode) {
        AffectiveMode.FRIEND -> listOf("初识", "熟悉", "热络", "默契", "疏远", "和好")[state.stage.ordinal]
        AffectiveMode.LOVER -> listOf("试探", "甜蜜", "热恋", "安稳", "冷战", "修复")[state.stage.ordinal]
    }

    fun classify(text: String): AffectiveEvent? = when {
        Regex("(别联系我|不要再找我|别再给我发消息)").containsMatchIn(text) -> AffectiveEvent.REJECT
        Regex("(对不起|我道歉|是我错了|原谅我)").containsMatchIn(text) -> AffectiveEvent.APOLOGY
        Regex("(我生气了|你让我很难过|我们吵架|讨厌你)").containsMatchIn(text) -> AffectiveEvent.FIGHT
        Regex("(想你了|喜欢你|爱你|抱抱|谢谢你陪我)").containsMatchIn(text) -> AffectiveEvent.AFFECTION
        else -> null
    }

    private fun draw(seed: Long): Pair<Double, Long> {
        val next = seed * 6364136223846793005L + 1442695040888963407L
        return ((next ushr 11).toDouble() / (1L shl 53).toDouble()) to next
    }

    private fun oneHot(stage: AffectiveStage) = AffectiveStage.entries.map { if (it == stage) 1.0 else 0.0 }
}
