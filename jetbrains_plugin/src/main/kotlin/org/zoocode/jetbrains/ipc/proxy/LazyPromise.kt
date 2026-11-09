// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.ipc.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Lazy Promise implementation
 * Corresponds to LazyPromise in VSCode
 */
open class LazyPromise {
    // Delegating the entire CompletableDeferred interface generates overrides
    // for obsolete Job APIs (plus, ChildJob and ChildHandle) in our bytecode.
    // Keep the coroutine implementation private and expose only RPC operations.
    private val deferred = CompletableDeferred<Any?>()

    val isCompleted: Boolean get() = deferred.isCompleted

    suspend fun await(): Any? = deferred.await()

    fun cancel() = deferred.cancel()

    fun invokeOnCompletion(handler: (Throwable?) -> Unit): DisposableHandle =
        deferred.invokeOnCompletion(handler)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getCompleted(): Any? = deferred.getCompleted()

    /**
     * Resolve Promise successfully
     * @param value Result value
     */
    fun resolveOk(value: Any?) {
        deferred.complete(value)
    }

    /**
     * Reject Promise
     * @param err Error object
     */
    fun resolveErr(err: Throwable) {
        deferred.completeExceptionally(err)
    }
}

/**
 * Canceled Lazy Promise implementation
 * Corresponds to CanceledLazyPromise in VSCode
 */
class CanceledLazyPromise : LazyPromise() {
    init {
        // Immediately complete with cancellation exception
        resolveErr(CanceledException())
    }
}

/**
 * Cancellation exception
 */
class CanceledException : Exception("Operation cancelled")
