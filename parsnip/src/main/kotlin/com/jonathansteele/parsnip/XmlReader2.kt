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

import okio.BufferedSource
import java.io.Closeable
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import kotlin.jvm.JvmOverloads
import java.io.IOException
import java.lang.StringBuilder
import java.io.EOFException
import java.lang.AssertionError
import java.util.Arrays

class XmlReader2(private val source: BufferedSource) : Closeable {
    private val buffer: Buffer = source.buffer
    private val nextStringBuffer: Buffer = Buffer()
    private var peeked = PEEKED_NONE
    private var state = STATE_BEFORE_DOCUMENT
    private var stackSize = 1
    private var pathNames = arrayOfNulls<String>(32)

    // We need to store all the attributes we come across for a given tag so that we can validate
    // duplicates
    private var attributeNames = arrayOfNulls<String>(32)
    private var attributeNamespaces = arrayOfNulls<String>(32)
    private var attributeSize = 0

    // Array of namespace keys (think 'foo' in 'xmlns:foo="bar"') sorted for quick binary search.
    private var namespaceKeys = arrayOfNulls<String>(4)

    // Array of namespace values (think 'bar' in 'xmlns:foo="bar"') sorted to match indices of keys.
    private var namespaceValues = arrayOfNulls<String>(4)

    // Array of position in the stack for the namespace, used to remove them when the stack is popped.
    private var namespaceStackPositions = IntArray(4)

    // Array of default namespaces (or null if there is one) for the given position in the stack.
    private var defaultNamespaces = arrayOfNulls<String>(32)

    // Shadowing a namespace is not likely to happen in practice, but it could, so we need to handle it.
    // Luckily we don't have to be as fast. The first index is the depth in the stack (stackSize) and the
    // second an index matching namespaceKeys. We want to lazily create this one because it's probably
    // not going to be used.
    private var shadowedNamespaces: Array<Array<String?>?>? = null
    private var namespaceSize = 0

    // We have to eagerly parse the next attribute in order to skip xmlns declarations,
    // therefore we should save what it was.
    private var lastAttribute: String? = null
    private val tempNamespace = Namespace()

    @JvmOverloads
    fun beginTag(namespace: Namespace = tempNamespace): String {
        var p = peeked
        if (p == PEEKED_NONE) {
            p = doPeek()
        }
        return if (p == PEEKED_BEGIN_TAG) {
            push()
            val tag = nextTag(namespace)
            pathNames[stackSize - 1] = if (namespace.alias == null) tag else namespace.alias + ":" + tag
            peeked = PEEKED_NONE
            tag
        } else {
            throw XmlDataException("Expected BEGIN_TAG but was " + peek() + " at path " + path)
        }
    }

    private val path: String
        get() {
            val path = StringBuilder("/")
            for (i in 1 until stackSize) {
                path.append(pathNames[i])
                if (i != stackSize - 1) {
                    path.append("/")
                }
            }
            return path.toString()
        }

    @Throws(IOException::class)
    fun endTag() {
        var p = peeked
        if (p == PEEKED_NONE) {
            p = doPeek()
        }
        if (p == PEEKED_END_TAG) {
            validateEndTag(pathNames[stackSize - 1])
        }
        if (p == PEEKED_EMPTY_TAG || p == PEEKED_END_TAG) {
            pop()
            state = STATE_DOCUMENT
            attributeSize = 0
            peeked = PEEKED_NONE
        } else {
            throw XmlDataException("Expected END_TAG but was " + peek() + " at path " + path)
        }
    }

