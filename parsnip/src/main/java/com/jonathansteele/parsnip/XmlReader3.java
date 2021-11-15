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

package com.jonathansteele.parsnip;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

import okio.Buffer;
import okio.BufferedSource;
import okio.ByteString;

public class XmlReader3 implements Closeable {
    private static final ByteString TAG_START_TERMINALS = ByteString.encodeUtf8(">/ \n\t\r\f");
    private static final ByteString TEXT_END_TERMINAL = ByteString.encodeUtf8("&<");
    private static final ByteString ATTRIBUTE_END_TERMINAL = ByteString.encodeUtf8("= ");
    private static final ByteString ATTRIBUTE_OR_NAMESPACE_END_TERMINAL = ByteString.encodeUtf8(":= ");
    private static final ByteString TAG_OR_NAMESPACE_END_TERMINAL = ByteString.encodeUtf8(":>/ \n\t\r\f");
    private static final ByteString SINGLE_QUOTE_OR_AMP = ByteString.encodeUtf8("'&");
    private static final ByteString DOUBLE_QUOTE_OR_AMP = ByteString.encodeUtf8("\"&");
    private static final ByteString TAG_TERMINAL = ByteString.encodeUtf8("<>");
    private static final ByteString CDATA = ByteString.encodeUtf8("<![CDATA[");
    private static final byte TEXT_END = (byte) '<';
    private static final byte TAG_END = (byte) '>';
    private static final byte SINGLE_QUOTE = (byte) '\'';
    private static final byte DOUBLE_QUOTE = (byte) '"';
    private static final byte ENTITY_END_TERMINAL = (byte) ';';

    private static final int PEEKED_NONE = 0;
    private static final int PEEKED_BEGIN_TAG = 1;
    private static final int PEEKED_ATTRIBUTE = 2;
    private static final int PEEKED_SINGLE_QUOTED_VALUE = 3;
    private static final int PEEKED_DOUBLE_QUOTED_VALUE = 4;
    private static final int PEEKED_EMPTY_TAG = 5;
    private static final int PEEKED_END_TAG = 6;
    private static final int PEEKED_TEXT = 7;
    private static final int PEEKED_CDATA = 8;
    private static final int PEEKED_EOF = 9;

    private static final int STATE_BEFORE_DOCUMENT = 0;
    private static final int STATE_DOCUMENT = 1;
    private static final int STATE_TAG = 2;
    private static final int STATE_ATTRIBUTE = 3;
    private static final int STATE_CLOSED = 4;

    private final BufferedSource source;
    private final Buffer buffer;
    private final Buffer nextStringBuffer;

    private int peeked = PEEKED_NONE;
    private int state = STATE_BEFORE_DOCUMENT;

    private int stackSize = 1;

    private String[] pathNames = new String[32];

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
    // Shadowing a namespace is not likely to happen in practice, but it could, so we need to handle it.
    // Luckily we don't have to be as fast. The first index is the depth in the stack (stackSize) and the
    // second an index matching namespaceKeys. We want to lazily create this one because it's probably
    // not going to be used.
    private String[][] shadowedNamespaces = null;
    private int namespaceSize = 0;
    // We have to eagerly parse the next attribute in order to skip xmlns declarations,
    // therefore we should save what it was.
    private String lastAttribute = null;
    private final Namespace tempNamespace = new Namespace();

    public XmlReader3(BufferedSource source) {
        if (source == null) {
            throw new IllegalArgumentException("source == null");
        }
        this.source = source;
        this.buffer = source.getBuffer();
        this.nextStringBuffer = new Buffer();
    }

    public String beginTag() throws IOException {
        return beginTag(tempNamespace);
    }

    public String beginTag(Namespace namespace) throws IOException {
        int p = peeked;
        if (p == PEEKED_NONE) {
            p = doPeek();
        }
        if (p == PEEKED_BEGIN_TAG) {
            push();
            String tag = nextTag(namespace);
            pathNames[stackSize - 1] = namespace.alias == null ? tag : namespace.alias + ":" + tag;
            peeked = PEEKED_NONE;
            return tag;
        } else {
            throw new XmlDataException("Expected BEGIN_TAG but was " + peek() + " at path " + getPath());
        }
    }

    public String getPath() {
        StringBuilder path = new StringBuilder("/");
        for (int i = 1; i < stackSize; i++) {
            path.append(pathNames[i]);
            if (i != stackSize - 1) {
                path.append("/");
            }
        }
        return path.toString();
    }

