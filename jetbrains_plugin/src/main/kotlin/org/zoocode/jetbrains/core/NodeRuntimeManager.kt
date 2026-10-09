// SPDX-FileCopyrightText: 2026 Zoo Code Org
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.core

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Managed Node.js runtime manager
 * Downloads a pinned, platform-specific Node.js distribution from nodejs.org on first launch
 * so the extension host does not depend on the user's local Node.js installation
 */
object NodeRuntimeManager {
    private val LOG = Logger.getInstance(NodeRuntimeManager::class.java)

    /**
     * Pinned Node.js version downloaded by the plugin
     */
    const val BUNDLED_NODE_VERSION = "20.19.2"

    private const val NODE_DIST_BASE_URL = "https://nodejs.org/dist"
    private const val RUNTIME_ROOT_DIR_NAME = "zoocode-node"
    private const val FAILURE_MARKER_PREFIX = "download-failed-"
    private const val SHA256_HEX_LENGTH = 64

    /**
     * Minimum delay before retrying a failed download when a fallback Node.js is available
     */
    private val FAILURE_RETRY_INTERVAL_MS = TimeUnit.HOURS.toMillis(24)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val downloadLock = ReentrantLock()

    /**
     * Platform-specific Node.js distribution descriptor
     */
    private data class NodeDistribution(
        val version: String,
        val platformDirName: String,
        val archiveExtension: String
    ) {
        val archiveFileName: String = "$platformDirName.$archiveExtension"
        val archiveUrl: String = "$NODE_DIST_BASE_URL/v$version/$archiveFileName"
        val checksumsUrl: String = "$NODE_DIST_BASE_URL/v$version/SHASUMS256.txt"
    }