    @JvmOverloads
    fun nextAttribute(namespace: Namespace = tempNamespace): String? {
        var p = peeked
        if (p == PEEKED_NONE) {
            p = doPeek()
        }
        return if (p == PEEKED_ATTRIBUTE) {
            var attribute: String?
            if (lastAttribute != null) {
                namespace.namespace = tempNamespace.namespace
                namespace.alias = tempNamespace.alias
                attribute = lastAttribute
                lastAttribute = null
            } else {
                // We must skip any xmlns attributes
                do {
                    attribute = readNextAttribute(namespace)
                } while (attribute == null)
            }
            val attributeSize = attributeSize
            for (i in 0 until attributeSize) {
                val name = attributeNames[i]
                if (attribute == name) {
                    val namespaceName = attributeNamespaces[i]
                    if (namespace.namespace == null && namespaceName == null) {
                        throw XmlDataException("Duplicate attribute '$name' at path $path")
                    } else if (namespace.namespace != null) {
                        if (namespace.namespace == namespaceName) {
                            throw XmlDataException("Duplicate attribute '{$namespace}$name' at path $path")
                        }
                    }
                }
            }
            if (attributeSize == attributeNames.size) {
                val newAttributeNames = arrayOfNulls<String>(attributeSize * 2)
                System.arraycopy(attributeNames, 0, newAttributeNames, 0, attributeSize)
                attributeNames = newAttributeNames
                val newAttributeNamespaces = arrayOfNulls<String>(attributeSize * 2)
                System.arraycopy(attributeNamespaces, 0, newAttributeNamespaces, 0, attributeSize)
                attributeNamespaces = newAttributeNamespaces
            }
            attributeNames[attributeSize] = attribute
            attributeNamespaces[attributeSize] = namespace.namespace
            this.attributeSize++
            peeked = PEEKED_NONE
            attribute
        } else {
            throw XmlDataException("Expected ATTRIBUTE but was " + peek() + " at path " + path)
        }
    }

    private fun nextValue(): String {
        var p = peeked
        if (p == PEEKED_NONE) {
            p = doPeek()
        }
        when (p) {
            PEEKED_SINGLE_QUOTED_VALUE -> {
                nextTerminatedString(SINGLE_QUOTE_OR_AMP, nextStringBuffer)
                buffer.readByte() // '''
            }
            PEEKED_DOUBLE_QUOTED_VALUE -> {
                nextTerminatedString(DOUBLE_QUOTE_OR_AMP, nextStringBuffer)
                buffer.readByte() // '"'
            }
            else -> {
                throw XmlDataException("Expected VALUE but was " + peek() + " at path " + path)
            }
        }
        peeked = PEEKED_NONE
        return nextStringBuffer.readUtf8()
    }

    fun nextText(): String {
        var p = peeked
        if (p == PEEKED_NONE) {
            p = doPeek()
        }
        return if (p == PEEKED_TEXT || p == PEEKED_CDATA) {
            // We need to read multiple times, to catch all text, cdata, and comments
            do {
                if (p == PEEKED_TEXT) {
                    nextTerminatedString(TEXT_END_TERMINAL, nextStringBuffer)
                } else {
                    nextCdataString(nextStringBuffer)
                }
                p = doPeek()
            } while (p == PEEKED_TEXT || p == PEEKED_CDATA)
            peeked = p
            nextStringBuffer.readUtf8()
        } else {
            throw XmlDataException("Expected TEXT but was " + peek() + " at path " + path)
        }
    }

    /**
     * Skips to the end of current tag, including all nested child tags. The 'current' tag is the
     * tag you last obtained from [beginTag]. This will consume up to and including [Token.END_TAG].
     */
    fun skipTag() {
        var p = peeked
        if (p == PEEKED_NONE) {
            p = doPeek()
        }
        var depth = 1
        while (true) {
            when (p) {
                PEEKED_END_TAG, PEEKED_EMPTY_TAG -> {
                    endTag()
                    depth--
                    if (depth == 0) {
                        return
                    } else {
                        break
                    }
                }
                PEEKED_BEGIN_TAG -> {
                    beginTag(tempNamespace)
                    depth++
                }
                PEEKED_EOF -> return
                PEEKED_CDATA, PEEKED_TEXT -> {
                    skipText()
                    lastAttribute = null
                    val i = source.indexOfElement(TAG_TERMINAL)
                    if (i == -1L) {
                        peeked = PEEKED_EOF
                        return
                    }
                    val c = buffer[i].toInt()
                    if (c == '<'.code) {
                        state = STATE_DOCUMENT
                        source.skip(i)
                    } else { // '>'
                        val c1 = buffer[i - 1].toInt()
                        state = STATE_TAG
                        if (c1 == '/'.code) {
                            // Self-closing.
                            source.skip(i - 1)
                        } else {
                            source.skip(i)
                        }
                    }
                }
                else -> {
                    lastAttribute = null
                    val i = source.indexOfElement(TAG_TERMINAL)
                    if (i == -1L) {
                        peeked = PEEKED_EOF
                        return
                    }
                    val c = buffer[i].toInt()
                    if (c == '<'.code) {
                        state = STATE_DOCUMENT
                        source.skip(i)
                    } else {
                        val c1 = buffer[i - 1].toInt()
                        state = STATE_TAG
                        if (c1 == '/'.code) {
                            source.skip(i - 1)
                        } else {
                            source.skip(i)
                        }
                    }
                }
            }
            p = doPeek()
        }
    }

