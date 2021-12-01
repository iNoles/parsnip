package com.jonathansteele.parsnip

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import java.io.IOException

class XmlReaderTest {
    val xmlReader = { xml: String -> XmlReader(Buffer().writeUtf8(xml)) }

    // Tag
    @Test
    fun checkEmptySelfClosingTag() {
        val reader = xmlReader("<test/>")
        reader.beginTag()
        val tag = reader.nextTagName()
        reader.endTag()

        assertEquals("test", tag)
    }

    @Test
    fun checkEmptySelfClosingTagWithSpace() {
        val reader = xmlReader("< test />")
        reader.beginTag()
        val tag = reader.nextTagName()
        reader.endTag()

        assertEquals("test", tag)
    }

    @Test
    fun checkEmptyTag() {
        val reader = xmlReader("<test></test>")
        reader.beginTag()
        val tag = reader.nextTagName()
        reader.endTag()

        assertEquals("test", tag)
    }

    @Test
    fun checkNestedTag() {
        val reader = xmlReader("<test1><test2/></test1>")
        reader.beginTag()
        val tag1 = reader.nextTagName()
        reader.beginTag()
        val tag2 = reader.nextTagName()
        reader.endTag()
        reader.endTag()

        assertEquals("test1", tag1)
        assertEquals("test2", tag2)
    }

    @Test
    fun checkNestedTagWithSpace() {
        val reader = xmlReader("<test1> <test2/> </test1>")
        reader.beginTag()
        val tag1 = reader.nextTagName()
        reader.beginTag()
        val tag2 = reader.nextTagName()
        reader.endTag()
        reader.endTag()

        assertEquals("test1", tag1)
        assertEquals("test2", tag2)
    }

    @Test
    fun checkTwoNestedTags() {
        val reader = xmlReader("<test1><test2/><test3/></test1>")
        reader.beginTag()
        val tag1 = reader.nextTagName()
        reader.beginTag()
        val tag2 = reader.nextTagName()
        reader.endTag()
        reader.beginTag()
        val tag3 = reader.nextTagName()
        reader.endTag()
        reader.endTag()

        assertEquals("test1", tag1)
        assertEquals("test2", tag2)
        assertEquals("test3", tag3)
    }

    @Test
    fun checkDeclarationBeforeDocument() {
        val reader = xmlReader("<?xml version=\"1.1\"?><test/>")
        reader.beginTag()
        val tag = reader.nextTagName()

        assertEquals("test", tag)
    }

    // Comment
    @Test
    fun checkCommentBetweenTags() {
        val reader = xmlReader("<test><!-- this should be ignored --></test>")
        reader.beginTag()
        val tag = reader.nextTagName()
        reader.endTag()

        assertEquals("test", tag)
    }

    // Text
    @Test
    fun chekTagWithText() {
        val reader = xmlReader("<test>value</test>")
        reader.beginTag()
        reader.nextTagName()
        val text = reader.nextText()

        assertEquals("value", text)
    }

    // Attribute
    @Test
    fun checkTagWithSingleQuotedAttribute() {
        val reader = xmlReader("<test attribute='value'/>")
        reader.beginTag()
        reader.nextTagName()
        val name = reader.nextAttribute()
        val value = reader.nextValue()

        assertEquals("attribute", name)
        assertEquals("value", value)
    }

    @Test
    fun checkTagWithDoubleQuotedAttribute() {
        val reader = xmlReader("<test attribute=\"value\"/>")
        reader.beginTag()
        reader.nextTagName()
        val name = reader.nextAttribute()
        val value = reader.nextValue()

        assertEquals("attribute", name)
        assertEquals("value", value)
    }

    @Test
    fun checkTagWithTwoAttributes() {
        val reader = xmlReader("<test attribute1='value1' attribute2='value2'/>")
        reader.beginTag()
        reader.nextTagName()
        val name1 = reader.nextAttribute()
        val value1 = reader.nextValue()
        val name2 = reader.nextAttribute()
        val value2 = reader.nextValue()

        assertEquals("attribute1", name1)
        assertEquals("value1", value1)
        assertEquals("attribute2", name2)
        assertEquals("value2", value2)
    }

    // Namespace
    @Test
    fun checkTagWithNamespaceName() {
        val reader = xmlReader("<test1 xmlns:ns='foo'><ns:test2/></test1>")
        reader.beginTag()
        reader.nextTagName()
        reader.nextAttribute()
        reader.nextValue()
        reader.beginTag()
        val tag = reader.nextTagName()

        assertEquals("test2", tag)
    }

