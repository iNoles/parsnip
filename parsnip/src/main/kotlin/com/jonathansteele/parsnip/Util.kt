@file:JvmName("Util")
package com.jonathansteele.parsnip

import com.jonathansteele.parsnip.annotations.XmlQualifier
import java.lang.reflect.*
import java.util.*

@JvmField val NO_ANNOTATIONS: Set<Annotation> = emptySet()
@JvmField val EMPTY_TYPE_ARRAY: Array<Type> = arrayOf()

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
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
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

/** Returns the raw [Class] type of this type. */
val Type.rawType: Class<*> get() = Types.getRawType(this)

fun Type.asArrayType(): GenericArrayType = Types.arrayOf(this)

/**
 * Returns a type that is functionally equal but not necessarily equal according to [[Object.equals()]][Object.equals].
 */
fun Type.canonicalize(): Type {
    return when (this) {
        is Class<*> -> {
            if (isArray) GenericArrayTypeImpl(this@canonicalize.componentType.canonicalize()) else this
        }
        is ParameterizedType -> {
            if (this is ParameterizedTypeImpl) return this
            ParameterizedTypeImpl(ownerType, rawType, *actualTypeArguments)
        }
        is GenericArrayType -> {
            if (this is GenericArrayTypeImpl) return this
            GenericArrayTypeImpl(genericComponentType)
        }
        is WildcardType -> {
            if (this is WildcardTypeImpl) return this
            WildcardTypeImpl(upperBounds, lowerBounds)
        }
        else -> this // This type is unsupported!
    }
}

/** If type is a "? extends X" wildcard, returns X; otherwise returns type unchanged. */
/*fun Type.removeSubtypeWildcard(): Type {
    if (this !is WildcardType) return this
    val lowerBounds = lowerBounds
    if (lowerBounds.isNotEmpty()) return this
    val upperBounds = upperBounds
    require(upperBounds.size == 1)
    return upperBounds[0]
}*/

fun resolveTypeVariable(context: Type, contextRawType: Class<*>, unknown: TypeVariable<*>): Type {
    val declaredByRaw = declaringClassOf(unknown) ?: return unknown

    // We can't reduce this further.
    val declaredBy = getGenericSupertype(context, contextRawType, declaredByRaw)
    if (declaredBy is ParameterizedType) {
        val index = declaredByRaw.typeParameters.indexOf(unknown)
        return declaredBy.actualTypeArguments[index]
    }
    return unknown
}

/**
 * Returns the generic supertype for `supertype`. For example, given a class `IntegerSet`, the result for when supertype is `Set.class` is `Set<Integer>` and the
 * result when the supertype is `Collection.class` is `Collection<Integer>`.
 */
fun getGenericSupertype(context: Type, rawTypeInitial: Class<*>, toResolve: Class<*>): Type {
    var rawType = rawTypeInitial
    if (toResolve == rawType) {
        return context
    }

    // we skip searching through interfaces if unknown is an interface
    if (toResolve.isInterface) {
        val interfaces = rawType.interfaces
        for (i in interfaces.indices) {
            if (interfaces[i] == toResolve) {
                return rawType.genericInterfaces[i]
            } else if (toResolve.isAssignableFrom(interfaces[i])) {
                return getGenericSupertype(rawType.genericInterfaces[i], interfaces[i], toResolve)
            }
        }
    }

    // check our supertypes
    if (!rawType.isInterface) {
        while (rawType != Any::class.java) {
            val rawSupertype = rawType.superclass
            if (rawSupertype == toResolve) {
                return rawType.genericSuperclass
            } else if (toResolve.isAssignableFrom(rawSupertype)) {
                return getGenericSupertype(rawType.genericSuperclass, rawSupertype, toResolve)
            }
            rawType = rawSupertype
        }
    }

    // we can't resolve this further
    return toResolve
}

val Any?.hashCodeOrZero: Int
    get() {
        return this?.hashCode() ?: 0
    }

fun Type.typeToString(): String {
    return if (this is Class<*>) name else toString()
}

/**
 * Returns the declaring class of `typeVariable`, or `null` if it was not declared by
 * a class.
 */
fun declaringClassOf(typeVariable: TypeVariable<*>): Class<*>? {
    val genericDeclaration = typeVariable.genericDeclaration
    return if (genericDeclaration is Class<*>) genericDeclaration else null
}

fun Type.checkNotPrimitive() {
    require(!(this is Class<*> && isPrimitive)) { "Unexpected primitive $this. Use the boxed type." }
}

/*fun Type.toStringWithAnnotations(annotations: Set<Annotation>): String {
    return toString() + if (annotations.isEmpty()) " (with no annotations)" else " annotated $annotations"
}*/

