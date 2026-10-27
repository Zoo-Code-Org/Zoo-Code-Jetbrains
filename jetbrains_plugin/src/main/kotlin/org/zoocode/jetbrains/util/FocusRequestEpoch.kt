// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.util

import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded invalidation for queued focus requests. Each request takes an epoch
 * number; a newer request or an invalidation supersedes every older queued
 * request, so delayed activation can never override newer preserving, hiding,
 * or manual intent.
 */
class FocusRequestEpoch {
    private val counter = AtomicInteger(0)

    fun next(): Int = counter.incrementAndGet()

    /**
     * Supersedes every queued request without creating a new one. Hide and
     * disposal paths call this so a queued show can no longer resurrect the
     * target it was queued for.
     */
    fun invalidate() {
        counter.incrementAndGet()
    }

    fun isCurrent(epoch: Int): Boolean = counter.get() == epoch
}
