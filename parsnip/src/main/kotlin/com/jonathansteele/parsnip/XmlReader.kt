package com.jonathansteele.parsnip

import okio.Buffer
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.util.*

//TODO: Namespace Support
class XmlReader internal constructor(private val source: BufferedSource) : Closeable {
    /** The input XML.  */
    private var peeked = PEEKED_NONE
    private var pathNames = arrayOfNulls<String>(32)
    private var pathIndices = IntArray(32)

    // We need to store all the attributes we come across for a given tag so that we can validate
    // duplicates
    // private var attributeNames = arrayOfNulls<String>(32)
    // private var attributeNamespaces = arrayOfNulls<String>(32)
    // private var attributeSize = 0

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

    private var stack = IntArray(32)
    private var stackSize = 0
    private val buffer: Buffer = source.buffer
    private var currentTagName: String? = null

    // We have to eagerly parse the next attribute in order to skip xmlns declarations,
    // therefore we should save what it was.
    // private var lastAttribute: String? = null
    private val tempNamespace = Namespace()

    init {
        stack[stackSize++] = XmlScope.EMPTY_DOCUMENT
    }

    /**
     * Get the next token without consuming it.
     *
     * @return [Token]
     */
    fun peek(): Token = when (val p = peekIfNone()) {
        PEEKED_BEGIN_TAG -> Token.BEGIN_TAG
        PEEKED_ELEMENT_NAME -> Token.ELEMENT_NAME
        PEEKED_END_TAG -> Token.END_TAG
        PEEKED_ATTRIBUTE_NAME -> Token.ATTRIBUTE
        PEEKED_DOUBLE_QUOTED, PEEKED_SINGLE_QUOTED -> Token.VALUE
        PEEKED_TEXT, PEEKED_CDATA -> Token.TEXT
        PEEKED_EOF -> Token.END_DOCUMENT
        else -> throw AssertionError("Unknown Token: Peeked = $p")
    }