internal inline fun <T : Any> checkNull(value: T?, lazyMessage: (T) -> Any) {
    if (value != null) {
        val message = lazyMessage(value)
        throw IllegalStateException(message.toString())
    }
}

internal class ParameterizedTypeImpl private constructor(
    private val ownerType: Type?,
    private val rawType: Type,
    @JvmField
    val typeArguments: Array<Type>
) : ParameterizedType {
    override fun getActualTypeArguments() = typeArguments.clone()

    override fun getRawType() = rawType

    override fun getOwnerType() = ownerType

    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    override fun equals(other: Any?) =
        other is ParameterizedType && Types.equals(this, other as ParameterizedType?)

    override fun hashCode(): Int {
        return typeArguments.contentHashCode() xor rawType.hashCode() xor ownerType.hashCodeOrZero
    }

    override fun toString(): String {
        val result = StringBuilder(30 * (typeArguments.size + 1))
        result.append(rawType.typeToString())
        if (typeArguments.isEmpty()) {
            return result.toString()
        }
        result.append("<").append(typeArguments[0].typeToString())
        for (i in 1 until typeArguments.size) {
            result.append(", ").append(typeArguments[i].typeToString())
        }
        return result.append(">").toString()
    }

    companion object {
        @JvmName("create")
        @JvmStatic
        operator fun invoke(
            ownerType: Type?,
            rawType: Type,
            vararg typeArguments: Type
        ): ParameterizedTypeImpl {
            // Require an owner type if the raw type needs it.
            if (rawType is Class<*>) {
                val enclosingClass = rawType.enclosingClass
                if (ownerType != null) {
                    require(!(enclosingClass == null || ownerType.rawType != enclosingClass)) { "unexpected owner type for $rawType: $ownerType" }
                } else require(enclosingClass == null) { "unexpected owner type for $rawType: null" }
            }
            @Suppress("UNCHECKED_CAST")
            val finalTypeArgs = typeArguments.clone() as Array<Type>
            for (t in finalTypeArgs.indices) {
                finalTypeArgs[t].checkNotPrimitive()
                finalTypeArgs[t] = finalTypeArgs[t].canonicalize()
            }
            return ParameterizedTypeImpl(ownerType?.canonicalize(), rawType.canonicalize(), finalTypeArgs)
        }
    }
}

internal class GenericArrayTypeImpl private constructor(private val componentType: Type) : GenericArrayType {
    override fun getGenericComponentType() = componentType

    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    override fun equals(other: Any?) =
        other is GenericArrayType && Types.equals(this, other as GenericArrayType?)

    override fun hashCode() = componentType.hashCode()

    override fun toString() = componentType.typeToString() + "[]"

    companion object {
        @JvmName("create")
        @JvmStatic
        operator fun invoke(componentType: Type): GenericArrayTypeImpl {
            return GenericArrayTypeImpl(componentType.canonicalize())
        }
    }
}

/**
 * The WildcardType interface supports multiple upper bounds and multiple lower bounds. We only
 * support what the Java 6 language needs - at most one bound. If a lower bound is set, the upper
 * bound must be Object.class.
 */
internal class WildcardTypeImpl private constructor(
    private val upperBound: Type,
    private val lowerBound: Type?
) : WildcardType {

    override fun getUpperBounds() = arrayOf(upperBound)

    override fun getLowerBounds() = lowerBound?.let { arrayOf(it) } ?: EMPTY_TYPE_ARRAY

    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    override fun equals(other: Any?) = other is WildcardType && Types.equals(this, other as WildcardType?)

    override fun hashCode(): Int {
        // This equals Arrays.hashCode(getLowerBounds()) ^ Arrays.hashCode(getUpperBounds()).
        return (if (lowerBound != null) 31 + lowerBound.hashCode() else 1) xor 31 + upperBound.hashCode()
    }

    override fun toString(): String {
        return when {
            lowerBound != null -> "? super ${lowerBound.typeToString()}"
            upperBound === Any::class.java -> "?"
            else -> "? extends ${upperBound.typeToString()}"
        }
    }

    companion object {
        @JvmStatic
        @JvmName("create")
        operator fun invoke(
            upperBounds: Array<Type>,
            lowerBounds: Array<Type>
        ): WildcardTypeImpl {
            require(lowerBounds.size <= 1)
            require(upperBounds.size == 1)
            return if (lowerBounds.size == 1) {
                lowerBounds[0].checkNotPrimitive()
                require(!(upperBounds[0] !== Any::class.java))
                WildcardTypeImpl(
                    lowerBound = lowerBounds[0].canonicalize(),
                    upperBound = Any::class.java
                )
            } else {
                upperBounds[0].checkNotPrimitive()
                WildcardTypeImpl(
                    lowerBound = null,
                    upperBound = upperBounds[0].canonicalize()
                )
            }
        }
    }
}