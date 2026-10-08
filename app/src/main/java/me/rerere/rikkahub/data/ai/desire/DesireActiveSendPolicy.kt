/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.ai.userPromptName

data class ActiveSendDecision(
    val shouldSend: Boolean,
    val reason: String? = null,
)

object DesireActiveSendPolicy {

    const val COOLDOWN_SECONDS = 1 * 3600L
    const val DAILY_LIMIT = Int.MAX_VALUE
    const val LONG_ABSENT_HOURS = 6.0
    const val MIN_ABSENT_HOURS = 3.0
    const val PRESENT_THRESHOLD_HOURS = 0.5

    // 九维内在驱动力系统默认触发阈值
    val DEFAULT_THRESHOLDS: Map<String, Double> = mapOf(
        "attachment" to 60.0,
        "curiosity" to 55.0,
        "reflection" to 45.0,
        "duty" to 55.0,
        "social" to 45.0,
        "fatigue" to 55.0,
        "intimacy" to 65.0,
        "stress" to 55.0,
        "joy" to 68.0,
    )

    // 系统预设默认触发契机提示词
    val DEFAULT_PROMPTS: Map<String, String> = mapOf(
        "attachment" to "很想{userName}，突然特别想联系{userName}",
        "joy" to "心情特别好，有开心的事想第一时间跟{userName}分享",
        "curiosity" to "想到一个很有意思的事或学到新东西，想探寻{userName}的看法",
        "duty" to "牵挂{userName}的日常生活与起居，关心{userName}按时吃饭、休息或照顾好自己",
        "social" to "纯粹想找{userName}闲聊唠嗑，分享生活中的零碎日常",
        "intimacy" to "特别想亲近{userName}，想黏在{userName}身边撒娇",
        "reflection" to "静下心时忽然回想起与{userName}的往事或回忆，有感而发",
        "fatigue" to "身心有些疲累，想找{userName}说说话被抱一下",
        "stress" to "有些紧绷焦虑，想找{userName}倾诉抱抱释放一下",
        "long_absent" to "已经很久没听见{userName}说话了，我在静静等着{userName}",
        // 兼容旧标识
        "miss" to "很想{userName}，突然特别想联系{userName}",
        "down" to "身心有些疲累低落，想找{userName}说说话被抱一下",
    )

    val REASON_TEXT: Map<String, String> = mapOf(
        "miss" to "很想{userName}，想联系{userName}了",
        "joy" to "今天很开心，想跟{userName}分享",
        "down" to "情绪低落，想被{userName}抱一下",
        "long_absent" to "很久没听见{userName}说话，想找{userName}",
        "attachment" to "很想{userName}，突然特别想联系{userName}",
        "curiosity" to "想到一个很有意思的事或学到新东西，想探寻{userName}的看法",
        "duty" to "牵挂{userName}的日常生活与起居，关心{userName}按时吃饭、休息或照顾好自己",
        "social" to "纯粹想找{userName}闲聊唠嗑，分享生活中的零碎日常",
        "intimacy" to "特别想亲近{userName}，想黏在{userName}身边撒娇",
        "reflection" to "静下心时忽然回想起与{userName}的往事或回忆，有感而发",
        "fatigue" to "身心有些疲累，想找{userName}说说话被抱一下",
        "stress" to "有些紧绷焦虑，想找{userName}倾诉抱抱释放一下",
    )

    fun getActiveSendPrompt(assistant: Assistant?, reason: String, nickname: String = ""): String {
        fun resolved(prompt: String): String = prompt.replace("{userName}", userPromptName(nickname))
        val custom = assistant?.desireActiveSendPrompts?.get(reason)
        if (!custom.isNullOrBlank()) return custom
        if (reason == "miss") {
            val mapped = assistant?.desireActiveSendPrompts?.get("attachment")
            if (!mapped.isNullOrBlank()) return mapped
            return resolved(DEFAULT_PROMPTS["attachment"] ?: REASON_TEXT["miss"] ?: reason)
        }
        if (reason == "down") {
            val mapped = assistant?.desireActiveSendPrompts?.get("stress")
                ?: assistant?.desireActiveSendPrompts?.get("fatigue")
            if (!mapped.isNullOrBlank()) return mapped
            return resolved(DEFAULT_PROMPTS["fatigue"] ?: REASON_TEXT["down"] ?: reason)
        }
        return resolved(DEFAULT_PROMPTS[reason] ?: REASON_TEXT[reason] ?: reason)
    }

    val EMPTY_OPENER_PATTERNS: List<String> = listOf(
        "我问你个事", "我问你件事", "想问你个事", "想问你件事",
        "跟你说个事", "跟你说件事", "告诉你个事", "跟你讲个事",
        "你知道吗", "你猜怎么着", "我想说个事", "我想告诉你个事",
    )