    /**
     * Actually do a peek. This method will return the peeked token and updates the internal variable [peeked]
     *
     * @return The peeked token
     */
    private fun doPeek(): Int {
        val peekStack = stack[stackSize - 1]
        if (peekStack == XmlScope.ELEMENT_OPENING) {
            val c = nextNonWhitespace(true)
            return if (isLiteral(c.toChar().code)) {
                setPeeked(PEEKED_ELEMENT_NAME)
            } else {
                throw syntaxError("Expected xml element name (literal expected)")
            }
        } else if (peekStack == XmlScope.ELEMENT_ATTRIBUTE) {
            var c = nextNonWhitespace(true)
            if (isLiteral(c)) {
                return setPeeked(PEEKED_ATTRIBUTE_NAME)
            }
            when (c.toChar()) {
                '>' -> {
                    // remove XmlScope.ELEMENT_ATTRIBUTE from top of the stack
                    popStack()

                    // set previous stack from XmlScope.ELEMENT_OPENING to XmlScope.ELEMENT_CONTENT
                    stack[stackSize - 1] = XmlScope.ELEMENT_CONTENT
                    buffer.readByte() // consume '>'
                    val nextChar = nextNonWhitespace(true)
                    if (nextChar != '<'.code) {
                        return setPeeked(PEEKED_TEXT)
                    }
                    if (isCDATA) {
                        buffer.skip(9) // skip opening cdata tag
                        return setPeeked(PEEKED_CDATA)
                    }
                }
                '/' -> // Self closing />
                    return if (fillBuffer(2) && buffer[1] == '>'.code.toByte()) {
                        // remove XmlScope.ELEMENT_ATTRIBUTE from top of the stack
                        popStack()

                        // correct closing xml tag
                        buffer.skip(2) // consuming '/>'
                        setPeeked(PEEKED_END_TAG)
                    } else {
                        throw syntaxError("Expected closing />")
                    }
                '=' -> {
                    buffer.readByte() // consume '='

                    // Read next char which should be a quote
                    c = nextNonWhitespace(true)
                    return when (c.toChar()) {
                        '"' -> {
                            buffer.readByte() // consume "
                            setPeeked(PEEKED_DOUBLE_QUOTED)
                        }
                        '\'' -> {
                            buffer.readByte() // consume '
                            setPeeked(PEEKED_SINGLE_QUOTED)
                        }
                        else -> throw syntaxError(
                            "Expected double quote (\") or single quote (') while reading xml elements attribute"
                        )
                    }
                }
                else -> throw syntaxError(
                    "Unexpected character '"
                            + c.toChar()
                            + "' while trying to read xml elements attribute"
                )
            }
        } else if (peekStack == XmlScope.ELEMENT_CONTENT) {
            val c = nextNonWhitespace(true)
            if (c != '<'.code) {
                return setPeeked(PEEKED_TEXT)
            }
            if (isCDATA) {
                buffer.skip(9) // skip opening cdata tag
                return setPeeked(PEEKED_CDATA)
            }
        } else if (peekStack == XmlScope.EMPTY_DOCUMENT) {
            stack[stackSize - 1] = XmlScope.NONEMPTY_DOCUMENT
        } else if (peekStack == XmlScope.NONEMPTY_DOCUMENT) {
            val c = nextNonWhitespace(false)
            if (c == -1) {
                return setPeeked(PEEKED_EOF)
            }
        } else
            check(peekStack != XmlScope.CLOSED) { "XmlReader is closed" }
        when (nextNonWhitespace(true, peekStack == XmlScope.EMPTY_DOCUMENT).toChar()) {
            '<' -> {
                buffer.readByte() // consume '<'.

                // Check if </ which means end of element
                if (fillBuffer(1) && buffer[0] == '/'.code.toByte()) {
                    buffer.readByte() // consume /

                    // Check if it is the corresponding xml element name
                    val closingElementName = readNextTagName()
                    if (closingElementName == pathNames[stackSize - 1]) {
                        if (nextNonWhitespace(false) == '>'.code) {
                            buffer.readByte() // consume >
                            return setPeeked(PEEKED_END_TAG)
                        } else {
                            throw syntaxError("Missing closing '>' character in </" + pathNames[stackSize - 1])
                        }
                    } else {
                        throw syntaxError("Expected a closing element tag </${pathNames[stackSize - 1]}>" +
                                " but found </$closingElementName>"
                        )
                    }
                }
                // its just a < which means begin of the element
                return setPeeked(PEEKED_BEGIN_TAG)
            }
            '"' -> {
                buffer.readByte() // consume '"'.
                return setPeeked(PEEKED_DOUBLE_QUOTED)
            }
            '\'' -> {
                buffer.readByte() // consume '
                return setPeeked(PEEKED_SINGLE_QUOTED)
            }
        }
        return PEEKED_NONE
    }

    /**
     * Checks for CDATA beginning `<![CDATA[ `. This method doesn't consume the opening CDATA
     * Tag
     *
     * @return true, if CDATA opening tag, otherwise false
     */
    private val isCDATA: Boolean
        get() = fillBuffer(CDATA_OPEN.size.toLong()) && buffer.rangeEquals(0, CDATA_OPEN)

    /**
     * Checks for DOCTYPE beginning `<!DOCTYPE `. This method doesn't consume the opening
     */
    private val isDocTypeDefinition: Boolean
        get() = buffer.size >= DOCTYPE_OPEN.size &&
                buffer.snapshot(DOCTYPE_OPEN.size).toAsciiUppercase() == DOCTYPE_OPEN

    /**
     * Consumes the next token from the JSON stream and asserts that it is the beginning of a new
     * object.
     */
    fun beginTag() {
        val p = peekIfNone()
        peeked = if (p == PEEKED_BEGIN_TAG) {
            pushStack(XmlScope.ELEMENT_OPENING)
            PEEKED_NONE
        } else {
            throw XmlDataException(
                "Expected " + Token.BEGIN_TAG + " but was " + peek()
                        + " at path " + path
            )
        }
    }

    /**
     * Consumes the next token from the JSON stream and asserts that it is the end of the current
     * object.
     */
    fun endTag() {
        val p = peekIfNone()
        peeked = if (p == PEEKED_END_TAG) {
            popStack()
            PEEKED_NONE
        } else {
            throw syntaxError("Expected end of element but was " + peek())
        }
    }

