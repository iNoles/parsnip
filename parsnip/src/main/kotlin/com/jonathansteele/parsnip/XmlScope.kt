/*
 * Copyright (C) 2015 Hannes Dorfmann
 * Copyright (C) 2015 Tickaroo, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.jonathansteele.parsnip

import kotlin.Throws
import java.io.IOException
import java.lang.StringBuilder

/**
 * Lexical scoping elements within a XML reader or writer.
 *
 * @author Hannes Dorfmann
 * @since 1.0
 */
internal object XmlScope {
    /** No object or array has been started.  */
    const val EMPTY_DOCUMENT = 0

    /** A document at least one object  */
    const val NONEMPTY_DOCUMENT = 1
    // /** XML declaration like {@code <?xml version="1.0" encoding="UTF-8"?>} */
    // static final int XML_DECLARATION = 2;
    /** We are in the opening xml tag like `<element>`  */
    const val ELEMENT_OPENING = 3

    /** We are in the scope of reading attributes of a given element  */
    const val ELEMENT_ATTRIBUTE = 4

    /**
     * We are in an element's content (between opening and closing xml element tag) like `<element>HERE WE ARE</element>`
     */
    const val ELEMENT_CONTENT = 5

    /**
     * A document that's been closed and cannot be accessed.
     */
    const val CLOSED = 6

    /**
     * Prints the XmlScope (mainly for debugging) for the element that is on top of the stack
     *
     * @param stackSize The size of the stack
     * @param stack The stack itself
     * @return String representing the XmlScope on top of the stack
     */
    @Throws(IOException::class)
    fun getTopStackElementAsToken(stackSize: Int, stack: IntArray): String {
        return when (stack[stackSize - 1]) {
            ELEMENT_OPENING -> "ELEMENT_OPENING"
            EMPTY_DOCUMENT -> "EMPTY_DOCUMENT"
            NONEMPTY_DOCUMENT -> "NONEMPTY_DOCUMENT"
            ELEMENT_ATTRIBUTE -> "ELEMENT_ATTRIBUTE"
            ELEMENT_CONTENT -> "ELEMENT_CONTENT"
            CLOSED -> "CLOSED"
            else -> throw IOException("Unexpected token on top of the stack. Was " + stack[stackSize - 1])
        }
    }

    /**
     * Renders the path in a JSON document to a string. The `pathNames` and `pathIndices`
     * parameters corresponds directly to stack: At indices where the stack contains an object
     * (EMPTY_OBJECT, DANGLING_NAME or NONEMPTY_OBJECT), pathNames contains the name at this scope.
     * Where it contains an array (EMPTY_ARRAY, NONEMPTY_ARRAY) pathIndices contains the current index
     * in that array. Otherwise the value is undefined, and we take advantage of that by incrementing
     * pathIndices when doing so isn't useful.
     */
    fun getPath(stackSize: Int, stack: IntArray, pathNames: Array<String?>, pathIndices: IntArray?): String {
        val result = StringBuilder()
        for (i in 0 until stackSize) {
            when (stack[i]) {
                ELEMENT_OPENING -> {
                    result.append('/')
                    if (pathNames[i] != null) {
                        result.append(pathNames[i])
                    }
                }
                ELEMENT_CONTENT -> {
                    result.append('/')
                    if (pathNames[i] != null) {
                        result.append(pathNames[i])
                        if (i == stackSize - 1) {
                            result.append("/text()")
                        }
                    }
                }
                ELEMENT_ATTRIBUTE -> if (pathNames[i] != null) {
                    result.append("[@")
                    result.append(pathNames[i])
                    result.append(']')
                }
                NONEMPTY_DOCUMENT, EMPTY_DOCUMENT, CLOSED -> {}
            }
        }
        return if (result.isEmpty()) "/" else result.toString()
    }
}