    /**
     * Skips the currently peeked token.
     */
    fun skip() {
        var p = peeked
        if (p == PEEKED_NONE) {
            p = doPeek()
        }
        when (p) {
            PEEKED_BEGIN_TAG -> beginTag(tempNamespace)
            PEEKED_EMPTY_TAG, PEEKED_END_TAG -> endTag()
            PEEKED_ATTRIBUTE -> skipAttribute()
            PEEKED_SINGLE_QUOTED_VALUE -> {
                skipTerminatedString(SINGLE_QUOTE)
                buffer.readByte() // '''
            }
            PEEKED_DOUBLE_QUOTED_VALUE -> {
                skipTerminatedString(DOUBLE_QUOTE)
                buffer.readByte() // '"'
            }
            PEEKED_TEXT, PEEKED_CDATA -> skipText()
            PEEKED_EOF -> throw EOFException("End of input")
        }
    }

    private fun skipAttribute() {
        nextAttribute() // TODO: more efficient impl.
    }

    private fun peek(): Token {
        var p = peeked
        if (p == PEEKED_NONE) {
            p = doPeek()
        }
        return when (p) {
            PEEKED_BEGIN_TAG -> Token.BEGIN_TAG
            PEEKED_ATTRIBUTE -> Token.ATTRIBUTE
            PEEKED_SINGLE_QUOTED_VALUE, PEEKED_DOUBLE_QUOTED_VALUE -> Token.VALUE
            PEEKED_TEXT, PEEKED_CDATA -> Token.TEXT
            PEEKED_END_TAG, PEEKED_EMPTY_TAG -> Token.END_TAG
            PEEKED_EOF -> Token.END_DOCUMENT
            else -> throw AssertionError("Unknown Token: Peeked =$p")
        }
    }