    /**
     * Consumes the next token attribute of a xml element. Assumes that [beginTag] has
     * been called before
     *
     * @return The name of the attribute
     */
    fun nextAttribute(): String {
        val p = peekIfNone()
        if (p != PEEKED_ATTRIBUTE_NAME) {
            throw syntaxError("Expected xml element attribute name but was " + peek())
        }

        /* var attribute: String?
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
        this.attributeSize++*/

        val result = readNextAttributeName()
        peeked = PEEKED_NONE
        pathNames[stackSize - 1] = result
        return result
    }

    /**
     * Consumes the next attribute's value. Assumes that [nextAttribute] has been called
     * before invoking this method
     *
     * @return The value of the attribute as string
     */
    fun nextValue(): String {
        val p = peekIfNone()
        return if (p == PEEKED_DOUBLE_QUOTED || p == PEEKED_SINGLE_QUOTED) {
            val attributeValue =
                nextQuotedValue(if (p == PEEKED_DOUBLE_QUOTED) DOUBLE_QUOTE else SINGLE_QUOTE)
            peeked = PEEKED_NONE
            // Remove attribute name from stack, do that after nextQuotedValue() to ensure that xpath is correctly
            // in case that nextQuotedValue() fails
            pathNames[stackSize - 1] = null
            attributeValue
        } else {
            throw XmlDataException(
                "Expected xml element attribute value (in double quotes or single quotes) but was ${peek()}" +
                        " at path $path")
        }
    }

    /**
     * Skip the value of an attribute if you don't want to read the value.
     * [nextAttribute] must be called before invoking this method
     */
    private fun skipAttributeValue() {
        val p = peekIfNone()
        if (p == PEEKED_DOUBLE_QUOTED || p == PEEKED_SINGLE_QUOTED) {
            peeked = PEEKED_NONE
            pathNames[stackSize - 1] = null // Remove attribute name from stack
            skipQuotedValue(if (p == PEEKED_DOUBLE_QUOTED) DOUBLE_QUOTE else SINGLE_QUOTE)
        } else {
            throw XmlDataException(
                "Expected xml element attribute value (in double quotes or single quotes) but was ${peek()} " +
                        "at path $path"
            )
        }
    }

    /**
     * Get the next text content of an xml element. Text content is `<element>text content</element>`
     *
     * If the element is empty (no content) like `<element></element>` this method will return the empty string "".
     *
     * `null` as return type is not supported yet, because there is no way in xml to distinguish
     * between empty string "" or null since both might be represented with `<element></element>`.
     * So if you want to represent a null element, simply don't write the corresponding xml tag. Then the parser
     * will not try set the mapped field and it will remain the default value (which is null).
     *
     * @return The xml element's text content
     */
    fun nextText(): String = when (peekIfNone()) {
        PEEKED_TEXT -> {
            peeked = PEEKED_NONE

            // Read text until '<' found
            val index = source.indexOf(OPENING_XML_ELEMENT)
            if (index == -1L) {
                throw syntaxError(
                    "Unterminated element text content. Expected </ ${pathNames[stackSize - 1]} > but haven't found"
                )
            }
            buffer.readUtf8(index)
        }
        PEEKED_CDATA -> {
            peeked = PEEKED_NONE

            // Search index of closing CDATA tag ]]>
            val index = indexOfClosingCDATA()
            val result = buffer.readUtf8(index)
            buffer.skip(3) // consume ]]>
            result
        }
        PEEKED_END_TAG -> {
            // this is an element without any text content. i.e. <foo></foo>.
            // In that case we return the default value of a string which is the empty string
            // Don't do peeked = PEEKED_NONE; because that would consume the end tag, which we haven't done yet.
            ""
        }
        else -> {
            throw XmlDataException(
                "Expected xml element text content but was ${peek()} at path $path"
            )
        }
    }

