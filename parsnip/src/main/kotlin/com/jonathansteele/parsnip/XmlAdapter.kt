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
import okio.BufferedSource
import okio.BufferedSink
import java.lang.AssertionError
import okio.Buffer
import java.lang.reflect.Type

abstract class XmlAdapter<T> {
    @Throws(IOException::class)
    abstract fun fromXml(reader: XmlReader): T

    @Throws(IOException::class)
    fun fromXml(source: BufferedSource): T {
        return fromXml(XmlReader(source))
    }

    @Throws(IOException::class)
    fun fromXml(string: String): T {
        return fromXml(Buffer().writeUtf8(string))
    }

    @Throws(IOException::class)
    abstract fun toXml(writer: XmlWriter, value: T)

    @Throws(IOException::class)
    fun toXml(sink: BufferedSink, value: T) {
        toXml(XmlWriter(sink), value)
    }

    fun toXml(value: T): String {
        val buffer = Buffer()
        try {
            toXml(buffer, value)
        } catch (e: IOException) {
            throw AssertionError(e) // No I/O writing to a Buffer.
        }
        return buffer.readUtf8()
    }

    fun interface Factory {
        fun create(type: Type, annotations: Set<Annotation>, adapters: XmlAdapters): XmlAdapter<*>?
    }
}