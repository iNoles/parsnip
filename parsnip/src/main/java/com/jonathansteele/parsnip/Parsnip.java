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
import com.jonathansteele.parsnip.annotations.XmlQualifier;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class Parsnip {
    private static final String ERROR_FORMAT = "No %s for %s annotated %s";

    private final XmlAdapters xmlAdapters;

    static final List<XmlAdapter.Factory> BUILT_IN_FACTORIES = new ArrayList<>(2);
    static {
        BUILT_IN_FACTORIES.add(TagXmlAdapter.FACTORY);
        BUILT_IN_FACTORIES.add(ClassXmlAdapter.FACTORY);
    }

    private Parsnip(Builder builder) {
        List<XmlAdapter.Factory> adapterFactories =
                new ArrayList<>(builder.adapterFactories.size() + BUILT_IN_FACTORIES.size());
        adapterFactories.addAll(builder.adapterFactories);
        adapterFactories.addAll(BUILT_IN_FACTORIES);
        List<TypeConverter.Factory> typeConverterFactories = new ArrayList<>(builder.typeConverterFactories);
        typeConverterFactories.add(StandardTypeConverters.FACTORY);
        xmlAdapters = new XmlAdapters(adapterFactories, typeConverterFactories);
    }

    public <T> XmlAdapter<T> adapter(Class<T> type) {
        return adapter(type, Util.NO_ANNOTATIONS);
    }

    public <T> XmlAdapter<T> adapter(Type type) {
        return adapter(type, Util.NO_ANNOTATIONS);
    }

    public <T> XmlAdapter<T> adapter(Type type, Set<? extends Annotation> annotations) {
        XmlAdapter<T> adapter = xmlAdapters.adapter(type, annotations);
        if (adapter == null) {
            throw new IllegalArgumentException(String.format(ERROR_FORMAT, "XmlAdapter", type, Util.NO_ANNOTATIONS));
        }
        Class<?> rawType = Types.getRawType(type);
        SerializedName serializedName = rawType.getAnnotation(SerializedName.class);
        String name;
        if (serializedName != null) {
            name = serializedName.value();
        } else {
            name = rawType.getSimpleName();
        }

        return xmlAdapters.root(name, adapter);
    }

    public static final class Builder {
        private final List<XmlAdapter.Factory> adapterFactories = new ArrayList<>();
        private final List<TypeConverter.Factory> typeConverterFactories = new ArrayList<>();

        public <T> Builder add(Type type, XmlAdapter<T> xmlAdapter) {
            if (type == null) throw new IllegalArgumentException("type == null");
            if (xmlAdapter == null) throw new IllegalArgumentException("xmlAdapter == null");
            return add((targetType, annotations, adapters) ->
                    (annotations != null && annotations.isEmpty()) && Util.typesMatch(type, targetType) ?
                            xmlAdapter : null
            );
        }

        public <T> Builder add(Type type, Class<? extends Annotation> annotation, XmlAdapter<T> xmlAdapter) {
            if (type == null) throw new IllegalArgumentException("type == null");
            if (annotation == null) throw new IllegalArgumentException("annotation == null");
            if (xmlAdapter == null) throw new IllegalArgumentException("xmlAdapter == null");
            if (!annotation.isAnnotationPresent(XmlQualifier.class)) {
                throw new IllegalArgumentException(annotation + " does not have @XmlQualifier");
            }
            return add((targetType, annotations, adapters) -> {
                if (!Util.typesMatch(type, targetType)) return null;
                // TODO: check for an annotations exact match.
                if (annotations != null && Util.isAnnotationPresent(annotations, annotation)) return null;
                return xmlAdapter;
            });
        }

        public Builder add(XmlAdapter.Factory xmlAdapter) {
            // TODO: define precedence order. Last added wins? First added wins?
            adapterFactories.add(xmlAdapter);
            return this;
        }

        public <T> Builder add(Type type, TypeConverter<T> typeConverter) {
            if (type == null) throw new IllegalArgumentException("type == null");
            if (typeConverter == null) throw new IllegalArgumentException("typeConverter == null");
            return add((targetType, annotations) ->
                    (annotations != null && annotations.isEmpty()) && Util.typesMatch(type, targetType) ?
                            typeConverter : null
            );
        }

        public <T> Builder add(Type type, Class<? extends Annotation> annotation, TypeConverter<T> typeConverter) {
            if (type == null) throw new IllegalArgumentException("type == null");
            if (annotation == null) throw new IllegalArgumentException("annotation == null");
            if (typeConverter == null) throw new IllegalArgumentException("typeConverter == null");
            if (!annotation.isAnnotationPresent(XmlQualifier.class)) {
                throw new IllegalArgumentException(annotation + " does not have @XmlQualifier");
            }
            return add((targetType, annotations) -> {
                if (!Util.typesMatch(type, targetType)) return null;
                // TODO: check for an annotations exact match.
                if (annotations != null && Util.isAnnotationPresent(annotations, annotation)) return null;
                return typeConverter;
            });
        }

        public Builder add(final TypeConverter.Factory typeConverter) {
            typeConverterFactories.add(typeConverter);
            return this;
        }

        public Builder add(Object adapter) {
            return add(AdapterMethodsFactory.get(adapter));
        }

        public Parsnip build() {
            return new Parsnip(this);
        }
    }
}
