// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.terminal

import com.jediterm.core.util.TermSize
import com.pty4j.PtyProcess
import com.pty4j.windows.conpty.WinConPtyProcess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val CLOSE_LIMIT_MS = 10_000L
private const val READER_LIMIT_MS = 5_000L
private const val DESTROY_LIMIT_MS = 10_000L
private const val TEARDOWN_BUDGET_MS = CLOSE_LIMIT_MS + READER_LIMIT_MS + DESTROY_LIMIT_MS + 5_000L

/**
 * Real ConPTY process tests for the terminal stack on native Windows. Each test
 * starts an owned PowerShell through the production builder [TerminalPtyBuilder]
 * and asserts the live process is the ConPTY backend [WinConPtyProcess], so a
 * silent winpty fallback can never pass these tests.
 *
 * ConPTY echoes typed input, so every awaited marker is assembled from string
 * parts: the echoed command line contains the parts separated by quotes and
 * plus signs, and only executed output contains the contiguous marker.
 *
 * Every test tears down through one bounded cleanup: the connector closes on a
 * daemon worker, the reader must terminate, and a watchdog destroys the
 * process if teardown hangs. Cleanup problems attach as suppressed failures so
 * they never mask the original assertion.
 *
 * The tests cover the process layer only: several commands inside one live
 * session, repeated fresh sessions, resize, pipeline interruption, and close.
 * They do not cover real IDE session reuse or IDE workspace-close behavior.
 */
class WindowsConPtyProcessTest {

    private fun requireWindows() {
        assumeTrue(
            "real ConPTY tests run on Windows only",
            System.getProperty("os.name").lowercase().contains("windows"),
        )
    }

    private class OutputCollector(connector: RawOutputTtyConnector) {
        private val text = StringBuilder()
        private val readerDone = CountDownLatch(1)

        init {
            thread(start = true, isDaemon = true) {
                try {
                    val buf = CharArray(512)
                    while (true) {
                        val count = connector.read(buf, 0, buf.size)
                        if (count < 0) break
                        synchronized(text) { text.append(buf, 0, count) }
                    }
                } catch (ignored: Exception) {
                    // The connector close interrupts this read; the latch
                    // records termination either way.
                } finally {
                    readerDone.countDown()
                }
            }
        }

        fun snapshot(): String = synchronized(text) { text.toString() }

        fun await(marker: String, timeoutSeconds: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (System.nanoTime() < deadline) {
                if (marker in snapshot()) return true
                Thread.sleep(100)
            }
            return false
        }

        fun awaitReaderTermination(timeoutMs: Long): Boolean =
            readerDone.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private class ConPtySession {
        val process: PtyProcess = TerminalPtyBuilder.configure(
            command = arrayOf("powershell.exe", "-NoLogo", "-NoProfile"),
            environment = HashMap(System.getenv()),
            workingDirectory = System.getProperty("java.io.tmpdir"),
            initialColumns = 120,
            initialRows = 30,
        ).setRedirectErrorStream(true).start()

        val connector = RawOutputTtyConnector(process, Charsets.UTF_8, null)
        val output = OutputCollector(connector)

        @Volatile
        private var closeError: Throwable? = null

        private val closeDone = CountDownLatch(1)
        private var closeStarted = false

        /**
         * Closes the connector on a daemon worker so a hung close cannot hang
         * the test. Returns true only when the close attempt returned in time.
         * Repeat calls wait on the same attempt instead of closing twice.
         */
        fun closeConnectorBounded(timeoutMs: Long): Boolean {
            if (!closeStarted) {
                closeStarted = true
                thread(start = true, isDaemon = true) {
                    try {
                        connector.close()
                    } catch (t: Throwable) {
                        closeError = t
                    } finally {
                        closeDone.countDown()
                    }
                }
            }
            return closeDone.await(timeoutMs, TimeUnit.MILLISECONDS)
        }

        fun teardownProblems(): List<String> {
            val problems = mutableListOf<String>()
            if (!closeConnectorBounded(CLOSE_LIMIT_MS)) {
                problems += "connector.close() did not return within $CLOSE_LIMIT_MS ms"
            } else {
                closeError?.let { problems += "connector.close() failed: $it" }
            }
            if (!output.awaitReaderTermination(READER_LIMIT_MS)) {
                problems += "the output reader did not terminate within $READER_LIMIT_MS ms"
            }
            if (process.isAlive()) {
                process.destroyForcibly()
                if (!process.waitFor(DESTROY_LIMIT_MS, TimeUnit.MILLISECONDS)) {
                    problems += "the process survived destroyForcibly()"
                }
            }
            return problems
        }
    }

    /**
     * Bounded teardown for one session. A watchdog force-destroys the process
     * when the graceful steps hang past the budget, so cleanup can never run
     * unbounded. Returns the problem descriptions; an empty list means the
     * teardown completed cleanly.
     */
    private fun closeSessionBounded(session: ConPtySession): List<String> {
        val watchdog = thread(start = true, isDaemon = true) {
            try {
                Thread.sleep(TEARDOWN_BUDGET_MS)
                if (session.process.isAlive()) session.process.destroyForcibly()
            } catch (ignored: InterruptedException) {
            }
        }
        try {
            return session.teardownProblems()
        } finally {
            watchdog.interrupt()
        }
    }

    private fun withConPtySession(name: String, block: (ConPtySession) -> Unit) {
        requireWindows()
        val session = ConPtySession()
        var bodyError: Throwable? = null
        try {
            assertTrue(
                "TerminalPtyBuilder requested ConPTY but the live process is " +
                    "${session.process.javaClass.name}; a winpty fallback must fail these tests",
                session.process is WinConPtyProcess,
            )
            block(session)
        } catch (t: Throwable) {
            bodyError = t
            throw t
        } finally {
            val problems = closeSessionBounded(session)
            if (problems.isNotEmpty()) {
                problems.forEach { println("$name cleanup: $it") }
                val cleanupError = IllegalStateException("cleanup failed: ${problems.joinToString("; ")}")
                val original = bodyError
                if (original == null) throw cleanupError else original.addSuppressed(cleanupError)
            }
        }
    }

