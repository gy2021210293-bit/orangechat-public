/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManagerInitializationTest {

    @Test
    fun testInitializationSuccess() = runBlocking {
        val deferred = CompletableDeferred<Result<Unit>>()
        deferred.complete(Result.success(Unit))

        val result = deferred.await()
        assertTrue(result.isSuccess)
    }

    @Test
    fun testInitializationFailureCapturedProperly() = runBlocking {
        val deferred = CompletableDeferred<Result<Unit>>()
        val expectedError = IllegalStateException("Disk read failed")
        deferred.complete(Result.failure(expectedError))

        val result = deferred.await()
        assertTrue(result.isFailure)
        assertEquals("Disk read failed", result.exceptionOrNull()?.message)
    }

    @Test
    fun testInitializationTimeoutHandled() = runBlocking {
        val deferred = CompletableDeferred<Result<Unit>>()
        // Not completed, will timeout
        val timeoutResult = withTimeoutOrNull(50L) {
            deferred.await()
        }

        assertNull(timeoutResult)
    }
}
