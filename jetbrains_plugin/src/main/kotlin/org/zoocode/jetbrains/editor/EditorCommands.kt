// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.editor

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DiffContent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import org.zoocode.jetbrains.commands.CommandRegistry
import org.zoocode.jetbrains.commands.ICommand
import org.zoocode.jetbrains.util.URI
import org.zoocode.jetbrains.util.URIComponents
import java.io.File

/**
 * Registers commands related to editor API operations
 * Currently registers the workbench diff command for file comparison
 *
 * @param project The current IntelliJ project
 * @param registry The command registry to register commands with
 */
fun registerOpenEditorAPICommands(project: Project,registry: CommandRegistry) {

    registry.registerCommand(
        object : ICommand{
            override fun getId(): String {
                return "_workbench.diff"
            }
            override fun getMethod(): String {
                return "workbench_diff"
            }

            override fun handler(): Any {
                return OpenEditorAPICommands(project)
            }

            override fun returns(): String? {
                return "void"
            }

        }
    )
}

/**
 * Handles editor API commands for operations like opening diff editors
 */
class OpenEditorAPICommands(val project: Project) {
    private val logger = Logger.getInstance(OpenEditorAPICommands::class.java)

    /**
     * Opens a diff editor to compare two files
     *
     * @param left Map containing URI components for the left file
     * @param right Map containing URI components for the right file
     * @param title Optional title for the diff editor
     * @param columnOrOptions Wire `[column, options]` tuple, a direct options map, or null
     * @return null after operation completes
     */
    suspend fun workbench_diff(left: Map<String, Any?>, right : Map<String, Any?>, title : String?,columnOrOptions : Any?): Any?{
        val preserveFocus = decodeDiffPreserveFocus(columnOrOptions)
        val rightURI = createURI(right)
        val leftURI = createURI(left)
        logger.info("Opening diff, preserveFocus=$preserveFocus")
        val content1 = createContent(left,project)
        val content2 = createContent(right,project)
        if (content1 != null && content2 != null){
            project.getService(EditorAndDocManager::class.java)
                .openDiffEditor(leftURI, rightURI, title ?: "File Comparison", preserveFocus)
        }
        logger.info("Opening diff completed")
        return null
    }

    /**
     * Creates a DiffContent object from URI components
     *
     * @param uri Map containing URI components
     * @param project The current IntelliJ project
     * @return DiffContent object or null if creation fails
     */
    fun createContent(uri: Map<String, Any?>, project: Project) : DiffContent?{
        val path = uri["path"]
        val scheme = uri["scheme"]
        val query = uri["query"]
        val fragment = uri["fragment"]
        if(scheme != null){
            val contentFactory = DiffContentFactory.getInstance()
            if(scheme == "file"){
                val vfs = LocalFileSystem.getInstance()
                val fileIO = File(path as String)
                if(!fileIO.exists()){
                    fileIO.createNewFile()
                    vfs.refreshIoFiles(listOf(fileIO.parentFile))
                }

                val file = vfs.refreshAndFindFileByPath(path as String) ?: run {
                    logger.warn("File not found: $path")
                    return null
                }
                return contentFactory.create(project, file)
            }else if(scheme == "cline-diff"){
                val string = if(query != null){
                    val bytes = java.util.Base64.getDecoder().decode(query as String)
                    String(bytes)
                }else ""
                val content = contentFactory.create(project, string)
                return content
            }
            return null
        }else{
            return null
        }
    }
}

/**
 * Decodes the preserveFocus display option.
 *
 * The upstream contract treats a missing flag as false, which requests editor
 * focus. Only a real boolean changes the intent. A malformed value keeps that
 * default and emits a content-free diagnostic, because the value itself is
 * never safe to log.
 *
 * @param options Display options from the extension host, or null
 * @return true when the caller asked to keep focus away from the editor
 */
fun decodePreserveFocus(options: Any?): Boolean {
    if (options !is Map<*, *>) return false
    if (!options.containsKey("preserveFocus")) return false
    val value = options["preserveFocus"]
    if (value is Boolean) return value
    Logger.getInstance("org.zoocode.jetbrains.editor.FocusIntent")
        .warn("Ignored malformed preserveFocus display option and used the default")
    return false
}

/**
 * vscode.diff is translated by ExtHostApiCommands to _workbench.diff with a
 * `[column, options]` tuple. Decoding the tuple itself as a map loses preservation.
 * A direct options map is also accepted; absent or malformed values use false.
 */
fun decodeDiffPreserveFocus(columnOrOptions: Any?): Boolean = decodePreserveFocus(
    if (columnOrOptions is List<*>) columnOrOptions.getOrNull(1) else columnOrOptions
)

/**
 * Maps the preservation intent to the platform focus boolean. The
 * [FileEditorManager.openFile] boolean requests editor focus, so a preserving
 * open must request the inverse. Both open calls in [EditorAndDocManager] pass
 * through this mapping.
 */
fun editorFocusRequest(preserveFocus: Boolean): Boolean = !preserveFocus

/**
 * Routing decision for a diff open. A preserving open cannot use the baseline
 * diff helper, because it ignores its focus argument and closes the right-hand
 * file first; it opens the chain virtual file through the file editor with
 * focus false instead. A deliberate open keeps the diff helper behavior.
 */
internal enum class DiffOpenPlan {
    PRESERVE_VIA_FILE_EDITOR,
    DELIBERATE_VIA_DIFF_HELPER
}

internal fun diffOpenPlan(preserveFocus: Boolean): DiffOpenPlan =
    if (preserveFocus) DiffOpenPlan.PRESERVE_VIA_FILE_EDITOR else DiffOpenPlan.DELIBERATE_VIA_DIFF_HELPER

/**
 * Creates a URI object from a map of URI components
 *
 * @param map Map containing URI components (scheme, authority, path, query, fragment)
 * @return URI object constructed from the components
 */
fun createURI(map: Map<String, Any?>): URI {
    val authority = if (map["authority"] != null) map["authority"] as String else ""
    val query = if (map["query"] != null) map["query"] as String else ""
    val fragment = if (map["fragment"] != null) map["fragment"] as String else ""

    val uriComponents = object : URIComponents {
        override val scheme: String = map["scheme"] as String
        override val authority: String = authority
        override val path: String = map["path"] as String
        override val query: String = query
        override val fragment: String = fragment
    }
    return URI.from(uriComponents)
}
