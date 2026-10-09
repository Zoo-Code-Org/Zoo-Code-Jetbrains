// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.util

import com.intellij.ui.jcef.JBCefApp

/**
 * Outcome of the JBCefApp availability probe.
 *
 * [AVAILABLE] means JBCefApp.isSupported() returned true, so JCEF views can
 * render. [RUNTIME_UNAVAILABLE] means the JBCefApp class is present but the
 * probe returned false, which a runtime without JCEF support causes.
 * [CLASSES_MISSING] means loading JBCefApp itself failed with a LinkageError;
 * on IDE builds 2026.2 (262) and newer that means the bundled
 * com.intellij.modules.jcef plugin is absent.
 */
enum class JcefSupport {
    AVAILABLE,
    RUNTIME_UNAVAILABLE,
    CLASSES_MISSING;

    /** Only [AVAILABLE] can render JCEF views. */
    val isRenderable: Boolean
        get() = this == AVAILABLE

    companion object {
        /** Runs the JBCefApp.isSupported() probe and maps the outcome. */
        fun current(): JcefSupport = classify { JBCefApp.isSupported() }

        /**
         * Test seam; production code calls [current]. Without the JCEF plugin
         * the probe fails with NoClassDefFoundError, so only LinkageError maps
         * to [CLASSES_MISSING] and every other probe failure propagates.
         */
        internal fun classify(isSupported: () -> Boolean): JcefSupport =
            try {
                if (isSupported()) AVAILABLE else RUNTIME_UNAVAILABLE
            } catch (_: LinkageError) {
                CLASSES_MISSING
            }
    }
}
