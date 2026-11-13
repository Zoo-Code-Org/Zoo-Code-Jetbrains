package org.zoocode.jetbrains.util

import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExecutablePathTest {
    @get:Rule val temporary = TemporaryFolder(File(".").absoluteFile)

    private fun executable(directory: File, name: String): File = File(directory, name).apply {
        writeText("test executable")
        check(setExecutable(true))
    }

    @Test fun `first executable in PATH wins and the requested name is used`() {
        assumeFalse(File.separatorChar == '\\')
        val first = temporary.newFolder("first")
        val second = temporary.newFolder("second")
        val expected = executable(first, "custom-node")
        executable(second, "custom-node")
        assertEquals(expected, findExecutableOnPath("custom-node", "$first:$second", false))
    }

    @Test fun `missing files directories and non executable files are skipped`() {
        assumeFalse(File.separatorChar == '\\')
        val first = temporary.newFolder("first")
        File(first, "node").writeText("not executable")
        val second = temporary.newFolder("second")
        val expected = executable(second, "node")
        assertEquals(expected, findExecutableOnPath("node", "$first:$second", false))
        assertNull(findExecutableOnPath("missing", "$first:$second", false))
        assertNull(findExecutableOnPath("second", temporary.root.path, false))
    }

    @Test fun `Windows lookup honors PATHEXT order quoted directories and explicit suffixes`() {
        val directory = temporary.newFolder("with spaces")
        val cmd = executable(directory, "node.CMD")
        val exe = executable(directory, "node.EXE")
        assertEquals(cmd, findExecutableOnPath("node", "\"$directory\"", true, ".CMD;.EXE"))
        assertEquals(exe, findExecutableOnPath("node.EXE", directory.path, true, ".CMD;.EXE"))
        assertEquals(exe, findExecutableOnPath("node", directory.path, true))
    }

    @Test fun `relative PATH entries cannot select an existing executable`() {
        val directory = temporary.newFolder("relative-path")
        val expected = executable(directory, "node.EXE")
        val relative = File(".").canonicalFile.toPath()
            .relativize(directory.canonicalFile.toPath()).toString()
        assertFalse(File(relative).isAbsolute)
        assertTrue(File(relative).isDirectory)
        assertEquals(expected, findExecutableOnPath("node.EXE", directory.path, true, ".EXE"))
        assertNull(findExecutableOnPath("node.EXE", relative, true, ".EXE"))
    }

    @Test fun `slash in executable name cannot escape the PATH directory`() {
        val root = temporary.newFolder("traversal")
        val sub = File(root, "sub").apply { mkdir() }
        val expected = executable(root, "node.EXE")
        assertEquals(expected, findExecutableOnPath("node.EXE", root.path, true, ".EXE"))
        assertNull(findExecutableOnPath("../node.EXE", sub.path, true, ".EXE"))
    }

    @Test fun `backslash in executable name cannot select an existing file`() {
        val directory = temporary.newFolder("backslash")
        // Backslash is a directory separator on Windows and a literal filename on Unix.
        val target = File(directory, "dir\\node.EXE")
        target.parentFile.mkdirs()
        executable(target.parentFile, target.name)
        assertTrue(target.isFile && target.canExecute())
        assertNull(findExecutableOnPath("dir\\node.EXE", directory.path, true, ".EXE"))
    }

    @Test fun `Windows PATH uses semicolons and skips missing directories`() {
        val missing = File(temporary.root, "missing")
        val directory = temporary.newFolder("second")
        val expected = executable(directory, "node.EXE")
        assertEquals(expected, findExecutableOnPath("node", "$missing;$directory", true, ".EXE"))
    }

    @Test fun `Windows malformed PATHEXT falls back or ignores entries without a dot`() {
        val directory = temporary.newFolder("extensions")
        val expected = executable(directory, "node.CMD")
        executable(directory, "nodeEXE")
        for (extensions in listOf(null, "", ";;", "EXE;.CMD")) {
            assertEquals(extensions, expected, findExecutableOnPath("node", directory.path, true, extensions))
        }
    }

    @Test fun `Windows default extensions keep their precedence`() {
        val directory = temporary.newFolder("defaults")
        val candidates = listOf(".COM", ".EXE", ".BAT", ".CMD")
            .map { executable(directory, "node$it") }
        for (expected in candidates) {
            assertEquals(expected, findExecutableOnPath("node", directory.path, true))
            assertTrue(expected.delete())
        }
    }

    @Test fun `Windows explicit extension matching ignores case`() {
        val directory = temporary.newFolder("case")
        val expected = executable(directory, "node.exe")
        assertEquals(expected, findExecutableOnPath("node.exe", directory.path, true, ".EXE"))
    }

    @Test fun `absent PATH and blank names are rejected`() {
        assertNull(findExecutableOnPath("node", null, false))
        assertNull(findExecutableOnPath("", temporary.root.path, true))
    }
}
