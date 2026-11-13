package org.zoocode.jetbrains.editor

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test
import org.zoocode.jetbrains.util.URI

class WorkspaceEditTest {
    @Test
    fun `IDE file events and RPC edits share one document identity`() {
        val ideResource = URI.file("C:/workspace/Main.java")
        val rpcResource = WorkspaceEdit.from("""{"edits":[{"resource":{"scheme":"file","path":"C:/workspace/Main.java"},"textEdit":{"range":{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":10},"text":"solheim_e2e"}}]}""").texts.single().resource
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
        val rpcResource = WorkspaceEdit.from("""{"edits":[{"resource":{"scheme":"file","path":"C:/workspace/Main.java"},"textEdit":{"range":{"startLineNumber":1,"startColumn":1,"endLineNumber":1,"endColumn":10},"text":"solheim_e2e"}}]}""").texts.single().resource
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

    @Test
    fun `file edits normalize both rename resources and preserve options`() {
        fun resource(path: String) = mapOf("scheme" to "file", "path" to path)
        val edits = listOf(
            mapOf("fileEdit" to true, "newResource" to resource("C:/workspace/new.txt")),
            mapOf("fileEdit" to true, "oldResource" to resource("C:/workspace/old.txt")),
            mapOf("fileEdit" to true, "oldResource" to resource("C:/workspace/old.txt"),
                "newResource" to resource("C:/workspace/new.txt"),
                "options" to mapOf("overwrite" to true, "copy" to true, "contents" to "aGVsbG8="),
                "metadata" to mapOf("isRefactoring" to true))
        )
        val result = WorkspaceEdit.from(Gson().toJson(mapOf("edits" to edits)))
        assertEquals(0, result.texts.size)
        assertEquals(3, result.files.size)
        assertEquals(null, result.files[0].oldResource)
        assertEquals(URI.file("C:/workspace/new.txt"), result.files[0].newResource)
        assertEquals(URI.file("C:/workspace/old.txt"), result.files[1].oldResource)
        assertEquals(null, result.files[1].newResource)
        val rename = result.files[2]
        assertEquals(URI.file("C:/workspace/old.txt"), rename.oldResource)
        assertEquals(URI.file("C:/workspace/new.txt"), rename.newResource)
        assertEquals(true, rename.options?.overwrite)
        assertEquals(true, rename.options?.copy)
        assertEquals("aGVsbG8=", rename.options?.contents)
        assertEquals(true, rename.metadata?.isRefactoring)
    }

    @Test
    fun `filesystem paths decode drive UNC and escaped characters`() {
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        fun separators(path: String) = if (windows) path.replace('/', '\\') else path
        assertEquals(separators("c:/workspace/a b.txt"), URI.parse("file:///C:/workspace/a%20b.txt").fsPath)
        assertEquals(separators("//server/share/a b.txt"), URI.parse("file://server/share/a%20b.txt").fsPath)
        assertEquals(separators("/workspace/a b.txt"), URI.parse("file:///workspace/a%20b.txt").fsPath)
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