    private fun doPeek(): Int {
        if (lastAttribute != null) {
            return PEEKED_ATTRIBUTE
        }
        when (val state = state) {
            STATE_TAG -> {
                when (nextNonWhiteSpace(true).toChar()) {
                    '/' -> {
                        // Self-closing tag
                        buffer.readByte() // '/'
                        if (!fillBuffer(1)) {
                            throw EOFException("End of input")
                        }
                        val next = buffer[0].toInt()
                        return if (next == '>'.code) {
                            buffer.readByte() // '>'
                            PEEKED_EMPTY_TAG.also { peeked = it }
                        } else {
                            throw syntaxError("Expected '>' but was '" + next.toChar() + "'")
                        }
                    }
                    '>' -> {
                        buffer.readByte() // '>'
                        this.state = STATE_DOCUMENT
                    }
                    else -> {
                        // Because of namespaces, we unfortunately have to eagerly read the next
                        // attribute because it should be skipped if it is a xmlns declaration.
                        val attribute = readNextAttribute(tempNamespace)
                        return if (attribute != null) {
                            lastAttribute = attribute
                            this.state = STATE_ATTRIBUTE
                            PEEKED_ATTRIBUTE.also { peeked = it }
                        } else {
                            // Normally recursion is bad, but if you are blowing the stack on xmlns
                            // declarations you have bigger troubles.
                            doPeek()
                        }
                    }
                }
            }
            STATE_ATTRIBUTE -> {
                this.state = STATE_TAG
                var c = nextNonWhiteSpace(true)
                if (c != '='.code) {
                    throw syntaxError("Expected '=' but was '" + c.toChar() + "'")
                }
                buffer.readByte() // '='
                c = nextNonWhiteSpace(true)
                return when (c.toChar()) {
                    '\'' -> {
                        buffer.readByte() // '''
                        PEEKED_SINGLE_QUOTED_VALUE.also { peeked = it }
                    }
                    '"' -> {
                        buffer.readByte() // '"'
                        PEEKED_DOUBLE_QUOTED_VALUE.also { peeked = it }
                    }
                    else -> throw syntaxError("Expected single or double quote but was " + c.toChar() + "'")
                }
            }
            STATE_BEFORE_DOCUMENT -> {
                // Skip over declaration if it exists.
                val c = nextNonWhiteSpace(false)
                if (c == '<'.code) {
                    fillBuffer(2)
                    if (buffer[1] == '?'.code.toByte()) {
                        skipTo("?>")
                        source.skip(2) // '?>'
                    }
                }
                this.state = STATE_DOCUMENT
            }
            else -> check(state != STATE_CLOSED) { "XmlReader is closed" }
        }
        var c = nextNonWhiteSpace(false)
        if (c == -1) {
            return PEEKED_EOF.also { peeked = it }
        }
        while (c == '<'.code) {
            // Need to figure out if we are:
            // a - starting a new tag
            // b - closing and existing tag
            // c - starting a comment.
            // d - starting cdata
            fillBuffer(2)
            when (buffer[1].toInt()) {
                '!'.code -> {
                    // We have either a comment or cdata, make sure it looks like one. (why are these so long?)
                    fillBuffer(4)
                    c = buffer[2].toInt()
                    when (c) {
                        '-'.code -> {
                            // Comment (probably)
                            c = buffer[3].toInt()
                            if (c != '-'.code) {
                                throw syntaxError("Expected '-' but was '" + c.toChar() + "'")
                            }
                            skipTo("-->")
                            fillBuffer(4)
                            source.skip(3) // '-->'
                            c = buffer[0].toInt()
                        }
                        '['.code -> {
                            val cdataSize = CDATA.size
                            fillBuffer(cdataSize.toLong())
                            // cdata (probably)
                            for (i in 3 until cdataSize) {
                                c = buffer[i.toLong()].toInt()
                                val expected = CDATA[i]
                                if (c != expected.toInt()) {
                                    throw syntaxError("Expected '" + Char(expected.toUShort()) + "' but was '" + c.toChar() + "'")
                                }
                            }
                            source.skip(cdataSize.toLong())
                            return PEEKED_CDATA.also { peeked = it }
                        }
                        else -> {
                            throw syntaxError("Expected '-' or '[' but was '" + c.toChar() + "'")
                        }
                    }
                }
                '/'.code -> {
                    buffer.readByte() // '<'
                    buffer.readByte() // '/'
                    return PEEKED_END_TAG.also { peeked = it }
                }
                else -> {
                    buffer.readByte() // '<'
                    this.state = STATE_TAG
                    return PEEKED_BEGIN_TAG.also { peeked = it }
                }
            }
        }
        return PEEKED_TEXT.also { peeked = it }
    }

    private fun push() {
        val stackSize = stackSize
        if (stackSize == pathNames.size) {
            val newPathNames = arrayOfNulls<String>(stackSize * 2)
            System.arraycopy(pathNames, 0, newPathNames, 0, stackSize)
            pathNames = newPathNames
            if (shadowedNamespaces != null) {
                val newShadowedNamespaces = arrayOfNulls<Array<String?>?>(stackSize * 2)
                System.arraycopy(shadowedNamespaces!!, 0, newShadowedNamespaces, 0, stackSize)
                shadowedNamespaces = newShadowedNamespaces
            }
            val newDefaultNamespaces = arrayOfNulls<String>(stackSize * 2)
            System.arraycopy(defaultNamespaces, 0, newDefaultNamespaces, 0, stackSize)
            defaultNamespaces = newDefaultNamespaces
        }
        defaultNamespaces[stackSize] = defaultNamespaces[stackSize - 1]
        this.stackSize++
    }

