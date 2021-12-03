package com.jonathansteele.parsnip

import com.jonathansteele.parsnip.XmlScope.ELEMENT_CONTENT
import com.jonathansteele.parsnip.XmlScope.ELEMENT_OPENING
import com.jonathansteele.parsnip.XmlScope.NONEMPTY_DOCUMENT
import com.jonathansteele.parsnip.XmlScope.getPath
import com.jonathansteele.parsnip.XmlScope.getTopStackElementAsToken
import okio.BufferedSink
import okio.ByteString.Companion.encodeUtf8
import java.io.Closeable
import java.io.IOException

class XmlWriter(private val sink: BufferedSink): Closeable {
    companion object {
        private const val DOUBLE_QUOTE = '"'.code.toByte()
        private const val OPENING_XML_ELEMENT = '<'.code.toByte()
        private const val CLOSING_XML_ELEMENT = '>'.code.toByte()
        private val CLOSING_XML_ELEMENT_START = "</".encodeUtf8()
        private val INLINE_CLOSING_XML_ELEMENT = "/>".encodeUtf8()
        private val ATTRIBUTE_ASSIGNMENT_BEGIN = "=\"".encodeUtf8()
    }

    private var stack = IntArray(32)
    private var stackSize = 0

    private var pathNames = arrayOfNulls<String>(32)
    private var pathIndices = IntArray(32)

    init {
        stack[stackSize++] = XmlScope.EMPTY_DOCUMENT
    }

    private fun pushStack(newTop: Int) {
        if (stackSize == stack.size) {
            val newStack = IntArray(stackSize * 2)
            val newPathIndices = IntArray(stackSize * 2)
            val newPathNames = arrayOfNulls<String>(stackSize * 2)
            System.arraycopy(stack, 0, newStack, 0, stackSize)
            System.arraycopy(pathIndices, 0, newPathIndices, 0, stackSize)
            System.arraycopy(pathNames, 0, newPathNames, 0, stackSize)
            stack = newStack
            pathIndices = newPathIndices
            pathNames = newPathNames
        }
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
    }

    /**
     * Returns the value on the top of the stack.
     */
    private fun peekStack(): Int {
        check(stackSize != 0) { "XML Writer is closed." }
        return stack[stackSize - 1]
    }

    /**
     * Replace the value on the top of the stack with the given value.
     */
    private fun replaceTopOfStack(topOfStack: Int) {
        stack[stackSize - 1] = topOfStack
    }

    override fun close() {
        sink.close()

        val size = stackSize
        if (size > 1 || size == 1 && stack[size - 1] != NONEMPTY_DOCUMENT) {
            throw IOException(
                "Incomplete document. Abrupt end at " + getPath(
                    stackSize, stack, pathNames,
                    pathIndices
                ) + " in scope " + getTopStackElementAsToken(stackSize, stack)
            )
        }
        stackSize = 0
    }

    /**
     * Throws a new IO exception with the given message and a context snippet with this reader's
     * content.
     */
    @Throws(IOException::class)
    private fun syntaxError(message: String): IOException {
        throw IOException(
            message + " at path " + getPath(stackSize, stack, pathNames, pathIndices)
        )
    }

    /**
     * Begin a new xml tag. Must be closed with [endTag]
     *
     * @param elementTagName The name of the xml element tag
     * @throws IOException
     */
    fun beginTag(elementTagName: String): XmlWriter {
        when (peekStack()) {
            XmlScope.EMPTY_DOCUMENT -> {
                replaceTopOfStack(NONEMPTY_DOCUMENT)
                pushStack(ELEMENT_OPENING)
                pathNames[stackSize - 1] = elementTagName
                sink.writeByte(OPENING_XML_ELEMENT.toInt())
                    .writeUtf8(elementTagName)
            }
            ELEMENT_CONTENT -> {
                pushStack(ELEMENT_OPENING)
                pathNames[stackSize - 1] = elementTagName
                sink.writeByte(OPENING_XML_ELEMENT.toInt())
                    .writeUtf8(elementTagName)
            }
            ELEMENT_OPENING -> {
                replaceTopOfStack(ELEMENT_CONTENT)
                pushStack(ELEMENT_OPENING)
                pathNames[stackSize - 1] = elementTagName
                sink.writeByte(CLOSING_XML_ELEMENT.toInt())
                    .writeByte(OPENING_XML_ELEMENT.toInt())
                    .writeUtf8(elementTagName)
            }
            NONEMPTY_DOCUMENT -> throw IOException(
                "A xml document can only have one root xml element. There is already one but you try to add another" +
                        " one < $elementTagName >"
            )
            else -> throw syntaxError("Unexpected begin of a new xml element < $elementTagName >. " +
                    "New xml elements can only begin on a empty document or in a text content but tried to insert " +
                    "an element on scope " + getTopStackElementAsToken(stackSize, stack)
            )
        }
        return this
    }

