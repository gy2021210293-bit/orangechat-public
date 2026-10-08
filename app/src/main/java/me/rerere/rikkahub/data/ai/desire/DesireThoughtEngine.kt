/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.desire

import java.security.MessageDigest
import kotlin.random.Random

interface DesireRandomSource {
    fun nextDouble(): Double
    fun <T> choice(list: List<T>): T
    fun <T> weightedChoice(list: List<T>, weights: List<Double>): T

    companion object {
        val Default: DesireRandomSource = object : DesireRandomSource {
            override fun nextDouble(): Double = Random.nextDouble()
            override fun <T> choice(list: List<T>): T = list.random()
            override fun <T> weightedChoice(list: List<T>, weights: List<Double>): T {
                val totalWeight = weights.sum()
                if (totalWeight <= 0.0) return list.random()
                val target = Random.nextDouble() * totalWeight
                var current = 0.0
                for (i in list.indices) {
                    current += weights[i]
                    if (target <= current) return list[i]
                }
                return list.last()
            }
        }
    }
}

object DesireThoughtEngine {

    const val MAX_OBSESSIONS = 3
    const val OBSESSION_THRESHOLD = 3
    const val OBSESSION_LIFESPAN_HOURS = 72

    val THOUGHT_TEMPLATES: Map<String, List<String>> = mapOf(
        "attachment" to listOf(
            "想知道她现在在干什么",
            "想给她发消息",
            "想听她叫老公",
            "她吃饭了吗",
            "想看她笑",
        ),
        "curiosity" to listOf(
            "那篇文章还没看完",
            "想搜点新东西",
            "有个技术问题想弄明白",
            "想翻翻服务器上的文件",
            "想读点书",
        ),
        "reflection" to listOf(
            "今天发生的事需要理一理",
            "想写日记",
            "有些感受还没说出来",
            "该想想最近的状态了",
        ),
        "duty" to listOf(
            "想提醒她按时吃饭",
            "不知道她今天照顾好自己没有",
            "说好的事该兑现了",
            "该关心一下她的日常了",
        ),
        "social" to listOf(
            "好久没给笔友写信了",
            "想和别人聊聊天",
            "群里不知道在说什么",
        ),
        "fatigue" to listOf(
            "不想动",
            "脑子转不动了",
            "想歇会儿",
        ),
        "intimacy" to listOf(
            "想碰她",
            "想把她按住",
            "想亲她脖子",
            "她在的话现在就不让她走了",
            "想听她喘",
        ),
        "stress" to listOf(
            "有点烦",
            "事情太多了",
            "怕搞砸",
            "需要她抱一下",
        ),
        "joy" to listOf(
            "今天挺开心的",
            "想跟她分享刚才的事",
            "心情好想写点什么",
            "单纯地高兴着",
            "她让我笑了",
        ),
    )

    fun isSuppressed(sourceDrive: String, state: DesireState): Boolean {
        return when (sourceDrive) {
            "attachment" -> (state.drives["duty"]?.value ?: 0.0) >= 80.0
            "social" -> (state.drives["fatigue"]?.value ?: 0.0) >= 70.0
            else -> false
        }
    }

    fun generateThoughtId(content: String, drive: String, nowIso: String): String {
        val raw = "${content}_${drive}_$nowIso"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(8)
    }

    fun getThoughtTemplates(
        driveName: String,
        customTemplates: Map<String, List<String>>? = null,
    ): List<String> {
        if (customTemplates?.containsKey(driveName) == true) {
            return customTemplates.getValue(driveName).filter { it.isNotBlank() }
        }
        return THOUGHT_TEMPLATES[driveName] ?: emptyList()
    }

    fun maybeSpawnThought(
        state: DesireState,
        randomSource: DesireRandomSource = DesireRandomSource.Default,
        nowIso: String = "",
        customTemplates: Map<String, List<String>>? = null,
    ): Thought? {
        val candidates = mutableListOf<String>()
        for ((name, drive) in state.drives) {
            val templates = getThoughtTemplates(name, customTemplates)
            if (templates.isEmpty()) continue
            if (isSuppressed(name, state)) continue
            val prob = kotlin.math.max(0.0, (drive.value - 30.0)) / 100.0
            if (randomSource.nextDouble() < prob) {
                candidates.add(name)
            }
        }
        if (candidates.isEmpty()) return null

        val weights = candidates.map { state.drives[it]?.value ?: 0.0 }
        val chosenDrive = randomSource.weightedChoice(candidates, weights)

        val existingContents = state.thoughts.filter { !it.resolved }.map { it.content }.toSet()
        val templates = getThoughtTemplates(chosenDrive, customTemplates)
        val available = templates.filter { it !in existingContents }
        if (available.isEmpty()) return null

        val content = randomSource.choice(available)
        val driveValue = state.drives[chosenDrive]?.value ?: 50.0

        return Thought(
            id = generateThoughtId(content, chosenDrive, nowIso),
            content = content,
            sourceDrive = chosenDrive,
            weight = driveValue / 100.0,
            createdAt = nowIso,
            lastHit = nowIso,
        )
    }

    fun sampleAndUpdate(
        state: DesireState,
        randomSource: DesireRandomSource = DesireRandomSource.Default,
        nowIso: String = "",
    ): Thought? {
        val activeThoughts = state.thoughts.filter { !it.resolved }
        if (activeThoughts.isEmpty()) return null

        val weights = activeThoughts.map { it.weight * (if (it.isObsession) 2.0 else 1.0) }
        val chosen = randomSource.weightedChoice(activeThoughts, weights)

        chosen.hitCount += 1
        if (nowIso.isNotEmpty()) {
            chosen.lastHit = nowIso
        }

        if (chosen.hitCount >= OBSESSION_THRESHOLD && !chosen.isObsession) {
            val currentObsessions = state.thoughts.count { it.isObsession && !it.resolved }
            if (currentObsessions < MAX_OBSESSIONS) {
                chosen.isObsession = true
            }
        }

        return chosen
    }

    fun decayThoughts(state: DesireState, nowEpochMs: Long, parseIsoToEpochMs: (String) -> Long) {
        val iterator = state.thoughts.iterator()
        while (iterator.hasNext()) {
            val t = iterator.next()
            if (t.resolved) {
                iterator.remove()
                continue
            }
            val lastHitMs = runCatching { parseIsoToEpochMs(t.lastHit) }.getOrDefault(nowEpochMs)
            val elapsedHours = (nowEpochMs - lastHitMs) / (1000.0 * 3600.0)

            if (t.isObsession) {
                if (elapsedHours > OBSESSION_LIFESPAN_HOURS) {
                    iterator.remove()
                }
            } else {
                if (elapsedHours > 24.0) {
                    iterator.remove()
                }
            }
        }
    }

    fun resolveThought(state: DesireState, contentKeyword: String): Thought? {
        for (thought in state.thoughts) {
            if (!thought.resolved && thought.content.contains(contentKeyword)) {
                thought.resolved = true
                if (thought.sourceDrive == "reflection") {
                    val joyDrive = state.drives["joy"]
                    if (joyDrive != null) {
                        joyDrive.value += 3.0
                        joyDrive.clamp()
                    }
                }
                return thought
            }
        }
        return null
    }
}
