package org.zoocode.jetbrains.ipc.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.zoocode.jetbrains.util.toCompletableFuture
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class LazyPromiseTest {
    @Test
    fun `failed reply rejects Java future with original error`() {
        val promise = LazyPromise()
        val future = promise.toCompletableFuture<String>()
        val error = IllegalStateException("remote failure")

        promise.resolveErr(error)

        val actual = assertThrows(ExecutionException::class.java) {
            future.get(1, TimeUnit.SECONDS)
        }
        assertSame(error, actual.cause)
    }

    @Test
    fun `late Java future subscriber receives original failure`() {
        val promise = LazyPromise()
        val error = IllegalStateException("remote failure")
        promise.resolveErr(error)

        val future = promise.toCompletableFuture<String>()

        val actual = assertThrows(ExecutionException::class.java) {
            future.get(1, TimeUnit.SECONDS)
        }
        assertSame(error, actual.cause)
    }

    @Test
    fun `cancelling promise cancels bridged Java future`() {
        val promise = LazyPromise()
        val future = promise.toCompletableFuture<String>()

        promise.cancel()

        assertTrue(future.isCancelled)
        assertThrows(CancellationException::class.java) {
            future.get(1, TimeUnit.SECONDS)
        }
    }

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
        assertFalse(promise.isCompleted)
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
            // The IDE's coroutine runtime can recover the asynchronous stack by
            // copying the exception and retaining the original as its cause.
            assertTrue(actual === error || actual.cause === error)
            assertEquals("remote failure", actual.message)
        }
    }

    @Test
    fun `cancelled promise completes and rejects awaiting callers`() = runBlocking {
        val promise = LazyPromise()
        assertFalse(promise.isCompleted)
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
