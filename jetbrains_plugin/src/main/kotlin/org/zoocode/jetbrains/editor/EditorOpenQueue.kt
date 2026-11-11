package org.zoocode.jetbrains.editor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keep this project's file and diff opens from superseding one another in NavigationService. */
internal class EditorOpenQueue {
    private val mutex = Mutex()

    suspend fun <T> open(action: suspend () -> T): T = mutex.withLock { action() }
}
