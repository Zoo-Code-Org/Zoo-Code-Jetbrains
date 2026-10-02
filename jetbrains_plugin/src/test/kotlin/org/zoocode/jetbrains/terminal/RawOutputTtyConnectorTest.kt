// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.pty4j.PtyProcess
import com.pty4j.WinSize
import com.pty4j.unix.UnixPtyProcess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RawOutputTtyConnectorTest {

    private class StubPtyProcess(
        private val input: InputStream,
        private val output: OutputStream,
    ) : PtyProcess() {
        var destroyed = false
            private set

        var lastWinSize: WinSize? = null
            private set

        override fun getInputStream(): InputStream = input

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getOutputStream(): OutputStream = output

        override fun isAlive(): Boolean = !destroyed

        override fun pid(): Long = 1

        override fun exitValue(): Int = 0

        override fun waitFor(): Int = 0

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process = this

        override fun info(): ProcessHandle.Info = ProcessHandle.current().info()

        override fun children(): java.util.stream.Stream<ProcessHandle> = ProcessHandle.current().children()

        override fun descendants(): java.util.stream.Stream<ProcessHandle> = ProcessHandle.current().descendants()

        override fun toHandle(): ProcessHandle = ProcessHandle.current()

        override fun onExit(): CompletableFuture<Process> = CompletableFuture.completedFuture(this)

        override fun setWinSize(winSize: WinSize) {
            lastWinSize = winSize
        }

        override fun getWinSize(): WinSize = WinSize(80, 24)

        override fun isConsoleMode(): Boolean = false
    }

    /**
     * Records every flush so write() can be checked against the platform
     * connectors, which flush after each write.
     */
    private class FlushRecordingOutputStream : OutputStream() {
        var flushed = 0
            private set

        val written = ByteArrayOutputStream()

        override fun write(b: Int) = written.write(b)

        override fun write(b: ByteArray, off: Int, len: Int) = written.write(b, off, len)

        override fun flush() {
            flushed++
        }
    }

    /**
     * Delivers the payload byte by byte, so a 2-, 3- or 4-byte UTF-8 sequence is
     * always cut at a byte boundary inside the decoder.
     */
    private class OneBytePerReadInputStream(private val data: ByteArray) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position < data.size) data[position++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= data.size) return -1
            b[off] = data[position++]
            return 1
        }
    }

    /**
     * Delivers the payload in fixed-size chunks that do not align with UTF-8
     * sequence lengths.
     */
    private class FixedChunkInputStream(
        private val data: ByteArray,
        private val chunkSize: Int,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position < data.size) data[position++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= data.size) return -1
            val count = minOf(len, chunkSize, data.size - position)
            System.arraycopy(data, position, b, off, count)
            position += count
            return count
        }
    }

    private fun newConnector(
        data: ByteArray,
        callback: TerminalRawDataCallback? = TerminalRawDataCallback { },
    ): Pair<RawOutputTtyConnector, StubPtyProcess> {
        val process = StubPtyProcess(
            ByteArrayInputStream(data),
            ByteArrayOutputStream(),
        )
        return RawOutputTtyConnector(process, StandardCharsets.UTF_8, callback) to process
    }

    private fun readAll(connector: RawOutputTtyConnector): String {
        val decoded = StringBuilder()
        val buf = CharArray(4)
        while (true) {
            val count = connector.read(buf, 0, buf.size)
            if (count < 0) break
            decoded.append(buf, 0, count)
        }
        return decoded.toString()
    }

    @Test
    fun readForwardsExactlyTheReturnedChunkToTheCallback() {
        val received = mutableListOf<String>()
        val (connector, _) = newConnector(
            "hello world".toByteArray(StandardCharsets.UTF_8),
            callback = TerminalRawDataCallback { received.add(it) },
        )

        val buf = CharArray(16)
        val count = connector.read(buf, 0, buf.size)

        assertEquals(11, count)
        assertEquals(1, received.size)
        assertEquals("hello world", received[0])
        assertEquals("hello world", String(buf, 0, count))
    }

    @Test
    fun readRespectsOffsetAndDoesNotDuplicateData() {
        val received = mutableListOf<String>()
        val (connector, _) = newConnector(
            "chunk".toByteArray(StandardCharsets.UTF_8),
            callback = TerminalRawDataCallback { received.add(it) },
        )

        val buf = CharArray(16)
        java.util.Arrays.fill(buf, 'x')
        val count = connector.read(buf, 3, 10)

        assertEquals(5, count)
        assertEquals(1, received.size)
        assertEquals("chunk", received[0])
        assertEquals("chunk", String(buf, 3, count))
        assertEquals("xxx", String(buf, 0, 3))
        assertEquals('x', buf[8])
    }

    @Test
    fun readAtEndOfStreamSendsNothingAndReturnsMinusOne() {
        val received = mutableListOf<String>()
        val (connector, _) = newConnector(
            ByteArray(0),
            callback = TerminalRawDataCallback { received.add(it) },
        )

        val buf = CharArray(8)
        val count = connector.read(buf, 0, buf.size)

        assertEquals(-1, count)
        assertTrue(received.isEmpty())
    }

    @Test
    fun zeroLengthReadReturnsZeroAndCallsNoCallback() {
        val received = mutableListOf<String>()
        val (connector, _) = newConnector(
            "data".toByteArray(StandardCharsets.UTF_8),
            callback = TerminalRawDataCallback { received.add(it) },
        )

        assertEquals(0, connector.read(CharArray(8), 0, 0))
        assertTrue("zero-length read must not feed the callback", received.isEmpty())
    }

    @Test
    fun callbackFailurePropagatesAndDoesNotDuplicateTheFailedChunk() {
        val received = mutableListOf<String>()
        var failOnSecondChunk = false
        val (connector, _) = newConnector(
            "hello world".toByteArray(StandardCharsets.UTF_8),
            callback = TerminalRawDataCallback {
                if (received.size == 1 && failOnSecondChunk) error("callback boom")
                received.add(it)
            },
        )

        val buf = CharArray(4)
        assertEquals(4, connector.read(buf, 0, buf.size))
        assertEquals(listOf("hell"), received)

        failOnSecondChunk = true
        try {
            connector.read(buf, 0, buf.size)
            throw AssertionError("callback failure must propagate to the reader")
        } catch (expected: IllegalStateException) {
            assertEquals("callback boom", expected.message)
        }
        failOnSecondChunk = false

        val count = connector.read(buf, 0, buf.size)
        assertEquals(3, count)
        assertEquals(
            "the failed chunk is dropped, every delivered chunk is forwarded exactly once",
            listOf("hell", "rld"),
            received,
        )
    }

    @Test
    fun readDecodesMultibyteSequencesCutAtEveryByteBoundary() {
        val received = mutableListOf<String>()
        val text = "naïve — 你好, τξζ, 🚀 done"
        val process = StubPtyProcess(
            OneBytePerReadInputStream(text.toByteArray(StandardCharsets.UTF_8)),
            ByteArrayOutputStream(),
        )
        val connector = RawOutputTtyConnector(
            process,
            StandardCharsets.UTF_8,
            TerminalRawDataCallback { received.add(it) },
        )

        val decoded = readAll(connector)

        assertEquals(text, decoded)
        assertEquals(text, received.joinToString(""))
        assertFalse(
            "decoder must not emit replacement characters (U+FFFD)",
            received.any { it.contains("\uFFFD") },
        )
    }

    @Test
    fun readDecodesMultibyteSequencesWhenChunksDoNotAlignWithSequences() {
        val received = mutableListOf<String>()
        val text = "naïve — 你好, τξζ, 🚀 done"
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        for (chunkSize in intArrayOf(2, 3, 5, 7)) {
            received.clear()
            val process = StubPtyProcess(
                FixedChunkInputStream(bytes, chunkSize),
                ByteArrayOutputStream(),
            )
            val connector = RawOutputTtyConnector(
                process,
                StandardCharsets.UTF_8,
                TerminalRawDataCallback { received.add(it) },
            )

            val decoded = readAll(connector)

            assertEquals("chunk size $chunkSize", text, decoded)
            assertEquals("chunk size $chunkSize", text, received.joinToString(""))
            assertFalse(
                "chunk size $chunkSize must not produce replacement characters (U+FFFD)",
                received.any { it.contains("\uFFFD") },
            )
        }
    }

    @Test
    fun writeEncodesMultibyteTextWithTheConnectorCharset() {
        val processOutput = ByteArrayOutputStream()
        val process = StubPtyProcess(
            ByteArrayInputStream(ByteArray(0)),
            processOutput,
        )
        val connector = RawOutputTtyConnector(process, StandardCharsets.UTF_8, null)
        val text = "echo 你好 🚀\n"

        connector.write(text)

        assertTrue(
            processOutput.toByteArray().contentEquals(text.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    @Test
    fun writeFlushesTheProcessOutputStream() {
        val processOutput = FlushRecordingOutputStream()
        val process = StubPtyProcess(
            ByteArrayInputStream(ByteArray(0)),
            processOutput,
        )
        val connector = RawOutputTtyConnector(process, StandardCharsets.UTF_8, null)

        connector.write("echo hi\n".toByteArray(StandardCharsets.UTF_8))
        connector.write("echo ho\n")

        assertEquals("echo hi\necho ho\n", processOutput.written.toString(StandardCharsets.UTF_8.name()))
        assertEquals("every write must flush, like the platform connectors", 2, processOutput.flushed)
    }

    @Test
    fun closeHangsUpARealUnixPtyProcessAndForwardsItsOutput() {
        assumeTrue("real PTY test runs on Linux only", System.getProperty("os.name").lowercase().contains("linux"))

        val process = com.pty4j.PtyProcessBuilder(arrayOf("/bin/sh"))
            .setRedirectErrorStream(true)
            .setEnvironment(mapOf("TERM" to "dumb", "PATH" to "/usr/bin:/bin"))
            .start()
        assertTrue("expected UnixPtyProcess on Linux", process is UnixPtyProcess)

        val received = StringBuilder()
        val sawMarker = CompletableFuture<Boolean>()
        val connector = RawOutputTtyConnector(process, StandardCharsets.UTF_8) { data ->
            received.append(data)
            if ("pty-roundtrip-mark" in received) sawMarker.complete(true)
        }
        val reader = thread(start = true, isDaemon = true) {
            val buf = CharArray(256)
            while (!sawMarker.isDone && process.isAlive()) {
                val count = connector.read(buf, 0, buf.size)
                if (count < 0) break
            }
        }

        connector.write("echo pty-roundtrip-mark\n")
        try {
            assertTrue("shell output was not forwarded within timeout", sawMarker.get(30, TimeUnit.SECONDS))
            assertFalse(
                "forwarded output must decode cleanly (no U+FFFD)",
                received.toString().contains("\uFFFD"),
            )

            val startNs = System.nanoTime()
            connector.close()
            val done = process.waitFor(15, TimeUnit.SECONDS)
            val closeToExitMs = (System.nanoTime() - startNs) / 1_000_000
            assertTrue("close did not terminate the shell within 15s", done)
            // The fallback destroy is scheduled one second after close, so only a
            // death before it proves the SIGHUP path. dash reports exit code 0 for a
            // SIGHUP death, so the exit code cannot discriminate here.
            assertTrue(
                "the shell died $closeToExitMs ms after close; the delayed destroy fires " +
                    "at 1000 ms, so a death before it can only come from SIGHUP",
                closeToExitMs < 1000,
            )
        } finally {
            sawMarker.complete(false)
            reader.join(1000)
            if (process.isAlive()) process.destroyForcibly()
        }
    }

    @Test
    fun writeSendsTextToTheProcessInputStream() {
        val processOutput = ByteArrayOutputStream()
        val process = StubPtyProcess(
            ByteArrayInputStream(ByteArray(0)),
            processOutput,
        )
        val connector = RawOutputTtyConnector(process, StandardCharsets.UTF_8, null)

        connector.write("echo hi\n")

        assertEquals("echo hi\n", String(processOutput.toByteArray(), StandardCharsets.UTF_8))
    }

    @Test
    fun closeDestroysNonUnixProcessLikeOnWindows() {
        val (connector, process) = newConnector(ByteArray(0))

        connector.close()

        assertTrue(process.destroyed)
    }

    @Test
    fun resizeAppliesTermSizeToTheProcess() {
        val (connector, process) = newConnector(ByteArray(0))

        connector.resize(TermSize(120, 40))

        assertEquals(120, process.lastWinSize?.columns)
        assertEquals(40, process.lastWinSize?.rows)
    }

    @Test
    fun resizeSkipsDeadProcessLikeThePlatformConnector() {
        val (connector, process) = newConnector(ByteArray(0))
        process.destroy()

        connector.resize(TermSize(120, 40))

        assertNull("resize must not touch a dead process", process.lastWinSize)
    }

    @Test
    fun nullCallbackStillReturnsData() {
        val data = "ok".toByteArray(StandardCharsets.UTF_8)
        val (connector, _) = newConnector(data, callback = null)

        val buf = CharArray(8)
        val count = connector.read(buf, 0, buf.size)

        assertEquals(2, count)
        assertEquals("ok", String(buf, 0, count))
    }

    @Test
    fun connectorIsAProcessTtyConnectorOverTheSameProcess() {
        val (connector, process) = newConnector(ByteArray(0))

        assertTrue(
            "the connector must extend jediterm ProcessTtyConnector, whose " +
                "(Process, Charset) constructor descriptor is identical on 233 and 263",
            connector is ProcessTtyConnector,
        )
        assertSame(
            "getProcess() must expose the exact process the connector was built with",
            process,
            connector.process,
        )
    }

    @Test
    fun inheritedLifecycleMethodsFollowTheProcess() {
        val (connector, process) = newConnector("in".toByteArray(StandardCharsets.UTF_8))

        assertEquals("Local", connector.name)
        assertTrue(connector.isConnected)
        assertTrue(connector.ready())
        assertEquals(0, connector.waitFor())
        assertSame(process, connector.process)

        process.destroy()

        assertFalse("isConnected must track process.isAlive", connector.isConnected)
    }
}
