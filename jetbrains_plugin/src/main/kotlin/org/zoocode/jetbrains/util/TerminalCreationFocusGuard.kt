// SPDX-FileCopyrightText: 2025 Weibo, Inc.
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.AWTEvent
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * One restoration after the platform's focus-requesting terminal insertion.
 * Created and completed on the EDT around one synchronous initialization.
 * Input observation spans initialization; the 1500 ms callback budget starts
 * after creation. Initialization failures close the guard at the call site.
 */
class TerminalCreationFocusGuard internal constructor(
    private val chat: Component?,
    private val environment: TerminalCreationFocusEnvironment
) : AutoCloseable {
    constructor(chat: Component?) : this(chat, SwingTerminalCreationFocusEnvironment)

    private val original = environment.focusOwner()
    private val ownedChat = contains(chat, original)
    private var userIntervened = false
    private var closed = false
    private var finished = false
    private var expiry: AutoCloseable? = null
    private val inputObservation: AutoCloseable? = if (ownedChat) {
        environment.observeInput(AWTEventListener { event ->
            if ((event is MouseEvent && event.id == MouseEvent.MOUSE_PRESSED ||
                    event is KeyEvent && event.id == KeyEvent.KEY_PRESSED) &&
                (!contains(chat, event.source as? Component) ||
                    event is KeyEvent && event.keyCode == KeyEvent.VK_TAB)) {
                userIntervened = true
            }
        })
    } else null

    fun finish(project: Project, terminal: Component?) {
        if (finished || closed) return
        finished = true
        if (!ownedChat || project.isDisposed) {
            close()
            return
        }
        expiry = environment.expire { close() }
        environment.afterCreation(project) {
            val restore = terminalCreationFocusRestore(
                ownedChat, contains(terminal, environment.focusOwner()), userIntervened,
                !project.isDisposed && original?.let(environment::isShowing) == true, closed
            )
            close()
            if (restore) original?.let(environment::requestFocus)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            expiry?.close()
        } finally {
            inputObservation?.close()
        }
    }

    private fun contains(root: Component?, owner: Component?): Boolean =
        root != null && owner != null && (root === owner || SwingUtilities.isDescendingFrom(owner, root))
}

/** Platform boundary: tests exercise the real guard lifecycle with controlled queued events. */
internal interface TerminalCreationFocusEnvironment {
    fun focusOwner(): Component?
    fun observeInput(listener: AWTEventListener): AutoCloseable
    fun expire(action: () -> Unit): AutoCloseable
    fun afterCreation(project: Project, action: () -> Unit)
    fun isShowing(component: Component): Boolean
    fun requestFocus(component: Component)
}

private object SwingTerminalCreationFocusEnvironment : TerminalCreationFocusEnvironment {
    override fun focusOwner(): Component? = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    override fun observeInput(listener: AWTEventListener): AutoCloseable {
        val toolkit = Toolkit.getDefaultToolkit()
        toolkit.addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.KEY_EVENT_MASK)
        return AutoCloseable { toolkit.removeAWTEventListener(listener) }
    }
    override fun expire(action: () -> Unit): AutoCloseable {
        val timer = Timer(1500) { action() }.apply { isRepeats = false }
        timer.start()
        return AutoCloseable { timer.stop() }
    }
    override fun afterCreation(project: Project, action: () -> Unit) {
        // Inline focus settling runs before newTab's queued activation.
        ToolWindowManager.getInstance(project).invokeLater {
            if (project.isDisposed) action()
            else IdeFocusManager.getInstance(project).doWhenFocusSettlesDown { action() }
        }
    }
    override fun isShowing(component: Component): Boolean = component.isShowing
    override fun requestFocus(component: Component) { component.requestFocusInWindow() }
}

/** Only the observed creation theft qualifies; unrelated focus and expired work do not. */
fun terminalCreationFocusRestore(
    chatOwnedFocus: Boolean,
    createdTerminalOwnsFocus: Boolean,
    userIntervened: Boolean,
    chatStillAvailable: Boolean,
    expired: Boolean
): Boolean = chatOwnedFocus && createdTerminalOwnsFocus && !userIntervened && chatStillAvailable && !expired
