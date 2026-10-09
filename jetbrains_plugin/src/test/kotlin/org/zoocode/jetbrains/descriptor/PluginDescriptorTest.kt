// SPDX-FileCopyrightText: 2025 Weibo, Inc.
//
// SPDX-License-Identifier: Apache-2.0

package org.zoocode.jetbrains.descriptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class PluginDescriptorTest {

    private val metaInf: File = sequenceOf(
        File("src/main/resources/META-INF"),
        File("jetbrains_plugin/src/main/resources/META-INF")
    ).first { it.isDirectory }

    private val pluginXml by lazy {
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(metaInf, "plugin.xml"))
    }

    private fun dependsNodes(): List<Pair<String, Map<String, String>>> {
        val nodes = pluginXml.getElementsByTagName("depends")
        return (0 until nodes.length).map { index ->
            val node = nodes.item(index)
            val attributes = node.attributes
            val values = (0 until attributes.length).associate {
                attributes.item(it).nodeName to attributes.item(it).nodeValue
            }
            node.textContent.trim() to values
        }
    }

    @Test
    fun jcefDependsStaysOptionalWithExtensionFreeCompanionDescriptor() {
        val optional = dependsNodes().filter { it.second["optional"] == "true" }
        assertEquals(
            "the only optional depends must be the jcef one",
            listOf("com.intellij.modules.jcef"),
            optional.map { it.first }
        )
        val jcef = optional.single()
        val configFile = jcef.second["config-file"]
        assertNotNull("optional depends requires config-file", configFile)
        assertEquals("ZooCode-jcef.xml", configFile)
        assertTrue(
            "companion descriptor $configFile must exist next to plugin.xml",
            File(metaInf, configFile).isFile
        )

        val companion = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(metaInf, configFile))
        assertEquals("idea-plugin", companion.documentElement.nodeName)
        val children = companion.documentElement.childNodes
        for (index in 0 until children.length) {
            assertFalse(
                "companion descriptor must stay extension-free so pre-262 IDEs lose nothing",
                children.item(index) is Element
            )
        }
    }

    @Test
    fun requiredDependsStayMandatory() {
        val ids = dependsNodes().map { it.first }
        assertTrue("descriptor must depend on the platform module", ids.contains("com.intellij.modules.platform"))
        assertTrue("descriptor must depend on the terminal plugin", ids.contains("org.jetbrains.plugins.terminal"))
        dependsNodes().forEach { (id, attributes) ->
            if (id != "com.intellij.modules.jcef") {
                assertFalse("required dependency $id must not be optional", attributes["optional"] == "true")
            }
        }
    }
}
