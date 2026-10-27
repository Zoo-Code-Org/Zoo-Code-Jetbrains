// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the display-intent decoding shared by tryShowTextDocument and
 * workbench_diff, the inverse mapping that both [EditorAndDocManager] open
 * calls pass to FileEditorManager.openFile, and the diff routing decision.
 * The real open behavior is exercised live by the UI harness, which needs a
 * real IDE.
 */
class EditorFocusIntentTest {

    @Test
    fun `preserveFocus true is preserved`() {
        assertTrue(decodePreserveFocus(mapOf("preserveFocus" to true)))
    }

    @Test
    fun `preserveFocus false requests focus`() {
        assertFalse(decodePreserveFocus(mapOf("preserveFocus" to false)))
    }

    @Test
    fun `missing flag uses the upstream focus default`() {
        assertFalse(decodePreserveFocus(mapOf<String, Any?>()))
        assertFalse(decodePreserveFocus(mapOf("selection" to mapOf<String, Any?>())))
    }

    @Test
    fun `null or non-map options use the upstream focus default`() {
        assertFalse(decodePreserveFocus(null))
        assertFalse(decodePreserveFocus("preserveFocus"))
        assertFalse(decodePreserveFocus(2))
    }

    @Test
    fun `malformed flag value falls back to the focus default`() {
        assertFalse(decodePreserveFocus(mapOf("preserveFocus" to "true")))
        assertFalse(decodePreserveFocus(mapOf("preserveFocus" to 1)))
    }

    @Test
    fun `numeric column argument is not a preservation flag`() {
        assertFalse(decodePreserveFocus(3))
        assertFalse(decodePreserveFocus(mapOf("viewColumn" to 3)))
    }

    @Test
    fun `openFile focus request is the inverse of preservation`() {
        assertFalse(editorFocusRequest(preserveFocus = true))
        assertTrue(editorFocusRequest(preserveFocus = false))
    }

    @Test
    fun `decoded intent flows through the inverse mapping end to end`() {
        for (options in listOf(
            mapOf<String, Any?>("preserveFocus" to true),
            mapOf<String, Any?>("preserveFocus" to false),
            mapOf<String, Any?>(),
            null
        )) {
            val preserveFocus = decodePreserveFocus(options)
            assertEquals(!preserveFocus, editorFocusRequest(preserveFocus))
        }
    }

    @Test
    fun `preserving diff opens through the file editor without closing the target`() {
        assertEquals(DiffOpenPlan.PRESERVE_VIA_FILE_EDITOR, diffOpenPlan(preserveFocus = true))
    }

    @Test
    fun `deliberate diff keeps the baseline diff helper path`() {
        assertEquals(DiffOpenPlan.DELIBERATE_VIA_DIFF_HELPER, diffOpenPlan(preserveFocus = false))
    }

    @Test
    fun `diff wire tuple preserves focus with an omitted column`() {
        assertTrue(decodeDiffPreserveFocus(listOf(null, mapOf("preserveFocus" to true))))
    }

    @Test
    fun `diff wire tuple still honors deliberate focus`() {
        assertFalse(decodeDiffPreserveFocus(listOf(2.0, mapOf("preserveFocus" to false))))
    }

    @Test
    fun `diff wire tuple missing options retains the upstream default`() {
        assertFalse(decodeDiffPreserveFocus(listOf(null, null)))
        assertFalse(decodeDiffPreserveFocus(emptyList<Any>()))
    }

    @Test
    fun `plugin local diff map and malformed tuple keep their existing decisions`() {
        assertTrue(decodeDiffPreserveFocus(mapOf("preserveFocus" to true)))
        assertFalse(decodeDiffPreserveFocus(listOf(null, mapOf("preserveFocus" to "true"))))
    }
}
