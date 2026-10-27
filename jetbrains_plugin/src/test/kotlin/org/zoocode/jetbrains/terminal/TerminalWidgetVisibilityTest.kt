// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JPanel

/**
 * Covers the visibility and argument halves of the terminal show path.
 * hide() sets the widget invisible; show() must restore that visibility
 * independently from the focus intent, so a hide-show cycle cannot leave an
 * invisible terminal inside a visible tool window. The bridge show default
 * must request focus when the argument is missing, like upstream
 * Terminal.show.
 */
class TerminalWidgetVisibilityTest {

    @Test
    fun `hidden widget becomes visible again`() {
        val widget = JPanel()
        widget.isVisible = false

        restoreWidgetVisibility(widget)

        assertTrue(widget.isVisible)
    }

    @Test
    fun `visible widget stays visible`() {
        val widget = JPanel()

        restoreWidgetVisibility(widget)

        assertTrue(widget.isVisible)
    }

    @Test
    fun `restore tolerates the uninitialized widget`() {
        restoreWidgetVisibility(null)
    }

    @Test
    fun `restored child is visible again inside its tool window parent`() {
        val parent = JPanel()
        val child = JPanel()
        parent.add(child)
        child.isVisible = false

        restoreWidgetVisibility(child)

        assertTrue(child.isVisible)
        assertEquals(1, parent.componentCount)
        assertSame(child, parent.getComponent(0))
    }

    @Test
    fun `restore does not reattach a widget removed from the hierarchy`() {
        val parent = JPanel()
        val child = JPanel()
        parent.add(child)
        parent.removeAll()
        child.isVisible = false

        restoreWidgetVisibility(child)

        assertTrue(child.isVisible)
        assertEquals(0, parent.componentCount)
    }

    @Test
    fun `missing show argument uses the upstream focus default`() {
        assertFalse(decodeTerminalPreserveFocus(null))
    }

    @Test
    fun `explicit show arguments pass through unchanged`() {
        assertTrue(decodeTerminalPreserveFocus(true))
        assertFalse(decodeTerminalPreserveFocus(false))
    }
}
