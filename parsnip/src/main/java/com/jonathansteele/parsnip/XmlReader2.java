package com.jonathansteele.parsnip;

import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

//TODO: Improving Parsing
public class XmlReader2 implements Closeable {
    private static final ByteString TAG_START_TERMINALS = ByteString.encodeUtf8(">/ \n\t\r\f");
    private static final ByteString UNQUOTED_STRING_TERMINALS = ByteString.encodeUtf8(" >/=\n");
    private static final ByteString ATTRIBUTE_END_TERMINAL = ByteString.encodeUtf8("= ");
    private static final ByteString TAG_OR_NAMESPACE_END_TERMINAL = ByteString.encodeUtf8(":>/ \n\t\r\f");
    private static final ByteString ATTRIBUTE_OR_NAMESPACE_END_TERMINAL = ByteString.encodeUtf8(":= ");
    private static final ByteString SINGLE_QUOTE_OR_AMP = ByteString.encodeUtf8("'&");
    private static final ByteString DOUBLE_QUOTE_OR_AMP = ByteString.encodeUtf8("\"&");
    private static final ByteString TAG_TERMINAL = ByteString.encodeUtf8("<>");

    private static final ByteString CDATA_CLOSE = ByteString.encodeUtf8("]]>");
    private static final ByteString CDATA_OPEN = ByteString.encodeUtf8("<![CDATA[");
    private static final ByteString DOCTYPE_OPEN = ByteString.encodeUtf8("<!DOCTYPE");
    private static final ByteString COMMENT_CLOSE = ByteString.encodeUtf8("-->");
    private static final ByteString XML_DECLARATION_CLOSE = ByteString.encodeUtf8("?>");
    private static final ByteString UTF8_BOM = ByteString.of((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);

    private static final byte DOUBLE_QUOTE = '"';
    private static final byte SINGLE_QUOTE = '\'';
    private static final byte OPENING_XML_ELEMENT = '<';
    private static final byte CLOSING_XML_ELEMENT = '>';
    private static final byte OPENING_DOCTYPE_BRACKET = '[';
    private static final byte CLOSING_DOCTYPE_BRACKET = ']';
    private static final byte ENTITY_END_TERMINAL = (byte) ';';

    //
    // Peek states
    //
    /** Nothing peeked */
    private static final int PEEKED_NONE = 0;
    /** Peeked an xml element / object */
    private static final int PEEKED_BEGIN_TAG = 1;
    /** Peeked the closing xml tag which indicates the end of an object */
    private static final int PEEKED_END_TAG = 2;
    /** Peeked the closing xml header tag, hence we are inner xml tag object body */
    private static final int PEEKED_TEXT = 3;
    /** Peeked the end of the stream */
    private static final int PEEKED_EOF = 4;
    /** Peeked an unquoted value which can be either xml element name or element attribute name */
    private static final int PEEKED_ELEMENT_NAME = 5;
    /** Peeked a quoted value which is the value of an xml attribute */
    private static final int PEEKED_DOUBLE_QUOTED = 6;
    /** Peeked a single quote which is the value of an xml attribute */
    private static final int PEEKED_SINGLE_QUOTED = 7;
    /** Peeked an attribute name (of a xml element) */
    private static final int PEEKED_ATTRIBUTE_NAME = 8;

    /** Peeked a CDATA */
    private static final int PEEKED_CDATA = 9;

    /** The input XML. */
    private int peeked = PEEKED_NONE;

    private String[] pathNames = new String[32];
    private int[] pathIndices = new int[32];

    // We need to store all the attributes we come across for a given tag so that we can validate
    // duplicates
    private String[] attributeNames = new String[32];
    private String[] attributeNamespaces = new String[32];
    private int attributeSize = 0;

    // Array of namespace keys (think 'foo' in 'xmlns:foo="bar"') sorted for quick binary search.
    private String[] namespaceKeys = new String[4];
    // Array of namespace values (think 'bar' in 'xmlns:foo="bar"') sorted to match indices of keys.
    private String[] namespaceValues = new String[4];
    // Array of position in the stack for the namespace, used to remove them when the stack is popped.
    private int[] namespaceStackPositions = new int[4];
    // Array of default namespaces (or null if there is one) for the given position in the stack.
    private String[] defaultNamespaces = new String[32];
    private String[][] shadowedNamespaces = null;
    private int namespaceSize = 0;
    // We have to eagerly parse the next attribute in order to skip xmlns declarations,
    // therefore we should save what it was.
    private String lastAttribute = null;
    private final Namespace tempNamespace = new Namespace();

    /*
     * The nesting stack. Using a manual array rather than an ArrayList saves 20%.
     */
    private int[] stack = new int[32];
    private int stackSize = 0;

    {
        stack[stackSize++] = XmlScope.EMPTY_DOCUMENT;
    }

    private final BufferedSource source;
    private final Buffer buffer;
    private final Buffer nextStringBuffer;

    XmlReader2(BufferedSource source) {
        if (source == null) {
            throw new NullPointerException("source == null");
        }
        this.source = source;
        this.buffer = source.getBuffer();
        this.nextStringBuffer = new Buffer();
    }

    /**
     * Get the next token without consuming it.
     *
     * @return {@link Token}
     */
    public Token peek() throws IOException {
        int p = peeked;
        if (p == PEEKED_NONE) {
            p = doPeek();
        }

        switch (p) {
            case PEEKED_BEGIN_TAG:
                return Token.BEGIN_TAG;

            case PEEKED_ELEMENT_NAME:
                return Token.ELEMENT_NAME;

            case PEEKED_END_TAG:
                return Token.END_TAG;

            case PEEKED_ATTRIBUTE_NAME:
                return Token.ATTRIBUTE;

            case PEEKED_DOUBLE_QUOTED:
            case PEEKED_SINGLE_QUOTED:
                return Token.ATTRIBUTE_VALUE;

            case PEEKED_TEXT:
            case PEEKED_CDATA:
                return Token.TEXT;

            case PEEKED_EOF:
                return Token.END_DOCUMENT;
            default:
                throw new AssertionError("Unknown XmlToken: Peeked = " + p);
        }
    }

    /**
     * Actually do a peek. This method will return the peeked token and updates the internal variable
     * {@link #peeked}
     *
     * @return The peeked token
     */
    private int doPeek() throws IOException {
        int peekStack = stack[stackSize - 1];
        if (peekStack == XmlScope.ELEMENT_OPENING) {
            int c = nextNonWhitespace(true);
            if (isLiteral((char) c)) {
                return peeked = PEEKED_ELEMENT_NAME;
            } else {
                throw syntaxError("Expected xml element name (literal expected)");
            }
        } else if (peekStack == XmlScope.ELEMENT_ATTRIBUTE) {
            int c = nextNonWhitespace(true);

            if (isLiteral(c)) {
                return peeked = PEEKED_ATTRIBUTE_NAME;
            }

            switch (c) {
                case '>':
                    // remove XmlScope.ELEMENT_ATTRIBUTE from top of the stack
                    popStack();

                    // set previous stack from XmlScope.ELEMENT_OPENING to XmlScope.ELEMENT_CONTENT
                    stack[stackSize - 1] = XmlScope.ELEMENT_CONTENT;
                    buffer.readByte(); // consume '>'

                    int nextChar = nextNonWhitespace(true);

                    if (nextChar != '<') {
                        return peeked = PEEKED_TEXT;
                    }

                    if (isCDATA()) {
                        buffer.skip(9); // skip opening cdata tag
                        return peeked = PEEKED_CDATA;
                    }
                    break;

                case '/':
                    // Self closing />

                    if (fillBuffer(2) && buffer.getByte(1) == '>') {
                        // remove XmlScope.ELEMENT_ATTRIBUTE from top of the stack
                        popStack();

                        // correct closing xml tag
                        buffer.skip(2); // consuming '/>'

                        return peeked = PEEKED_END_TAG;
                    } else {
                        throw syntaxError("Expected closing />");
                    }

                case '=':
                    buffer.readByte(); // consume '='

                    // Read next char which should be a quote
                    c = nextNonWhitespace(true);

                    switch (c) {
                        case '"':
                            buffer.readByte(); // consume "
                            return peeked = PEEKED_DOUBLE_QUOTED;
                        case '\'':
                            buffer.readByte(); // consume '
                            return peeked = PEEKED_SINGLE_QUOTED;

                        default:
                            throw syntaxError(
                                    "Expected double quote (\") or single quote (') while reading xml elements attribute");
                    }

                default:
                    throw syntaxError("Unexpected character '"
                            + ((char) c)
                            + "' while trying to read xml elements attribute");
            }
        } else if (peekStack == XmlScope.ELEMENT_CONTENT) {
            int c = nextNonWhitespace(true);

            if (c != '<') {
                return peeked = PEEKED_TEXT;
            }

            if (isCDATA()) {
                buffer.skip(9); // skip opening cdata tag
                return peeked = PEEKED_CDATA;
            }
        } else if (peekStack == XmlScope.EMPTY_DOCUMENT) {
            stack[stackSize - 1] = XmlScope.NONEMPTY_DOCUMENT;
        } else if (peekStack == XmlScope.NONEMPTY_DOCUMENT) {
            int c = nextNonWhitespace(false);
            if (c == -1) {
                return peeked = PEEKED_EOF;
            }
        } else if (peekStack == XmlScope.CLOSED) {
            throw new IllegalStateException("XmlReader is closed");
        }

        int c = nextNonWhitespace(true, peekStack == XmlScope.EMPTY_DOCUMENT);
        switch (c) {
            // Handling open < and closing </
            case '<':
                buffer.readByte(); // consume '<'.

                // Check if </ which means end of element
                if (fillBuffer(1) && buffer.getByte(0) == '/') {

                    buffer.readByte(); // consume /

                    // Check if it is the corresponding xml element name
                    String closingElementName = nextUnquotedValue();
                    if (closingElementName.equals(pathNames[stackSize - 1])) {

                        if (nextNonWhitespace(false) == '>') {
                            buffer.readByte(); // consume >
                            return peeked = PEEKED_END_TAG;
                        } else {
                            syntaxError("Missing closing '>' character in </" + pathNames[stackSize - 1]);
                        }
                    } else {
                        syntaxError("Expected a closing element tag </"
                                + pathNames[stackSize - 1]
                                + "> but found </"
                                + closingElementName
                                + ">");
                    }
                }
                // its just a < which means begin of the element
                return peeked = PEEKED_BEGIN_TAG;

            case '"':
                buffer.readByte(); // consume '"'.
                return peeked = PEEKED_DOUBLE_QUOTED;

            case '\'':
                buffer.readByte(); // consume '
                return peeked = PEEKED_SINGLE_QUOTED;
        }

        return PEEKED_NONE;
    }

    /**
     * Checks for CDATA beginning {@code <![CDATA[ }. This method doesn't consume the opening CDATA
     * Tag
     *
     * @return true, if CDATA opening tag, otherwise false
     */
    private boolean isCDATA() throws IOException {
        return fillBuffer(CDATA_OPEN.size()) && buffer.rangeEquals(0, CDATA_OPEN);
    }

    /**
     * Checks for DOCTYPE beginning {@code <!DOCTYPE }. This method doesn't consume the opening <!DOCTYPE
     * Tag
     *
     * @return true, if DOCTYPE opening tag, otherwise false
     */
    private boolean isDocTypeDefinition() {
        return buffer.size() >= DOCTYPE_OPEN.size() &&
                buffer.snapshot(DOCTYPE_OPEN.size()).toAsciiUppercase().equals(DOCTYPE_OPEN);
    }

    /**
     * Skips to the end of current tag, including all nested child tags. The 'current' tag is the
     * tag you last obtained from {@link #beginTag()}. This will consume up to and including {@link
     * XmlReader.Token#END_TAG}.
     */
    public void skipTag() throws IOException {
        int p = peeked;
        if (p == PEEKED_NONE) {
            p = doPeek();
        }

        int depth = 1;
        while (true) {
            switch (p) {
                case PEEKED_END_TAG:
                    endTag();
                    depth--;
                    if (depth == 0) {
                        return;
                    } else {
                        break;
                    }
                case PEEKED_BEGIN_TAG:
                    beginTag(tempNamespace);
                    depth++;
                    break;
                case PEEKED_EOF:
                    return;
                case PEEKED_CDATA:
                case PEEKED_TEXT:
                    skipText();
                default:
                    lastAttribute = null;
                    long i = source.indexOfElement(TAG_TERMINAL);
                    if (i == -1) {
                        peeked = PEEKED_EOF;
                        return;
                    }
                    int c = buffer.getByte(i);
                    if (c == '<') {
                        pushStack(XmlScope.NONEMPTY_DOCUMENT);
                        source.skip(i);
                    } else { // '>'
                        int c1 = buffer.getByte(i - 1);
                        pushStack(XmlScope.ELEMENT_OPENING);
                        if (c1 == '/') {
                            // Self-closing.
                            source.skip(i - 1);
                        } else {
                            source.skip(i);
                        }
                    }
            }
            p = doPeek();
        }
    }

    /**
     * Skips the currently peeked token.
     */
    public void skip() throws IOException {
        int p = peeked;
        if (p == PEEKED_NONE) {
            p = doPeek();
        }
        switch (p) {
            case PEEKED_BEGIN_TAG:
                beginTag(tempNamespace);
                break;
            case PEEKED_END_TAG:
                endTag();
                break;
            case PEEKED_ATTRIBUTE_NAME:
                skipAttribute();
                break;
            case PEEKED_SINGLE_QUOTED:
                skipTerminatedString(SINGLE_QUOTE);
                buffer.readByte(); // '''
                break;
            case PEEKED_DOUBLE_QUOTED:
                skipTerminatedString(DOUBLE_QUOTE);
                buffer.readByte(); // '"'
                break;
            case PEEKED_TEXT:
            case PEEKED_CDATA:
                skipText();
                break;
            case PEEKED_EOF:
                throw new EOFException("End of input");
        }
    }

    private void skipAttribute() throws IOException {
        nextAttribute(); // TODO: more efficient impl.
    }

    /**
     * Consumes the next token from the JSON stream and asserts that it is the beginning of a new
     * object.
     */
    public String beginTag() throws IOException {
        return beginTag(tempNamespace);
    }

    public String beginTag(Namespace namespace) throws IOException {
        int p = peeked;
        if (p == PEEKED_NONE) {
            p = doPeek();
        }
        if (p == PEEKED_BEGIN_TAG) {
            pushStack(XmlScope.ELEMENT_OPENING);
            String tag = nextTag(namespace);
            pathNames[stackSize - 1] = namespace.alias == null ? tag : namespace.alias + ":" + tag;
            peeked = PEEKED_NONE;
            return tag;
        } else {
            throw new XmlDataException("Expected " + Token.BEGIN_TAG + " but was " + peek()
                    + " at path " + getPath());
        }
    }

    /**
     * Consumes the next token from the JSON stream and asserts that it is the end of the current
     * object.
     */
    public void endTag() throws IOException {
        int p = peeked;
        if (p == PEEKED_NONE) {
            p = doPeek();
        }

        if (p == PEEKED_END_TAG) {
            validateEndTag(pathNames[stackSize - 1]);
        }

        if (p == PEEKED_END_TAG) {
            popStack();
            peeked = PEEKED_NONE;
        } else {
            throw syntaxError("Expected end of element but was " + peek());
        }
    }

    public String nextAttribute() throws IOException {
        return nextAttribute(tempNamespace);
    }

    public String nextAttribute(Namespace namespace) throws IOException {
        int p = peeked;
        if (p == PEEKED_NONE) {
            p = doPeek();
        }

        if (p == PEEKED_ATTRIBUTE_NAME) {
            String attribute;
            if (lastAttribute != null) {
                namespace.namespace = tempNamespace.namespace;
                namespace.alias = tempNamespace.alias;
                attribute = lastAttribute;
                lastAttribute = null;
            } else {
                // We must skip any xmlns attributes
                do {
                    attribute = readNextAttribute(namespace);
                } while (attribute == null);
            }

            int attributeSize = this.attributeSize;

            for (int i = 0; i < attributeSize; i++) {
                String name = attributeNames[i];
                if (attribute.equals(name)) {
                    String namespaceName = attributeNamespaces[i];
                    if (namespace.namespace == null && namespaceName == null) {
                        throw new XmlDataException("Duplicate attribute '" + name + "' at path " + getPath());
                    } else if (namespace.namespace != null) {
                        if (namespace.namespace.equals(namespaceName)) {
                            throw new XmlDataException("Duplicate attribute '{" + namespace + "}" + name + "' at path " + getPath());
                        }
                    }
                }
            }

            if (attributeSize == attributeNames.length) {
                String[] newAttributeNames = new String[attributeSize * 2];
                System.arraycopy(attributeNames, 0, newAttributeNames, 0, attributeSize);
                attributeNames = newAttributeNames;
                String[] newAttributeNamespaces = new String[attributeSize * 2];
                System.arraycopy(attributeNamespaces, 0, newAttributeNamespaces, 0, attributeSize);
                attributeNamespaces = newAttributeNamespaces;
            }

            attributeNames[attributeSize] = attribute;
            attributeNamespaces[attributeSize] = namespace.namespace;
            this.attributeSize++;

            peeked = PEEKED_NONE;
            return attribute;
        } else {
            throw new XmlDataException("Expected ATTRIBUTE but was " + peek() + " at path " + getPath());
        }
    }

    public String nextValue() throws IOException {
        int p = peeked;
        if (p == PEEKED_NONE) {
            p = doPeek();
        }

        if (p == PEEKED_SINGLE_QUOTED) {
            nextTerminatedString(SINGLE_QUOTE_OR_AMP, nextStringBuffer);
            buffer.readByte(); // '''
        } else if (p == PEEKED_DOUBLE_QUOTED) {
            nextTerminatedString(DOUBLE_QUOTE_OR_AMP, nextStringBuffer);
            buffer.readByte(); // '"'
        } else {
            throw new XmlDataException("Expected VALUE but was " + peek() + " at path " + getPath());
        }
        peeked = PEEKED_NONE;
        return nextStringBuffer.readUtf8();
    }

    /**
     * Get the next text content of an xml element. Text content is {@code <element>text
     * content</element>}
     *
     * If the element is empty (no content) like {@code <element></element>} this method will return
     * the empty string "".
     *
     * {@code null} as return type is not supported yet, because there is no way in xml to distinguish
     * between empty string "" or null since both might be represented with {@code
     * <element></element>}. So if you want to represent a null element, simply don't write the
     * corresponding xml tag. Then the parser will not try set the mapped field and it will remain the
     * default value (which is null).
     *
     * @return The xml element's text content
     */
    public String nextText() throws IOException {
        int p = peeked;
        if (p == PEEKED_NONE) {
            p = doPeek();
        }

        if (p == PEEKED_TEXT) {

            peeked = PEEKED_NONE;

            // Read text until '<' found
            long index = source.indexOf(OPENING_XML_ELEMENT);
            if (index == -1L) {
                throw syntaxError("Unterminated element text content. Expected </"
                        + pathNames[stackSize - 1]
                        + "> but haven't found");
            }

            return buffer.readUtf8(index);
        } else if (p == PEEKED_CDATA) {
            peeked = PEEKED_NONE;

            // Search index of closing CDATA tag ]]>
            long index = indexOfClosingCDATA();

            String result = buffer.readUtf8(index);
            buffer.skip(3); // consume ]]>
            return result;
        } else if (p == PEEKED_END_TAG) {
            // this is an element without any text content. i.e. <foo></foo>.
            // In that case we return the default value of a string which is the empty string

            // Don't do peeked = PEEKED_NONE; because that would consume the end tag, which we haven't done yet.
            return "";
        } else {
            throw new XmlDataException("Expected xml element text content but was " + peek()
                    + " at path " + getPath());
        }
    }

    /**
     * Skip the text content. Text content is {@code <element>text content</element>}
     */
    public void skipText() throws IOException {
        int p = peeked;
        if (p == PEEKED_NONE) {
            p = doPeek();
        }

        if (p == PEEKED_TEXT) {
            peeked = PEEKED_NONE;

            // Read text until '<' found
            long index = source.indexOf(OPENING_XML_ELEMENT);
            if (index == -1L) {
                throw syntaxError("Unterminated element text content. Expected </"
                        + pathNames[stackSize - 1]
                        + "> but haven't found");
            }

            buffer.skip(index);
        } else if (p == PEEKED_CDATA) {
            peeked = PEEKED_NONE;
            // Search index of closing CDATA tag ]]>
            long index = indexOfClosingCDATA();
            buffer.skip(index + 3); // +3 because of consuming closing tag
        } else {
            throw new XmlDataException("Expected xml element text content but was " + peek()
                    + " at path " + getPath());
        }
    }

    /**
     * Returns the index of the last character before starting the CDATA closing tag "{@code ]]>}".
     * This method does not consume the closing CDATA tag.
     *
     * @return index of last character before closing tag.
     */
    private long indexOfClosingCDATA() throws IOException {
        long index = source.indexOf(CDATA_CLOSE);
        if (index == -1) {
            throw new EOFException("<![CDATA[ at " + getPath() + " has never been closed with ]]>");
        }
        return index;
    }

    /**
     * Skips the string up to but not including {@code runTerminator}. This does not consume the
     * {@code runTerminator}.
     */
    private void skipTerminatedString(byte runTerminator) throws IOException {
        long index = source.indexOf(runTerminator);
        if (index == -1L) {
            throw syntaxError("Unterminated string");
        }
        source.skip(index);
        peeked = PEEKED_NONE;
    }

    /**
     * Returns the string up to but not including {@code runTerminator}, expanding any entities
     * encountered along the way. This does not consume the {@code runTerminator}.
     *
     * @throws IOException if any entities are malformed.
     */
    private void nextTerminatedString(ByteString runTerminator, Buffer outBuffer) throws IOException {
        while (true) {
            long index = source.indexOfElement(runTerminator);
            if (index == -1L) {
                throw syntaxError("Unterminated string");
            }

            byte c = buffer.getByte(index);
            // If we've got an entity, we're going to need a string builder.
            if (c == '&') {
                outBuffer.write(buffer, index);
                buffer.readByte(); // '&'
                readEntity(outBuffer);
                continue;
            }
            outBuffer.write(buffer, index);
            return;
        }
    }

    /**
     * Push a new scope on top of the scope stack
     *
     * @param newTop The scope that should be pushed on top of the stack
     */
    private void pushStack(int newTop) {
        if (stackSize == stack.length) {
            int[] newStack = new int[stackSize * 2];
            int[] newPathIndices = new int[stackSize * 2];
            String[] newPathNames = new String[stackSize * 2];
            System.arraycopy(stack, 0, newStack, 0, stackSize);
            System.arraycopy(pathIndices, 0, newPathIndices, 0, stackSize);
            System.arraycopy(pathNames, 0, newPathNames, 0, stackSize);

            if (shadowedNamespaces != null) {
                String[][] newShadowedNamespaces = new String[stackSize * 2][];
                System.arraycopy(shadowedNamespaces, 0, newShadowedNamespaces, 0, stackSize);
                shadowedNamespaces = newShadowedNamespaces;
            }

            String[] newDefaultNamespaces = new String[stackSize * 2];
            System.arraycopy(defaultNamespaces, 0, newDefaultNamespaces, 0, stackSize);
            defaultNamespaces = newDefaultNamespaces;

            stack = newStack;
            pathIndices = newPathIndices;
            pathNames = newPathNames;
        }
        defaultNamespaces[stackSize] = defaultNamespaces[stackSize - 1];
        stack[stackSize++] = newTop;
    }

    /**
     * Removes the top element of the stack
     */
    private void popStack() {
        stack[stackSize - 1] = 0;
        stackSize--;
        pathNames[stackSize] = null; // Free the last path name so that it can be garbage collected!
        pathIndices[stackSize - 1]++;
        if (stackSize > 1) {
            int namespaceSize = this.namespaceSize;
            int removeCount = 0;
            for (int i = namespaceSize - 1; i >= 0; i--) {
                if (stackSize < namespaceStackPositions[i]) {
                    namespaceSize--;
                    removeCount++;
                    int len = namespaceSize;
                    if (len > 0) {
                        System.arraycopy(namespaceStackPositions, i, namespaceStackPositions, i - 1, len);
                        System.arraycopy(namespaceKeys, i, namespaceKeys, i - 1, len);
                        System.arraycopy(namespaceValues, i, namespaceValues, i - 1, len);
                    }
                }
            }
            this.namespaceSize -= removeCount;
            defaultNamespaces[stackSize] = null;
        }
        if (shadowedNamespaces != null) {
            String[] indexesForStack = shadowedNamespaces[stackSize];
            for (int i = 0; i < indexesForStack.length; i++) {
                String value = indexesForStack[i];
                if (value == null) continue;
                namespaceValues[i] = value;
            }
            shadowedNamespaces[stackSize] = null;
        }
    }

    /**
     * Returns a XPath to the current location in the XML value.
     */
    public String getPath() {
        return XmlScope.getPath(stackSize, stack, pathNames, pathIndices);
    }

    /**
     * Returns true once {@code limit - pos >= minimum}. If the data is exhausted before that many
     * characters are available, this returns false.
     */
    private boolean fillBuffer(long minimum) throws IOException {
        return source.request(minimum);
    }

    /**
     * Returns the next character in the stream that is neither whitespace nor a part of a comment.
     * When this returns, the returned character is always at {@code buffer[pos-1]}; this means the
     * caller can always pushStack back the returned character by decrementing {@code pos}.
     */
    private int nextNonWhitespace(boolean throwOnEof) throws IOException {
        return nextNonWhitespace(throwOnEof, false);
    }

    /**
     * Returns the next character in the stream that is neither whitespace nor a part of a comment.
     * When this returns, the returned character is always at {@code buffer[pos-1]}; this means the
     * caller can always pushStack back the returned character by decrementing {@code pos}.
     */
    private int nextNonWhitespace(boolean throwOnEof, boolean isDocumentBeginning) throws IOException {
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
            source.skip(3);
        }

        int p = 0;
        while (fillBuffer(p + 1)) {
            int c = buffer.getByte(p++);
            if (c == '\n' || c == ' ' || c == '\r' || c == '\t') {
                continue;
            }

            buffer.skip(p - 1);
            if (c == '<' && !isCDATA() && fillBuffer(2)) {

                byte peek = buffer.getByte(1);
                int peekStack = stack[stackSize - 1];

                if (peekStack == XmlScope.NONEMPTY_DOCUMENT && isDocTypeDefinition()) {
                    long index = source.indexOf(CLOSING_XML_ELEMENT, DOCTYPE_OPEN.size());
                    if (index == -1) {
                        throw syntaxError("Unterminated <!DOCTYPE> . Inline DOCTYPE is not support at the moment.");
                    }
                    // check if doctype uses brackets
                    long bracketIndex = source.indexOf(OPENING_DOCTYPE_BRACKET, DOCTYPE_OPEN.size(), index);
                    if (bracketIndex != -1) {
                        index = source.indexOf(ByteString.of(CLOSING_DOCTYPE_BRACKET, CLOSING_XML_ELEMENT), index + bracketIndex);
                        if (index == -1) {
                            throw syntaxError("Unterminated <!DOCTYPE []>. Expected closing ]>");
                        }
                        source.skip(index + 2); // skip behind ]>
                    } else {
                        source.skip(index + 1); // skip behind >
                    }
                    // TODO inline DOCTYPE.
                    p = 0;
                    continue;
                } else if (peek == '!' && fillBuffer(4)) {
                    long index = source.indexOf(COMMENT_CLOSE, 4); // skip <!-- in comparison by offset 4
                    if (index == -1) {
                        throw syntaxError("Unterminated comment");
                    }
                    source.skip(index + COMMENT_CLOSE.size()); // skip behind --!>
                    p = 0;
                    continue;
                } else if (peek == '?') {
                    long index = source.indexOf(XML_DECLARATION_CLOSE, 2); // skip <? in comparison by offset 2
                    if (index == -1) {
                        throw syntaxError("Unterminated xml declaration or processing instruction \"<?\"");
                    }
                    source.skip(index + XML_DECLARATION_CLOSE.size()); // skip behind ?>
                    p = 0;
                    continue;
                }
            }

            return c;
        }

        if (throwOnEof) {
            throw new EOFException("Unexpected end of input at path " + getPath());
        } else {
            return -1;
        }
    }

