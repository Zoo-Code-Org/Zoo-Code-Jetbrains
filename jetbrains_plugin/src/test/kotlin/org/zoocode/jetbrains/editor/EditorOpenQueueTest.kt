package org.zoocode.jetbrains.editor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EditorOpenQueueTest {
    @Test(timeout = 5000)
    fun `file open waits for diff navigation to finish`() = runBlocking {
        val queue = EditorOpenQueue()
        val releaseDiff = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val diff = async(start = CoroutineStart.UNDISPATCHED) {
            queue.open {
                events.add("diff started")
                releaseDiff.await()
                events.add("diff completed")
            }
        }
        val file = async(start = CoroutineStart.UNDISPATCHED) {
            queue.open { events.add("file opened") }
        }
        assertEquals(listOf("diff started"), events)
        assertFalse(file.isCompleted)
        releaseDiff.complete(Unit)
        diff.await()
        file.await()
        assertEquals(listOf("diff started", "diff completed", "file opened"), events)
    }

    @Test(timeout = 5000)
    fun `cancelling a waiting open does not cancel the active navigation`() = runBlocking {
        val queue = EditorOpenQueue()
        val release = CompletableDeferred<Unit>()
        val active = async(start = CoroutineStart.UNDISPATCHED) {
            queue.open { release.await(); "opened" }
        }
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.open { fail("Cancelled waiter must never navigate") }
        }
        waiting.cancelAndJoin()
        assertTrue(active.isActive)
        release.complete(Unit)
        assertEquals("opened", active.await())
        assertEquals("next", queue.open { "next" })
    }

    @Test(timeout = 5000)
    fun `cancelling active navigation releases the next open`() = runBlocking {
        val queue = EditorOpenQueue()
        val active = launch(start = CoroutineStart.UNDISPATCHED) {
            queue.open { awaitCancellation() }
        }
        val next = async(start = CoroutineStart.UNDISPATCHED) { queue.open { "opened" } }
        assertFalse(next.isCompleted)
        active.cancelAndJoin()
        assertEquals("opened", next.await())
    }
}
