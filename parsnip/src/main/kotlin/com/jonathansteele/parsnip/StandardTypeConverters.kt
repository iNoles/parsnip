/*
 * Copyright 2015 Evan Tatarka.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jonathansteele.parsnip

import com.jonathansteele.parsnip.annotations.SerializedName

@Suppress("UNCHECKED_CAST")
internal object StandardTypeConverters {
    @JvmField
    val FACTORY = TypeConverter.Factory { type, _ ->
       return@Factory when {
            type === Boolean::class.javaPrimitiveType -> BOOLEAN_TYPE_CONVERTER
            type === Byte::class.javaPrimitiveType -> BYTE_TYPE_CONVERTER
            type === Char::class.javaPrimitiveType -> CHARACTER_TYPE_CONVERTER
            type === Double::class.javaPrimitiveType -> DOUBLE_TYPE_CONVERTER
            type === Float::class.javaPrimitiveType -> FLOAT_TYPE_CONVERTER
            type === Int::class.javaPrimitiveType -> INTEGER_TYPE_CONVERTER
            type === Long::class.javaPrimitiveType -> LONG_TYPE_CONVERTER
            type === Short::class.javaPrimitiveType -> SHORT_TYPE_CONVERTER
            type === Boolean::class.java -> BOOLEAN_TYPE_CONVERTER
            type === Byte::class.java -> BYTE_TYPE_CONVERTER
            type === Char::class.java -> CHARACTER_TYPE_CONVERTER
            type === Double::class.java -> DOUBLE_TYPE_CONVERTER
            type === Float::class.java -> FLOAT_TYPE_CONVERTER
            type === Int::class.java ->  INTEGER_TYPE_CONVERTER
            type === Long::class.java -> LONG_TYPE_CONVERTER
            type === Short::class.java -> SHORT_TYPE_CONVERTER
            type === String::class.java -> STRING_TYPE_CONVERTER
            else -> {
                val rawType = Types.getRawType(type)
                if (rawType.isEnum) {
                    return@Factory EnumTypeConverter(rawType as Class<out Enum<*>>)
                } else null
            }
       }
    }

    private const val ERROR_FORMAT = "Expected %s but was %s"

    @Throws(XmlDataException::class)
    private fun rangeCheckInt(strValue: String, typeMessage: String, min: Int, max: Int): Int {
        val value = strValue.toInt()
        if (value < min || value > max) {
            throw XmlDataException(String.format(ERROR_FORMAT, typeMessage, value))
        }
        return value
    }

    private val BOOLEAN_TYPE_CONVERTER: TypeConverter<Boolean> = object : TypeConverter<Boolean> {
        override fun from(value: String): Boolean = value.toBoolean()

        override fun to(value: Boolean): String = value.toString()
    }

    private val BYTE_TYPE_CONVERTER: TypeConverter<Byte> = object : TypeConverter<Byte> {
        override fun from(value: String): Byte =
            rangeCheckInt(value, "a byte", Byte.MIN_VALUE.toInt(), 0xFF).toByte()

        override fun to(value: Byte): String = value.toString()
    }

    private val CHARACTER_TYPE_CONVERTER: TypeConverter<Char> = object : TypeConverter<Char> {
        override fun from(value: String): Char {
            if (value.length > 1) {
                throw XmlDataException(String.format(ERROR_FORMAT, "a char", '"'.toString() + value + '"'))
            }
            return value[0]
        }

        override fun to(value: Char): String = value.toString()
    }

    private val DOUBLE_TYPE_CONVERTER: TypeConverter<Double> = object : TypeConverter<Double> {
        override fun from(value: String): Double = value.toDouble()

        override fun to(value: Double): String = value.toString()
    }

    private val FLOAT_TYPE_CONVERTER: TypeConverter<Float> = object : TypeConverter<Float> {
        override fun from(value: String): Float = value.toFloat()

        override fun to(value: Float): String = value.toString()
    }

    private val INTEGER_TYPE_CONVERTER: TypeConverter<Int> = object : TypeConverter<Int> {
        override fun from(value: String): Int = value.toInt()

        override fun to(value: Int): String = value.toString()
    }

    private val LONG_TYPE_CONVERTER: TypeConverter<Long> = object : TypeConverter<Long> {
        override fun from(value: String): Long = value.toLong()

        override fun to(value: Long): String = value.toString()
    }

    private val SHORT_TYPE_CONVERTER: TypeConverter<Short> = object : TypeConverter<Short> {
        override fun from(value: String): Short =
            rangeCheckInt(value, "a short", Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

        override fun to(value: Short): String = value.toString()
    }

    private val STRING_TYPE_CONVERTER: TypeConverter<String> = object : TypeConverter<String> {
        override fun from(value: String): String = value

        override fun to(value: String): String = value
    }

    private class EnumTypeConverter<T : Enum<T>>(enumType: Class<out Enum<*>>) : TypeConverter<T> {
        private val nameConstantMap: Map<String, T>
        private val nameStrings: Array<String?>
        init {
            try {
                val constants = enumType.enumConstants
                nameConstantMap = LinkedHashMap()
                nameStrings = arrayOfNulls(constants.size)
                for (i in constants.indices) {
                    val constant = constants[i]
                    val annotation = enumType.getField(constant.name).getAnnotation(
                        SerializedName::class.java
                    )
                    val name = annotation?.value ?: constant.name
                    nameConstantMap.put(name, constant as T)
                    nameStrings[i] = name
                }
            } catch (e: NoSuchFieldException) {
                throw AssertionError("Missing field in " + enumType.name)
            }
        }

        override fun from(value: String): T {
            val constant = nameConstantMap[value]
            if (constant != null) return constant
            throw XmlDataException("Expected one of " + nameConstantMap.keys + " but was " + value)
        }

        override fun to(value: T): String = nameStrings[value.ordinal]!!
    }
}