    public void endTag() throws IOException {
        int p = peeked;
        if (p == PEEKED_NONE) {
            p = doPeek();
        }

        if (p == PEEKED_END_TAG) {
            validateEndTag(pathNames[stackSize - 1]);
        }

        if (p == PEEKED_EMPTY_TAG || p == PEEKED_END_TAG) {
            pop();
            state = STATE_DOCUMENT;
            attributeSize = 0;
            peeked = PEEKED_NONE;
        } else {
            throw new XmlDataException("Expected END_TAG but was " + peek() + " at path " + getPath());
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

        if (p == PEEKED_ATTRIBUTE) {
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

        if (p == PEEKED_SINGLE_QUOTED_VALUE) {
            nextTerminatedString(SINGLE_QUOTE_OR_AMP, nextStringBuffer);
            buffer.readByte(); // '''
        } else if (p == PEEKED_DOUBLE_QUOTED_VALUE) {
            nextTerminatedString(DOUBLE_QUOTE_OR_AMP, nextStringBuffer);
            buffer.readByte(); // '"'
        } else {
            throw new XmlDataException("Expected VALUE but was " + peek() + " at path " + getPath());
        }
        peeked = PEEKED_NONE;
        return nextStringBuffer.readUtf8();
    }

    public String nextText() throws IOException {
        int p = peeked;
        if (p == PEEKED_NONE) {
            p = doPeek();
        }

        if (p == PEEKED_TEXT || p == PEEKED_CDATA) {
            // We need to read multiple times, to catch all text, cdata, and comments
            do {
                if (p == PEEKED_TEXT) {
                    nextTerminatedString(TEXT_END_TERMINAL, nextStringBuffer);
                } else {
                    nextCdataString(nextStringBuffer);
                }
                p = doPeek();
            } while (p == PEEKED_TEXT || p == PEEKED_CDATA);
            peeked = p;
            return nextStringBuffer.readUtf8();
        } else {
            throw new XmlDataException("Expected TEXT but was " + peek() + " at path " + getPath());
        }
    }

    /**
     * Skips to the end of current tag, including all nested child tags. The 'current' tag is the
     * tag you last obtained from {@link #beginTag()}. This will consume up to and including {@link
     * Token#END_TAG}.
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
                case PEEKED_EMPTY_TAG:
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
                        state = STATE_DOCUMENT;
                        source.skip(i);
                    } else { // '>'
                        int c1 = buffer.getByte(i - 1);
                        state = STATE_TAG;
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
            case PEEKED_EMPTY_TAG:
            case PEEKED_END_TAG:
                endTag();
                break;
            case PEEKED_ATTRIBUTE:
                skipAttribute();
                break;
            case PEEKED_SINGLE_QUOTED_VALUE:
                skipTerminatedString(SINGLE_QUOTE);
                buffer.readByte(); // '''
                break;
            case PEEKED_DOUBLE_QUOTED_VALUE:
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

    public Token peek() throws IOException {
        int p = peeked;
        if (p == PEEKED_NONE) {
            p = doPeek();
        }

        switch (p) {
            case PEEKED_BEGIN_TAG:
                return Token.BEGIN_TAG;
            case PEEKED_ATTRIBUTE:
                return Token.ATTRIBUTE;
            case PEEKED_SINGLE_QUOTED_VALUE:
            case PEEKED_DOUBLE_QUOTED_VALUE:
                return Token.VALUE;
            case PEEKED_TEXT:
            case PEEKED_CDATA:
                return Token.TEXT;
            case PEEKED_END_TAG:
            case PEEKED_EMPTY_TAG:
                return Token.END_TAG;
            case PEEKED_EOF:
                return Token.END_DOCUMENT;
            default:
                throw new AssertionError("Unknown Token: Peeked =" + p);
        }
    }

    private int doPeek() throws IOException {
        if (lastAttribute != null) {
            return PEEKED_ATTRIBUTE;
        }

        int state = this.state;

        if (state == STATE_TAG) {
            int c = nextNonWhiteSpace(true);
            switch (c) {
                case '/':
                    // Self-closing tag
                    buffer.readByte(); // '/'
                    if (!fillBuffer(1)) {
                        throw new EOFException("End of input");
                    }
                    int next = buffer.getByte(0);
                    if (next == '>') {
                        buffer.readByte(); // '>'
                        return peeked = PEEKED_EMPTY_TAG;
                    } else {
                        throw syntaxError("Expected '>' but was '" + (char) next + "'");
                    }
                case '>':
                    buffer.readByte(); // '>'
                    this.state = STATE_DOCUMENT;
                    break;
                default:
                    // Because of namespaces, we unfortunately have to eagerly read the next
                    // attribute because it should be skipped if it is a xmlns declaration.
                    String attribute = readNextAttribute(tempNamespace);
                    if (attribute != null) {
                        lastAttribute = attribute;
                        this.state = STATE_ATTRIBUTE;
                        return peeked = PEEKED_ATTRIBUTE;
                    } else {
                        // Normally recursion is bad, but if you are blowing the stack on xmlns
                        // declarations you have bigger troubles.
                        return doPeek();
                    }
            }
        } else if (state == STATE_ATTRIBUTE) {
            this.state = STATE_TAG;
            int c = nextNonWhiteSpace(true);
            if (c != '=') {
                throw syntaxError("Expected '=' but was '" + (char) c + "'");
            }
            buffer.readByte(); // '='
            c = nextNonWhiteSpace(true);
            switch (c) {
                case '\'':
                    buffer.readByte(); // '''
                    return peeked = PEEKED_SINGLE_QUOTED_VALUE;
                case '"':
                    buffer.readByte(); // '"'
                    return peeked = PEEKED_DOUBLE_QUOTED_VALUE;
                default:
                    throw syntaxError("Expected single or double quote but was " + (char) c + "'");
            }
        } else if (state == STATE_BEFORE_DOCUMENT) {
            // Skip over declaration if it exists.
            int c = nextNonWhiteSpace(false);
            if (c == '<') {
                fillBuffer(2);
                if (buffer.getByte(1) == '?') {
                    skipTo("?>");
                    source.skip(2); // '?>'
                }
            }
            this.state = STATE_DOCUMENT;
        } else if (state == STATE_CLOSED) {
            throw new IllegalStateException("XmlReader is closed");
        }

        int c = nextNonWhiteSpace(false);
        if (c == -1) {
            return peeked = PEEKED_EOF;
        }

        while (c == '<') {
            // Need to figure out if we are:
            // a - starting a new tag
            // b - closing and existing tag
            // c - starting a comment.
            // d - starting cdata
            fillBuffer(2);
            int next = buffer.getByte(1);

            if (next == '!') {
                // We have either a comment or cdata, make sure it looks like one. (why are these so long?)
                fillBuffer(4);
                c = buffer.getByte(2);
                if (c == '-') {
                    // Comment (probably)
                    c = buffer.getByte(3);
                    if (c != '-') {
                        throw syntaxError("Expected '-' but was '" + (char) c + "'");
                    }
                    skipTo("-->");
                    fillBuffer(4);
                    source.skip(3); // '-->'
                    c = buffer.getByte(0);
                } else if (c == '[') {
                    int cdataSize = CDATA.size();
                    fillBuffer(cdataSize);
                    // cdata (probably)
                    for (int i = 3; i < cdataSize; i++) {
                        c = buffer.getByte(i);
                        byte expected = CDATA.getByte(i);
                        if (c != expected) {
                            throw syntaxError("Expected '" + (char) expected + "' but was '" + (char) c + "'");
                        }
                    }
                    source.skip(cdataSize);
                    return peeked = PEEKED_CDATA;
                } else {
                    throw syntaxError("Expected '-' or '[' but was '" + (char) c + "'");
                }
            } else if (next == '/') {
                buffer.readByte(); // '<'
                buffer.readByte(); // '/'
                return peeked = PEEKED_END_TAG;
            } else {
                buffer.readByte(); // '<'
                this.state = STATE_TAG;
                return peeked = PEEKED_BEGIN_TAG;
            }
        }
        return peeked = PEEKED_TEXT;
    }

    private void push() {
        int stackSize = this.stackSize;
        if (stackSize == pathNames.length) {
            String[] newPathNames = new String[stackSize * 2];
            System.arraycopy(pathNames, 0, newPathNames, 0, stackSize);
            pathNames = newPathNames;

            if (shadowedNamespaces != null) {
                String[][] newShadowedNamespaces = new String[stackSize * 2][];
                System.arraycopy(shadowedNamespaces, 0, newShadowedNamespaces, 0, stackSize);
                shadowedNamespaces = newShadowedNamespaces;
            }
            String[] newDefaultNamespaces = new String[stackSize * 2];
            System.arraycopy(defaultNamespaces, 0, newDefaultNamespaces, 0, stackSize);
            defaultNamespaces = newDefaultNamespaces;
        }
        defaultNamespaces[stackSize] = defaultNamespaces[stackSize - 1];
        this.stackSize++;
    }

    private void pop() {
        stackSize--;
        int stackSize = this.stackSize;
        pathNames[stackSize] = null;
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
        nextNonWhiteSpace(true);
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
                state = STATE_ATTRIBUTE;
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
                state = STATE_ATTRIBUTE;
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
        nextWithWhitespace(TAG_END);
    }

    /**
     * Reads up to an including the {@code terminatorByte} asserting that everything before it is
     * whitespace.
     */
    private void nextWithWhitespace(byte terminatorByte) throws IOException {
        long index = source.indexOf(terminatorByte);
        if (index == -1) {
            // Just complain whatever the next char is if there is one.
            if (buffer.size() > 0) {
                throw syntaxError("Expected '" + (char) terminatorByte + "' but was '" + (char) buffer.getByte(0) + "'");
            } else {
                throw syntaxError("Expected '" + (char) terminatorByte + "'");
            }
        }
        for (int i = 0; i < index; i++) {
            int c = buffer.getByte(i);
            if (c == '\n' || c == ' ' || c == '\r' || c == '\t') {
                continue;
            }
            throw syntaxError("Expected '" + (char) terminatorByte + "' but was '" + (char) c + "'");
        }
        source.skip(index + 1);
    }

    private int nextNonWhiteSpace(boolean throwOnEof) throws IOException {
        int p = 0;
        while (fillBuffer(p + 1)) {
            int c = buffer.getByte(p++);
            if (c == '\n' || c == ' ' || c == '\r' || c == '\t') {
                continue;
            }
            source.skip(p - 1);
            return c;
        }
        if (throwOnEof) {
            throw new EOFException("End of input");
        } else {
            return -1;
        }
    }


    /**
     * Returns true once {@code limit - pos >= minimum}. If the data is exhausted before that many
     * characters are available, this returns false.
     */
    private boolean fillBuffer(long minimum) throws IOException {
        return source.request(minimum);
    }

    /**
     * @param toFind a string to search for. Must not contain a newline.
     */
    private boolean skipTo(String toFind) throws IOException {
        outer:
        while (fillBuffer(toFind.length())) {
            for (int c = 0; c < toFind.length(); c++) {
                if (buffer.getByte(c) != toFind.charAt(c)) {
                    buffer.readByte();
                    continue outer;
                }
            }
            return true;
        }
        return false;
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

    private void nextCdataString(Buffer outBuffer) throws IOException {
        long start = 0;
        while (true) {
            long index = source.indexOf((byte) ']', start);
            if (index == -1L) {
                throw syntaxError("Unterminated CDATA");
            }
            start = index;
            source.request(index + 2);
            byte c = buffer.getByte(index + 1);
            if (c != ']') {
                continue;
            }
            c = buffer.getByte(index + 2);
            if (c != '>') {
                continue;
            }
            outBuffer.write(buffer, index);
            source.skip(3); // ]]>
            return;
        }
    }

    private void skipText() throws IOException {
        int p = peeked;
        if (p == PEEKED_NONE) {
            p = doPeek();
        }
        while (p == PEEKED_TEXT || p == PEEKED_CDATA) {
            if (p == PEEKED_TEXT) {
                skipTerminatedString(TEXT_END);
            } else {
                skipCdataString();
            }
            p = doPeek();
        }
    }

    private void skipCdataString() throws IOException {
        long start = 0;
        while (true) {
            long index = source.indexOf((byte) ']', start);
            if (index == -1L) {
                throw syntaxError("Unterminated CDATA");
            }
            start = index;
            source.request(index + 2);
            byte c = buffer.getByte(index + 1);
            if (c != ']') {
                continue;
            }
            c = buffer.getByte(index + 2);
            if (c != '>') {
                continue;
            }
            source.skip(index + 3);
            break;
        }
        peeked = PEEKED_NONE;
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

    @Override
    public void close() throws IOException {
        peeked = PEEKED_NONE;
        stackSize = 1;
        nextStringBuffer.clear();
        buffer.clear();
        source.close();
    }

    public enum Token {
        BEGIN_TAG,
        ATTRIBUTE,
        VALUE,
        TEXT,
        END_TAG,
        END_DOCUMENT
    }
}