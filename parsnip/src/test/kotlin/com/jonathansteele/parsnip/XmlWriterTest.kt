package com.jonathansteele.parsnip

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class XmlWriterTest {
    val xmlWriter: ((XmlWriter) -> Unit) -> String = { xml ->
        val buffer = Buffer()
        val writer = XmlWriter(buffer)
        xml(writer)
        buffer.readUtf8()
    }

    @Test
    fun checkEmptyTag() {
        val result = xmlWriter {
            it.beginTag("test").endTag()
        }
        assertEquals("<test/>", result)
    }

    @Test
    fun checkNestedTag() {
        val result = xmlWriter {
            it.beginTag("test1").beginTag("test2").endTag().endTag()
        }
        assertEquals("<test1><test2/></test1>", result)
    }

    @Test
    fun checkTagWithAttribute() {
        val result = xmlWriter {
            it.beginTag("test").attribute("attribute", "value").endTag()
        }
        assertEquals("<test attribute=\"value\"/>", result)
    }

    @Test
    fun checkTagWithTwoAttributes() {
        val result = xmlWriter {
            it.beginTag("test")
                .attribute("attribute1", "value1")
                .attribute("attribute2", "value2")
                .endTag()
        }
        assertEquals("<test attribute1=\"value1\" attribute2=\"value2\"/>", result)
    }

    @Test
    fun checkTagWithAttributeWithNestedTag() {
        val result = xmlWriter {
            it.beginTag("test1")
                .attribute("attribute", "value")
                .beginTag("test2").endTag()
                .endTag()
        }
        assertEquals("<test1 attribute=\"value\"><test2/></test1>", result)
    }

    @Test
    fun checkTagText() {
        val result = xmlWriter {
            it.beginTag("test").text("text").endTag()
        }
        assertEquals("<test>text</test>", result)
    }

    @Test
    fun checkTagWithAttributeAndText() {
        val result = xmlWriter {
            it.beginTag("test")
                .attribute("attribute", "value")
                .text("text")
                .endTag()
        }
        assertEquals("<test attribute=\"value\">text</test>", result)
    }

   @Test
    fun checkTagWithNamespaceDeclaration() {
        val namespace = Namespace("ns", "foo")
        val result = xmlWriter {
            it.beginTag("test").namespace(namespace).endTag()
        }
        assertEquals("<test xmlns:ns=\"foo\"/>", result)
        assertEquals("ns", namespace.alias)
        assertEquals("foo", namespace.namespace)
    }

    @Test
    fun checkTagWithNamespace() {
        val namespace = Namespace("ns", "foo")
        val result = xmlWriter {
            it.beginTag("test").namespace(namespace).endTag()
        }
        assertEquals("<test xmlns:ns=\"foo\"/>", result)
        assertEquals("ns", namespace.alias)
        assertEquals("foo", namespace.namespace)
    }

    @Test
    fun checkTagWithNamespaceAttribute() {
        val namespace = Namespace("ns", "foo")
        val result = xmlWriter {
            it.beginTag("test")
                .namespace(namespace)
                .attribute("attribute", "value")
                .endTag()
        }
        assertEquals("<test xmlns:ns=\"foo\" attribute=\"value\"/>", result)
        assertEquals("ns", namespace.alias)
        assertEquals("foo", namespace.namespace)
    }

    @Test
    fun checkAttributeValueToReplace() {
        val result = xmlWriter {
            it.beginTag("test").attribute("attribute", "\"\'<>&").endTag()
        }
        assertEquals("<test attribute=\"\"'<>&\"/>", result)
    }

    @Test
    fun checkTextCharacterToReplace() {
        val result = xmlWriter {
            it.beginTag("test").text("\"\'<>&").endTag()
        }
        assertEquals("<test>\"'<>&</test>", result)
    }
}