    private fun pop() {
        stackSize--
        val stackSize = stackSize
        pathNames[stackSize] = null
        if (stackSize > 1) {
            var namespaceSize = namespaceSize
            var removeCount = 0
            for (i in namespaceSize - 1 downTo 0) {
                if (stackSize < namespaceStackPositions[i]) {
                    namespaceSize--
                    removeCount++
                    val len = namespaceSize
                    if (len > 0) {
                        System.arraycopy(namespaceStackPositions, i, namespaceStackPositions, i - 1, len)
                        System.arraycopy(namespaceKeys, i, namespaceKeys, i - 1, len)
                        System.arraycopy(namespaceValues, i, namespaceValues, i - 1, len)
                    }
                }
            }
            this.namespaceSize -= removeCount
            defaultNamespaces[stackSize] = null
        }
        if (shadowedNamespaces != null) {
            val indexesForStack = shadowedNamespaces!![stackSize]
            for (i in indexesForStack!!.indices) {
                val value = indexesForStack[i] ?: continue
                namespaceValues[i] = value
            }
            shadowedNamespaces!![stackSize] = null
        }
    }

    private fun insertNamespace(key: String, value: String) {
        val namespaceSize = namespaceSize
        val searchIndex = Arrays.binarySearch(namespaceKeys, 0, namespaceSize, key)
        val insertIndex: Int
        if (searchIndex >= 0) {
            insertIndex = searchIndex
            if (shadowedNamespaces == null) {
                shadowedNamespaces = arrayOfNulls<Array<String?>?>(stackSize)
            }
            var indexesForStack = shadowedNamespaces!![stackSize - 1]
            if (indexesForStack == null) {
                shadowedNamespaces!![stackSize - 1] = arrayOfNulls(searchIndex + 1)
                indexesForStack = shadowedNamespaces!![stackSize - 1]
            }
            if (searchIndex > indexesForStack!!.size) {
                val newIndexesForStack = arrayOfNulls<String>(searchIndex + 1)
                System.arraycopy(indexesForStack, 0, newIndexesForStack, 0, searchIndex + 1)
                shadowedNamespaces!![stackSize - 1] = newIndexesForStack
                indexesForStack = shadowedNamespaces!![stackSize - 1]
            }
            indexesForStack!![searchIndex] = namespaceValues[searchIndex]
        } else {
            insertIndex = searchIndex.inv()
        }
        if (namespaceSize == namespaceKeys.size) {
            val newNamespaceKeys = arrayOfNulls<String>(namespaceSize * 2)
            System.arraycopy(namespaceKeys, 0, newNamespaceKeys, 0, insertIndex)
            newNamespaceKeys[insertIndex] = key
            System.arraycopy(namespaceKeys, insertIndex, newNamespaceKeys, insertIndex + 1, namespaceSize - insertIndex)
            val newNamespaceValues = arrayOfNulls<String>(namespaceSize * 2)
            System.arraycopy(namespaceValues, 0, newNamespaceValues, 0, insertIndex)
            newNamespaceValues[insertIndex] = value
            System.arraycopy(
                namespaceValues,
                insertIndex,
                newNamespaceValues,
                insertIndex + 1,
                namespaceSize - insertIndex
            )
            val newNamespaceStackPositions = IntArray(namespaceSize * 2)
            System.arraycopy(namespaceStackPositions, 0, newNamespaceStackPositions, 0, insertIndex)
            newNamespaceStackPositions[insertIndex] = stackSize
            System.arraycopy(
                namespaceStackPositions,
                insertIndex,
                newNamespaceStackPositions,
                insertIndex + 1,
                namespaceSize - insertIndex
            )
            namespaceKeys = newNamespaceKeys
            namespaceValues = newNamespaceValues
            namespaceStackPositions = newNamespaceStackPositions
        } else {
            System.arraycopy(namespaceKeys, insertIndex, namespaceKeys, insertIndex + 1, namespaceSize - insertIndex)
            namespaceKeys[insertIndex] = key
            System.arraycopy(
                namespaceValues,
                insertIndex,
                namespaceValues,
                insertIndex + 1,
                namespaceSize - insertIndex
            )
            namespaceValues[insertIndex] = value
            System.arraycopy(
                namespaceStackPositions,
                insertIndex,
                namespaceStackPositions,
                insertIndex + 1,
                namespaceSize - insertIndex
            )
            namespaceStackPositions[insertIndex] = stackSize
        }
        this.namespaceSize++
    }

