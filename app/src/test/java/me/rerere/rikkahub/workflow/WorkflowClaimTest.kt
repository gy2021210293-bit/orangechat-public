/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.workflow.db.WorkflowFireClaimEntity
import me.rerere.rikkahub.workflow.db.WorkflowFireClaimStatus
import me.rerere.rikkahub.workflow.model.ClaimResult
import me.rerere.rikkahub.workflow.model.WorkflowFireSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class WorkflowClaimTest {

    /**
     * 模拟原子事务 Claim 机制，验证状态流转、并发抢占与 ClaimToken 所有权控制
     */
    private class InMemoryClaimEngine {
        private val table = ConcurrentHashMap<String, WorkflowFireClaimEntity>()
        private val mutex = Mutex()

        private fun key(workflowId: String, scheduledAt: Long) = "$workflowId:$scheduledAt"

        suspend fun tryClaim(
            workflowId: String,
            scheduledAt: Long,
            source: String,
            nowMs: Long,
            leaseDurationMs: Long = 10 * 60 * 1000L,
            maxAttempts: Int = 2,
        ): ClaimResult = mutex.withLock {
            val k = key(workflowId, scheduledAt)
            val existing = table[k]

            if (existing == null) {
                val token = java.util.UUID.randomUUID().toString()
                val newEntity = WorkflowFireClaimEntity(
                    workflowId = workflowId,
                    scheduledAt = scheduledAt,
                    status = WorkflowFireClaimStatus.RUNNING,
                    source = source,
                    claimedAt = nowMs,
                    leaseUntil = nowMs + leaseDurationMs,
                    claimToken = token,
                    attemptCount = 1,
                )
                table[k] = newEntity
                return ClaimResult.Acquired(token)
            }

            when (existing.status) {
                WorkflowFireClaimStatus.SUCCEEDED,
                WorkflowFireClaimStatus.SKIPPED -> {
                    return ClaimResult.AlreadyCompleted(existing.status)
                }
                WorkflowFireClaimStatus.RUNNING -> {
                    if (nowMs < existing.leaseUntil) {
                        return ClaimResult.AlreadyRunning(existing.leaseUntil)
                    }
                    if (existing.attemptCount >= maxAttempts) {
                        return ClaimResult.RetryLimitReached
                    }
                    val token = java.util.UUID.randomUUID().toString()
                    val updated = existing.copy(
                        source = source,
                        status = WorkflowFireClaimStatus.RUNNING,
                        attemptCount = existing.attemptCount + 1,
                        claimedAt = nowMs,
                        leaseUntil = nowMs + leaseDurationMs,
                        claimToken = token,
                    )
                    table[k] = updated
                    return ClaimResult.Acquired(token)
                }
                WorkflowFireClaimStatus.FAILED_RETRYABLE -> {
                    if (existing.attemptCount >= maxAttempts) {
                        return ClaimResult.RetryLimitReached
                    }
                    val token = java.util.UUID.randomUUID().toString()
                    val updated = existing.copy(
                        source = source,
                        status = WorkflowFireClaimStatus.RUNNING,
                        attemptCount = existing.attemptCount + 1,
                        claimedAt = nowMs,
                        leaseUntil = nowMs + leaseDurationMs,
                        claimToken = token,
                        error = null,
                    )
                    table[k] = updated
                    return ClaimResult.Acquired(token)
                }
                WorkflowFireClaimStatus.FAILED_FINAL -> {
                    return ClaimResult.RetryLimitReached
                }
                else -> {
                    return ClaimResult.AlreadyCompleted(existing.status)
                }
            }
        }

        suspend fun updateStatus(
            workflowId: String,
            scheduledAt: Long,
            claimToken: String,
            status: String,
            completedAt: Long?,
            error: String?,
        ): Boolean = mutex.withLock {
            val k = key(workflowId, scheduledAt)
            val existing = table[k] ?: return false
            if (existing.status != WorkflowFireClaimStatus.RUNNING || existing.claimToken != claimToken) {
                return false
            }
            table[k] = existing.copy(
                status = status,
                completedAt = completedAt,
                error = error,
            )
            true
        }

        suspend fun markSucceeded(workflowId: String, scheduledAt: Long, claimToken: String, nowMs: Long): Boolean {
            return updateStatus(
                workflowId = workflowId,
                scheduledAt = scheduledAt,
                claimToken = claimToken,
                status = WorkflowFireClaimStatus.SUCCEEDED,
                completedAt = nowMs,
                error = null,
            )
        }

        suspend fun markFailed(
            workflowId: String,
            scheduledAt: Long,
            claimToken: String,
            error: String?,
            retryable: Boolean,
            nowMs: Long,
        ): Boolean {
            val status = if (retryable) {
                WorkflowFireClaimStatus.FAILED_RETRYABLE
            } else {
                WorkflowFireClaimStatus.FAILED_FINAL
            }
            return updateStatus(
                workflowId = workflowId,
                scheduledAt = scheduledAt,
                claimToken = claimToken,
                status = status,
                completedAt = nowMs,
                error = error,
            )
        }

        fun getClaim(workflowId: String, scheduledAt: Long): WorkflowFireClaimEntity? {
            return table[key(workflowId, scheduledAt)]
        }
    }

    @Test
    fun testFirstClaimAcquiresLockWithToken() = runBlocking {
        val engine = InMemoryClaimEngine()
        val now = 1000000L
        val result = engine.tryClaim("wf-1", 1000000L, WorkflowFireSource.ALARM.name, now)

        assertTrue(result is ClaimResult.Acquired)
        val token = (result as ClaimResult.Acquired).claimToken
        assertTrue(token.isNotBlank())
    }

    @Test
    fun testConcurrent20ClaimsOnlyOneAcquires() = runBlocking {
        val engine = InMemoryClaimEngine()
        val scheduledAt = 2000000L
        val now = 2000000L

        // 模拟 20 个 Alarm 与 Worker 同时并发到达
        val deferreds = (1..20).map { i ->
            async {
                val source = if (i % 2 == 0) WorkflowFireSource.ALARM.name else WorkflowFireSource.WORKER.name
                engine.tryClaim("wf-concurrent", scheduledAt, source, now)
            }
        }

        val results = deferreds.awaitAll()
        val acquiredCount = results.count { it is ClaimResult.Acquired }
        val runningCount = results.count { it is ClaimResult.AlreadyRunning }

        assertEquals(1, acquiredCount)
        assertEquals(19, runningCount)
    }

    @Test
    fun testAlreadyCompletedCannotBeClaimed() = runBlocking {
        val engine = InMemoryClaimEngine()
        val scheduledAt = 3000000L
        val now = 3000000L

        val claim1 = engine.tryClaim("wf-done", scheduledAt, WorkflowFireSource.ALARM.name, now)
        assertTrue(claim1 is ClaimResult.Acquired)
        val token = (claim1 as ClaimResult.Acquired).claimToken

        // 标记完成
        val ok = engine.markSucceeded("wf-done", scheduledAt, token, now + 5000L)
        assertTrue(ok)

        // Worker 在 15 分钟后触发
        val workerResult = engine.tryClaim("wf-done", scheduledAt, WorkflowFireSource.WORKER.name, now + 15 * 60 * 1000L)
        assertTrue(workerResult is ClaimResult.AlreadyCompleted)
        assertEquals(WorkflowFireClaimStatus.SUCCEEDED, (workerResult as ClaimResult.AlreadyCompleted).status)
    }

    @Test
    fun testExpiredLeaseIsTakenOverByWorkerAndOldExecutionCannotOverwrite() = runBlocking {
        val engine = InMemoryClaimEngine()
        val scheduledAt = 4000000L
        val alarmTime = 4000000L

        // 1. Alarm 领取任务 (attempt 1)
        val claim1 = engine.tryClaim("wf-takeover", scheduledAt, WorkflowFireSource.ALARM.name, alarmTime)
        assertTrue(claim1 is ClaimResult.Acquired)
        val alarmToken = (claim1 as ClaimResult.Acquired).claimToken

        // 2. Alarm 执行卡死或超时 15 分钟（租约 10 分钟已过期）
        val workerTime = alarmTime + 15 * 60 * 1000L
        val claim2 = engine.tryClaim("wf-takeover", scheduledAt, WorkflowFireSource.WORKER.name, workerTime)
        assertTrue(claim2 is ClaimResult.Acquired)
        val workerToken = (claim2 as ClaimResult.Acquired).claimToken
        assertTrue(alarmToken != workerToken)

        // 3. 关键场景：Alarm 随后延迟返回，尝试写入 SUCCEEDED
        val alarmUpdateResult = engine.markSucceeded("wf-takeover", scheduledAt, alarmToken, workerTime + 1000L)
        // 必须被拒绝（返回 false）
        assertEquals(false, alarmUpdateResult)

        // 校验数据库状态仍为 RUNNING 且 token 依然是 Worker 的 token
        val claimInDb = engine.getClaim("wf-takeover", scheduledAt)
        assertEquals(WorkflowFireClaimStatus.RUNNING, claimInDb?.status)
        assertEquals(workerToken, claimInDb?.claimToken)
        assertEquals(2, claimInDb?.attemptCount)

        // 4. Worker 正常完成并写入
        val workerUpdateResult = engine.markSucceeded("wf-takeover", scheduledAt, workerToken, workerTime + 5000L)
        assertEquals(true, workerUpdateResult)

        val finalClaimInDb = engine.getClaim("wf-takeover", scheduledAt)
        assertEquals(WorkflowFireClaimStatus.SUCCEEDED, finalClaimInDb?.status)
    }

    @Test
    fun testRetryLimitReachedStopsFurtherAttempts() = runBlocking {
        val engine = InMemoryClaimEngine()
        val scheduledAt = 5000000L
        var time = 5000000L

        // 尝试 1：Alarm 失败
        val c1 = engine.tryClaim("wf-retry", scheduledAt, WorkflowFireSource.ALARM.name, time)
        assertTrue(c1 is ClaimResult.Acquired)
        val token1 = (c1 as ClaimResult.Acquired).claimToken
        val ok1 = engine.markFailed("wf-retry", scheduledAt, token1, "Network timeout", retryable = true, time + 2000L)
        assertTrue(ok1)

        // 尝试 2：Worker 补偿接管
        time += 15 * 60 * 1000L
        val c2 = engine.tryClaim("wf-retry", scheduledAt, WorkflowFireSource.WORKER.name, time)
        assertTrue(c2 is ClaimResult.Acquired)
        val token2 = (c2 as ClaimResult.Acquired).claimToken
        assertTrue(token1 != token2)
        val ok2 = engine.markFailed("wf-retry", scheduledAt, token2, "Network timeout again", retryable = true, time + 2000L)
        assertTrue(ok2)

        // 尝试 3：后续触发，已达上限 (maxAttempts = 2)
        time += 5 * 60 * 1000L
        val c3 = engine.tryClaim("wf-retry", scheduledAt, WorkflowFireSource.WORKER.name, time)
        assertTrue(c3 is ClaimResult.RetryLimitReached)
    }

    @Test
    fun testErrorRetryabilityClassification() {
        // 网络/超时/限流 -> 可重试
        assertTrue(me.rerere.rikkahub.workflow.model.isRetryableWorkflowError("java.net.SocketTimeoutException: timeout"))
        assertTrue(me.rerere.rikkahub.workflow.model.isRetryableWorkflowError("HTTP 429 Too Many Requests"))
        assertTrue(me.rerere.rikkahub.workflow.model.isRetryableWorkflowError("503 Service Unavailable"))
        assertTrue(me.rerere.rikkahub.workflow.model.isRetryableWorkflowError("Network connection failed"))
        assertTrue(me.rerere.rikkahub.workflow.model.isRetryableWorkflowError("UnknownHostException: api.openai.com"))

        // 配置/工具缺失/语法错误 -> 终态不可重试
        org.junit.Assert.assertFalse(me.rerere.rikkahub.workflow.model.isRetryableWorkflowError("action 0: unknown_tool:foo"))
        org.junit.Assert.assertFalse(me.rerere.rikkahub.workflow.model.isRetryableWorkflowError("no_workflows_assistant"))
        org.junit.Assert.assertFalse(me.rerere.rikkahub.workflow.model.isRetryableWorkflowError("workflow_not_found"))
        org.junit.Assert.assertFalse(me.rerere.rikkahub.workflow.model.isRetryableWorkflowError(null))
        org.junit.Assert.assertFalse(me.rerere.rikkahub.workflow.model.isRetryableWorkflowError(""))
    }
}