    private void insertNamespace(String key, String value) {
        int namespaceSize = this.namespaceSize;
        int searchIndex = Arrays.binarySearch(namespaceKeys, 0, namespaceSize, key);

        int insertIndex;
        if (searchIndex >= 0) {
            insertIndex = searchIndex;
            if (shadowedNamespaces == null) {
                shadowedNamespaces = new String[stackSize][];
            }
            String[] indexesForStack = shadowedNamespaces[stackSize - 1];
            if (indexesForStack == null) {
                indexesForStack = shadowedNamespaces[stackSize - 1] = new String[searchIndex + 1];
            }
            if (searchIndex > indexesForStack.length) {
                String[] newIndexesForStack = new String[searchIndex + 1];
                System.arraycopy(indexesForStack, 0, newIndexesForStack, 0, searchIndex + 1);
                indexesForStack = shadowedNamespaces[stackSize - 1] = newIndexesForStack;
            }
            indexesForStack[searchIndex] = namespaceValues[searchIndex];
        } else {
            insertIndex = ~searchIndex;
        }

        if (namespaceSize == namespaceKeys.length) {
            String[] newNamespaceKeys = new String[namespaceSize * 2];
            System.arraycopy(namespaceKeys, 0, newNamespaceKeys, 0, insertIndex);
            newNamespaceKeys[insertIndex] = key;
            System.arraycopy(namespaceKeys, insertIndex, newNamespaceKeys, insertIndex + 1, namespaceSize - insertIndex);

            String[] newNamespaceValues = new String[namespaceSize * 2];
            System.arraycopy(namespaceValues, 0, newNamespaceValues, 0, insertIndex);
            newNamespaceValues[insertIndex] = value;
            System.arraycopy(namespaceValues, insertIndex, newNamespaceValues, insertIndex + 1, namespaceSize - insertIndex);

            int[] newNamespaceStackPositions = new int[namespaceSize * 2];
            System.arraycopy(namespaceStackPositions, 0, newNamespaceStackPositions, 0, insertIndex);
            newNamespaceStackPositions[insertIndex] = stackSize;
            System.arraycopy(namespaceStackPositions, insertIndex, newNamespaceStackPositions, insertIndex + 1, namespaceSize - insertIndex);

            namespaceKeys = newNamespaceKeys;
            namespaceValues = newNamespaceValues;
            namespaceStackPositions = newNamespaceStackPositions;
        } else {
            System.arraycopy(namespaceKeys, insertIndex, namespaceKeys, insertIndex + 1, namespaceSize - insertIndex);
            namespaceKeys[insertIndex] = key;

            System.arraycopy(namespaceValues, insertIndex, namespaceValues, insertIndex + 1, namespaceSize - insertIndex);
            namespaceValues[insertIndex] = value;

            System.arraycopy(namespaceStackPositions, insertIndex, namespaceStackPositions, insertIndex + 1, namespaceSize - insertIndex);
            namespaceStackPositions[insertIndex] = stackSize;
        }
        this.namespaceSize++;
    }

