/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.model

/**
 * 触发来源标识
 */
enum class WorkflowFireSource {
    ALARM,
    WORKER,
    MANUAL,
}

/**
 * 领取结果密封接口
 */
sealed interface ClaimResult {
    data class Acquired(val claimToken: String) : ClaimResult
    data class AlreadyCompleted(val status: String) : ClaimResult
    data class AlreadyRunning(val leaseUntil: Long) : ClaimResult
    data object RetryLimitReached : ClaimResult
}

/**
 * 判定工作流执行错误是否属于瞬时 / 可重试错误（如网络中断、DNS 解析失败、请求超时、临时限流等）。
 */
fun isRetryableWorkflowError(error: String?): Boolean {
    if (error.isNullOrBlank()) return false
    val lower = error.lowercase()
    return lower.contains("network") ||
        lower.contains("timeout") ||
        lower.contains("timed out") ||
        lower.contains("connection") ||
        lower.contains("connectexception") ||
        lower.contains("sockettimeoutexception") ||
        lower.contains("unknownhostexception") ||
        lower.contains("ioexception") ||
        lower.contains("rate limit") ||
        lower.contains("429") ||
        lower.contains("502") ||
        lower.contains("503") ||
        lower.contains("504")
}