    private fun namespaceValue(key: String): String? {
        val index = Arrays.binarySearch(namespaceKeys, 0, namespaceSize, key)
        return if (index >= 0) {
            namespaceValues[index]
        } else {
            null
        }
    }

    /**
     * Returns the next tag and fills the given namespace.
     */
    private fun nextTag(namespace: Namespace): String {
        // There may be space between the opening and the tag.
        nextNonWhiteSpace(true)
        val i = source.indexOfElement(TAG_OR_NAMESPACE_END_TERMINAL)
        val tagOrNs = if (i != -1L) buffer.readUtf8(i) else buffer.readUtf8()
        fillBuffer(1)
        val n = buffer[0].toInt()
        return if (n == ':'.code) {
            buffer.readByte() // ':'
            namespace.alias = tagOrNs
            namespace.namespace = namespaceValue(tagOrNs)
            readNextTagName()
        } else {
            namespace.alias = null
            namespace.namespace = defaultNamespaces[stackSize - 1]
            tagOrNs
        }
    }

    private fun readNextTagName(): String {
        val i = source.indexOfElement(TAG_START_TERMINALS)
        return if (i != -1L) buffer.readUtf8(i) else buffer.readUtf8()
    }

    /**
     * Reads the next attribute, and it's namespace if not null. Since declaring namespaces are
     * attributes themselves, this method may return null if it is parsing a xmlns declaration. In
     * that case, the attribute should be skipped and not given to the client.
     */
    private fun readNextAttribute(namespace: Namespace?): String? {
        val i = source.indexOfElement(ATTRIBUTE_OR_NAMESPACE_END_TERMINAL)
        val attrOrNs = if (i != -1L) buffer.readUtf8(i) else buffer.readUtf8()
        fillBuffer(1)
        val n = buffer[0].toInt()
        return if (n == ':'.code) {
            buffer.readByte() // ':'
            if ("xmlns" == attrOrNs) {
                val name = readNextAttributeName()
                state = STATE_ATTRIBUTE
                peeked = PEEKED_NONE
                val value = nextValue()
                insertNamespace(name, value)
                null
            } else {
                if (namespace != null) {
                    namespace.alias = attrOrNs
                    namespace.namespace = namespaceValue(attrOrNs)
                }
                readNextAttributeName()
            }
        } else {
            if ("xmlns" == attrOrNs) {
                state = STATE_ATTRIBUTE
                peeked = PEEKED_NONE
                val value = nextValue()
                defaultNamespaces[stackSize - 1] = value
                null
            } else {
                if (namespace != null) {
                    namespace.alias = null
                    namespace.namespace = defaultNamespaces[stackSize - 1]
                }
                attrOrNs
            }
        }
    }

    private fun readNextAttributeName(): String {
        val i = source.indexOfElement(ATTRIBUTE_END_TERMINAL)
        return if (i != -1L) buffer.readUtf8(i) else buffer.readUtf8()
    }

    private fun validateEndTag(name: String?) {
        fillBuffer((name!!.length + 1).toLong())
        val end = source.readUtf8(name.length.toLong())
        if (name != end) {
            throw syntaxError("Mismatched tags: Expected '$name' but was '$end'")
        }
        nextWithWhitespace(TAG_END)
    }

    /**
     * Reads up to an including the `terminatorByte` asserting that everything before it is
     * whitespace.
     */
    private fun nextWithWhitespace(terminatorByte: Byte) {
        val index = source.indexOf(terminatorByte)
        if (index == -1L) {
            // Just complain whatever the next char is if there is one.
            if (buffer.size > 0) {
                throw syntaxError("Expected '" + Char(terminatorByte.toUShort()) + "' but was '" + Char(buffer[0].toUShort()) + "'")
            } else {
                throw syntaxError("Expected '" + Char(terminatorByte.toUShort()) + "'")
            }
        }
        for (i in 0 until index) {
            val c = buffer[i].toInt()
            if (c == '\n'.code || c == ' '.code || c == '\r'.code || c == '\t'.code) {
                continue
            }
            throw syntaxError("Expected '" + Char(terminatorByte.toUShort()) + "' but was '" + c.toChar() + "'")
        }
        source.skip(index + 1)
    }

