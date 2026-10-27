// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.util

import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the bounded guard behind the deliberate terminal and webview show.
 * The delayed activation aborts when the guard saw a focus-owner move while
 * the request waited, and proceeds when the user has not moved focus, so an
 * explicit show still works. A real focus transition needs a display; the
 * move is recorded through the same entry the production listener calls.
 */
class ManualFocusGuardTest {

    @Test
    fun `quiet guard lets the delayed activation proceed`() {
        val guard = ManualFocusGuard()
        guard.start()
        guard.close()

        assertFalse(guard.focusMoved())
    }

    @Test
    fun `manual focus move during the queue window aborts the activation`() {
        val guard = ManualFocusGuard()
        guard.start()

        guard.noteFocusMove()

        assertTrue(guard.focusMoved())
        guard.close()
    }

    @Test
    fun `move observed after close does not mark the guard`() {
        val guard = ManualFocusGuard()
        guard.start()
        guard.close()

        guard.noteFocusMove()

        assertFalse(guard.focusMoved())
    }

    @Test
    fun `close is idempotent and can run in a finally block`() {
        val guard = ManualFocusGuard()
        guard.start()
        guard.close()
        guard.close()

        assertFalse(guard.focusMoved())
    }

    @Test
    fun `second start does not create a second observation window`() {
        val guard = ManualFocusGuard()
        guard.start()
        guard.start()
        guard.close()

        assertFalse(guard.focusMoved())
    }
    @Test
    fun `registered focus listener records actual property delivery and is removed`() {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val before = manager.getPropertyChangeListeners("focusOwner").toSet()
        val guard = ManualFocusGuard()
        try {
            guard.start()
            guard.start()
            val added = manager.getPropertyChangeListeners("focusOwner").toSet() - before
            assertEquals(1, added.size)
            added.single().propertyChange(PropertyChangeEvent(manager, "focusOwner", null, Any()))
            assertTrue(guard.focusMoved())
        } finally {
            guard.close()
        }
        assertEquals(before, manager.getPropertyChangeListeners("focusOwner").toSet())
    }

    @Test
    fun `start after close does not leak a listener`() {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val before = manager.getPropertyChangeListeners("focusOwner").toList()
        val guard = ManualFocusGuard()
        guard.close()
        guard.start()
        guard.close()
        assertEquals(before, manager.getPropertyChangeListeners("focusOwner").toList())
    }
}
