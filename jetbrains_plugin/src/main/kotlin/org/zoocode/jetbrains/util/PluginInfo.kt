// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.util

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import java.nio.file.Path

/** Information about this plugin obtained from its own class loader. */
object PluginInfo {
    private val descriptor
        get() = (PluginInfo::class.java.classLoader as? PluginAwareClassLoader)?.pluginDescriptor

    val version: String
        get() = descriptor?.version ?: "unknown"

    val installationPath: Path?
        get() = descriptor?.pluginPath
}
