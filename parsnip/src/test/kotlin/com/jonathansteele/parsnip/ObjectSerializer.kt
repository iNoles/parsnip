package com.jonathansteele.parsnip

import com.jonathansteele.parsnip.classes.*
import org.junit.Assert
import org.junit.Test

class ObjectSerializer {
    @Test
    fun checkForEmptyObject() {
        val parsnip = Parsnip.Builder().build()
        val emptyObjectAdapter = parsnip.adapter(EmptyObject::class.java)
        val emptyObject = emptyObjectAdapter.toXml(EmptyObject())
        Assert.assertEquals("<EmptyObject/>", emptyObject)
    }

    @Test
    fun checkForPrimitiveAttributeObject() {
        val parsnip = Parsnip.Builder().build()
        val adapter = parsnip.adapter(PrimitiveObject::class.java)
        val primitiveObject = PrimitiveObject(
            boolean = true,
            byte = 1,
            char = 'a',
            double = 1.0,
            float = 1.0f,
            int = 1,
            long = 1L,
            short = 1
        )
        val result = adapter.toXml(primitiveObject)
        Assert.assertEquals("<PrimitiveObject boolean=\"true\" byte=\"1\" char=\"a\" double=\"1.0\" float=\"1.0\" int=\"1\" long=\"1\" short=\"1\"/>", result)
    }

    @Test
    fun checkStringObject() {
        val parsnip = Parsnip.Builder().build()
        val adapter = parsnip.adapter(StringObject::class.java)
        val stringObject = StringObject(string1 = "test", string2 = null)
        val result = adapter.toXml(stringObject)
        Assert.assertEquals("<StringObject string1=\"test\"/>", result)
    }

    @Test
    fun checkEnumObject() {
        val parsnip = Parsnip.Builder().build()
        val adapter = parsnip.adapter(EnumObject::class.java)
        val enumObject = EnumObject(enum1 = TestEnum.One, enum2 = TestEnum.Two)
        val result = adapter.toXml(enumObject)
        Assert.assertEquals("<EnumObject enum1=\"One\" enum2=\"Two\"/>", result)
    }

    @Test
    fun checkTextObject() {
        val parsnip = Parsnip.Builder().build()
        val adapter = parsnip.adapter(TextObject::class.java)
        val textObject = TextObject(text = "test")
        val result = adapter.toXml(textObject)
        Assert.assertEquals("<TextObject>test</TextObject>", result)
    }

    @Test
    fun checkTagObject() {
        val parsnip = Parsnip.Builder().build()
        val adapter = parsnip.adapter(TagObject::class.java)
        val tagObject = TagObject(text = "test", items = listOf("test1", "test2"))
        val result = adapter.toXml(tagObject)
        Assert.assertEquals("<TagObject><text>test</text><item>test1</item><item>test2</item></TagObject>", result)
    }

    @Test
    fun checkNestedObject() {
        val parsnip = Parsnip.Builder().build()
        val adapter = parsnip.adapter(NestedObject::class.java)
        val nestedObject = NestedObject(StringObject(string1 = "test", string2 = null))
        val result = adapter.toXml(nestedObject)
        Assert.assertEquals("<NestedObject><nested string1=\"test\"/></NestedObject>", result)
    }

    @Test
    fun checkNamespaceObject() {
        val parsnip = Parsnip.Builder().build()
        val adapter = parsnip.adapter(NamespaceObject::class.java)
        val namespaceObject = NamespaceObject("value", StringObject("value", null), listOf(StringObject("test1", null), StringObject("test2", null)))
        val result = adapter.toXml(namespaceObject)
        Assert.assertEquals("<NestedObject><nested string1=\"test\"/></NestedObject>", result)
    }

    @Test
    fun checkSameNameObject() {
        val parsnip = Parsnip.Builder().build()
        val adapter = parsnip.adapter(SameNameObject::class.java)
        val sameNameObject = SameNameObject("value", StringObject("value", null))
        val result = adapter.toXml(sameNameObject)
        Assert.assertEquals("<SameNameObject name=\"value\"><name string1=\"value\"/></SameNameObject>", result)
    }
}