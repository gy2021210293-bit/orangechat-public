/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

/**
 * 本地轻量低信息门禁 (Low-Information Gate).
 *
 * 在生成 query embedding 或发起召回前进行本地轻量判断：
 * - 纯标点、纯空白、纯表情或单字常见语气词通常跳过；
 * - 但若包含已知实体、指代词、时间追问、偏好/关系词等白名单，绝不误伤；
 * - 规则不确定时放行检索，允许最终召回返回 0 条。
 */
object MemoryRecallGate {

    // 放行白名单：一旦包含这些词，即使极短也绝不拦截
    private val PASS_KEYWORDS = listOf(
        // 疑问代词与追问
        "谁", "哪", "何时", "几点", "什么时候", "为什么", "怎么", "什么", "多少",
        "哪儿", "哪里", "那个", "那个人", "上次", "之前", "昨天", "刚才", "哪天", "多久",
        // 偏好、习惯与属性
        "喜欢", "讨厌", "习惯", "生日", "电话", "手机", "号码", "地址", "住", "住哪",
        "密码", "邮箱", "朋友", "家人", "计划", "打算", "爱吃", "爱喝", "工作", "公司",
        "猫", "狗", "宠物", "名字", "叫什么", "多大", "岁数", "年龄", "爱好", "兴趣",
        "记得", "记不记得", "想起来", "备忘",
    )

    // 纯无意义语气词与寒暄词黑名单
    private val LOW_INFO_EXACT_SET = setOf(
        "嗯", "嗯嗯", "哦", "哦哦", "好", "好的", "行", "行吧", "可以", "可以的",
        "哈哈", "哈哈哈", "收到", "1", "ok", "OK", "yes", "no", "hi", "hello",
        "你好", "在吗", "在", "拜拜", "再见", "早", "早安", "早上好", "晚安",
        "好呀", "好的呀", "谢谢", "谢谢呀", "多谢", "没事", "不客气", "算了吧", "算了",
    )

    // 标点、空白与控制字符正则
    private val PUNCT_AND_SPACES = Regex("""[\p{Punct}\p{P}\p{S}\p{Z}\s]""")

    /**
     * 判断当前查询是否属于低信息量输入。
     *
     * @param query 当前用户本轮输入的文本
     * @return true 表示为低信息量输入，应跳过自动召回；false 表示应放行召回。
     */
    fun isLowInformation(query: String?): Boolean {
        if (query.isNullOrBlank()) return true

        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true

        // 1. 优先检查白名单：包含任意白名单关键词则绝不拦截
        for (keyword in PASS_KEYWORDS) {
            if (trimmed.contains(keyword, ignoreCase = true)) {
                return false
            }
        }

        // 2. 去除所有标点、空白、符号与表情符号
        val stripped = PUNCT_AND_SPACES.replace(trimmed, "").lowercase()
        if (stripped.isEmpty()) return true

        // 3. 检查是否完全命中黑名单语气词
        if (LOW_INFO_EXACT_SET.contains(stripped)) {
            return true
        }

        // 4. 重复单一字符（如 "呃呃呃"、"啊啊啊"）判定为低信息
        if (stripped.length <= 4 && stripped.all { it == stripped[0] }) {
            return true
        }

        return false
    }
}
