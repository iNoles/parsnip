package com.jonathansteele.parsnip

import com.jonathansteele.parsnip.classes.PrimitiveObject
import org.junit.Assert
import org.junit.Before
import org.junit.Test

class PrimitiveObjectTest {
    private lateinit var primitiveObject: PrimitiveObject

    @Before
    fun beforeTest() {
        val parsnip = Parsnip.Builder().build()
        val adapter = parsnip.adapter(PrimitiveObject::class.java)
        primitiveObject = adapter.fromXml("<PrimitiveObject boolean=\"true\" byte=\"1\" char=\"a\" double=\"1.0\" float=\"1.0\" int=\"1\" long=\"1\" short=\"1\" />")
    }

    @Test
    fun createObject() {
        Assert.assertNotNull(primitiveObject)
    }

    @Test
    fun setBooleanField() {
        Assert.assertEquals(true, primitiveObject.boolean)
    }

    @Test
    fun setByteField() {
        Assert.assertEquals(1.toByte(), primitiveObject.byte)
    }

    @Test
    fun setCharField() {
        Assert.assertEquals('a', primitiveObject.char)
    }

    @Test
    fun setDoubleField() {
        Assert.assertEquals(1.0, primitiveObject.double, 0.1)
    }

    @Test
    fun setFloatField() {
        Assert.assertEquals(1.0F, primitiveObject.float)
    }

    @Test
    fun setIntField() {
        Assert.assertEquals(1, primitiveObject.int)
    }

    @Test
    fun setLongField() {
        Assert.assertEquals(1L, primitiveObject.long)
    }

    @Test
    fun setShortField() {
        Assert.assertEquals(1.toShort(), primitiveObject.short)
    }
}
