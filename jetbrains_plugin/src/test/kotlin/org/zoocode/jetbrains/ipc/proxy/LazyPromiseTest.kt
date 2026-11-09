package org.zoocode.jetbrains.ipc.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.zoocode.jetbrains.util.toCompletableFuture
import org.junit.Assert.*
import org.junit.Test

class LazyPromiseTest {
    @Test
    fun `completion callbacks still bridge replies to Java futures`() {
        val promise = LazyPromise()
        val future = promise.toCompletableFuture<String>()
        assertFalse(future.isDone)
        promise.resolveOk("reply")
        assertTrue(future.isDone)
        assertEquals("reply", future.get())
        assertEquals("reply", promise.toCompletableFuture<String>().get())
    }

    @Test
    fun `reply wakes waiting caller and first completion wins`() = runBlocking {
        val promise = LazyPromise()
        val caller = async(start = CoroutineStart.UNDISPATCHED) { promise.await() }
        assertFalse(caller.isCompleted)
        promise.resolveOk("reply")
        promise.resolveErr(IllegalStateException("late failure"))
        assertEquals("reply", caller.await())
        assertTrue(promise.isCompleted)
    }

    @Test
    fun `failed reply preserves original error`() = runBlocking {
        val error = IllegalStateException("remote failure")
        val promise = LazyPromise()
        promise.resolveErr(error)
        try {
            promise.await()
            fail("Expected remote failure")
        } catch (actual: IllegalStateException) {
            assertSame(error, actual)
        }
    }

    @Test
    fun `cancelled promise completes and rejects awaiting callers`() = runBlocking {
        val promise = LazyPromise()
        promise.cancel()
        assertTrue(promise.isCompleted)
        try {
            promise.await()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `pre-cancelled RPC reply keeps its protocol error`() = runBlocking {
        val promise = CanceledLazyPromise()
        assertTrue(promise.isCompleted)
        try {
            promise.await()
            fail("Expected protocol cancellation")
        } catch (_: CanceledException) {
        }
    }
}
