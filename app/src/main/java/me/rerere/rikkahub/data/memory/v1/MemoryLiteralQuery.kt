/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

/**
 * 专名、书名号、引号短语与短 CJK 专名词提取器。
 * 移植并优化自 LMC-5 的字面检索提取逻辑，适配中文专名、作品名与代号。
 */
object MemoryLiteralQuery {

    private const val MAX_QUERY_CHARS = 80

    private val QUOTE_REGEX = Regex("""[「『“"\'`《〈]([^」』”"\'`》〉]{2,80})[」』”"\'`》〉]""")

    private val LITERAL_TRIGGER_REGEX = Regex(
        """(?:搜一下|查一下|找一下|搜索|检索|搜|查|找|记得|说过|提到|关于|叫|叫做|名为)([\u4e00-\u9fffA-Za-z0-9_-]{2,40})"""
    )

    private val ENGLISH_TOKEN_REGEX = Regex("""[A-Za-z][A-Za-z0-9_-]{2,40}""")

    private val CJK_SEQ_REGEX = Regex("""[\u4e00-\u9fff]{2,24}""")

    private val PUNCTUATION_REGEX = Regex("""[\s：:，,。.!！?？、；;“”‘’'"'`「」『』（）()【】\[\]《》〈〉]+""")

    private val GENERIC_CJK_STOPS = setOf(
        "今天", "刚才", "现在", "什么", "怎么", "为什么", "是不是", "可以",
        "需要", "感觉", "觉得", "想要", "开心", "难过", "很累", "继续",
        "你好", "在吗", "哈哈", "好的", "行吧", "没事", "谢谢", "再见",
        "那个", "这个", "知道", "明白"
    )

    private val PREFIXES = listOf(
        "你帮我", "帮我", "能不能", "可不可以", "你能", "我们", "刚才",
        "上次", "上一段", "上个", "这个", "那个", "搜一下", "查一下",
        "找一下", "搜索", "检索", "记得", "说过", "提到", "关于",
        "叫", "叫做", "名为", "你", "我", "搜", "查", "找"
    )

    private val SUFFIXES = listOf("吗", "呢", "啊", "呀", "么", "吧", "不")

    /**
     * 判断当前查询是否适合进行独立字面/专名检索。
     */
    fun shouldRunLiteralSearch(query: String, maxChars: Int = MAX_QUERY_CHARS): Boolean {
        val text = query.trim()
        if (text.isEmpty() || text.length > maxChars) {
            return false
        }
        // 包含引号或书名号
        if (QUOTE_REGEX.containsMatchIn(text)) {
            return true
        }
        // 包含检索/记忆触发词后接专名
        if (LITERAL_TRIGGER_REGEX.containsMatchIn(text)) {
            return true
        }
        // 包含英文代号/专名
        if (ENGLISH_TOKEN_REGEX.containsMatchIn(text)) {
            return true
        }
        // 短 CJK 查询（2-8 字符且不为纯通用停用词）
        val compact = text.replace(PUNCTUATION_REGEX, "")
        if (compact.length in 2..8 && compact.all { it in '\u4e00'..'\u9fff' }) {
            return GENERIC_CJK_STOPS.none { stop -> compact == stop }
        }
        return false
    }

    /**
     * 提取查询中适合进行精确检索的专名短语。
     */
    fun extractLiteralQueryTerms(query: String, maxTerms: Int = 16): List<String> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()

        val terms = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun addTerm(term: String) {
            val clean = term.trim(' ', '\t', '\r', '\n', '：', ':', '，', ',', '。', '.', '!', '！', '?', '？', '、', '；', ';', '“', '”', '‘', '’', '\'', '"', '`', '「', '」', '『', '』', '（', '）', '(', ')', '[', ']', '【', '】', '《', '》', '〈', '〉')
            if (clean.length >= 2 && clean !in seen && clean !in GENERIC_CJK_STOPS) {
                seen.add(clean)
                terms.add(clean)
            }
        }

        // 1. 显式书名号/引号优先级最高
        QUOTE_REGEX.findAll(text).forEach { m ->
            m.groups[1]?.value?.let { addTerm(it) }
        }

        // 2. 触发词后专名
        LITERAL_TRIGGER_REGEX.findAll(text).forEach { m ->
            m.groups[1]?.value?.let { addTerm(it) }
        }

        // 3. 剥离前缀/后缀的 CJK 片段
        CJK_SEQ_REGEX.findAll(text).forEach { m ->
            var seq = m.value
            var changed = true
            while (changed) {
                changed = false
                for (p in PREFIXES) {
                    if (seq.startsWith(p) && seq.length - p.length >= 2) {
                        seq = seq.substring(p.length)
                        changed = true
                        break
                    }
                }
                for (s in SUFFIXES) {
                    if (seq.endsWith(s) && seq.length - s.length >= 2) {
                        seq = seq.substring(0, seq.length - s.length)
                        changed = true
                        break
                    }
                }
            }
            addTerm(seq)

            if (seq.length in 3..12 && terms.size < maxTerms) {
                val maxN = minOf(8, seq.length)
                for (n in maxN downTo 2) {
                    for (i in 0..seq.length - n) {
                        addTerm(seq.substring(i, i + n))
                        if (terms.size >= maxTerms) return terms.take(maxTerms)
                    }
                }
            }
        }

        // 4. 英文标识符 / 代号
        ENGLISH_TOKEN_REGEX.findAll(text).forEach { m ->
            addTerm(m.value)
            if (terms.size >= maxTerms) return terms.take(maxTerms)
        }

        return terms.take(maxTerms)
    }
}
