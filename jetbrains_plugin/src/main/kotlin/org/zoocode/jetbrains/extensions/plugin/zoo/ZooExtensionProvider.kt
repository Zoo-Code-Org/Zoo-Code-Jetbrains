// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.extensions.plugin.zoo

import com.intellij.openapi.project.Project
import org.zoocode.jetbrains.extensions.common.ExtensionType
import org.zoocode.jetbrains.extensions.config.ExtensionConfiguration
import org.zoocode.jetbrains.extensions.core.ExtensionManagerFactory
import org.zoocode.jetbrains.extensions.config.ExtensionProvider
import org.zoocode.jetbrains.extensions.config.ExtensionMetadata
import org.zoocode.jetbrains.util.PluginConstants
import org.zoocode.jetbrains.util.PluginConstants.ConfigFiles.getUserConfigDir
import org.zoocode.jetbrains.util.PluginResourceUtil
import java.io.File

/**
 * Zoo Code extension provider implementation
 */
class ZooExtensionProvider : ExtensionProvider {
    
    override fun getExtensionId(): String = "zoo-code"
    
    override fun getDisplayName(): String = "Zoo Code"
    
    override fun getDescription(): String = "AI-powered code assistant"
    
    override fun initialize(project: Project) {
        // Initialize roo extension configuration
        val extensionConfig = ExtensionConfiguration.getInstance(project)
        extensionConfig.initialize()
        
        // Initialize extension manager factory
        val extensionManagerFactory = ExtensionManagerFactory.getInstance(project)
        extensionManagerFactory.initialize()
    }
    
    override fun isAvailable(project: Project): Boolean {
        // Always return true for Zoo Code since it's the default extension
        // The actual files will be handled by the extension host
        return true
    }
    
    override fun getConfiguration(project: Project): ExtensionMetadata {
        val extensionConfig = ExtensionConfiguration.getInstance(project)
        val config = extensionConfig.getConfig(ExtensionType.ZOO_CODE);

        return object : ExtensionMetadata {
            override fun getCodeDir(): String = config.codeDir
            override fun getPublisher(): String = config.publisher
            override fun getVersion(): String = config.version
            override fun getMainFile(): String = config.mainFile
            override fun getActivationEvents(): List<String> = config.activationEvents
            override fun getEngines(): Map<String, String> = config.engines
            override fun getCapabilities(): Map<String, Any> = config.capabilities
            override fun getExtensionDependencies(): List<String> = config.extensionDependencies
        }
    }
    
    override fun dispose() {
        // Cleanup resources if needed
    }
} 