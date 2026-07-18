// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.util

import com.intellij.openapi.diagnostic.Logger
import org.zoocode.jetbrains.plugin.DEBUG_MODE
import org.zoocode.jetbrains.plugin.WecoderPluginService
import java.io.File
import java.nio.file.Paths
import kotlin.io.path.pathString

/**
 * Plugin resource utility class
 * Used to obtain resource file paths in the plugin
 */
object PluginResourceUtil {
    private val LOG = Logger.getInstance(PluginResourceUtil::class.java)

    /**
     * Get resource path
     *
     * @param pluginId Plugin ID
     * @param resourceName Resource name
     * @return Resource path, or null if failed to get
     */
    fun getResourcePath(pluginId: String, resourceName: String): String? {
        return try {
            if(WecoderPluginService.getDebugMode() == DEBUG_MODE.IDEA) {
                // Debug mode: directly use plugin service to get resource path
                return WecoderPluginService.getDebugResource() + "/$resourceName"
            }
            require(pluginId == PluginConstants.PLUGIN_ID) { "Cannot access resources of another plugin: $pluginId" }
            val pluginPath = PluginInfo.installationPath
                ?: throw IllegalStateException("Cannot determine the Zoo Code plugin path")

            // Determine whether it is development mode or production mode
            val isDevMode = checkDevMode()

            if (isDevMode) {
                // Development mode: load from classpath or project resource directory
                loadDevResource(resourceName, pluginPath)
            } else {
                // Production mode: load from plugin JAR or installation directory
                loadProdResource(resourceName, pluginPath)
            }
        } catch (e: Exception) {
            LOG.error("Failed to get plugin resource path: $resourceName", e)
            null
        }
    }

    /**
     * Load resources in development mode
     */
    private fun loadDevResource(resourceName: String, pluginPath: java.nio.file.Path): String {
        val resourcePath = Paths.get(pluginPath.parent.parent.parent.parent.parent.pathString, "debug-resources/$resourceName")
        return resourcePath.toString()
    }

    /**
     * Load resources in production mode
     */
    private fun loadProdResource(resourceName: String, pluginPath: java.nio.file.Path): String? {
        // Load from plugin installation directory (compatible with old version)
        val pluginDir = pluginPath.toFile()
        val resourceDir = pluginDir.resolve(resourceName)
        if (resourceDir.exists()) {
            return resourceDir.absolutePath
        }
        return null
    }

    /**
     * Check whether it is in development mode
     */
    private fun checkDevMode(): Boolean {
        return try {
            WecoderPluginService.getDebugMode() != DEBUG_MODE.NONE
        }catch (e: Exception){
            false
        }
    }

    /**
     * Extract resource from URL to temporary file
     *
     * @param resourceUrl Resource URL
     * @param filename File name
     * @return Temporary file path, or null if extraction fails
     */
    fun extractResourceToTempFile(resourceUrl: java.net.URL, filename: String): String? {
        return try {
            val tempFile = File.createTempFile("zoo-code-", "-$filename")
            tempFile.deleteOnExit()
            
            resourceUrl.openStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            LOG.info("Resource extracted to temporary file: ${tempFile.absolutePath}")
            tempFile.absolutePath
        } catch (e: Exception) {
            LOG.error("Failed to extract resource to temporary file: $filename", e)
            null
        }
    }
}