    private String namespaceValue(String key) {
        int index = Arrays.binarySearch(namespaceKeys, 0, namespaceSize, key);
        if (index >= 0) {
            return namespaceValues[index];
        } else {
            return null;
        }
    }

    /**
     * Returns the next tag and fills the given namespace.
     */
    private String nextTag(Namespace namespace) throws IOException {
        // There may be space between the opening and the tag.
        nextNonWhitespace(true);
        long i = source.indexOfElement(TAG_OR_NAMESPACE_END_TERMINAL);
        String tagOrNs = i != -1 ? buffer.readUtf8(i) : buffer.readUtf8();
        fillBuffer(1);
        int n = buffer.getByte(0);
        if (n == ':') {
            buffer.readByte(); // ':'
            namespace.alias = tagOrNs;
            namespace.namespace = namespaceValue(tagOrNs);
            return readNextTagName();
        } else {
            namespace.alias = null;
            namespace.namespace = defaultNamespaces[stackSize - 1];
            return tagOrNs;
        }
    }

    private String readNextTagName() throws IOException {
        long i = source.indexOfElement(TAG_START_TERMINALS);
        return i != -1 ? buffer.readUtf8(i) : buffer.readUtf8();
    }

    /**
     * Reads the next attribute, and it's namespace if not null. Since declaring namespaces are
     * attributes themselves, this method may return null if it is parsing a xmlns declaration. In
     * that case, the attribute should be skipped and not given to the client.
     */
    private String readNextAttribute(Namespace namespace) throws IOException {
        long i = source.indexOfElement(ATTRIBUTE_OR_NAMESPACE_END_TERMINAL);
        String attrOrNs = i != -1 ? buffer.readUtf8(i) : buffer.readUtf8();
        fillBuffer(1);
        int n = buffer.getByte(0);
        if (n == ':') {
            buffer.readByte(); // ':'
            if ("xmlns".equals(attrOrNs)) {
                String name = readNextAttributeName();
                pushStack(XmlScope.ELEMENT_ATTRIBUTE);
                peeked = PEEKED_NONE;
                String value = nextValue();
                insertNamespace(name, value);
                return null;
            } else {
                if (namespace != null) {
                    namespace.alias = attrOrNs;
                    namespace.namespace = namespaceValue(attrOrNs);
                }
                return readNextAttributeName();
            }
        } else {
            if ("xmlns".equals(attrOrNs)) {
                pushStack(XmlScope.ELEMENT_ATTRIBUTE);
                peeked = PEEKED_NONE;
                String value = nextValue();
                defaultNamespaces[stackSize - 1] = value;
                return null;
            } else {
                if (namespace != null) {
                    namespace.alias = null;
                    namespace.namespace = defaultNamespaces[stackSize - 1];
                }
                return attrOrNs;
            }
        }
    }

