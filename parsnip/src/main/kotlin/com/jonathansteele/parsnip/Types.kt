/*
 * Copyright (C) 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jonathansteele.parsnip

import com.jonathansteele.parsnip.annotations.XmlClass
import java.lang.reflect.*
import java.util.*

object Types {
    /**
     * Resolves the generated [XmlAdapter] fully qualified class name for a given [clazz]. This is the same lookup logic
     * used by both the Parsnip code generation as well as lookup for any XmlClass-annotated classes. This can be useful
     * if generating your own XmlAdapters without using Parsnip's first party code gen.
     *
     * @param clazz the class to calculate a generated XmlAdapter name for.
     * @return the resolved fully qualified class name to the expected generated XmlAdapter class.
     * Note that this name will always be a top-level class name and not a nested class.
     */
    @JvmStatic
    fun generatedXmlAdapterName(clazz: Class<*>): String {
        if (clazz.getAnnotation(XmlClass::class.java) == null) {
            throw IllegalArgumentException("Class does not have a XmlClass annotation: $clazz")
        }
        return generatedXmlAdapterName(clazz.name)
    }

    /**
     * Resolves the generated [XmlAdapter] fully qualified class name for a given [ ] `className`.
     * This is the same lookup logic used by both the Parsnip code generation as well as lookup for any
     * xmlClass-annotated classes. This can be useful if generating your own XmlAdapters without using Parsnip's
     * first party code gen.
     *
     * @param className the fully qualified class to calculate a generated XmlAdapter name for.
     * @return the resolved fully qualified class name to the expected generated XmlAdapter class.
     * Note that this name will always be a top-level class name and not a nested class.
     */
    @JvmStatic
    fun generatedXmlAdapterName(className: String): String = className.replace("$", "_") + "XmlAdapter"

    /**
     * Returns a new parameterized type, applying `typeArguments` to `rawType`. Use this
     * method if `rawType` is not enclosed in another type.
     */
    @JvmStatic
    fun newParameterizedType(rawType: Type, vararg typeArguments: Type): ParameterizedType {
        require(typeArguments.isNotEmpty()) {
            "Missing type arguments for $rawType"
        }
        return ParameterizedTypeImpl(null, rawType, *typeArguments)
    }

    /** Returns an array type whose elements are all instances of `componentType`. */
    @JvmStatic
    fun arrayOf(componentType: Type): GenericArrayType = GenericArrayTypeImpl(componentType)

    /**
     * Returns a type that represents an unknown type that extends `bound`. For example, if
     * `bound` is `CharSequence.class`, this returns `? extends CharSequence`. If
     * `bound` is `Object.class`, this returns `?`, which is shorthand for `?
     * extends Object`.
     */
    @JvmStatic
    fun subtypeOf(bound: Type): WildcardType {
        val upperBounds = if (bound is WildcardType) {
            bound.upperBounds
        } else {
            arrayOf<Type>(bound)
        }
        return WildcardTypeImpl(upperBounds, EMPTY_TYPE_ARRAY)
    }

    /**
     * Returns a type that represents an unknown supertype of `bound`. For example, if `bound` is `String.class`, this returns `? super String`.
     */
    @JvmStatic
    fun supertypeOf(bound: Type): WildcardType {
        val lowerBounds = if (bound is WildcardType) {
            bound.lowerBounds
        } else {
            arrayOf<Type>(bound)
        }
        return WildcardTypeImpl(arrayOf<Type>(Any::class.java), lowerBounds)
    }

    @JvmStatic
    fun getRawType(type: Type?): Class<*> {
        return when (type) {
            is Class<*> -> {
                // type is a normal class.
                type
            }
            is ParameterizedType -> {
                // I'm not exactly sure why getRawType() returns Type instead of Class. Neal isn't either but
                // suspects some pathological case related to nested classes exists.
                val rawType = type.rawType
                rawType as Class<*>
            }
            is GenericArrayType -> {
                val componentType = type.genericComponentType
                java.lang.reflect.Array.newInstance(getRawType(componentType), 0).javaClass
            }
            is TypeVariable<*> -> {
                // We could use the variable's bounds, but that won't work if there are multiple. having a raw
                // type that's more general than necessary is okay.
                Any::class.java
            }
            is WildcardType -> getRawType(type.upperBounds[0])
            else -> {
                val className = if (type == null) "null" else type.javaClass.name
                throw IllegalArgumentException("Expected a Class, ParameterizedType, or GenericArrayType, but <$type> is of type $className")
            }
        }
    }

    /**
     * Returns the element type of this collection type.
     *
     * @throws IllegalArgumentException if this type is not a collection.
     */
    @JvmStatic
    fun collectionElementType(context: Type, contextRawType: Class<*>): Type {
        var collectionType: Type? = getSupertype(context, contextRawType, MutableCollection::class.java)
        if (collectionType is WildcardType) {
            collectionType = collectionType.upperBounds[0]
        }
        return if (collectionType is ParameterizedType) {
            collectionType.actualTypeArguments[0]
        } else Any::class.java
    }

    /** Returns true if `a` and `b` are equal. */
    @JvmStatic
    fun equals(a: Type?, b: Type?): Boolean {
        if (a === b) {
            return true // Also handles (a == null && b == null).
        }
        // This isn't a supported type.
        when (a) {
            is Class<*> -> {
                return if (b is GenericArrayType) {
                    equals(a.componentType, b.genericComponentType)
                } else {
                    a == b // Class already specifies equals().
                }
            }
            is ParameterizedType -> {
                if (b !is ParameterizedType) return false
                val aTypeArguments = if (a is ParameterizedTypeImpl) a.typeArguments else a.actualTypeArguments
                val bTypeArguments = if (b is ParameterizedTypeImpl) b.typeArguments else b.actualTypeArguments
                return (
                        equals(a.ownerType, b.ownerType) &&
                                (a.rawType == b.rawType) && aTypeArguments.contentEquals(bTypeArguments)
                        )
            }
            is GenericArrayType -> {
                if (b is Class<*>) {
                    return equals(b.componentType, a.genericComponentType)
                }
                if (b !is GenericArrayType) return false
                return equals(a.genericComponentType, b.genericComponentType)
            }
            is WildcardType -> {
                if (b !is WildcardType) return false
                return (a.upperBounds.contentEquals(b.upperBounds) && a.lowerBounds.contentEquals(b.lowerBounds))
            }
            is TypeVariable<*> -> {
                if (b !is TypeVariable<*>) return false
                return (a.genericDeclaration === b.genericDeclaration && (a.name == b.name))
            }
            else -> return false // This isn't a supported type.
        }
    }

    /**
     * Returns a two element array containing this map's key and value types in positions 0 and 1
     * respectively.
     */
    @JvmStatic
    fun mapKeyAndValueTypes(context: Type, contextRawType: Class<*>): Array<Type> {
        // Work around a problem with the declaration of java.util.Properties. That class should extend
        // Hashtable<String, String>, but it's declared to extend Hashtable<Object, Object>.
        if (context === Properties::class.java) return arrayOf(String::class.java, String::class.java)
        val mapType = getSupertype(context, contextRawType, MutableMap::class.java)
        if (mapType is ParameterizedType) {
            return mapType.actualTypeArguments
        }
        return arrayOf(Any::class.java, Any::class.java)
    }

    /**
     * Returns the generic form of `supertype`. For example, if this is `ArrayList<String>`, this returns `Iterable<String>` given the input `Iterable.class`.
     *
     * @param supertype a superclass of, or interface implemented by, this.
     */
    @JvmStatic
    fun getSupertype(context: Type, contextRawType: Class<*>, supertype: Class<*>): Type {
        if (!supertype.isAssignableFrom(contextRawType)) throw IllegalArgumentException()
        return resolve((context), (contextRawType), getGenericSupertype(context, contextRawType, supertype))
    }

    @JvmStatic
    fun getGenericSuperclass(type: Type): Type {
        val rawType = getRawType(type)
        return resolve(type, rawType, rawType.genericSuperclass)
    }

    @JvmStatic
    fun resolve(
        context: Type,
        contextRawType: Class<*>,
        parameterResolve: Type
    ): Type {
        // This implementation is made a little more complicated in an attempt to avoid object-creation.
        var toResolve = parameterResolve
        while (true) {
            when {
                toResolve is TypeVariable<*> -> {
                    val typeVariable = toResolve
                    toResolve = resolveTypeVariable(context, contextRawType, typeVariable)
                    if (toResolve === typeVariable) return toResolve
                }
                toResolve is Class<*> && toResolve.isArray -> {
                    val original = toResolve
                    val componentType: Type = original.componentType
                    val newComponentType = resolve(context, contextRawType, componentType)
                    return if (componentType === newComponentType) original else newComponentType.asArrayType()
                }
                toResolve is GenericArrayType -> {
                    val original = toResolve
                    val componentType = original.genericComponentType
                    val newComponentType = resolve(context, contextRawType, componentType)
                    return if (componentType === newComponentType) original else newComponentType.asArrayType()
                }
                toResolve is ParameterizedType -> {
                    val original = toResolve
                    val ownerType: Type? = original.ownerType
                    val newOwnerType = ownerType?.let {
                        resolve(context, contextRawType, it)
                    }
                    var changed = newOwnerType !== ownerType
                    var args = original.actualTypeArguments
                    for (t in args.indices) {
                        val resolvedTypeArgument = resolve(context, contextRawType, args[t])
                        if (resolvedTypeArgument !== args[t]) {
                            if (!changed) {
                                args = args.clone()
                                changed = true
                            }
                            args[t] = resolvedTypeArgument
                        }
                    }
                    return if (changed) ParameterizedTypeImpl(newOwnerType, original.rawType, *args) else original
                }
                toResolve is WildcardType -> {
                    val original = toResolve
                    val originalLowerBound = original.lowerBounds
                    val originalUpperBound = original.upperBounds
                    if (originalLowerBound.size == 1) {
                        val lowerBound = resolve(context, contextRawType, originalLowerBound[0])
                        if (lowerBound !== originalLowerBound[0]) {
                            return supertypeOf(lowerBound)
                        }
                    } else if (originalUpperBound.size == 1) {
                        val upperBound = resolve(context, contextRawType, originalUpperBound[0])
                        if (upperBound !== originalUpperBound[0]) {
                            return subtypeOf(upperBound)
                        }
                    }
                    return original
                }
                else -> return toResolve
            }
        }
    }
}