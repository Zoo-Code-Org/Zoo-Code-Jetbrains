// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JPanel

/**
 * Covers the mount decision and the placeholder bookkeeping of the tool
 * window content. Instance tokens play the WebViewInstance role.
 */
class WebViewMountPlanTest {

    @Test
    fun `first mount replaces the empty panel`() {
        assertEquals(MountAction.REPLACE, planMount(null, "A", incomingAttached = false))
    }

    @Test
    fun `same attached instance keeps the mount and only cleans placeholders`() {
        assertEquals(MountAction.KEEP_MOUNTED, planMount("A", "A", incomingAttached = true))
    }

    @Test
    fun `same instance detached from the panel plans a remount`() {
        assertEquals(MountAction.REMOUNT_SAME, planMount("A", "A", incomingAttached = false))
    }

    @Test
    fun `different instance replaces even when already attached`() {
        assertEquals(MountAction.REPLACE, planMount("A", "B", incomingAttached = true))
    }

    @Test
    fun `placeholder removal detaches only registered panels`() {
        val parent = JPanel()
        val placeholders = PlaceholderPanels()
        val panel = JPanel()
        parent.add(panel)
        placeholders.register(panel)

        assertTrue(placeholders.removeAttached(parent))
        assertEquals(0, parent.componentCount)
        assertFalse(placeholders.removeAttached(parent))
    }

    @Test
    fun `placeholder removal drops panels detached by a full clear`() {
        val parent = JPanel()
        val placeholders = PlaceholderPanels()
        val first = JPanel()
        parent.add(first)
        placeholders.register(first)

        parent.removeAll()

        val second = JPanel()
        parent.add(second)
        placeholders.register(second)

        assertTrue(placeholders.removeAttached(parent))
        assertEquals(0, parent.componentCount)
        assertFalse(placeholders.removeAttached(parent))
        parent.add(first)
        assertFalse(placeholders.removeAttached(parent))
        assertEquals(1, parent.componentCount)
    }

    @Test
    fun `reattached placeholder is still removable through reattachment`() {
        val parent = JPanel()
        val placeholders = PlaceholderPanels()
        val panel = JPanel()
        parent.add(panel)
        placeholders.register(panel)

        parent.removeAll()
        parent.add(panel)

        assertTrue(placeholders.removeAttached(parent))
        assertEquals(0, parent.componentCount)
        assertFalse(placeholders.removeAttached(parent))
    }

    @Test
    fun `unregistered panels are never touched`() {
        val parent = JPanel()
        val placeholders = PlaceholderPanels()
        val stranger = JPanel()
        parent.add(stranger)

        assertFalse(placeholders.removeAttached(parent))
        assertEquals(1, parent.componentCount)
    }
}
