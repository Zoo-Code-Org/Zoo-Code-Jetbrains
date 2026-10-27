// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.util

import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded manual-focus guard for one queued deliberate focus request.
 *
 * The guard starts when the request is created and any Swing focus-owner move
 * while the request waits in the EDT queue cancels the queued request. The
 * delayed activation checks [focusMoved] before starting activation and aborts
 * to leave the current owner in place. This never requests, restores, or repairs
 * focus. Terminal creation separately uses [TerminalCreationFocusGuard] for one
 * evidence-backed restoration; this deliberate-show guard remains cancellation only.
 * Its listener lives only until the queued request executes or is
 * dropped: the caller disposes it in the EDT task, so the observation window is
 * one queued request and never a permanent listener.
 */
class ManualFocusGuard : AutoCloseable {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val moved = AtomicBoolean(false)

    @Volatile
    private var listener: PropertyChangeListener? = null

    /**
     * Starts observing focus-owner moves. Idempotent; safe from any thread.
     */
    @Synchronized
    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) {
            return
        }
        val changeListener = PropertyChangeListener { noteFocusMove() }
        listener = changeListener
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addPropertyChangeListener(FOCUS_OWNER_PROPERTY, changeListener)
    }

    /**
     * True when the focus owner changed while this guard was active, so a
     * delayed activation must abort and leave the current owner in place.
     */
    fun focusMoved(): Boolean = moved.get()

    internal fun noteFocusMove() {
        if (!closed.get()) {
            moved.set(true)
        }
    }

    /**
     * Stops observing. Idempotent; the queued task calls this when it executes
     * so the observation window stays finite.
     */
    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        listener?.let {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removePropertyChangeListener(FOCUS_OWNER_PROPERTY, it)
        }
        listener = null
    }

    private companion object {
        const val FOCUS_OWNER_PROPERTY = "focusOwner"
    }
}
