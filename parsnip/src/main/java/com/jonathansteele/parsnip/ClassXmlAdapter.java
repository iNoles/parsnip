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

import com.jonathansteele.parsnip.annotations.SerializedName;
import com.jonathansteele.parsnip.annotations.Text;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ClassXmlAdapter<T> extends XmlAdapter<T> {

    static final Factory FACTORY = new Factory() {
        @Override
        public XmlAdapter<?> create(Type type, Set<? extends Annotation> annotations, XmlAdapters adapters) {
            Class<?> rawType = Types.getRawType(type);
            if (rawType.isInterface() || rawType.isEnum() || isPlatformType(rawType) || rawType.isPrimitive())
                return null;
            if (!annotations.isEmpty()) return null;

            if (rawType.getEnclosingClass() != null && !Modifier.isStatic(rawType.getModifiers())) {
                if (rawType.getSimpleName().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Cannot serialize anonymous class " + rawType.getName());
                } else {
                    throw new IllegalArgumentException(
                            "Cannot serialize non-static nested class " + rawType.getName());
                }
            }
            if (Modifier.isAbstract(rawType.getModifiers())) {
                throw new IllegalArgumentException("Cannot serialize abstract class " + rawType.getName());
            }

            ClassFactory<Object> classFactory = ClassFactory.get(rawType);
            ArrayList<AttributeFieldBinding<?>> attributes = new ArrayList<>();
            ArrayList<TagFieldBinding<?>> tags = new ArrayList<>();
            // Only a single text, but this makes it easier to check for duplicates
            ArrayList<TextFieldBinding<?>> text = new ArrayList<>(1);
            for (Type t = type; t != Object.class; t = Types.getGenericSuperclass(t)) {
                createFieldBindings(adapters, t, attributes, tags, text);
            }
            return new ClassXmlAdapter<>(classFactory, attributes, tags, text.isEmpty() ? null : text.get(0));
        }

        /** Creates a field binding for each of declared field of {@code type}. */
        private void createFieldBindings(
                XmlAdapters adapters, Type type,
                ArrayList<AttributeFieldBinding<?>> attributes,
                ArrayList<TagFieldBinding<?>> tags,
                ArrayList<TextFieldBinding<?>> text
        ) {
            Class<?> rawType = Types.getRawType(type);
            boolean platformType = isPlatformType(rawType);
            for (Field field : rawType.getDeclaredFields()) {
                if (!includeField(platformType, field.getModifiers())) continue;

                field.setAccessible(true);
                // Look up a type adapter for this type.
                Type fieldType = Types.resolve(type, rawType, field.getGenericType());
                Set<? extends Annotation> annotations = Util.getXmlAnnotations(field);

                // Create the binding between field and Xml.
                Class<?> rawFieldType = Types.getRawType(fieldType);
                if (rawFieldType == List.class || rawFieldType == Collection.class || rawFieldType == Set.class) {
                    // Collections are weird in xml. A collection is multiple tags of the same name.
                    // However, they may be interspersed with other items. To handle this, we will
                    // just use the collection element type's adapter, and append it to the field's
                    // collection each time one is found.
                    Type elementType = Types.collectionElementType(fieldType, Collection.class);
                    Class<?> rawElementType = Types.getRawType(elementType);
                    CollectionFactory collectionFactory = rawFieldType == List.class || rawFieldType == Collection.class
                            ? ARRAY_LIST_COLLECTION_FACTORY : LINKED_HASH_SET_COLLECTION_FACTORY;
                    String name = getCollectionFieldName(field, rawElementType);
                    Namespace namespace = getNamespace(field);
                    XmlAdapter<?> adapter = adapters.adapter(rawElementType, annotations);
                    tags.add(new CollectionFieldBinding<>(field, name, namespace, adapter, collectionFactory));
                } else {
                    if (field.isAnnotationPresent(Text.class)) {
                        TypeConverter<?> converter = adapters.converter(fieldType, annotations);
                        if (converter == null) {
                            throw new IllegalArgumentException("No TypeConverter for type " + fieldType + " and annotations " + annotations);
                        }
                        TextFieldBinding<?> fieldBinding = new TextFieldBinding<>(field, converter);
                        if (!text.isEmpty()) {
                            FieldBinding<?> replaced = tags.get(0);
                            throw new IllegalArgumentException("Text annotation collision: @Text is on both '"
                                    + field.getName() + "' and '" + replaced.field.getName() + "'.");
                        }
                        text.add(fieldBinding);
                    } else {
                        XmlAdapter<?> adapter = adapters.adapter(fieldType, annotations);
                        String name = getFieldName(field);
                        Namespace namespace = getNamespace(field);
                        if (adapter != null) {
                            TagFieldBinding<?> fieldBinding = new TagFieldBinding<>(field, name, namespace, adapter);
                            FieldBinding<?> replaced = getFieldBindingTags(tags, name, namespace);
                            // Store it using the field's name. If there was already a field with this name, fail!
                            if (replaced != null) {
                                throw new IllegalArgumentException("Field name collision: '" + field.getName() + "'"
                                        + " declared by both " + replaced.field.getDeclaringClass().getName()
                                        + " and superclass " + fieldBinding.field.getDeclaringClass().getName());
                            }
                            tags.add(fieldBinding);
                        } else {
                            TypeConverter<?> converter = adapters.converter(fieldType, annotations);
                            if (converter == null) {
                                throw new IllegalArgumentException("No XmlAdapter or TypeConverter for type " + fieldType + " and annotations " + annotations);
                            }
                            AttributeFieldBinding<?> fieldBinding = new AttributeFieldBinding<>(field, name, namespace, converter);
                            FieldBinding<?> replaced = getFieldBindingAttributes(attributes, name, namespace);
                            // Store it using the field's name. If there was already a field with this name, fail!
                            if (replaced != null) {
                                throw new IllegalArgumentException("Field name collision: '" + field.getName() + "'"
                                        + " declared by both " + replaced.field.getDeclaringClass().getName()
                                        + " and superclass " + fieldBinding.field.getDeclaringClass().getName());
                            }
                            attributes.add(fieldBinding);
                        }
                    }
                }
            }
        }

        /**
         * Returns true if {@code rawType} is built in. We don't reflect on private fields of platform
         * types because they're unspecified and likely to be different on Java vs. Android.
         */
        private boolean isPlatformType(Class<?> rawType) {
            return rawType.getName().startsWith("java.")
                    || rawType.getName().startsWith("javax.")
                    || rawType.getName().startsWith("android.");
        }

        /** Returns true if fields with {@code modifiers} are included in the emitted JSON. */
        private boolean includeField(boolean platformType, int modifiers) {
            if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) return false;
            return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers) || !platformType;
        }

        /**
         * Returns the field name, taking into account the @SerializeName annotation.
         */
        private String getFieldName(Field field) {
            SerializedName serializedName = field.getAnnotation(SerializedName.class);
            if (serializedName != null) {
                return serializedName.value();
            } else {
                return field.getName();
            }
        }

        /**
         * Returns the field namespace, if it exists.
         */
        private Namespace getNamespace(Field field) {
            com.jonathansteele.parsnip.annotations.Namespace namespace = field.getAnnotation(com.jonathansteele.parsnip.annotations.Namespace.class);
            if (namespace == null) return null;
            String alias = namespace.alias().isEmpty() ? null : namespace.alias();
            return new Namespace(alias, namespace.value());
        }

        /**
         * Returns the collection field name, taking into account @Serialize name. Uses the name of
         * the collection type by default, as that is likely to be singular as opposed to the field
         * name which is likely to be plural.
         */
        private String getCollectionFieldName(Field field, Class<?> rawElementType) {
            SerializedName serializedName = field.getAnnotation(SerializedName.class);
            if (serializedName != null) {
                return serializedName.value();
            } else {
                serializedName = rawElementType.getAnnotation(SerializedName.class);
                if (serializedName != null) {
                    return serializedName.value();
                } else {
                    return rawElementType.getSimpleName();
                }
            }
        }
    };

    private final ClassFactory<T> classFactory;
    private final ArrayList<AttributeFieldBinding<?>> attributes;
    private final ArrayList<TagFieldBinding<?>> tags;
    private final TextFieldBinding<?> text;
    // Namespaces to declare when writing.
    private LinkedHashSet<Namespace> declareNamespaces;

    private ClassXmlAdapter(
            ClassFactory<T> classFactory,
            ArrayList<AttributeFieldBinding<?>> attributes,
            ArrayList<TagFieldBinding<?>> tags,
            TextFieldBinding<?> text
    ) {
        this.classFactory = classFactory;
        this.attributes = attributes;
        this.tags = tags;
        this.text = text;
    }

    private LinkedHashSet<Namespace> initDeclareNamespaces() {
        LinkedHashSet<Namespace> declareNamespaces = new LinkedHashSet<>();
        for (AttributeFieldBinding<?> attribute : attributes) {
            Namespace namespace = attribute.namespace;
            if (namespace != null) {
                declareNamespaces.add(namespace);
            }
        }
        for (TagFieldBinding<?> tag : tags) {
            Namespace namespace = tag.namespace;
            if (namespace != null) {
                declareNamespaces.add(namespace);
            }
        }
        return declareNamespaces;
    }

    @Override
    public T fromXml(@NotNull XmlReader reader) throws IOException {
        T result;
        try {
            result = classFactory.newInstance();
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            Throwable targetException = e.getTargetException();
            if (targetException instanceof RuntimeException)
                throw (RuntimeException) targetException;
            if (targetException instanceof Error) throw (Error) targetException;
            throw new RuntimeException(targetException);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }

        try {
            for (TagFieldBinding<?> fieldBinding : tags) {
                if (fieldBinding instanceof CollectionFieldBinding) {
                    ((CollectionFieldBinding<?>) fieldBinding).init(result);
                }
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }

        try {
            Namespace namespace = new Namespace();
            XmlReader.Token token = reader.peek();
            while (token != XmlReader.Token.END_TAG) {
                switch (token) {
                    case ATTRIBUTE: {
                        String name = reader.nextAttribute();
                        FieldBinding<?> fieldBinding = getFieldBindingAttributes(attributes, name, namespace);
                        if (fieldBinding != null) {
                            fieldBinding.read(reader, result);
                        } else {
                            reader.skip();
                        }
                        break;
                    }
                    case TEXT: {
                        FieldBinding<?> fieldBinding = text;
                        if (fieldBinding != null) {
                            fieldBinding.read(reader, result);
                        } else {
                            reader.skip();
                        }
                        break;
                    }
                    case BEGIN_TAG: {
                        reader.beginTag();
                        String name = reader.nextTagName();
                        FieldBinding<?> fieldBinding = getFieldBindingTags(tags, name, namespace);
                        if (fieldBinding != null) {
                            fieldBinding.read(reader, result);
                        } else {
                            reader.skip();
                        }
                        break;
                    }
                    case END_DOCUMENT: {
                        throw new XmlDataException("Unexpected end of document");
                    }
                }
                token = reader.peek();
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
        return result;
    }

    @Override
    public void toXml(@NotNull XmlWriter writer, T value) throws IOException {
        // Write declared namespaces for attributes and tags
        if (declareNamespaces == null) {
            declareNamespaces = initDeclareNamespaces();
        }
        if (!declareNamespaces.isEmpty()) {
            for (Namespace namespace : declareNamespaces) {
                writer.namespace(namespace);
            }
        }
        
        try {
            // Write actual stuff
            for (FieldBinding<?> fieldBinding : attributes) {
                fieldBinding.write(writer, value);
            }
            for (FieldBinding<?> fieldBinding : tags) {
                fieldBinding.write(writer, value);
            }
            if (text != null) {
                text.write(writer, value);
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static FieldBinding<?> getFieldBindingTags(
            ArrayList<? extends TagFieldBinding<?>> fields,
            String name,
            Namespace namespace
    ) {
        for (TagFieldBinding<?> fieldBinding : fields) {
            if (fieldBinding.name.equals(name) && nsEquals(fieldBinding.namespace, namespace)) {
                return fieldBinding;
            }
        }
        return null;
    }

    private static FieldBinding<?> getFieldBindingAttributes(
            ArrayList<? extends AttributeFieldBinding<?>> fields,
            String name,
            Namespace namespace
    ) {
        for (AttributeFieldBinding<?> fieldBinding : fields) {
            if (fieldBinding.name.equals(name) && nsEquals(fieldBinding.namespace, namespace)) {
                return fieldBinding;
            }
        }
        return null;
    }

    private static boolean nsEquals(Namespace expected, Namespace actual) {
        // All namespaces match if none expected.
        if (expected == null) return true;
        if (actual == null) return expected.namespace == null;
        return expected.equals(actual);
    }

    private static abstract class FieldBinding<T> {
        final Field field;

        FieldBinding(Field field) {
            this.field = field;
        }

        void read(XmlReader reader, Object value) throws IOException, IllegalAccessException {
            Object fieldValue = readValue(reader);
            field.set(value, fieldValue);
        }

        @SuppressWarnings("unchecked")
            // We require that field's values are of type T.
        void write(XmlWriter writer, Object value) throws IllegalAccessException, IOException {
            T fieldValue = (T) field.get(value);
            writeValue(writer, fieldValue);
        }

        abstract T readValue(XmlReader reader) throws IOException;

        abstract void writeValue(XmlWriter writer, T value) throws IOException;
    }

    private static class TagFieldBinding<T> extends FieldBinding<T> {
        final String name;
        final Namespace namespace;
        final XmlAdapter<T> adapter;

        TagFieldBinding(Field field, String name, Namespace namespace, XmlAdapter<T> adapter) {
            super(field);
            this.name = name;
            this.namespace = namespace;
            this.adapter = adapter;
        }

        @Override
        T readValue(XmlReader reader) throws IOException {
            T value = adapter.fromXml(reader);
            reader.endTag();
            return value;
        }

        @Override
        void writeValue(XmlWriter writer, T value) throws IOException {
            if (namespace == null) {
                writer.beginTag(name);
            } else {
                writer.beginTag(namespace, name);
            }
            adapter.toXml(writer, value);
            writer.endTag();
        }
    }

    private static class AttributeFieldBinding<T> extends FieldBinding<T> {
        final String name;
        final Namespace namespace;
        final TypeConverter<T> converter;

        AttributeFieldBinding(Field field, String name, Namespace namespace, TypeConverter<T> converter) {
            super(field);
            this.name = name;
            this.namespace = namespace;
            this.converter = converter;
        }

        @Override
        T readValue(XmlReader reader) {
            return converter.from(reader.nextValue());
        }

        @Override
        void writeValue(XmlWriter writer, T value) {
            if (namespace == null) {
                writer.name(name);
            } else {
                writer.name(namespace, name);
            }
            writer.value(converter.to(value));
        }
    }

    private static class TextFieldBinding<T> extends FieldBinding<T> {
        final TypeConverter<T> converter;

        TextFieldBinding(Field field, TypeConverter<T> converter) {
            super(field);
            this.converter = converter;
        }

        @Override
        T readValue(XmlReader reader) {
            return converter.from(reader.nextText());
        }

        @Override
        void writeValue(XmlWriter writer, T value) {
            writer.text(converter.to(value));
        }
    }

    private static class CollectionFieldBinding<T> extends TagFieldBinding<T> {
        final CollectionFactory collectionFactory;

        CollectionFieldBinding(Field field, String name, Namespace namespace, XmlAdapter<T> adapter, CollectionFactory collectionFactory) {
            super(field, name, namespace, adapter);
            this.collectionFactory = collectionFactory;
        }

        @SuppressWarnings("unchecked")
        void init(Object value) throws IllegalAccessException {
            // Ensure field holds a collection.
            Collection<T> currentValue = (Collection<T>) field.get(value);
            if (currentValue == null) {
                field.set(value, collectionFactory.newCollection());
            }
        }

        @Override
        @SuppressWarnings("unchecked")
            // We require that field's values are of type Collection<T>.
        void read(XmlReader reader, Object value) throws IOException, IllegalAccessException {
            T additionalValue = readValue(reader);
            Collection<T> currentValue = (Collection<T>) field.get(value);
            currentValue.add(additionalValue);
        }

        @Override
        @SuppressWarnings("unchecked")
            // We require that field's values are of type Collection<T>.
        void write(XmlWriter writer, Object value) throws IllegalAccessException, IOException {
            Collection<T> fieldValue = (Collection<T>) field.get(value);
            if (fieldValue != null) {
                for (T singleValue : fieldValue) {
                    writeValue(writer, singleValue);
                }
            }
        }
    }

    private static abstract class CollectionFactory {
        abstract <T> Collection<T> newCollection();
    }

    private static final CollectionFactory ARRAY_LIST_COLLECTION_FACTORY = new CollectionFactory() {
        @Override
        <C> ArrayList<C> newCollection() {
            return new ArrayList<>();
        }
    };

    private static final CollectionFactory LINKED_HASH_SET_COLLECTION_FACTORY = new CollectionFactory() {
        @Override
        <C> LinkedHashSet<C> newCollection() {
            return new LinkedHashSet<>();
        }
    };
}