    private fun nextNonWhiteSpace(throwOnEof: Boolean): Int {
        var p = 0
        while (fillBuffer((p + 1).toLong())) {
            val c = buffer[p++.toLong()].toInt()
            if (c == '\n'.code || c == ' '.code || c == '\r'.code || c == '\t'.code) {
                continue
            }
            source.skip((p - 1).toLong())
            return c
        }
        return if (throwOnEof) {
            throw EOFException("End of input")
        } else {
            -1
        }
    }

    /**
     * Returns true once `limit - pos >= minimum`. If the data is exhausted before that many
     * characters are available, this returns false.
     */
    private fun fillBuffer(minimum: Long): Boolean {
        return source.request(minimum)
    }

    /**
     * @param toFind a string to search for. Must not contain a newline.
     */
    private fun skipTo(toFind: String): Boolean {
        outer@ while (fillBuffer(toFind.length.toLong())) {
            for (c in toFind.indices) {
                if (buffer[c.toLong()] != toFind[c].code.toByte()) {
                    buffer.readByte()
                    continue@outer
                }
            }
            return true
        }
        return false
    }

    /**
     * Returns the string up to but not including `runTerminator`, expanding any entities
     * encountered along the way. This does not consume the `runTerminator`.
     */
    private fun nextTerminatedString(runTerminator: ByteString, outBuffer: Buffer) {
        while (true) {
            val index = source.indexOfElement(runTerminator)
            if (index == -1L) {
                throw syntaxError("Unterminated string")
            }
            val c = buffer[index]
            // If we've got an entity, we're going to need a string builder.
            if (c == '&'.code.toByte()) {
                outBuffer.write(buffer, index)
                buffer.readByte() // '&'
                readEntity(outBuffer)
                continue
            }
            outBuffer.write(buffer, index)
            return
        }
    }

    private fun nextCdataString(outBuffer: Buffer) {
        var start: Long = 0
        while (true) {
            val index = source.indexOf(']'.code.toByte(), start)
            if (index == -1L) {
                throw syntaxError("Unterminated CDATA")
            }
            start = index
            source.request(index + 2)
            var c = buffer[index + 1]
            if (c != ']'.code.toByte()) {
                continue
            }
            c = buffer[index + 2]
            if (c != '>'.code.toByte()) {
                continue
            }
            outBuffer.write(buffer, index)
            source.skip(3) // ]]>
            return
        }
    }

    private fun skipText() {
        var p = peeked
        if (p == PEEKED_NONE) {
            p = doPeek()
        }
        while (p == PEEKED_TEXT || p == PEEKED_CDATA) {
            if (p == PEEKED_TEXT) {
                skipTerminatedString(TEXT_END)
            } else {
                skipCdataString()
            }
            p = doPeek()
        }
    }

    private fun skipCdataString() {
        var start: Long = 0
        while (true) {
            val index = source.indexOf(']'.code.toByte(), start)
            if (index == -1L) {
                throw syntaxError("Unterminated CDATA")
            }
            start = index
            source.request(index + 2)
            var c = buffer[index + 1]
            if (c != ']'.code.toByte()) {
                continue
            }
            c = buffer[index + 2]
            if (c != '>'.code.toByte()) {
                continue
            }
            source.skip(index + 3)
            break
        }
        peeked = PEEKED_NONE
    }

    /**
     * Skips the string up to but not including `runTerminator`. This does not consume the
     * `runTerminator`.
     */
    private fun skipTerminatedString(runTerminator: Byte) {
        val index = source.indexOf(runTerminator)
        if (index == -1L) {
            throw syntaxError("Unterminated string")
        }
        source.skip(index)
        peeked = PEEKED_NONE
    }