    @Test
    fun `conPty executes input and preserves Unicode end to end`() {
        withConPtySession("unicode") { session ->
            val executedMarker = "uni:" + "café 日本語 🚀"
            session.connector.write(
                "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; " +
                    "Write-Output ('uni:' + 'café 日本語 🚀'); exit 0\r",
            )

            assertTrue(
                "the executed Unicode marker did not appear within 30s; the typed echo holds only " +
                    "the separated parts and can never satisfy this wait: ${session.output.snapshot()}",
                session.output.await(executedMarker, 30),
            )
            assertFalse(
                "the decoded output must not contain replacement characters",
                "\uFFFD" in session.output.snapshot(),
            )
            assertTrue("the shell did not exit within 15s", session.process.waitFor(15, TimeUnit.SECONDS))
            assertEquals("expected the exact exit code 0", 0, session.process.waitFor())
        }
    }

    @Test
    fun `conPty preserves the exact exit code`() {
        withConPtySession("exit-code") { session ->
            session.connector.write("exit 7\r")

            assertTrue("the shell did not exit within 15s", session.process.waitFor(15, TimeUnit.SECONDS))
            assertEquals(7, session.process.waitFor())
        }
    }

    @Test
    fun `conPty applies resize to the real console`() {
        withConPtySession("resize") { session ->
            session.connector.write(
                "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; " +
                    "Write-Output ('resize-' + 'ready')\r",
            )
            assertTrue("the shell was not ready within 30s", session.output.await("resize-ready", 30))

            session.connector.resize(TermSize(101, 31))

            session.connector.write(
                "Write-Output ('resize-w:' + \$Host.UI.RawUI.WindowSize.Width); " +
                    "Write-Output ('resize-h:' + \$Host.UI.RawUI.WindowSize.Height)\r",
            )
            assertTrue(
                "the client console did not observe the resized width within 20s: ${session.output.snapshot()}",
                session.output.await("resize-w:101", 20),
            )
            assertTrue(
                "the client console did not observe the resized height within 20s: ${session.output.snapshot()}",
                session.output.await("resize-h:31", 20),
            )
            assertTrue("the shell must stay alive across a resize", session.process.isAlive())
        }
    }

    /**
     * Proves the ETX byte cancels the running pipeline: the 90s statement
     * never completes and the shell accepts a new command while staying alive.
     * The test does not distinguish an interrupt that hits an already-running
     * Start-Sleep from one that discards the pipeline before the sleep starts;
     * a console test cannot observe that difference. The elapsed bound is the
     * proof that the 90s statement never ran to completion.
     */
    @Test
    fun `conPty interrupt byte stops the running pipeline and the shell survives`() {
        withConPtySession("interrupt") { session ->
            val armedAtNs = System.nanoTime()
            session.connector.write(
                "Write-Output ('interrupt-' + 'armed'); Start-Sleep -Seconds 90; " +
                    "Write-Output ('sleep-' + 'finished')\r",
            )
            assertTrue("the long pipeline was not started within 30s", session.output.await("interrupt-armed", 30))

            session.connector.write("\u0003")
            session.connector.write("Write-Output ('after-' + 'interrupt')\r")

            assertTrue(
                "the shell did not accept input after the interrupt within 20s: ${session.output.snapshot()}",
                session.output.await("after-interrupt", 20),
            )
            val elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - armedAtNs)
            assertTrue(
                "the 90s pipeline was not interrupted; the next command ran after $elapsedSeconds s",
                elapsedSeconds < 60,
            )

            session.connector.write("exit 0\r")
            assertTrue(
                "the shell did not exit after the interrupt within 15s",
                session.process.waitFor(15, TimeUnit.SECONDS),
            )
            assertEquals(
                "the shell must survive the interrupt and exit cleanly",
                0,
                session.process.waitFor(),
            )
            assertFalse(
                "the interrupted pipeline must never print its completion marker",
                "sleep-finished" in session.output.snapshot(),
            )
        }
    }

    @Test
    fun `conPty survives five repeated fresh startups`() {
        repeat(5) { startup ->
            withConPtySession("startup-$startup") { session ->
                session.connector.write("Write-Output ('startup-' + '$startup-done')\r")
                assertTrue(
                    "startup $startup produced no executed output within 20s",
                    session.output.await("startup-$startup-done", 20),
                )
            }
        }
    }

    @Test
    fun `connector close terminates the ConPTY process within timeout`() {
        withConPtySession("close") { session ->
            session.connector.write("Write-Output ('close-' + 'armed')\r")
            assertTrue("the shell was not ready within 30s", session.output.await("close-armed", 30))

            val closedAtNs = System.nanoTime()
            assertTrue(
                "connector.close() did not return within $CLOSE_LIMIT_MS ms",
                session.closeConnectorBounded(CLOSE_LIMIT_MS),
            )
            assertTrue(
                "close did not terminate the process within 15s",
                session.process.waitFor(15, TimeUnit.SECONDS),
            )
            assertFalse("the process must not be alive after close", session.process.isAlive())
            assertTrue(
                "the output reader did not terminate after close within $READER_LIMIT_MS ms",
                session.output.awaitReaderTermination(READER_LIMIT_MS),
            )
            val closeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closedAtNs)
            assertTrue(
                "close took $closeMs ms; the interrupt plus destroy path must stay under 15s",
                closeMs < 15_000,
            )
        }
    }
}
