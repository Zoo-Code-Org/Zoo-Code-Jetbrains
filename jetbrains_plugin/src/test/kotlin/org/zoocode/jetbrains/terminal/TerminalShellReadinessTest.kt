package org.zoocode.jetbrains.terminal

import org.junit.Assert.assertEquals
import org.junit.Test
import org.zoocode.jetbrains.ipc.proxy.IRPCProtocol
import java.lang.reflect.Proxy

class TerminalShellReadinessTest {
    private fun unusedProtocol(): IRPCProtocol = Proxy.newProxyInstance(
        IRPCProtocol::class.java.classLoader,
        arrayOf(IRPCProtocol::class.java)
    ) { _, _, _ -> error("Readiness must not invoke RPC") } as IRPCProtocol

    @Test
    fun `commands wait for a complete prompt and readiness is announced once`() {
        val events = mutableListOf<String>()
        val integration = TerminalShellIntegration("test", 1, unusedProtocol()) { events.add("ready") }
        integration.setupShellIntegration()
        try {
            integration.whenReady { events.add("first") }
            integration.whenReady { events.add("second") }
            integration.appendRawOutput("\u001b]633;B")
            assertEquals(emptyList<String>(), events)
            integration.appendRawOutput("\u0007")
            assertEquals(listOf("ready", "first", "second"), events)
            integration.appendRawOutput("\u001b]633;B\u0007")
            integration.whenReady { events.add("third") }
            assertEquals(listOf("ready", "first", "second", "third"), events)
        } finally {
            integration.dispose()
        }
    }

    @Test
    fun `disposal discards queued commands`() {
        val events = mutableListOf<String>()
        val integration = TerminalShellIntegration("test", 1, unusedProtocol()) { events.add("ready") }
        integration.setupShellIntegration()
        integration.whenReady { events.add("queued") }
        integration.dispose()
        integration.whenReady { events.add("late") }
        integration.appendRawOutput("\u001b]633;B\u0007")
        assertEquals(emptyList<String>(), events)
    }
}