    @Test
    fun checkTagWithNamespaceAttribute() {
        val reader = xmlReader("<test xmlns:ns='foo' ns:attribute='value'/>")
        reader.beginTag()
        reader.nextTagName()
        reader.nextAttribute()
        val fooValue = reader.nextValue()
        val attribute = reader.nextAttribute()
        val attributeValue = reader.nextValue()

        assertEquals("attribute", attribute)
        assertEquals("foo", fooValue)
        assertEquals("value", attributeValue)
    }

    @Test
    fun checkTagWithAttributeAndDuplicateNamespace() {
        val reader = xmlReader("<test1 xmlns:ns='foo'><test2 xmlns:ns='bar' ns:attribute='value'/></test1>")
        reader.beginTag()
        val test1 = reader.nextTagName()
        assertEquals("test1", test1)
        reader.nextAttribute()
        reader.nextValue()

        reader.beginTag()
        val test2 = reader.nextTagName()
        assertEquals("test2", test2)

        reader.nextAttribute()
        reader.nextValue()

        val attribute = reader.nextAttribute()
        val value = reader.nextValue()
        assertEquals("attribute", attribute)
        assertEquals("value", value)
    }

    @Test
    fun checkTagWithNamespaceAndAttributeWithIncorrectlyUsed() {
        val reader = xmlReader("<test1><test2 xmlns:ns='foo'/><test3 ns:attribute='value'/></test1>")
        reader.beginTag()
        val test1 = reader.nextTagName()
        assertEquals("test1", test1)

        reader.beginTag()
        val test2 = reader.nextTagName()
        assertEquals("test2", test2)
        reader.nextAttribute()
        reader.nextValue()
        reader.endTag()

        reader.beginTag()
        val test3 = reader.nextTagName()
        assertEquals("test3", test3)
        val attribute = reader.nextAttribute()
        val value = reader.nextValue()

        assertEquals("attribute", attribute)
        assertEquals("value", value)
    }

    @Test
    fun checkTagWithDefaultNamespace() {
        val reader = xmlReader("<test1 xmlns='foo'><test2/></test1>")
        reader.beginTag()
        reader.nextTagName()
        reader.nextAttribute()
        reader.nextValue()
        reader.beginTag()
        val tag = reader.nextTagName()

        assertEquals("test2", tag)
    }

    @Test
    fun checkAttributeOnTagWithDefaultNamespace() {
        val reader = xmlReader("<test xmlns='foo' attribute='value'/>")
        reader.beginTag()
        reader.nextTagName()
        reader.nextAttribute()
        reader.nextValue()
        val attribute = reader.nextAttribute()

        assertEquals("attribute", attribute)
    }

    @Test
    fun checkTagWithCDATA() {
        val reader = xmlReader("<test><![CDATA[<a>text</b>]]></test>")
        reader.beginTag()
        reader.nextTagName()
        val text = reader.nextText()

        assertEquals("<a>text</b>", text)
    }

    @Test(expected = IOException::class)
    fun checkIncorrectlyClosedSelfClosingTagForEOF() {
        val reader = xmlReader("<test/")
        reader.beginTag()
        reader.nextTagName()
        reader.endTag()
    }

    @Test(expected = IOException::class)
    fun checkIncorrectlyClosedSelfClosingTagForChar() {
        val reader = xmlReader("<test/!")
        reader.beginTag()
        reader.nextTagName()
        reader.endTag()
    }

    @Test(expected = IOException::class)
    fun checkIncorrectlyClosingEndTagForEOF() {
        val reader = xmlReader("<test></test")
        reader.beginTag()
        reader.nextTagName()
        reader.endTag()
    }

    @Test(expected = IOException::class)
    fun checkIncorrectlyClosingEndTagForName() {
        val reader = xmlReader("<test></nope>")
        reader.beginTag()
        reader.nextTagName()
        reader.endTag()
    }

    @Test(expected = IOException::class)
    fun checkIncorrectlyClosingEndTagForChar() {
        val reader = xmlReader("<test></test!")
        reader.beginTag()
        reader.nextTagName()
        reader.endTag()
    }

    // TODO: This should Fail
    @Test
    @Ignore
    fun checkIncorrectlyDuplicatedAttribute() {
        val reader = xmlReader("<test attribute='value1' attribute='value2/>")
        reader.beginTag()
        reader.nextTagName()
        reader.nextAttribute()
        reader.nextValue()
        reader.nextAttribute()

        //assertEquals("Duplicate attribute 'attribute' at path /test", error?.getMessage())
    }
}