    /**
     * Resolve the Node.js distribution for the current platform, or null if unsupported
     */
    private fun resolveDistribution(version: String): NodeDistribution? {
        val os = when {
            SystemInfo.isWindows -> "win"
            SystemInfo.isMac -> "darwin"
            SystemInfo.isLinux -> "linux"
            else -> {
                LOG.warn("Unsupported OS for managed Node.js runtime: ${System.getProperty("os.name")}")
                return null
            }
        }
        val arch = when (System.getProperty("os.arch").lowercase()) {
            "x86_64", "amd64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> {
                LOG.warn("Unsupported architecture for managed Node.js runtime: ${System.getProperty("os.arch")}")
                return null
            }
        }
        val archiveExtension = if (os == "win") "zip" else "tar.gz"
        return NodeDistribution(version, "node-v$version-$os-$arch", archiveExtension)
    }

    /**
     * Root directory holding managed Node.js runtimes
     */
    private fun getRuntimeRootDir(): File = File(PathManager.getSystemPath(), RUNTIME_ROOT_DIR_NAME)

    private fun nodeExecutableIn(runtimeDir: File): File {
        return if (SystemInfo.isWindows) {
            File(runtimeDir, "node.exe")
        } else {
            File(runtimeDir, "bin/node")
        }
    }

    /**
     * Find the managed Node.js executable if it was previously downloaded
     * @return Absolute path to the managed node executable, or null if not installed
     */
    fun findManagedNodeExecutable(version: String = BUNDLED_NODE_VERSION): String? {
        val dist = resolveDistribution(version) ?: return null
        val runtimeDir = File(getRuntimeRootDir(), dist.platformDirName)
        val nodeExecutable = nodeExecutableIn(runtimeDir)
        return if (nodeExecutable.isFile && nodeExecutable.canExecute()) {
            nodeExecutable.absolutePath
        } else {
            null
        }
    }

    /**
     * Whether a previous download attempt failed recently and should not be retried yet
     */
    fun hasRecentDownloadFailure(version: String = BUNDLED_NODE_VERSION): Boolean {
        val marker = failureMarkerFile(version)
        if (!marker.isFile) {
            return false
        }
        val timestamp = try {
            marker.readText().trim().toLong()
        } catch (e: Exception) {
            return false
        }
        return System.currentTimeMillis() - timestamp < FAILURE_RETRY_INTERVAL_MS
    }

    /**
     * Get the managed Node.js executable, downloading and installing it with visible
     * progress when it is not installed yet
     * @return Absolute path to the managed node executable, or null on failure or cancellation
     */
    fun getOrDownloadNodeExecutable(): String? {
        findManagedNodeExecutable()?.let { return it }

        val dist = resolveDistribution(BUNDLED_NODE_VERSION) ?: return null

        downloadLock.lock()
        try {
            // Another thread may have completed the download while we waited for the lock
            findManagedNodeExecutable()?.let { return it }
            return downloadWithProgress(dist)
        } finally {
            downloadLock.unlock()
        }
    }

    /**
     * Run the download under a modal progress indicator.
     * Blocks the calling thread (must not be the EDT) until the download finishes.
     */
    private fun downloadWithProgress(dist: NodeDistribution): String? {
        var installedPath: String? = null
        var canceled = false

        // ProgressManager.run(Task) queues a Backgroundable and returns before
        // it finishes, so every first launch saw null and the extension host
        // did not start until the next IDE session. The synchronous variant
        // blocks the caller while still showing progress; the calling thread
        // is never the EDT.
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            try {
                val indicator = ProgressManager.getInstance().progressIndicator
                installedPath = doDownload(dist, indicator ?: EmptyProgressIndicator())
            } catch (e: ProcessCanceledException) {
                canceled = true
                LOG.info("Node.js runtime download canceled by user")
            } catch (e: Exception) {
                LOG.warn("Failed to download Node.js runtime from ${dist.archiveUrl}", e)
            }
        }, "Downloading Node.js runtime", true, null)

        if (installedPath != null) {
            clearDownloadFailure(dist.version)
        } else if (!canceled) {
            markDownloadFailure(dist.version)
        }
        return installedPath
    }

    private fun doDownload(dist: NodeDistribution, indicator: ProgressIndicator): String {
        val rootDir = getRuntimeRootDir()
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            throw IOException("Cannot create Node.js runtime directory: ${rootDir.absolutePath}")
        }

        val finalDir = File(rootDir, dist.platformDirName)
        val tempArchive = File(rootDir, "${dist.archiveFileName}.download")
        val stagingDir = File(rootDir, "${dist.platformDirName}.staging")

        try {
            indicator.text = "Fetching Node.js ${dist.version} checksums..."
            val expectedSha256 = fetchExpectedChecksum(dist)
                ?: throw IOException("Checksum for ${dist.archiveFileName} not found in SHASUMS256.txt")

            indicator.text = "Downloading Node.js ${dist.version}..."
            downloadFile(dist.archiveUrl, tempArchive, indicator)

            indicator.text = "Verifying Node.js download..."
            indicator.checkCanceled()
            val actualSha256 = sha256Hex(tempArchive)
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                throw IOException(
                    "Checksum mismatch for ${dist.archiveFileName}: expected $expectedSha256, got $actualSha256"
                )
            }

            indicator.text = "Installing Node.js ${dist.version}..."
            indicator.checkCanceled()
            if (stagingDir.exists() && !stagingDir.deleteRecursively()) {
                throw IOException("Cannot clean staging directory: ${stagingDir.absolutePath}")
            }
            extractArchive(tempArchive, dist.archiveExtension, stagingDir)

            val extractedDir = File(stagingDir, dist.platformDirName)
            if (!extractedDir.isDirectory) {
                throw IOException("Expected directory ${dist.platformDirName} not found after extraction")
            }
            if (finalDir.exists() && !finalDir.deleteRecursively()) {
                throw IOException("Cannot replace existing runtime directory: ${finalDir.absolutePath}")
            }
            if (!extractedDir.renameTo(finalDir)) {
                throw IOException("Cannot move Node.js runtime into place: ${finalDir.absolutePath}")
            }

            val nodeExecutable = nodeExecutableIn(finalDir)
            if (!SystemInfo.isWindows) {
                nodeExecutable.setExecutable(true, false)
            }
            if (!nodeExecutable.isFile || !nodeExecutable.canExecute()) {
                throw IOException("Node.js executable missing after installation: ${nodeExecutable.absolutePath}")
            }

            LOG.info("Managed Node.js runtime installed: ${nodeExecutable.absolutePath}")
            return nodeExecutable.absolutePath
        } finally {
            tempArchive.delete()
            stagingDir.deleteRecursively()
        }
    }

    /**
     * Download a URL to a local file, reporting progress on the indicator
     */
    private fun downloadFile(url: String, dest: File, indicator: ProgressIndicator) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} downloading $url")
            }
            val body = response.body ?: throw IOException("Empty response downloading $url")
            val totalBytes = body.contentLength()
            body.byteStream().use { input ->
                dest.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloadedBytes = 0L
                    while (true) {
                        indicator.checkCanceled()
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            indicator.fraction = downloadedBytes.toDouble() / totalBytes
                            indicator.text2 = "${downloadedBytes / 1024 / 1024} MB / ${totalBytes / 1024 / 1024} MB"
                        }
                    }
                }
            }
        }
    }

    /**
     * Fetch the expected SHA-256 checksum for the distribution archive from SHASUMS256.txt
     */
    private fun fetchExpectedChecksum(dist: NodeDistribution): String? {
        val request = Request.Builder().url(dist.checksumsUrl).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                LOG.warn("Failed to fetch Node.js checksums: HTTP ${response.code}")
                return null
            }
            val content = response.body?.string() ?: return null
            return content.lineSequence()
                .map { it.trim() }
                .filter { it.endsWith(dist.archiveFileName) }
                .map { it.substringBefore(' ').trim() }
                .firstOrNull { it.length == SHA256_HEX_LENGTH }
        }
    }

    /**
     * Compute the SHA-256 checksum of a file as lowercase hex
     */
    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Extract a Node.js distribution archive into the destination directory
     */
    private fun extractArchive(archive: File, archiveExtension: String, destDir: File) {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IOException("Cannot create archive destination: ${destDir.absolutePath}")
        }
        if (archiveExtension == "zip") {
            extractZip(archive, destDir)
        } else {
            extractTarGz(archive, destDir)
        }
    }

    private fun extractZip(archive: File, destDir: File) {
        ZipInputStream(archive.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val target = safeArchiveTarget(destDir, entry.name)
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target).use { output -> input.copyTo(output) }
                }
                input.closeEntry()
            }
        }
    }

    private fun extractTarGz(archive: File, destDir: File) {
        val pendingLinks = mutableListOf<Pair<Path, TarArchiveEntry>>()
        TarArchiveInputStream(GZIPInputStream(archive.inputStream().buffered())).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val target = safeArchiveTarget(destDir, entry.name)
                when {
                    entry.isDirectory -> Files.createDirectories(target)
                    entry.isSymbolicLink || entry.isLink -> pendingLinks.add(target to entry)
                    entry.isFile -> {
                        Files.createDirectories(target.parent)
                        Files.newOutputStream(target).use { output -> input.copyTo(output) }
                        applyPosixPermissions(target, entry.mode)
                    }
                }
            }
        }

        pendingLinks.forEach { (target, entry) ->
            Files.createDirectories(target.parent)
            if (entry.isSymbolicLink) {
                val linkTarget = Path.of(entry.linkName)
                val resolvedLinkTarget = if (linkTarget.isAbsolute) {
                    linkTarget.normalize()
                } else {
                    target.parent.resolve(linkTarget).normalize()
                }
                val root = destDir.toPath().toAbsolutePath().normalize()
                if (!resolvedLinkTarget.startsWith(root)) {
                    throw IOException("Archive link escapes destination: ${entry.name} -> ${entry.linkName}")
                }
                Files.createSymbolicLink(target, linkTarget)
            } else {
                val linkTarget = safeArchiveTarget(destDir, entry.linkName)
                Files.createLink(target, linkTarget)
            }
        }
    }

    private fun applyPosixPermissions(path: Path, mode: Int) {
        val permissions = mutableSetOf<PosixFilePermission>()
        if (mode and 0b100_000_000 != 0) permissions.add(PosixFilePermission.OWNER_READ)
        if (mode and 0b010_000_000 != 0) permissions.add(PosixFilePermission.OWNER_WRITE)
        if (mode and 0b001_000_000 != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE)
        if (mode and 0b000_100_000 != 0) permissions.add(PosixFilePermission.GROUP_READ)
        if (mode and 0b000_010_000 != 0) permissions.add(PosixFilePermission.GROUP_WRITE)
        if (mode and 0b000_001_000 != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE)
        if (mode and 0b000_000_100 != 0) permissions.add(PosixFilePermission.OTHERS_READ)
        if (mode and 0b000_000_010 != 0) permissions.add(PosixFilePermission.OTHERS_WRITE)
        if (mode and 0b000_000_001 != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE)
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
            // Windows and other non-POSIX file systems do not expose Unix mode bits.
        }
    }

    /**
     * Resolve an archive entry without allowing it to escape the destination directory.
     */
    private fun safeArchiveTarget(destDir: File, entryName: String): Path {
        val root = destDir.toPath().toAbsolutePath().normalize()
        val target = root.resolve(entryName).normalize()
        if (!target.startsWith(root)) {
            throw IOException("Archive entry escapes destination: $entryName")
        }

        // Refuse to write through a symlink left by a partially extracted archive.
        var current = target.parent
        while (current != null && current.startsWith(root)) {
            if (Files.isSymbolicLink(current) || Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(current)) {
                throw IOException("Archive entry has an unsafe parent: $entryName")
            }
            if (current == root) break
            current = current.parent
        }
        return target
    }

    private fun failureMarkerFile(version: String): File {
        return File(getRuntimeRootDir(), "$FAILURE_MARKER_PREFIX$version")
    }

    private fun markDownloadFailure(version: String) {
        try {
            val rootDir = getRuntimeRootDir()
            if (!rootDir.exists()) {
                rootDir.mkdirs()
            }
            failureMarkerFile(version).writeText(System.currentTimeMillis().toString())
        } catch (e: Exception) {
            LOG.warn("Failed to record Node.js download failure", e)
        }
    }

    private fun clearDownloadFailure(version: String) {
        failureMarkerFile(version).delete()
    }
}
