// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.terminal

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.pty4j.PtyProcess
import com.pty4j.WinSize
import com.pty4j.unix.UnixPtyProcess
import com.pty4j.windows.conpty.WinConPtyProcess
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

/**
 * Callback that receives terminal output as the terminal emulator reads it from the PTY.
 */
fun interface TerminalRawDataCallback {
    fun onRawData(data: String)
}

/**
 * TtyConnector over a PtyProcess that forwards every chunk of decoded output to
 * [callback] exactly once while returning it to the terminal emulator.
 *
 * The class extends jediterm ProcessTtyConnector. Its
 * (java.lang.Process, java.nio.charset.Charset) constructor has the exact same
 * descriptor in IntelliJ Platform builds 233 (lib/lib-client.jar) and 263
 * (lib/intellij.libraries.jediterm.core.jar), so one compiled super call
 * resolves on both. ready, isConnected, waitFor, both write overloads, and
 * getProcess are inherited unchanged. The platform
 * com.intellij.terminal.pty.PtyProcessTtyConnector cannot be the superclass:
 * on 233 its constructor takes com.pty4j.PtyProcess and on 2026.3 it takes
 * java.lang.Process, so no single compiled super call resolves on both. The
 * session teardown handles any TtyConnector: it only asks
 * LocalTerminalTtyConnector for closeSafely and falls back to plain close
 * otherwise (TerminalUtilKt.closeAndWaitFor).
 *
 * Overrides keep the behavior the platform connectors provide:
 * - read forwards each decoded chunk to [callback] before returning it.
 * - resize skips a dead process, matching PtyProcessTtyConnector on both builds.
 * - close sends SIGHUP first on Unix and destroys after one second; on Windows
 *   ConPTY it writes the ETX interrupt byte to the process stdin before
 *   destroy; every other process is destroyed immediately. super.close is not
 *   called: it destroys immediately, which would remove the one-second SIGHUP
 *   grace window. It closes the same process streams that the inherited reader
 *   wraps.
 */
class RawOutputTtyConnector(
    process: PtyProcess,
    charset: Charset,
    private val callback: TerminalRawDataCallback?,
) : ProcessTtyConnector(process, charset) {

    private val logger = Logger.getInstance(RawOutputTtyConnector::class.java)

    override fun getName(): String = "Local"

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        val count = super.read(buf, offset, length)
        if (count > 0 && callback != null) {
            callback.onRawData(String(buf, offset, count))
        }
        return count
    }

    override fun resize(termSize: TermSize) {
        if (isConnected) {
            (process as PtyProcess).winSize = WinSize(termSize.columns, termSize.rows)
        }
    }

    override fun close() {
        closeProcess()
        try {
            process.inputStream.close()
        } catch (ignored: IOException) {
        }
    }

    /**
     * Mirrors the close behavior of the JetBrains local terminal connector:
     * Unix processes receive SIGHUP first and get destroyed one second later if
     * they survived it; Windows ConPTY processes receive the ETX interrupt byte
     * on stdin before destroy; every other process is destroyed immediately.
     */
    private fun closeProcess() {
        val proc = process
        if (proc is UnixPtyProcess) {
            proc.hangup()
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                if (proc.isAlive()) {
                    logger.info("Terminal process survived SIGHUP, performing default termination")
                    proc.destroy()
                }
            }, 1, TimeUnit.SECONDS)
        } else if (proc is WinConPtyProcess) {
            sendInterrupt(proc)
            proc.destroy()
        } else {
            proc.destroy()
        }
    }

    /**
     * Matches LocalTerminalTtyConnector.sendInterruptToWinConPtyProcess: the
     * interrupt byte reaches the console only while the process is alive, and a
     * closed stream must not fail the close path.
     */
    private fun sendInterrupt(ptyProcess: Process) {
        if (!ptyProcess.isAlive) return
        try {
            ptyProcess.outputStream.write(0x03)
            ptyProcess.outputStream.flush()
        } catch (ignored: IOException) {
            logger.info("Failed to send the interrupt byte to the Windows ConPTY process during close")
        }
    }
}
