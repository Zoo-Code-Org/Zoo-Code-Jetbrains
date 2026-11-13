package org.zoocode.jetbrains.editor

import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Static integration checks complement queue behavior tests without starting an IDE.
 * Read compiled production methods so deleting a call-site wrapper fails the test.
 */
class EditorRoutingContractTest {
    private data class Call(val callerClass: String, val caller: String, val owner: String, val method: String)

    private fun calls(root: String): List<Call> {
        val calls = mutableListOf<Call>()
        val visited = mutableSetOf<String>()
        fun read(name: String) {
            if (!visited.add(name)) return
            val visitedClass = name
            val nested = mutableListOf<String>()
            val bytes = javaClass.classLoader.getResourceAsStream("$name.class")!!.use { it.readBytes() }
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                    if (name.startsWith(root + "$")) nested.add(name)
                }
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, method: String, descriptor: String, isInterface: Boolean) {
                            calls.add(Call(visitedClass, name, owner, method))
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            nested.forEach { read(it) }
        }
        read(root)
        return calls
    }

    @Test
    fun `production file and deliberate diff opens enter the same queue`() {
        val calls = calls("org/zoocode/jetbrains/editor/EditorAndDocManager")
        for (method in listOf("openFileAsync", "openDiffEditor")) {
            assertTrue("$method must enter EditorOpenQueue", calls.any {
                it.callerClass == "org/zoocode/jetbrains/editor/EditorAndDocManager" &&
                    it.caller == method && it.owner == "org/zoocode/jetbrains/editor/EditorOpenQueue" && it.method == "open"
            })
        }
    }

    @Test
    fun `document creation and post edit refresh use filesystem paths`() {
        for (root in listOf("org/zoocode/jetbrains/actors/MainThreadDocuments", "org/zoocode/jetbrains/editor/EditorHolder")) {
            assertTrue("$root must convert URI to fsPath for java File", calls(root).any {
                val expectedCaller = if (root.endsWith("MainThreadDocuments"))
                    it.callerClass == root && it.caller == "tryOpenDocument"
                else it.callerClass.startsWith(root + "\$applyEdit\$")
                expectedCaller && it.owner == "org/zoocode/jetbrains/util/URI" && it.method == "getFsPath"
            })
        }
    }

    @Test
    fun `bulk file create delete and both rename endpoints use filesystem paths`() {
        val calls = calls("org/zoocode/jetbrains/actors/MainThreadBulkEdits")
        assertEquals("All four file-operation conversions must use fsPath", 4, calls.count {
            it.caller == "tryApplyWorkspaceEdit" &&
                it.owner == "org/zoocode/jetbrains/util/URI" && it.method == "getFsPath"
        })
    }
}