    private String readNextAttributeName() throws IOException {
        long i = source.indexOfElement(ATTRIBUTE_END_TERMINAL);
        return i != -1 ? buffer.readUtf8(i) : buffer.readUtf8();
    }

    private void validateEndTag(String name) throws IOException {
        fillBuffer(name.length() + 1);
        String end = source.readUtf8(name.length());
        if (!name.equals(end)) {
            throw syntaxError("Mismatched tags: Expected '" + name + "' but was '" + end + "'");
        }
    }

    /**
     * Reads an entity and replaces it with its value. The '&' should already have been read. This
     * supports both built-in and user-defined entities.
     *
     * @throws IOException if the entity is malformed
     */
    private void readEntity(Buffer outputBuffer) throws IOException {
        long index = source.indexOf(ENTITY_END_TERMINAL);
        if (index == -1) {
            throw syntaxError("Unterminated entity sequence");
        }

        String entity = buffer.readUtf8(index);
        buffer.readByte(); // ';'

        if (entity.charAt(0) == '#') {
            int result = 0;

            if (entity.charAt(1) == 'x') {
                int len = (int) (index - 2);

                for (int i = 2 /* #x */, end = i + len; i < end; i++) {
                    int c = entity.charAt(i);
                    result <<= 4;
                    if (c >= '0' && c <= '9') {
                        result += (c - '0');
                    } else if (c >= 'a' && c <= 'f') {
                        result += (c - 'a' + 10);
                    } else if (c >= 'A' && c <= 'F') {
                        result += (c - 'A' + 10);
                    } else {
                        throw syntaxError(entity);
                    }
                }
            } else {
                int len = (int) (index - 1);

                // 10^(len-1)
                int n = 1;
                for (int i = 1; i < len; i++) {
                    n *= 10;
                }

                for (int i = 1 /* # */, end = i + len; i < end; i++) {
                    int c = entity.charAt(i);
                    if (c >= '0' && c <= '9') {
                        result += n * (c - '0');
                    } else {
                        throw syntaxError(entity);
                    }
                    n /= 10;
                }
            }
            outputBuffer.writeUtf8CodePoint(result);
        } else {
            switch (entity) {
                case "quot":
                    outputBuffer.writeByte('"');
                    break;
                case "apos":
                    outputBuffer.writeByte('\'');
                    break;
                case "lt":
                    outputBuffer.writeByte('<');
                    break;
                case "gt":
                    outputBuffer.writeByte('>');
                    break;
                case "amp":
                    outputBuffer.writeByte('&');
                    break;
                default:
                    throw syntaxError("User-defined entities not yet supported");
            }
        }
    }