    /**
     * Returns the index of the last character before starting the CDATA closing tag "`]]>`".
     * This method does not consume the closing CDATA tag.
     *
     * @return index of last character before closing tag.
     */
    private fun indexOfClosingCDATA(): Long {
        val index = source.indexOf(CDATA_CLOSE)
        if (index == -1L) {
            throw EOFException("<![CDATA[ at $path has never been closed with ]]>")
        }
        return index
    }

    /**
     * Skip the text content. Text content is `<element>text content</element>`
     */
    private fun skipText() {
        when (peekIfNone()) {
            PEEKED_TEXT -> {
                peeked = PEEKED_NONE

                // Read text until '<' found
                val index = source.indexOf(OPENING_XML_ELEMENT)
                if (index == -1L) {
                    throw syntaxError(
                        "Unterminated element text content. Expected </ ${pathNames[stackSize - 1]} >" +
                                " but haven't found"
                    )
                }
                buffer.skip(index)
            }
            PEEKED_CDATA -> {
                peeked = PEEKED_NONE
                // Search index of closing CDATA tag ]]>
                val index = indexOfClosingCDATA()
                buffer.skip(index + 3) // +3 because of consuming closing tag
            }
            else -> {
                throw XmlDataException("Expected xml element text content but was ${peek()} at path $path")
            }
        }
    }

    /**
     * Push a new scope on top of the scope stack
     *
     * @param newTop The scope that should be pushed on top of the stack
     */
    private fun pushStack(newTop: Int) {
        if (stackSize == stack.size) {
            val newStack = IntArray(stackSize * 2)
            val newPathIndices = IntArray(stackSize * 2)
            val newPathNames = arrayOfNulls<String>(stackSize * 2)
            System.arraycopy(stack, 0, newStack, 0, stackSize)
            System.arraycopy(pathIndices, 0, newPathIndices, 0, stackSize)
            System.arraycopy(pathNames, 0, newPathNames, 0, stackSize)

            if (shadowedNamespaces != null) {
                val newShadowedNamespaces = arrayOfNulls<Array<String?>?>(stackSize * 2)
                System.arraycopy(shadowedNamespaces!!, 0, newShadowedNamespaces, 0, stackSize)
                shadowedNamespaces = newShadowedNamespaces
            }
            val newDefaultNamespaces = arrayOfNulls<String>(stackSize * 2)
            System.arraycopy(defaultNamespaces, 0, newDefaultNamespaces, 0, stackSize)
            defaultNamespaces = newDefaultNamespaces

            stack = newStack
            pathIndices = newPathIndices
            pathNames = newPathNames
        }
        defaultNamespaces[stackSize] = defaultNamespaces[stackSize - 1]
        stack[stackSize++] = newTop
    }

