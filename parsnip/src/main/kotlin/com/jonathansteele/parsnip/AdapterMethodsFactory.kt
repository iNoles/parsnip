/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jonathansteele.parsnip

import java.lang.IllegalArgumentException
import kotlin.Throws
import java.io.IOException
import java.lang.IllegalAccessException
import java.lang.AssertionError
import java.lang.reflect.InvocationTargetException
import com.jonathansteele.parsnip.annotations.ToXml
import com.jonathansteele.parsnip.annotations.FromXml
import java.lang.reflect.Method
import java.lang.reflect.Type
import java.util.ArrayList

internal class AdapterMethodsFactory(
    private val toAdapters: List<AdapterMethod>,
    private val fromAdapters: List<AdapterMethod>
) : XmlAdapter.Factory {
    override fun create(type: Type, annotations: Set<Annotation>, adapters: XmlAdapters): XmlAdapter<*>? {
        val toAdapter = Companion[toAdapters, type, annotations]
        val fromAdapter = Companion[fromAdapters, type, annotations]
        if (toAdapter == null && fromAdapter == null) return null
        val delegate: XmlAdapter<Any>?
        if (toAdapter == null || fromAdapter == null) {
            delegate = adapters.nextAdapter(this, type, annotations)
            if (delegate == null) {
                val missingAnnotation = if (toAdapter == null) "@ToXml" else "@FromXml"
                throw IllegalArgumentException(
                    "No " + missingAnnotation + " adapter for "
                            + type + " annotated " + annotations
                )
            }
        } else {
            delegate = null
        }
        return object : XmlAdapter<Any?>() {
            @Throws(IOException::class)
            override fun fromXml(reader: XmlReader): Any {
                return if (fromAdapter == null) {
                    delegate!!.fromXml(reader)
                } else {
                    try {
                        fromAdapter.fromXml(adapters, reader)
                    } catch (e: IllegalAccessException) {
                        throw AssertionError()
                    } catch (e: InvocationTargetException) {
                        if (e.cause is IOException) throw (e.cause as IOException?)!!
                        throw XmlDataException(e.cause.toString() + " at " + reader.path)
                    }
                }
            }

            @Throws(IOException::class)
            override fun toXml(writer: XmlWriter, value: Any?) {
                if (toAdapter == null) {
                    if (value != null) {
                        delegate!!.toXml(writer, value)
                    }
                } else {
                    try {
                        toAdapter.toXml(adapters, writer, value)
                    } catch (e: IllegalAccessException) {
                        throw AssertionError()
                    } catch (e: InvocationTargetException) {
                        if (e.cause is IOException) throw (e.cause as IOException?)!!
                        throw XmlDataException(e.cause.toString() + " at " + "writer.getPath()")
                    }
                }
            }
        }
    }

    internal abstract class AdapterMethod(
        val type: Type,
        val annotations: Set<Annotation>,
        val adapter: Any,
        val method: Method,
        val nullable: Boolean
    ) {
        @Throws(IOException::class, IllegalAccessException::class, InvocationTargetException::class)
        open fun toXml(adapters: XmlAdapters, writer: XmlWriter?, value: Any?) {
            throw AssertionError()
        }

        @Throws(IOException::class, IllegalAccessException::class, InvocationTargetException::class)
        open fun fromXml(adapters: XmlAdapters, reader: XmlReader?): Any {
            throw AssertionError()
        }
    }

    companion object {
        operator fun get(adapter: Any): AdapterMethodsFactory {
            val toAdapters: MutableList<AdapterMethod> = ArrayList()
            val fromAdapters: MutableList<AdapterMethod> = ArrayList()
            var c: Class<*> = adapter.javaClass
            while (c != Any::class.java) {
                for (m in c.declaredMethods) {
                    if (m.isAnnotationPresent(ToXml::class.java)) {
                        val toAdapter = toAdapter(adapter, m)
                        val conflicting = Companion[toAdapters, toAdapter.type, toAdapter.annotations]
                        checkNull(conflicting) {
                            "Conflicting @ToXml methods:\n    ${it.method}\n    ${toAdapter.method}"
                        }
                        toAdapters.add(toAdapter)
                    }
                    if (m.isAnnotationPresent(FromXml::class.java)) {
                        val fromAdapter = fromAdapter(adapter, m)
                        val conflicting = Companion[fromAdapters, fromAdapter.type, fromAdapter.annotations]
                        checkNull(conflicting) {
                            "Conflicting @FromXml methods:\n    ${it.method}\n    ${fromAdapter.method}"
                        }
                        fromAdapters.add(fromAdapter)
                    }
                }
                c = c.superclass
            }
            require(!(toAdapters.isEmpty() && fromAdapters.isEmpty())) {
                ("Expected at least one @ToXml or @FromXml method on "
                        + adapter.javaClass.name)
            }
            return AdapterMethodsFactory(toAdapters, fromAdapters)
        }

        /**
         * Returns an object that calls a `method` method on `adapter` in service of
         * converting an object to JSON.
         */
        private fun toAdapter(adapter: Any, method: Method): AdapterMethod {
            method.isAccessible = true
            val parameterTypes = method.genericParameterTypes
            val returnType = method.genericReturnType
            return if (parameterTypes.size == 2 && parameterTypes[0] === XmlWriter::class.java && returnType === Void.TYPE) {
                // public void pointToXml(XmlWriter writer, Point point) throws Exception {
                val parameterAnnotations = method.parameterAnnotations[1].xmlAnnotations
                object : AdapterMethod(parameterTypes[1], parameterAnnotations, adapter, method, false) {
                    @Throws(InvocationTargetException::class, IllegalAccessException::class)
                    override fun toXml(adapters: XmlAdapters, writer: XmlWriter?, value: Any?) {
                        method.invoke(adapter, writer, value)
                    }
                }
            } else if (parameterTypes.size == 1 && returnType !== Void.TYPE) {
                // public List<Integer> pointToXml(Point point) throws Exception {
                val returnTypeAnnotations = method.xmlAnnotations
                val parameterAnnotations = method.parameterAnnotations
                val qualifierAnnotations = parameterAnnotations[0].xmlAnnotations
                val nullable = parameterAnnotations[0].hasNullable
                object : AdapterMethod(parameterTypes[0], qualifierAnnotations, adapter, method, nullable) {
                    @Throws(IOException::class, InvocationTargetException::class, IllegalAccessException::class)
                    override fun toXml(adapters: XmlAdapters, writer: XmlWriter?, value: Any?) {
                        val delegate = adapters.adapter<Any>(returnType, returnTypeAnnotations)
                            ?: throw IllegalArgumentException("No XmlAdapter for type $returnType and annotations $returnTypeAnnotations")
                        val intermediate = method.invoke(adapter, value)
                        delegate.toXml(writer!!, intermediate)
                    }
                }
            } else {
                throw IllegalArgumentException(
                    """Unexpected signature for $method.
@ToXml method signatures may have one of the following structures:
    <any access modifier> void toXml(XmlWriter writer, T value) throws <any>;
    <any access modifier> R toXml(T value) throws <any>;
"""
                )
            }
        }

        /**
         * Returns an object that calls a `method` method on `adapter` in service of
         * converting an object from JSON.
         */
        private fun fromAdapter(adapter: Any, method: Method): AdapterMethod {
            method.isAccessible = true
            val parameterTypes = method.genericParameterTypes
            val returnType = method.genericReturnType
            return if (parameterTypes.size == 1 && parameterTypes[0] === XmlReader::class.java && returnType !== Void.TYPE) {
                // public Point pointFromXml(XmlReader xmlReader) throws Exception {
                val returnTypeAnnotations = method.xmlAnnotations
                object : AdapterMethod(returnType, returnTypeAnnotations, adapter, method, false) {
                    @Throws(IllegalAccessException::class, InvocationTargetException::class)
                    override fun fromXml(adapters: XmlAdapters, reader: XmlReader?): Any {
                        return method.invoke(adapter, reader)
                    }
                }
            } else if (parameterTypes.size == 1 && returnType !== Void.TYPE) {
                // public Point pointFromXml(List<Integer> o) throws Exception {
                val returnTypeAnnotations = method.xmlAnnotations
                val parameterAnnotations = method.parameterAnnotations
                val qualifierAnnotations = parameterAnnotations[0].xmlAnnotations
                val nullable = parameterAnnotations[0].hasNullable
                object : AdapterMethod(returnType, returnTypeAnnotations, adapter, method, nullable) {
                    @Throws(IOException::class, IllegalAccessException::class, InvocationTargetException::class)
                    override fun fromXml(adapters: XmlAdapters, reader: XmlReader?): Any {
                        val delegate = adapters.adapter<Any>(parameterTypes[0], qualifierAnnotations)
                            ?: throw IllegalArgumentException("No XmlAdapter for type " + parameterTypes[0] + " and annotations " + qualifierAnnotations)
                        val intermediate = delegate.fromXml(reader!!)
                        return method.invoke(adapter, intermediate)
                    }
                }
            } else {
                throw IllegalArgumentException(
                    """Unexpected signature for $method.
@ToXml method signatures may have one of the following structures:
    <any access modifier> void toXml(XmlWriter writer, T value) throws <any>;
    <any access modifier> R toXml(T value) throws <any>;
"""
                )
            }
        }

        /**
         * Returns the matching adapter method from the list.
         */
        private operator fun get(
            adapterMethods: List<AdapterMethod>, type: Type, annotations: Set<Annotation>
        ): AdapterMethod? {
            for (adapterMethod in adapterMethods) {
                if (adapterMethod.type == type && adapterMethod.annotations == annotations) {
                    return adapterMethod
                }
            }
            return null
        }
    }
}