    /**
     * Throws a new IO exception with the given message and a context snippet with this reader's
     * content.
     */
    private IOException syntaxError(String message) throws IOException {
        throw new IOException(message + " at path " + getPath());
    }

    /** Returns an unquoted value as a string. */
    private String nextUnquotedValue() throws IOException {
        long i = source.indexOfElement(UNQUOTED_STRING_TERMINALS);
        return i != -1 ? buffer.readUtf8(i) : buffer.readUtf8();
    }

    /**
     * Checks whether the passed character is a literal or not
     *
     * @param c the character to check
     * @return true if literal, otherwise false
     */
    private boolean isLiteral(int c) {
        switch (c) {
            case '=':
            case '<':
            case '>':
            case '/':
            case ' ':
                return false;
            default:
                return true;
        }
    }

    @Override
    public void close() throws IOException {
        peeked = PEEKED_NONE;
        nextStringBuffer.clear();
        buffer.clear();
        source.close();
    }

    /**
     * Skip an unquoted value
     */
    public enum Token {
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
        ATTRIBUTE_VALUE,

        /**
         * Indicates that we are reading the text content of an xml element like this {@code <element>
         * This is the text content </element>}
         */
        TEXT,

        /**
         * Indicates that we have reached the end of the document
         */
        END_DOCUMENT
    }
}
