package org.zoocode.jetbrains.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellIntegrationOutputStateTest {
    @Test
    fun `escaped commands and cwd survive every chunk boundary`() {
        val command = "& Write-Output \"a ; b 日本語 ∑≈√ 🚀\" | Out-String C:\\Users\\test"
        val escaped = command.toByteArray(Charsets.UTF_8).joinToString("") {
            val byte = it.toInt() and 0xff
            if (byte >= 127 || byte == 59 || byte == 92) "\\x%02x".format(byte)
            else byte.toChar().toString()
        }
        val raw = "\u001b]633;P;Cwd=C:\\x5cUsers\\x5ctest\u0007" +
            "\u001b]633;E;$escaped;nonce\u0007\u001b]633;C\u0007" +
            "hello\r\n\u001b]633;D;7\u0007"
        for (boundary in 0..raw.length) {
            val state = ShellIntegrationOutputState()
            val events = mutableListOf<String>()
            state.addListener(object : ShellEventListener {
                override fun onShellExecutionStart(commandLine: String, cwd: String) {
                    events.add("start:$commandLine:$cwd")
                }
                override fun onShellExecutionEnd(commandLine: String, exitCode: Int?) {
                    events.add("end:$commandLine:$exitCode")
                }
                override fun onShellExecutionData(data: String) = Unit
                override fun onCwdChange(cwd: String) = Unit
            })
            try {
                state.appendRawOutput(raw.take(boundary))
                state.appendRawOutput(raw.drop(boundary))
                assertEquals("boundary=$boundary", listOf(
                    "start:$command:C:\\Users\\test", "end:$command:7"
                ), events)
            } finally {
                state.dispose()
            }
        }
    }

    @Test
    fun `decoding is single pass and preserves literal escape text`() {
        val state = ShellIntegrationOutputState()
        try {
            assertEquals("\\x3b", state.decodeMarkerValue("\\x5cx3b"))
            assertEquals("a;b\\c", state.decodeMarkerValue("a\\x3bb\\\\c"))
        } finally {
            state.dispose()
        }
    }
}
