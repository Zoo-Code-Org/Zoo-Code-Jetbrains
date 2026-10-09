// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Covers [JcefSupport.classify] only. The WebViewManager guard that refuses
 * webview creation when [JcefSupport.isRenderable] is false is not covered
 * here: constructing WebViewManager needs the IntelliJ platform, so that
 * early-return boundary relies on IDE smoke testing.
 */
class JcefSupportTest {

    @Test
    fun supportedRuntimeIsRenderable() {
        assertEquals(JcefSupport.AVAILABLE, JcefSupport.classify { true })
        assertTrue(JcefSupport.AVAILABLE.isRenderable)
    }

    @Test
    fun unavailableRuntimeIsDistinguishedFromMissingClasses() {
        assertEquals(JcefSupport.RUNTIME_UNAVAILABLE, JcefSupport.classify { false })
        assertFalse(JcefSupport.RUNTIME_UNAVAILABLE.isRenderable)
    }

    @Test
    fun missingApiClassesAreClassifiedAsClassesMissing() {
        assertEquals(
            JcefSupport.CLASSES_MISSING,
            JcefSupport.classify { throw NoClassDefFoundError("com/intellij/ui/jcef/JBCefApp") }
        )
        assertEquals(
            JcefSupport.CLASSES_MISSING,
            JcefSupport.classify { throw ClassFormatError("bad JBCefApp bytecode") }
        )
        assertFalse(JcefSupport.CLASSES_MISSING.isRenderable)
    }

    @Test
    fun nonLinkageErrorProbeFailuresPropagate() {
        try {
            JcefSupport.classify { throw IllegalStateException("probe crashed") }
            fail("classify must not swallow non-LinkageError probe failures")
        } catch (expected: IllegalStateException) {
            assertEquals("probe crashed", expected.message)
        }
    }
}
