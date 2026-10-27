// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the retirement ownership behind WebViewManager.registerProvider.
 * Instance names play the WebViewInstance role. The call order mirrors
 * production: the UI marks an instance mounted, then each registration tracks
 * the previous latest and sweeps the never-mounted orphans.
 */
class InstanceRetirementTrackerTest {

    @Test
    fun `tracked instance that is mounted is not an orphan`() {
        val tracker = InstanceRetirementTracker<String>()
        tracker.markMounted("A")
        tracker.track("A")
        assertTrue(tracker.orphans().isEmpty())
    }

    @Test
    fun `rapid registrations dispose only the never-mounted orphan`() {
        val tracker = InstanceRetirementTracker<String>()
        tracker.markMounted("A")

        tracker.track("A")
        assertTrue(tracker.orphans().isEmpty())

        tracker.track("B")
        assertEquals(listOf("B"), tracker.orphans())
    }

    @Test
    fun `handoff disposes the previously mounted instance exactly once`() {
        val tracker = InstanceRetirementTracker<String>()
        tracker.markMounted("A")
        tracker.track("A")

        assertTrue(tracker.retireMounted("A"))
        assertFalse(tracker.retireMounted("A"))
        assertTrue(tracker.orphans().isEmpty())
    }

    @Test
    fun `unknown instance handoff owns no disposal`() {
        val tracker = InstanceRetirementTracker<String>()
        assertFalse(tracker.retireMounted("X"))
    }

    @Test
    fun `swept orphan handoff owns no second disposal`() {
        val tracker = InstanceRetirementTracker<String>()
        tracker.markMounted("A")
        tracker.track("A")
        tracker.track("B")
        assertEquals(listOf("B"), tracker.orphans())

        assertFalse(tracker.retireMounted("B"))
    }

    @Test
    fun `unmounted chain disposes every superseded instance in the sweep`() {
        val tracker = InstanceRetirementTracker<String>()
        tracker.track("A")
        assertEquals(listOf("A"), tracker.orphans())
        tracker.track("B")
        assertEquals(listOf("B"), tracker.orphans())
        assertTrue(tracker.orphans().isEmpty())
    }

    @Test
    fun `clearMounted releases the instance to the orphan sweep`() {
        val tracker = InstanceRetirementTracker<String>()
        tracker.markMounted("A")
        tracker.track("A")
        tracker.clearMounted("A")
        assertEquals(listOf("A"), tracker.orphans())
    }

    @Test
    fun `isMounted tracks only the current mount`() {
        val tracker = InstanceRetirementTracker<String>()
        tracker.markMounted("A")
        assertTrue(tracker.isMounted("A"))
        assertFalse(tracker.isMounted("B"))
        tracker.markMounted("B")
        assertFalse(tracker.isMounted("A"))
        assertTrue(tracker.isMounted("B"))
    }

    @Test
    fun `shutdown claims a mounted instance superseded by a never-mounted newer one`() {
        val tracker = InstanceRetirementTracker<String>()
        tracker.markMounted("A")
        tracker.track("A")

        tracker.track("B")

        assertEquals(listOf("A", "B"), tracker.claimAll())
    }

    @Test
    fun `shutdown claims every instance after rapid registrations`() {
        val tracker = InstanceRetirementTracker<String>()
        tracker.markMounted("A")
        tracker.track("A")
        tracker.track("B")
        tracker.track("C")

        assertEquals(listOf("A", "B", "C"), tracker.claimAll())
    }

    @Test
    fun `tracked never mounted instance can be retired exactly once`() {
        val tracker = InstanceRetirementTracker<String>()
        tracker.track("A")
        assertTrue(tracker.retireMounted("A"))
        assertFalse(tracker.retireMounted("A"))
        assertTrue(tracker.claimAll().isEmpty())
    }

    @Test
    fun `repeated shutdown claims own nothing the second time`() {
        val tracker = InstanceRetirementTracker<String>()
        tracker.markMounted("A")
        tracker.track("A")
        tracker.track("B")

        val first = tracker.claimAll()
        val second = tracker.claimAll()

        assertEquals(listOf("A", "B"), first)
        assertTrue(second.isEmpty())
    }

    @Test
    fun `claimed shutdown leaves no handoff or orphan work behind`() {
        val tracker = InstanceRetirementTracker<String>()
        tracker.markMounted("A")
        tracker.track("A")
        tracker.track("B")

        tracker.claimAll()

        assertTrue(tracker.orphans().isEmpty())
        assertFalse(tracker.retireMounted("A"))
        assertFalse(tracker.retireMounted("B"))
        assertFalse(tracker.isMounted("A"))
    }

    @Test
    fun `clearMounted ignores a wrong instance and keeps the mount protected`() {
        val tracker = InstanceRetirementTracker<String>()
        tracker.markMounted("A")
        tracker.track("A")

        tracker.clearMounted("B")

        assertTrue(tracker.isMounted("A"))
        assertTrue(tracker.orphans().isEmpty())
    }

    @Test
    fun `full swap retires the old instance once after the new mount`() {
        val tracker = InstanceRetirementTracker<String>()
        tracker.markMounted("A")
        tracker.track("A")

        tracker.markMounted("B")

        assertTrue(tracker.isMounted("B"))
        assertFalse(tracker.isMounted("A"))
        assertTrue(tracker.retireMounted("A"))
        assertFalse(tracker.retireMounted("A"))
        assertTrue(tracker.orphans().isEmpty())
    }
    @Test fun `mounted instance not yet tracked can be retired exactly once`() {
        val tracker = InstanceRetirementTracker<String>()
        tracker.markMounted("A")
        assertTrue(tracker.retireMounted("A"))
        assertFalse(tracker.retireMounted("A"))
        assertFalse(tracker.isMounted("A"))
        assertTrue(tracker.claimAll().isEmpty())
    }
}
