/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

data class QueryEmotion(
    val valence: Float,   // -1.0 .. 1.0
    val arousal: Float,   // 0.0 .. 1.0
    val confidence: Float // 0.0 .. 1.0
)

interface QueryEmotionDetector {
    /**
     * 检测 query 中的显式情绪坐标。
     * 低置信度（< 0.6）或中性/无情绪时返回 null，避免情绪共振通道误启动。
     */
    fun detect(text: String): QueryEmotion?
}

/**
 * 基于保守规则/关键词的本地查询情绪检测器。
 * 遵循计划规范：
 * 1. 仅基于显式情绪词、否定词、程度副词、emoji 与标点模式；
 * 2. 置信度低于 0.6 时直接返回 null；
 * 3. 绝不读取外部 Desire 状态，不臆测时间段。
 */
class RuleBasedQueryEmotionDetector(
    private val minConfidence: Float = 0.6f
) : QueryEmotionDetector {

    private data class EmotionSeed(
        val words: List<String>,
        val valence: Float,
        val arousal: Float,
        val weight: Float = 1.0f
    )

    private val seeds = listOf(
        // 消极低唤醒 (悲伤、委屈、失落、疲倦、孤单)
        EmotionSeed(
            words = listOf("难过", "伤心", "想哭", "低落", "委屈", "好累", "太累了", "心累", "孤独", "无助", "抑郁", "丧", "绝望", "心痛", "痛苦", "舍不得", "哭泣", "😭", "😢", "💔", "😔", "🥺", "😿", "😞"),
            valence = -0.75f,
            arousal = 0.25f,
            weight = 1.0f
        ),
        // 消极高唤醒 (愤怒、烦躁、焦虑、抓狂、慌乱)
        EmotionSeed(
            words = listOf("生气", "烦死", "烦躁", "抓狂", "气死", "愤怒", "火大", "可恶", "离谱", "焦虑", "恐慌", "崩溃", "暴躁", "恶心", "发飙", "😡", "🤬", "😤", "💢", "🤯"),
            valence = -0.80f,
            arousal = 0.85f,
            weight = 1.0f
        ),
        // 积极高唤醒 (高兴、兴奋、太棒了、激动、狂喜)
        EmotionSeed(
            words = listOf("高兴", "开心", "太棒了", "兴奋", "激动", "好棒", "太好啦", "哈哈", "好耶", "狂喜", "欣喜", "庆幸", "爽", "嗨", "🎉", "🥳", "✨", "😄", "😆", "🥰", "❤️"),
            valence = 0.80f,
            arousal = 0.80f,
            weight = 1.0f
        ),
        // 积极低唤醒 (安心、温暖、轻松、平静、舒坦)
        EmotionSeed(
            words = listOf("安心", "温暖", "轻松", "舒服", "平静", "踏实", "释然", "治愈", "知足", "满足", "坦然", "暖和", "安稳", "😌", "🍵", "🌿", "☺️"),
            valence = 0.65f,
            arousal = 0.25f,
            weight = 1.0f
        )
    )

    private val intensifiers = listOf("好", "很", "真", "太", "非常", "特别", "超级", "无比", "极其", "越发", "十分")
    private val negators = listOf("不", "没", "毫无", "并不", "别", "无")

    override fun detect(text: String): QueryEmotion? {
        val trimmed = text.trim()
        if (trimmed.length < 2) return null

        var weightedValence = 0f
        var weightedArousal = 0f
        var totalWeight = 0f
        var matchCount = 0

        for (seed in seeds) {
            for (w in seed.words) {
                val index = trimmed.indexOf(w)
                if (index != -1) {
                    val prefix = trimmed.substring(maxOf(0, index - 4), index)
                    val cleanPrefix = intensifiers.fold(prefix) { acc, intensifier -> acc.replace(intensifier, "") }
                    val isNegated = negators.any { cleanPrefix.endsWith(it) || cleanPrefix.contains(it) }
                    val isIntensified = intensifiers.any { prefix.contains(it) }

                    val multiplier = when {
                        isNegated -> -0.7f
                        isIntensified -> 1.3f
                        else -> 1.0f
                    }

                    val effectiveWeight = seed.weight * (if (isIntensified) 1.2f else 1.0f)
                    weightedValence += (seed.valence * multiplier) * effectiveWeight
                    weightedArousal += (seed.arousal * (if (isNegated) 0.5f else 1.0f)) * effectiveWeight
                    totalWeight += effectiveWeight
                    matchCount++
                }
            }
        }

        if (totalWeight <= 0f || matchCount == 0) {
            return null
        }

        val avgValence = (weightedValence / totalWeight).coerceIn(-1.0f, 1.0f)
        val avgArousal = (weightedArousal / totalWeight).coerceIn(0.0f, 1.0f)

        var baseConfidence = 0.5f + (matchCount * 0.15f)
        if (trimmed.contains("！") || trimmed.contains("!")) {
            baseConfidence += 0.1f
        }
        val confidence = baseConfidence.coerceIn(0.0f, 1.0f)

        if (confidence < minConfidence) {
            return null
        }

        return QueryEmotion(
            valence = avgValence,
            arousal = avgArousal,
            confidence = confidence
        )
    }
}
