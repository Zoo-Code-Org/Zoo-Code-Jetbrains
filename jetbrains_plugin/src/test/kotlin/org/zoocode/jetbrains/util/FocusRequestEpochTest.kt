// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the bounded invalidation shared by the webview view show and the
 * terminal show. A newer request must supersede every older queued request,
 * so a delayed activation callback can never override newer preserving or
 * manual intent.
 */
class FocusRequestEpochTest {

    @Test
    fun `queued request stays current until a newer one arrives`() {
        val epoch = FocusRequestEpoch()
        val first = epoch.next()
        assertTrue(epoch.isCurrent(first))
    }

    @Test
    fun `newer request supersedes the queued older request`() {
        val epoch = FocusRequestEpoch()
        val preserving = epoch.next()
        val deliberate = epoch.next()
        assertFalse(epoch.isCurrent(preserving))
        assertTrue(epoch.isCurrent(deliberate))
    }

    @Test
    fun `interleaved requests leave only the newest valid`() {
        val epoch = FocusRequestEpoch()
        val a = epoch.next()
        val b = epoch.next()
        val c = epoch.next()
        assertFalse(epoch.isCurrent(a))
        assertFalse(epoch.isCurrent(b))
        assertTrue(epoch.isCurrent(c))
    }

    @Test
    fun `unchanged epoch stays valid for the activation callback`() {
        val epoch = FocusRequestEpoch()
        val request = epoch.next()
        assertTrue(epoch.isCurrent(request))
        assertTrue(epoch.isCurrent(request))
    }

    @Test
    fun `hide invalidation supersedes every queued show`() {
        val epoch = FocusRequestEpoch()
        val queuedDeliberate = epoch.next()
        val queuedPreserving = epoch.next()

        epoch.invalidate()

        assertFalse(epoch.isCurrent(queuedDeliberate))
        assertFalse(epoch.isCurrent(queuedPreserving))
    }

    @Test
    fun `explicit show after a hide is still current`() {
        val epoch = FocusRequestEpoch()
        epoch.next()
        epoch.invalidate()

        val explicitShow = epoch.next()

        assertTrue(epoch.isCurrent(explicitShow))
    }

    @Test
    fun `invalidation without a request still invalidates older work`() {
        val epoch = FocusRequestEpoch()
        val request = epoch.next()
        assertTrue(epoch.isCurrent(request))

        epoch.invalidate()

        assertFalse(epoch.isCurrent(request))
    }
}
