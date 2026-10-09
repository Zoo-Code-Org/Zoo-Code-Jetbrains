// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.terminal

import com.pty4j.PtyProcessBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalPtyBuilderTest {

    @Test
    fun builderRequestsWinConPtyExplicitly() {
        val builder = TerminalPtyBuilder.configure(
            command = arrayOf("/bin/sh"),
            environment = emptyMap(),
            workingDirectory = "/tmp",
            initialColumns = 80,
            initialRows = 24
        )

        // PtyProcessBuilder has no getter for the flag, so the test reads the
        // private field of the public pty4j class. This is test-only state
        // inspection; production code never uses reflection.
        val field = PtyProcessBuilder::class.java.getDeclaredField("myUseWinConPty")
        field.isAccessible = true
        assertEquals(
            "TerminalPtyBuilder must keep the explicit ConPTY selection",
            true,
            field.getBoolean(builder)
        )
    }

    @Test
    fun builderKeepsPlainConfigurationValues() {
        val builder = TerminalPtyBuilder.configure(
            command = arrayOf("/bin/sh", "-i"),
            environment = mapOf("PATH" to "/usr/bin"),
            workingDirectory = "/tmp",
            initialColumns = 120,
            initialRows = 40
        )

        val commandField = PtyProcessBuilder::class.java.getDeclaredField("myCommand")
        commandField.isAccessible = true
        assertArrayEquals(arrayOf("/bin/sh", "-i"), commandField.get(builder) as Array<*>)

        val environmentField = PtyProcessBuilder::class.java.getDeclaredField("myEnvironment")
        environmentField.isAccessible = true
        assertEquals(mapOf("PATH" to "/usr/bin"), environmentField.get(builder))

        val directoryField = PtyProcessBuilder::class.java.getDeclaredField("myDirectory")
        directoryField.isAccessible = true
        assertEquals("/tmp", directoryField.get(builder))

        val columnsField = PtyProcessBuilder::class.java.getDeclaredField("myInitialColumns")
        columnsField.isAccessible = true
        assertEquals(120, columnsField.get(builder))

        val rowsField = PtyProcessBuilder::class.java.getDeclaredField("myInitialRows")
        rowsField.isAccessible = true
        assertEquals(40, rowsField.get(builder))
    }
}
