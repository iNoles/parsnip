package com.jonathansteele.parsnip.annotations

/** Customizes how a type is encoded as Xml.  */
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class XmlClass(
    /**
     * True to trigger the annotation processor to generate an adapter for this type.
     *
     *
     * There are currently some restrictions on which types that can be used with generated
     * adapters:
     *
     *
     *  * The class must be implemented in Kotlin (unless using a custom generator, see [       ][.generator]).
     *  * The class may not be an abstract class, an inner class, or a local class.
     *  * All superclasses must be implemented in Kotlin.
     *  * All properties must be public, protected, or internal.
     *  * All properties must be either non-transient or have a default value.
     *
     */
    val generateAdapter: Boolean, val generator: String = ""
)