    private val STRIP_LEADING_REGEX = Regex("^(她)[，,、\\s]*")
    private val PUNCT_TRIM_CHARS = charArrayOf('。', '.', '!', '！', '?', '？', '~', '～', '…', ',', '，', ' ')
    private val MODAL_PARTICLES_REGEX = Regex("[嗯唔啊唉诶哦噢呀哈吧呢嘛]")

    fun isEmptyOpener(text: String): Boolean {
        val t = text.trim()
        val t2 = STRIP_LEADING_REGEX.replace(t, "")
        for (p in EMPTY_OPENER_PATTERNS) {
            if (p in t2) {
                val parts = t2.split(p, limit = 2)
                if (parts.size > 1) {
                    var rest = parts[1].trim { it in PUNCT_TRIM_CHARS }
                    rest = MODAL_PARTICLES_REGEX.replace(rest, "").trim()
                    if (rest.length < 8) {
                        return true
                    }
                }
            }
        }
        return false
    }

    fun shouldSend(
        drivesSnapshot: Map<String, Double>,
        absentHours: Double,
        lastSentEpochMs: Long?,
        countToday: Int,
        nowEpochMs: Long,
        assistant: Assistant? = null,
        hasSentInCurrentAbsence: Boolean = false,
        passCooldownEndEpochMs: Long? = null,
    ): ActiveSendDecision {
        // 1. 最近真实用户消息不足 30 分钟，不主动发送
        if (absentHours < PRESENT_THRESHOLD_HOURS) {
            return ActiveSendDecision(shouldSend = false)
        }

        // 2. 发送冷却检查 (1 小时)
        if (lastSentEpochMs != null) {
            val diffSeconds = (nowEpochMs - lastSentEpochMs) / 1000L
            if (diffSeconds < COOLDOWN_SECONDS) {
                return ActiveSendDecision(shouldSend = false)
            }
        }

        // 3. PASS 评估冷却检查
        if (passCooldownEndEpochMs != null && nowEpochMs < passCooldownEndEpochMs) {
            return ActiveSendDecision(shouldSend = false)
        }

        // 4. 每日上限检查
        if (countToday >= DAILY_LIMIT) {
            return ActiveSendDecision(shouldSend = false)
        }

        // 5. 6 小时硬触发检查：到达 6 小时且本次失联阶段尚未成功主动联系过
        val longAbsentEnabled = assistant?.desireActiveSendLongAbsentEnabled ?: true
        val longAbsentHours = assistant?.desireActiveSendLongAbsentHours ?: LONG_ABSENT_HOURS
        if (longAbsentEnabled && absentHours >= longAbsentHours && !hasSentInCurrentAbsence) {
            return ActiveSendDecision(shouldSend = true, reason = "long_absent")
        }

        // 6. 3 小时基础离线门槛检查：3 小时以前不因普通维度主动发送
        val minAbsentHours = assistant?.desireActiveSendMinAbsentHours ?: MIN_ABSENT_HOURS
        if (absentHours < minAbsentHours) {
            return ActiveSendDecision(shouldSend = false)
        }

        // 7. 九维内在欲望缺口触发检查（3~6 小时选择标准化紧迫度最高维度；6 小时后其他欲望仍可按阈值触发）
        val candidateDrives = listOf(
            "attachment",
            "joy",
            "curiosity",
            "duty",
            "social",
            "intimacy",
            "reflection",
            "fatigue",
            "stress",
        )

        data class Candidate(val driveKey: String, val urgency: Double, val originalIndex: Int)
        val candidates = mutableListOf<Candidate>()

        for ((index, key) in candidateDrives.withIndex()) {
            val enabled = assistant?.desireActiveSendDriveEnabled?.get(key) ?: true
            if (!enabled) continue

            val threshold = assistant?.desireActiveSendDriveThresholds?.get(key)
                ?: DEFAULT_THRESHOLDS[key]
                ?: 85.0

            val value = drivesSnapshot[key] ?: 0.0
            if (value >= threshold) {
                // duty 成功表达后，本次失联阶段不再因纯时间增长重复触发责任提醒
                if (key == "duty" && hasSentInCurrentAbsence) {
                    continue
                }
                val urgency = if (threshold < 100.0) {
                    (value - threshold) / (100.0 - threshold)
                } else {
                    1.0
                }
                candidates.add(Candidate(key, urgency, index))
            }
        }

        if (candidates.isNotEmpty()) {
            val top = candidates.sortedWith(
                compareByDescending<Candidate> { it.urgency }
                    .thenBy { it.originalIndex }
            ).first()
            return ActiveSendDecision(shouldSend = true, reason = top.driveKey)
        }

        return ActiveSendDecision(shouldSend = false)
    }
}
