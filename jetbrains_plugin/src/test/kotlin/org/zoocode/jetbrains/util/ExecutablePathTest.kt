package org.zoocode.jetbrains.util

import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExecutablePathTest {
    @get:Rule val temporary = TemporaryFolder()

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

    @Test fun `invalid names absent PATH and relative entries are rejected`() {
        assertNull(findExecutableOnPath("node", null, false))
        assertNull(findExecutableOnPath("", temporary.root.path, false))
        assertNull(findExecutableOnPath("../node", temporary.root.path, false))
        assertNull(findExecutableOnPath("dir\\node", temporary.root.path, true))
        assertNull(findExecutableOnPath("node", ":.:relative", false))
    }
}
