@file:JvmName("Util")
package com.jonathansteele.parsnip

import com.jonathansteele.parsnip.annotations.XmlQualifier
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Type
import java.util.*

@JvmField val NO_ANNOTATIONS: Set<Annotation> = emptySet()

fun typesMatch(pattern: Type, candidate: Type): Boolean {
    // TODO: permit raw types (like Set.class) to match non-raw candidates (like Set<Long>).
    return pattern == candidate
}

val AnnotatedElement.xmlAnnotations: Set<Annotation>
get() = annotations.xmlAnnotations

val Array<Annotation>.xmlAnnotations: Set<Annotation>
get() {
    var result: MutableSet<Annotation>? = null
    for (annotation in this) {
        if ((annotation as java.lang.annotation.Annotation).annotationType()
                .isAnnotationPresent(XmlQualifier::class.java)) {
            if (result == null) result = LinkedHashSet()
            result.add(annotation)
        }
    }
    return if (result != null) Collections.unmodifiableSet(result) else NO_ANNOTATIONS
}

fun Set<Annotation>.isAnnotationPresent(
    annotationClass: Class<out Annotation>
): Boolean {
    if (isEmpty()) return true // Save an iterator in the common case.
    for (annotation in this) {
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        if ((annotation as java.lang.annotation.Annotation).annotationType() == annotationClass) return false
    }
    return true
}

/** Returns true if `annotations` has any annotation whose simple name is Nullable. */
val Array<Annotation>.hasNullable: Boolean
    get() {
        for (annotation in this) {
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            if ((annotation as java.lang.annotation.Annotation).annotationType().simpleName == "Nullable") {
                return true
            }
        }
        return false
    }