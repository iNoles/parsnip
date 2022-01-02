package com.jonathansteele.parsnip

import com.jonathansteele.parsnip.classes.*
import org.junit.Assert
import org.junit.Test

class ObjectDeserializer {
    @Test
    fun checkForEmptyObject() {
        val parsnip = Parsnip.Builder().build()
        val emptyObject = parsnip.adapter(EmptyObject::class.java)
        Assert.assertNotNull(emptyObject)
    }

    @Test
    fun checkStringObject() {
        val parsnip = Parsnip.Builder().build()
        val stringObjectAdapter = parsnip.adapter(StringObject::class.java)
        val stringObject = stringObjectAdapter.fromXml("<StringObject string1=\"test\" />")
        Assert.assertEquals("test", stringObject.string1)
        Assert.assertNull(stringObject.string2)
    }

    @Test
    fun checkEnumObject() {
        val parsnip = Parsnip.Builder().build()
        val enumObjectAdapter = parsnip.adapter(EnumObject::class.java)
        val enumObject = enumObjectAdapter.fromXml("<EnumObject enum1=\"One\" enum2=\"Two\" />")
        Assert.assertEquals(TestEnum.One, enumObject.enum1)
        Assert.assertEquals(TestEnum.Two, enumObject.enum2)
    }

    @Test
    fun checkNamedEnumObject() {
        val parsnip = Parsnip.Builder().build()
        val namedEnumObjectAdapter = parsnip.adapter(NamedEnumObject::class.java)
        val namedEnumObject = namedEnumObjectAdapter.fromXml(
            "<NamedEnumObject enum1=\"ONE\" enum2=\"TWO\" />"
        )
        Assert.assertEquals(NamedTestEnum.One, namedEnumObject.enum1)
        Assert.assertEquals(NamedTestEnum.Two, namedEnumObject.enum2)
    }

    @Test
    fun checkTextObject() {
        val parsnip = Parsnip.Builder().build()
        val textObjectAdapter = parsnip.adapter(TextObject::class.java)
        val textObject = textObjectAdapter.fromXml("<TextObject>test</TextObject>")
        Assert.assertEquals("test", textObject.text)
    }

    @Test
    fun checkTagObject() {
        val parsnip = Parsnip.Builder().build()
        val tagObjectAdapter = parsnip.adapter(TagObject::class.java)
        val tagObject = tagObjectAdapter.fromXml(
            "<TagObject><text>test</text><item>test1</item><item>test2</item></TagObject>"
        )
        Assert.assertEquals("test", tagObject.text)
        Assert.assertEquals(listOf("test1", "test2"), tagObject.items)
    }

    @Test
    fun checkNestedObject() {
        val parsnip = Parsnip.Builder().build()
        val nestedObjectAdapter = parsnip.adapter(NestedObject::class.java)
        val nestedObject = nestedObjectAdapter.fromXml(
            "<NestedObject><nested string1=\"test\"/></NestedObject>"
        )
        Assert.assertNotNull(nestedObject)
        Assert.assertEquals("test", nestedObject.nested.string1)
    }

    @Test
    fun checkTagCollections() {
        val parsnip = Parsnip.Builder().build()
        val collectionObjectAdapter = parsnip.adapter(CollectionObject::class.java)
        val collectionObject = collectionObjectAdapter.fromXml(
                "<CollectionObject><StringObject string1=\"test1\"/>" +
                        "<StringObject string1=\"test2\"/>" +
                        "</CollectionObject>"
        )
        Assert.assertEquals(
            listOf(StringObject("test1", null), StringObject("test2", null)),
            collectionObject.item
        )
    }

    @Test
    fun checkNamespaceObject() {
        val parsnip = Parsnip.Builder().build()
        val namespaceObjectAdapter = parsnip.adapter(NamespaceObject::class.java)
        val namespaceObject = namespaceObjectAdapter.fromXml(
            "<NamespaceObject xmlns:ns=\"foo\" ns:attribute=\"value\" attribute=\"notValue\">" +
                "<ns:tag string1=\"test\"/>" +
                "<ns:StringObject string1=\"test1\"/>" +
                "<ns:StringObject string1=\"test2\"/>" +
                "</NamespaceObject>"
        )
        //Assert.assertEquals("value", namespaceObject.attribute)
        Assert.assertEquals(StringObject("test", null), namespaceObject.tag)
        Assert.assertEquals(listOf(StringObject("test1", null), StringObject("test2", null)), namespaceObject.item)
    }

    @Test
    fun checkAttributeAndTagForSameName() {
        val parsnip = Parsnip.Builder().build()
        val sameNameObjectAdapter = parsnip.adapter(SameNameObject::class.java)
        val sameNameObject = sameNameObjectAdapter.fromXml(
                "<SameNameObject name=\"value\"><name string1=\"value\"/></SameNameObject>"
        )
        Assert.assertEquals("value", sameNameObject.attribute)
        Assert.assertEquals(StringObject("value", null), sameNameObject.tag)
    }

    @Test
    fun checkXmlWithoutNamespace() {
        val parsnip = Parsnip.Builder().build()
        val stringObjectAdapter = parsnip.adapter(StringObject::class.java)
        val stringObject = stringObjectAdapter.fromXml(
            "<StringObject xmlns:ns=\"foo\" ns:string1=\"value\"/>"
        )
        Assert.assertEquals("value", stringObject.string1)
    }
  }