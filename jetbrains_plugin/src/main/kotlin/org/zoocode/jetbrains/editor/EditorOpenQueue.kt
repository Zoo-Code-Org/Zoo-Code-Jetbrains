package org.zoocode.jetbrains.editor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal class EditorNavigationTimeoutException(timeoutMillis: Long) :
    IllegalStateException("Editor navigation did not complete within $timeoutMillis ms")

/** Keep this project's file and diff opens from superseding one another in NavigationService. */
internal class EditorOpenQueue(private val navigationTimeoutMillis: Long = 120_000) {
    private val mutex = Mutex()
    private data class Completed<T>(val value: T)

    // Bound the active navigation, not the time spent waiting for earlier opens.
    // Cancellation unwinds navigation before the next caller acquires the mutex.
    suspend fun <T> open(action: suspend () -> T): T = mutex.withLock {
        // Only our deadline becomes an error. Parent cancellation and nested
        // operation timeouts retain their original cancellation semantics.
        val completed = withTimeoutOrNull(navigationTimeoutMillis) { Completed(action()) }
            ?: throw EditorNavigationTimeoutException(navigationTimeoutMillis)
        completed.value
    }
}