    /**
     * Closes a xml element previously opened with [beginTag]
     */
    fun endTag(): XmlWriter {
        when (peekStack()) {
            ELEMENT_OPENING -> {
                sink.write(INLINE_CLOSING_XML_ELEMENT)
                popStack()
            }
            ELEMENT_CONTENT -> {
                sink.write(CLOSING_XML_ELEMENT_START)
                    .writeUtf8(pathNames[stackSize - 1]!!)
                    .writeByte(CLOSING_XML_ELEMENT.toInt())
                popStack()
            }
            else -> {
                val elementName = pathNames[stackSize - 1]
                if (elementName != null) {
                    throw syntaxError("Trying to close the xml element </ $elementName > but I'm in xml scope "
                            + getTopStackElementAsToken(stackSize, stack)
                    )
                } else {
                    throw syntaxError("Trying to close the xml element, but all xml elements are already closed " +
                            "properly. Xml scope is " + getTopStackElementAsToken(stackSize, stack))
                }
            }
        }
        return this
    }

    /**
     * Writes the text content into an element: `<element>text content</element>`
     *
     * @param textContentValue The text content
     */
    fun text(textContentValue: String): XmlWriter {
        when (peekStack()) {
            ELEMENT_OPENING -> {
                sink.writeByte(CLOSING_XML_ELEMENT.toInt())
                replaceTopOfStack(ELEMENT_CONTENT)
                sink.writeUtf8(textContentValue)
            }
            ELEMENT_CONTENT -> sink.writeUtf8(textContentValue)
            else -> {
                val elementName = pathNames[stackSize - 1]
                if (elementName != null) {
                    throw syntaxError(
                        "Error while trying to write text content into xml element < $elementName > " +
                                "$textContentValue </ $elementName>." +
                                " Xml scope was " + getTopStackElementAsToken(stackSize, stack)
                    )
                } else {
                    throw syntaxError("Error while trying to write text content $textContentValue." +
                            " Xml scope was " + getTopStackElementAsToken(stackSize, stack))
                }
            }
        }
        return this
    }

    /**
     * Writes a xml attribute and the corresponding value.
     * Must be called after [beginTag] and before [endTag] or [text]
     *
     * @param attributeName The name of the attribute
     * @param value the value
     */
    fun attribute(attributeName: String, value: String): XmlWriter {
        if (ELEMENT_OPENING == peekStack()) {
            sink.writeByte(' '.code) // Write a whitespace
                .writeUtf8(attributeName)
                .write(ATTRIBUTE_ASSIGNMENT_BEGIN)
                .writeUtf8(value)
                .writeByte(DOUBLE_QUOTE.toInt())
        } else {
            throw syntaxError(
                "Error while trying to write attribute "
                        + attributeName
                        + "=\""
                        + value
                        + "\". Attributes can only be written in a opening xml element but was in xml scope "
                        + getTopStackElementAsToken(stackSize, stack)
            )
        }
        return this
    }

    fun namespace(namespace: Namespace) : XmlWriter {
        return if (!namespace.alias.isNullOrEmpty()) {
            attribute("xmlns:" + namespace.alias, namespace.namespace!!)
        } else {
            attribute("xmlns", namespace.namespace!!)
        }
    }
}