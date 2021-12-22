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

import kotlin.Throws
import java.io.IOException
import java.util.LinkedHashSet
import java.lang.IllegalArgumentException
import com.jonathansteele.parsnip.annotations.Tag
import java.lang.reflect.Type

/**
 * If there is a [Tag] annotation, this will delegate to just the text of the tag. This is
 * useful for handling xml tags without attributes.
 */
class TagXmlAdapter<T>(private val converter: TypeConverter<T>) : XmlAdapter<T>() {
    override fun fromXml(reader: XmlReader): T = converter.from(reader.nextText())

    @Throws(IOException::class)
    override fun toXml(writer: XmlWriter, value: T) {
        writer.text(converter.to(value))
    }

    companion object {
        @JvmField
        val FACTORY = Factory { type: Type?, annotations: Set<Annotation?>?, adapters: XmlAdapters? ->
            if (Util.isAnnotationPresent(annotations, Tag::class.java)) {
                return@Factory null
            }
            val restOfAnnotations: MutableSet<Annotation?> = LinkedHashSet()
            annotations?.forEach { annotation ->
                if (annotation !is Tag) {
                    restOfAnnotations.add(annotation)
                }
            }
            val converter = adapters?.converter<Any>(type, restOfAnnotations)
                ?: throw IllegalArgumentException("No TypeConverter for type $type and annotations $restOfAnnotations")
            TagXmlAdapter(converter)
        }
    }
}