package com.jonathansteele.parsnip.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** Customizes how a type is encoded as Xml. */
@Retention(RUNTIME)
@Documented
public @interface XmlClass {
    /**
     * True to trigger the annotation processor to generate an adapter for this type.
     *
     * <p>There are currently some restrictions on which types that can be used with generated
     * adapters:
     *
     * <ul>
     *   <li>The class must be implemented in Kotlin (unless using a custom generator, see {@link
     *       #generator()}).
     *   <li>The class may not be an abstract class, an inner class, or a local class.
     *   <li>All superclasses must be implemented in Kotlin.
     *   <li>All properties must be public, protected, or internal.
     *   <li>All properties must be either non-transient or have a default value.
     * </ul>
     */
    boolean generateAdapter();

    String generator() default "";
}
