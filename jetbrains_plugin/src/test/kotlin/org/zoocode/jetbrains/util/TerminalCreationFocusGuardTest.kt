// SPDX-FileCopyrightText: 2025 Weibo, Inc.
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.util

import com.intellij.openapi.project.Project
import java.awt.Component
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.awt.event.KeyEvent
import java.lang.reflect.Proxy
import javax.swing.JPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCreationFocusGuardTest {
    @Test fun `creation theft restores chat once`() {
        assertTrue(terminalCreationFocusRestore(true, true, false, true, false))
    }
    @Test fun `creation from editor does not focus chat`() {
        assertFalse(terminalCreationFocusRestore(false, true, false, true, false))
    }
    @Test fun `click on new terminal vetoes restoration`() {
        assertFalse(terminalCreationFocusRestore(true, true, true, true, false))
    }
    @Test fun `focus in editor or balloon is left alone`() {
        assertFalse(terminalCreationFocusRestore(true, false, false, true, false))
    }
    @Test fun `hidden or disposed chat is left alone`() {
        assertFalse(terminalCreationFocusRestore(true, true, false, false, false))
    }
    @Test fun `expired or completed restoration cannot run again`() {
        assertFalse(terminalCreationFocusRestore(true, true, false, true, true))
    }

    private var disposed = false
    private val project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) {
        _, method, _ -> if (method.name == "isDisposed") disposed else null
    } as Project

    private class Environment(var owner: Component?) : TerminalCreationFocusEnvironment {
        var listener: AWTEventListener? = null
        var timeout: (() -> Unit)? = null
        var queued: (() -> Unit)? = null
        val requests = mutableListOf<Component>()
        var showing = true
        override fun focusOwner() = owner
        override fun observeInput(listener: AWTEventListener): AutoCloseable {
            this.listener = listener
            return AutoCloseable { this.listener = null }
        }
        override fun expire(action: () -> Unit): AutoCloseable {
            timeout = action
            return AutoCloseable { timeout = null }
        }
        override fun afterCreation(project: Project, action: () -> Unit) { queued = action }
        override fun isShowing(component: Component) = showing
        override fun requestFocus(component: Component) { requests.add(component) }
    }

    @Test fun `initialization observes choices without consuming the after creation timeout`() {
        val chat = JPanel()
        val env = Environment(chat)
        val guard = TerminalCreationFocusGuard(chat, env)
        assertNotNull(env.listener)
        assertNull(env.timeout)
        val terminal = JPanel()
        env.owner = terminal
        guard.finish(project, terminal)
        assertNotNull(env.timeout)
        env.queued!!()
        assertEquals(listOf(chat), env.requests)
        assertNull(env.listener)
        assertNull(env.timeout)
        env.queued!!()
        guard.finish(project, terminal)
        assertEquals(1, env.requests.size)
    }

    @Test fun `click on the new terminal before the callback relinquishes restoration`() {
        val chat = JPanel()
        val terminal = JPanel()
        val env = Environment(chat)
        val guard = TerminalCreationFocusGuard(chat, env)
        guard.finish(project, terminal)
        env.owner = terminal
        env.listener!!.eventDispatched(MouseEvent(terminal, MouseEvent.MOUSE_PRESSED, 0, 0, 1, 1, 1, false))
        env.queued!!()
        assertTrue(env.requests.isEmpty())
        assertNull(env.listener)
    }

    @Test fun `Tab navigation in chat vetoes restoration while ordinary typing does not`() {
        for (key in listOf(KeyEvent.VK_TAB, KeyEvent.VK_A)) {
            val chat = JPanel()
            val terminal = JPanel()
            val env = Environment(chat)
            val guard = TerminalCreationFocusGuard(chat, env)
            env.listener!!.eventDispatched(KeyEvent(chat, KeyEvent.KEY_PRESSED, 0, 0, key, 'a'))
            env.owner = terminal
            guard.finish(project, terminal)
            env.queued!!()
            assertEquals(if (key == KeyEvent.VK_TAB) 0 else 1, env.requests.size)
        }
    }

    @Test fun `expiry unregisters observation and prevents a late focus callback`() {
        val chat = JPanel()
        val terminal = JPanel()
        val env = Environment(chat)
        val guard = TerminalCreationFocusGuard(chat, env)
        guard.finish(project, terminal)
        env.owner = terminal
        env.timeout!!()
        assertNull(env.listener)
        env.queued!!()
        assertTrue(env.requests.isEmpty())
    }

    @Test fun `failed initialization closes observation before finish`() {
        val chat = JPanel()
        val env = Environment(chat)
        val guard = TerminalCreationFocusGuard(chat, env)
        guard.close()
        guard.finish(project, JPanel())
        assertNull(env.listener)
        assertNull(env.timeout)
        assertNull(env.queued)
    }

    @Test fun `starting outside chat installs neither listener nor timeout`() {
        val env = Environment(JPanel())
        val guard = TerminalCreationFocusGuard(JPanel(), env)
        guard.finish(project, JPanel())
        assertNull(env.listener)
        assertNull(env.timeout)
        assertNull(env.queued)
    }

    @Test fun `restoration captures the actual chat child and recognizes a terminal child`() {
        val chat = JPanel()
        val chatOwner = JPanel()
        chat.add(chatOwner)
        val terminal = JPanel()
        val terminalOwner = JPanel()
        terminal.add(terminalOwner)
        val env = Environment(chatOwner)
        val guard = TerminalCreationFocusGuard(chat, env)
        env.owner = terminalOwner
        guard.finish(project, terminal)
        env.queued!!()
        assertEquals(listOf(chatOwner), env.requests)
    }
    @Test fun `hidden captured chat owner cannot be restored`() {
        val chat = JPanel()
        val terminal = JPanel()
        val env = Environment(chat)
        val guard = TerminalCreationFocusGuard(chat, env)
        guard.finish(project, terminal)
        env.owner = terminal
        env.showing = false
        env.queued!!()
        assertTrue(env.requests.isEmpty())
        assertNull(env.listener)
        assertNull(env.timeout)
    }

    @Test fun `disposed project before finish closes without scheduling restoration`() {
        val env = Environment(JPanel())
        val guard = TerminalCreationFocusGuard(env.owner, env)
        disposed = true
        guard.finish(project, JPanel())
        assertNull(env.listener)
        assertNull(env.timeout)
        assertNull(env.queued)
        assertTrue(env.requests.isEmpty())
    }

    @Test fun `project disposal while queued prevents restoration and closes observation`() {
        val chat = JPanel()
        val terminal = JPanel()
        val env = Environment(chat)
        val guard = TerminalCreationFocusGuard(chat, env)
        guard.finish(project, terminal)
        env.owner = terminal
        disposed = true
        env.queued!!()
        assertTrue(env.requests.isEmpty())
        assertNull(env.listener)
        assertNull(env.timeout)
    }
}
