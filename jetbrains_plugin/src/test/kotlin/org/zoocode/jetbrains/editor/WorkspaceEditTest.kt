package org.zoocode.jetbrains.editor

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test
import org.zoocode.jetbrains.util.URI

class WorkspaceEditTest {
    @Test
    fun `IDE file events and RPC edits share one document identity`() {
        val ideResource = URI.file("C:/workspace/Main.java")
        val rpcResource = createURI(mapOf("scheme" to "file", "path" to "C:/workspace/Main.java"))
        val documents = hashMapOf(ideResource to false)
        documents[rpcResource] = true
        assertEquals("An edit must update the original document rather than add a second one", 1, documents.size)
        assertEquals(true, documents[ideResource])
        assertEquals(rpcResource, ideResource)
        assertEquals(rpcResource.toString(), ideResource.toString())
    }

    @Test
    fun `editing an IDE document sends a dirty change rather than a second document`() {
        val ideResource = URI.file("C:/workspace/Main.java")
        val rpcResource = createURI(mapOf("scheme" to "file", "path" to "C:/workspace/Main.java"))
        val original = ModelAddedData(ideResource, 1, listOf("throwaway"), "\n", "java", false, "utf8")
        val previous = DocumentsAndEditorsState(documents = hashMapOf(ideResource to original))
        val current = DocumentsAndEditorsState(documents = hashMapOf(ideResource to original))
        current.documents[rpcResource] = original.copy(uri = rpcResource, versionId = 2,
            lines = listOf("solheim_e2e"), isDirty = true)
        val delta = current.delta(previous)
        assertEquals(true, delta.itemsDelta?.addedDocuments.isNullOrEmpty())
        assertEquals(1, delta.documentDeltas.size)
        assertEquals(true, delta.documentDeltas[ideResource]?.isDirty)
        assertEquals("solheim_e2e", delta.documentDeltas[ideResource]?.changes?.single()?.text)
    }

    @Test
    fun `Windows workspace edits share the open and save document path`() {
        assertDocumentIdentity("C:/Users/runner/workspace/src/Main.java", "/C:/Users/runner/workspace/src/Main.java")
    }

    @Test
    fun `canonical Windows Unix and UNC paths retain document identity`() {
        assertDocumentIdentity("/C:/workspace/Main.java", "/C:/workspace/Main.java")
        assertDocumentIdentity("/workspace/Main.java", "/workspace/Main.java")
        assertDocumentIdentity("/share/Main.java", "/share/Main.java", "server")
    }

    private fun assertDocumentIdentity(editPath: String, savePath: String, authority: String = "") {
        val components = mapOf("scheme" to "file", "authority" to authority,
            "path" to editPath, "query" to "", "fragment" to "")
        val dto = Gson().toJson(mapOf("edits" to listOf(mapOf(
            "resource" to components,
            "textEdit" to mapOf("range" to mapOf("startLineNumber" to 1, "startColumn" to 1,
                "endLineNumber" to 1, "endColumn" to 10), "text" to "solheim_e2e"),
            "metadata" to mapOf("isRefactoring" to false)
        ))))
        val edit = WorkspaceEdit.from(dto).texts.single()
        val savedResource = createURI(components + ("path" to savePath))
        // Document holders are looked up by path; a mismatch makes save miss the edited holder.
        assertEquals(savedResource.path, edit.resource.path)
        assertEquals(savedResource.authority, edit.resource.authority)
        assertEquals("solheim_e2e", edit.textEdit.text)
        assertEquals(10, edit.textEdit.range.endColumn)
        assertEquals(false, edit.metadata?.isRefactoring)
    }
}