    /**
     * Reads an entity and replaces it with its value. The '&' should already have been read. This
     * supports both built-in and user-defined entities.
     */
    private fun readEntity(outputBuffer: Buffer) {
        val index = source.indexOf(ENTITY_END_TERMINAL)
        if (index == -1L) {
            throw syntaxError("Unterminated entity sequence")
        }
        val entity = buffer.readUtf8(index)
        buffer.readByte() // ';'
        if (entity[0] == '#') {
            var result = 0
            if (entity[1] == 'x') {
                val len = (index - 2).toInt()
                var i = 2 /* #x */
                val end = i + len
                while (i < end) {
                    val c = entity[i].code
                    result = result shl 4
                    result += if (c >= '0'.code && c <= '9'.code) {
                        c - '0'.code
                    } else if (c >= 'a'.code && c <= 'f'.code) {
                        c - 'a'.code + 10
                    } else if (c >= 'A'.code && c <= 'F'.code) {
                        c - 'A'.code + 10
                    } else {
                        throw syntaxError(entity)
                    }
                    i++
                }
            } else {
                val len = (index - 1).toInt()

                // 10^(len-1)
                var n = 1
                for (i in 1 until len) {
                    n *= 10
                }
                var i = 1 /* # */
                val end = i + len
                while (i < end) {
                    val c = entity[i].code
                    result += if (c >= '0'.code && c <= '9'.code) {
                        n * (c - '0'.code)
                    } else {
                        throw syntaxError(entity)
                    }
                    n /= 10
                    i++
                }
            }
            outputBuffer.writeUtf8CodePoint(result)
        } else {
            when (entity) {
                "quot" -> outputBuffer.writeByte('"'.code)
                "apos" -> outputBuffer.writeByte('\''.code)
                "lt" -> outputBuffer.writeByte('<'.code)
                "gt" -> outputBuffer.writeByte('>'.code)
                "amp" -> outputBuffer.writeByte('&'.code)
                else -> throw syntaxError("User-defined entities not yet supported")
            }
        }
    }

    /**
     * Throws a new IO exception with the given message and a context snippet with this reader's
     * content.
     */
    private fun syntaxError(message: String): IOException {
        throw IOException("$message at path $path")
    }

    override fun close() {
        peeked = PEEKED_NONE
        stackSize = 1
        nextStringBuffer.clear()
        buffer.clear()
        source.close()
    }

    enum class Token {
        BEGIN_TAG, ATTRIBUTE, VALUE, TEXT, END_TAG, END_DOCUMENT
    }

    companion object {
        private val TAG_START_TERMINALS = ">/ \n\t\r\u000c".encodeUtf8()
        private val TEXT_END_TERMINAL = "&<".encodeUtf8()
        private val ATTRIBUTE_END_TERMINAL = "= ".encodeUtf8()
        private val ATTRIBUTE_OR_NAMESPACE_END_TERMINAL = ":= ".encodeUtf8()
        private val TAG_OR_NAMESPACE_END_TERMINAL = ":>/ \n\t\r\u000c".encodeUtf8()
        private val SINGLE_QUOTE_OR_AMP = "'&".encodeUtf8()
        private val DOUBLE_QUOTE_OR_AMP = "\"&".encodeUtf8()
        private val TAG_TERMINAL = "<>".encodeUtf8()
        private val CDATA = "<![CDATA[".encodeUtf8()
        private const val TEXT_END = '<'.code.toByte()
        private const val TAG_END = '>'.code.toByte()
        private const val SINGLE_QUOTE = '\''.code.toByte()
        private const val DOUBLE_QUOTE = '"'.code.toByte()
        private const val ENTITY_END_TERMINAL = ';'.code.toByte()
        private const val PEEKED_NONE = 0
        private const val PEEKED_BEGIN_TAG = 1
        private const val PEEKED_ATTRIBUTE = 2
        private const val PEEKED_SINGLE_QUOTED_VALUE = 3
        private const val PEEKED_DOUBLE_QUOTED_VALUE = 4
        private const val PEEKED_EMPTY_TAG = 5
        private const val PEEKED_END_TAG = 6
        private const val PEEKED_TEXT = 7
        private const val PEEKED_CDATA = 8
        private const val PEEKED_EOF = 9
        private const val STATE_BEFORE_DOCUMENT = 0
        private const val STATE_DOCUMENT = 1
        private const val STATE_TAG = 2
        private const val STATE_ATTRIBUTE = 3
        private const val STATE_CLOSED = 4
    }
}