    /**
     * Removes the top element of the stack
     */
    private fun popStack() {
        stack[stackSize - 1] = 0
        stackSize--
        pathNames[stackSize] = null // Free the last path name so that it can be garbage collected!
        pathIndices[stackSize - 1]++
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
     * Returns a XPath to the current location in the XML value.
     */
    val path: String
        get() = XmlScope.getPath(stackSize, stack, pathNames, pathIndices)

    override fun close() {
        peeked = PEEKED_NONE
        buffer.clear()
        source.close()
    }

    /**
     * Returns true once `limit - pos >= minimum`. If the data is exhausted before that many
     * characters are available, this returns false.
     */
    private fun fillBuffer(minimum: Long): Boolean = source.request(minimum)

    /**
     * Returns the next character in the stream that is neither whitespace nor a part of a comment.
     * When this returns, the returned character is always at `buffer[pos-1]`; this means the
     * caller can always pushStack back the returned character by decrementing `pos`.
     */
    private fun nextNonWhitespace(throwOnEof: Boolean, isDocumentBeginning: Boolean = false): Int {
        /*
         * This code uses ugly local variables 'p' and 'l' representing the 'pos'
         * and 'limit' fields respectively. Using locals rather than fields saves
         * a few field reads for each whitespace character in a pretty-printed
         * document, resulting in a 5% speedup. We need to flush 'p' to its field
         * before any (potentially indirect) call to fillBuffer() and reread both
         * 'p' and 'l' after any (potentially indirect) call to the same method.
         */

        // Look for UTF-8 BOM sequence 0xEFBBBF and skip it
        if (isDocumentBeginning && source.rangeEquals(0, UTF8_BOM)) {
            source.skip(3)
        }
        var p = 0
        while (fillBuffer((p + 1).toLong())) {
            val c = buffer[p++.toLong()].toInt()
            if (c == '\n'.code || c == ' '.code || c == '\r'.code || c == '\t'.code) {
                continue
            }
            buffer.skip((p - 1).toLong())
            if (c == '<'.code && !isCDATA && fillBuffer(2)) {
                val peek = buffer[1]
                val peekStack = stack[stackSize - 1]
                if (peekStack == XmlScope.NONEMPTY_DOCUMENT && isDocTypeDefinition) {
                    var index = source.indexOf(CLOSING_XML_ELEMENT, DOCTYPE_OPEN.size.toLong())
                    if (index == -1L) {
                        throw syntaxError("Unterminated <!DOCTYPE>. Inline DOCTYPE is not support at the moment.")
                    }
                    // check if doctype uses brackets
                    val bracketIndex = source.indexOf(OPENING_DOCTYPE_BRACKET, DOCTYPE_OPEN.size.toLong(), index)
                    if (bracketIndex != -1L) {
                        index =
                            source.indexOf(ByteString.of(CLOSING_DOCTYPE_BRACKET, CLOSING_XML_ELEMENT), index + bracketIndex)
                        if (index == -1L) {
                            throw syntaxError("Unterminated <!DOCTYPE []>. Expected closing ]>")
                        }
                        source.skip(index + 2) // skip behind ]>
                    } else {
                        source.skip(index + 1) // skip behind >
                    }
                    // TODO inline DOCTYPE.
                    p = 0
                    continue
                } else if (peek == '!'.code.toByte() && fillBuffer(4)) {
                    val index = source.indexOf(COMMENT_CLOSE, 4) // skip <!-- in comparison by offset 4
                    if (index == -1L) {
                        throw syntaxError("Unterminated comment")
                    }
                    source.skip(index + COMMENT_CLOSE.size) // skip behind --!>
                    p = 0
                    continue
                } else if (peek == '?'.code.toByte()) {
                    val index = source.indexOf(XML_DECLARATION_CLOSE, 2) // skip <? in comparison by offset 2
                    if (index == -1L) {
                        throw syntaxError("Unterminated xml declaration or processing instruction \"<?\"")
                    }
                    source.skip(index + XML_DECLARATION_CLOSE.size) // skip behind ?>
                    p = 0
                    continue
                }
            }
            return c
        }
        return if (throwOnEof) {
            throw EOFException("Unexpected end of input at path $path")
        } else {
            -1
        }
    }

    /**
     * Throws a new IO exception with the given message and a context snippet with this reader's
     * content.
     */
    @Throws(IOException::class)
    private fun syntaxError(message: String) : IOException {
        throw IOException("$message at path $path")
    }

    /**
     * Get the name of the opening xml name
     *
     * @return The name
     */
    @JvmOverloads
    fun nextTagName(namespace: Namespace = tempNamespace): String? {
        val p = peekIfNone()
        if (p != PEEKED_ELEMENT_NAME) {
            throw syntaxError("Expected XML Tag Element name, but have " + peek())
        }
        val tag = nextTag(namespace)
        currentTagName = tag
        peeked = PEEKED_NONE
        pathNames[stackSize - 1] = if (namespace.alias == null) tag else namespace.alias + ":" + tag

        // Next we expect element attributes block
        pushStack(XmlScope.ELEMENT_ATTRIBUTE)
        return currentTagName
    }

    /**
     * Returns the next tag and fills the given namespace.
     */
    private fun nextTag(namespace: Namespace): String {
        // There may be space between the opening and the tag.
        nextNonWhitespace(true)
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
                pushStack(XmlScope.ELEMENT_ATTRIBUTE)
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
                pushStack(XmlScope.ELEMENT_ATTRIBUTE)
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

    /**
     * Returns the string up to but not including `quote`, non-escaping any character escape
     * sequences encountered along the way. The opening quote should have already been read. This
     * consumes the closing quote, but does not include it in the returned string.
     *
     * @throws IOException if any unicode escape sequences are malformed.
     */
    private fun nextQuotedValue(runTerminator: Byte): String {
        var builder: StringBuilder? = null
        while (true) {
            val index = source.indexOf(runTerminator)
            if (index == -1L) {
                throw syntaxError(
                    "Unterminated string (" + (if (runTerminator == DOUBLE_QUOTE) "double quote \"" else "single quote '") + " is missing)"
                )
            }

            // If we've got an escape character, we're going to need a string builder.
            if (buffer[index] == '\\'.code.toByte()) {
                if (builder == null) builder = StringBuilder()
                builder.append(buffer.readUtf8(index))
                buffer.readByte() // '\'
                builder.append(readEscapeCharacter())
                continue
            }

            // If it isn't the escape character, it's the quote. Return the string.
            return if (builder == null) {
                val result = buffer.readUtf8(index)
                buffer.readByte() // Consume the quote character.
                result
            } else {
                builder.append(buffer.readUtf8(index))
                buffer.readByte() // Consume the quote character.
                builder.toString()
            }
        }
    }

    /**
     * Checks whether the passed character is a literal or not
     *
     * @param c the character to check
     * @return true if literal, otherwise false
     */
    private fun isLiteral(c: Int): Boolean =
        when (c.toChar()) {
            '=', '<', '>', '/', ' ' -> false
            else -> true
    }

    /**
     * Unescapes the character identified by the character or characters that immediately follow a
     * backslash. The backslash '\' should have already been read. This supports both unicode escapes
     * "u000A" and two-character escapes "\n".
     *
     * @throws IOException if any unicode escape sequences are malformed.
     */
    private fun readEscapeCharacter(): Char {
        if (!fillBuffer(1)) {
            throw syntaxError("Unterminated escape sequence")
        }
        return when (val escaped = buffer.readByte().toInt().toChar()) {
            'u' -> {
                if (!fillBuffer(4)) {
                    throw EOFException("Unterminated escape sequence at path $path")
                }
                // Equivalent to Integer.parseInt(stringPool.get(buffer, pos, 4), 16);
                var result = 0.toChar()
                for (i in 0 until 4) {
                    result = (result.code shl 4).toChar()
                    result += when (val c = buffer[i.toLong()].toInt().toChar()) {
                        in '0'..'9' -> c - '0'
                        in 'a'..'f' -> c - 'a' + 10
                        in 'A'..'F' -> c - 'A' + 10
                        else -> throw syntaxError("\\u" + buffer.readUtf8(4))
                    }
                }
                buffer.skip(4)
                result
            }
            't' -> '\t'
            'b' -> '\b'
            'n' -> '\n'
            'r' -> '\r'
            'f' -> '\u000C' /*\f*/
            '\n', '\'', '"', '\\' -> escaped
            else -> escaped
        }
    }

    /**
     * Skip a quoted value
     *
     * @param runTerminator The terminator to skip
     */
    private fun skipQuotedValue(runTerminator: Byte) {
        while (true) {
            val index = source.indexOf(runTerminator)
            if (index == -1L) throw syntaxError("Unterminated string")
            if (buffer[index] == '\\'.code.toByte()) {
                buffer.skip(index + 1)
                readEscapeCharacter()
            } else {
                buffer.skip(index + 1)
                return
            }
        }
    }

    /**
     * This method skips the rest of an xml Element.
     * This method is typically invoked once [beginTag] ang [nextTagName] has been consumed,
     * but we don't want to consume the xml element with the given name.
     * So with this method we can skip the whole remaining xml element (attribute, text content and child elements)
     * by using this method.
     */
    fun skip() {
        val stackPeek = stack[stackSize - 1]
        if (stackPeek != XmlScope.ELEMENT_OPENING && stackPeek != XmlScope.ELEMENT_ATTRIBUTE) {
            throw AssertionError(
                "This method can only be invoked after having consumed the opening element via beginTag()"
            )
        }
        var count = 1
        do {
            when (peek()) {
                Token.BEGIN_TAG -> {
                    beginTag()
                    count++
                }
                Token.END_TAG -> {
                    endTag()
                    count--
                }
                Token.ELEMENT_NAME -> nextTagName() // TODO add a skip element name method
                Token.ATTRIBUTE -> nextAttribute() // TODO add a skip attribute name method
                Token.VALUE -> skipAttributeValue()
                Token.TEXT -> skipText()
                Token.END_DOCUMENT -> throw syntaxError("Unexpected end of file! At least one xml element is not closed!")
            }
            peeked = PEEKED_NONE
        } while (count != 0)
    }

    private fun peekIfNone(): Int {
        val p = peeked
        return if (p == PEEKED_NONE) doPeek() else p
    }

    private fun setPeeked(peekedType: Int): Int {
        peeked = peekedType
        return peekedType
    }

    enum class Token {
        /**
         * Indicates that an xml element begins.
         */
        BEGIN_TAG,

        /**
         * xml element name
         */
        ELEMENT_NAME,

        /**
         * Indicates that an xml element ends
         */
        END_TAG,

        /**
         * Indicates that we are reading an attribute name (of an xml element)
         */
        ATTRIBUTE,

        /**
         * Indicates that we are reading a xml elements attribute value
         */
        VALUE,

        /**
         * Indicates that we are reading the text content of an xml element like this `<element>
         * This is the text content </element>`
         */
        TEXT,

        /**
         * Indicates that we have reached the end of the document
         */
        END_DOCUMENT
    }

    companion object {
        private val TAG_START_TERMINALS = ">/ \n\t\r\u000c".encodeUtf8()
        private val ATTRIBUTE_END_TERMINAL = "= ".encodeUtf8()
        private val ATTRIBUTE_OR_NAMESPACE_END_TERMINAL = ":= ".encodeUtf8()
        private val TAG_OR_NAMESPACE_END_TERMINAL = ":>/ \n\t\r\u000c".encodeUtf8()
        private val CDATA_CLOSE = "]]>".encodeUtf8()
        private val CDATA_OPEN = "<![CDATA[".encodeUtf8()
        private val DOCTYPE_OPEN = "<!DOCTYPE".encodeUtf8()
        private val COMMENT_CLOSE = "-->".encodeUtf8()
        private val XML_DECLARATION_CLOSE = "?>".encodeUtf8()
        private val UTF8_BOM = ByteString.of(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        private const val DOUBLE_QUOTE = '"'.code.toByte()
        private const val SINGLE_QUOTE = '\''.code.toByte()
        private const val OPENING_XML_ELEMENT = '<'.code.toByte()
        private const val CLOSING_XML_ELEMENT = '>'.code.toByte()
        private const val OPENING_DOCTYPE_BRACKET = '['.code.toByte()
        private const val CLOSING_DOCTYPE_BRACKET = ']'.code.toByte()

        //
        // Peek states
        //
        /** Nothing peeked  */
        private const val PEEKED_NONE = 0

        /** Peeked an xml element / object  */
        private const val PEEKED_BEGIN_TAG = 1

        /** Peeked the closing xml tag which indicates the end of an object  */
        private const val PEEKED_END_TAG = 2

        /** Peeked the closing xml header tag, hence we are inner xml tag object body  */
        private const val PEEKED_TEXT = 3

        /** Peeked the end of the stream  */
        private const val PEEKED_EOF = 4

        /** Peeked an unquoted value which can be either xml element name or element attribute name  */
        private const val PEEKED_ELEMENT_NAME = 5

        /** Peeked a quoted value which is the value of an xml attribute  */
        private const val PEEKED_DOUBLE_QUOTED = 6

        /** Peeked a single quote which is the value of an xml attribute  */
        private const val PEEKED_SINGLE_QUOTED = 7

        /** Peeked an attribute name (of a xml element)  */
        private const val PEEKED_ATTRIBUTE_NAME = 8

        /** Peeked a CDATA  */
        private const val PEEKED_CDATA = 9
    }
}