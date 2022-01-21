package com.jonathansteele.parsnip

import com.jonathansteele.parsnip.annotations.SerializedName
import com.jonathansteele.parsnip.annotations.XmlQualifier
import java.lang.reflect.Type

class Parsnip internal constructor(builder: Builder) {
    private val factories: List<XmlAdapter.Factory> = buildList {
        addAll(builder.adapterFactories)
        addAll(BUILT_IN_FACTORIES)
    }

    private val typeConverterFactories: List<TypeConverter.Factory> = buildList(builder.typeConverterFactories.size) {
        add(StandardTypeConverters.FACTORY)
    }

    private val xmlAdapters = XmlAdapters(factories, typeConverterFactories)

    fun <T> adapter(type: Class<T>?): XmlAdapter<T> = adapter(type, NO_ANNOTATIONS)

    fun <T> adapter(type: Type?): XmlAdapter<T> = adapter(type, NO_ANNOTATIONS)

    fun <T> adapter(type: Type?, annotations: Set<Annotation?>?): XmlAdapter<T> {
        val adapter: XmlAdapter<T> = xmlAdapters.adapter(type, annotations)
            ?: throw IllegalArgumentException(String.format(ERROR_FORMAT, "XmlAdapter", type, NO_ANNOTATIONS))
        val rawType = Types.getRawType(type)
        val serializedName = rawType.getAnnotation(
            SerializedName::class.java
        )
        val name: String = serializedName?.value ?: rawType.simpleName
        return xmlAdapters.root(name, adapter)
    }

    class Builder {
        internal val adapterFactories: MutableList<XmlAdapter.Factory> = ArrayList()
        internal val typeConverterFactories: MutableList<TypeConverter.Factory> = ArrayList()

        fun <T> add(type: Type, xmlAdapter: XmlAdapter<T>): Builder = apply {
            add(newAdapterFactory(type, xmlAdapter))
        }

        fun <T> add(
            type: Type,
            annotation: Class<out Annotation>,
            xmlAdapter: XmlAdapter<T>
        ): Builder = apply {
            add(newAdapterFactory(type, annotation, xmlAdapter))
        }

        fun add(factory: XmlAdapter.Factory): Builder = apply {
            // TODO: define precedence order. Last added wins? First added wins?
            adapterFactories.add(factory)
        }

        fun <T> add(type: Type, typeConverter: TypeConverter<T>): Builder = apply {
            add(newConverterFactory(type, typeConverter))
        }

        fun <T> add(
            type: Type,
            annotation: Class<out Annotation>,
            typeConverter: TypeConverter<T>): Builder = apply {
            add(newConverterFactory(type, annotation, typeConverter))
        }

        fun add(typeConverter: TypeConverter.Factory): Builder = apply {
            typeConverterFactories.add(typeConverter)
        }

        fun add(adapter: Any): Builder = add(AdapterMethodsFactory[adapter])

        fun build(): Parsnip = Parsnip(this)
    }

    internal companion object {
        private const val ERROR_FORMAT = "No %s for %s annotated %s"

        @JvmField
        val BUILT_IN_FACTORIES: List<XmlAdapter.Factory> = buildList(2) {
            add(TagXmlAdapter.FACTORY)
            add(ClassXmlAdapter.FACTORY)
        }

        fun <T> newAdapterFactory(
            type: Type,
            xmlAdapter: XmlAdapter<T>
        ): XmlAdapter.Factory {
            return XmlAdapter.Factory { targetType, annotations, _ ->
                if (annotations.isEmpty() && typesMatch(type, targetType)) xmlAdapter else null
            }
        }

        fun <T> newAdapterFactory(
            type: Type,
            annotation: Class<out Annotation>,
            xmlAdapter: XmlAdapter<T>
        ): XmlAdapter.Factory {
            require(annotation.isAnnotationPresent(XmlQualifier::class.java)) { "$annotation does not have @XmlQualifier" }
            require(annotation.declaredMethods.isEmpty()) { "Use XmlAdapter.Factory for annotations with elements" }
            return XmlAdapter.Factory { targetType, annotations, _ ->
                if (typesMatch(type, targetType) && annotations.size == 1 && annotations.isAnnotationPresent(annotation))
                    xmlAdapter
                else
                    null
            }
        }

        fun <T> newConverterFactory(
            type: Type,
            typeConverter: TypeConverter<T>
        ): TypeConverter.Factory {
            return TypeConverter.Factory { targetType, annotations ->
                if (annotations.isEmpty() && typesMatch(type, targetType)) typeConverter else null
            }
        }

        fun <T> newConverterFactory(
            type: Type,
            annotation: Class<out Annotation>,
            typeConverter: TypeConverter<T>
        ): TypeConverter.Factory {
            require(annotation.isAnnotationPresent(XmlQualifier::class.java)) { "$annotation does not have @XmlQualifier" }
            require(annotation.declaredMethods.isEmpty()) { "Use TypeConverter.Factory for annotations with elements" }
            return TypeConverter.Factory { targetType, annotations ->
                if (typesMatch(type, targetType) && annotations.size == 1 && annotations.isAnnotationPresent(annotation))
                    typeConverter
                else
                    null
            }
        }
    }
}