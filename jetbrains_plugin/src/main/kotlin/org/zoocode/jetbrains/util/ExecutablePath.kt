package org.zoocode.jetbrains.util

import java.io.File

/** PATH lookup shared by Java 17 and newer runtimes; never searches the current directory. */
internal fun findExecutableOnPath(
    name: String,
    path: String?,
    windows: Boolean,
    pathExt: String? = null
): File? {
    if (path == null || name.isBlank() || name.any { it == '/' || it == '\\' }) return null
    val extensions = if (windows) {
        pathExt?.split(';')?.filter { it.startsWith('.') && it.length > 1 }
            ?.takeIf { it.isNotEmpty() } ?: listOf(".COM", ".EXE", ".BAT", ".CMD")
    } else emptyList()
    val names = if (windows && extensions.none { name.endsWith(it, ignoreCase = true) }) {
        extensions.map { name + it }
    } else listOf(name)
    for (entry in path.split(if (windows) ';' else ':')) {
        val directory = File(entry.removeSurrounding("\""))
        if (!directory.isAbsolute || !directory.isDirectory) continue
        for (candidate in names) {
            val file = File(directory, candidate)
            if (file.isFile && file.canExecute()) return file
        }
    }
    return null
}
