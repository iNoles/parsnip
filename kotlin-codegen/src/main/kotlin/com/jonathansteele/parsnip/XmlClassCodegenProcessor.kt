package com.jonathansteele.parsnip

import com.google.auto.service.AutoService
import com.jonathansteele.parsnip.annotations.XmlClass
import com.squareup.kotlinpoet.metadata.KotlinPoetMetadataPreview
import com.squareup.kotlinpoet.metadata.toKmClass
import net.ltgt.gradle.incap.IncrementalAnnotationProcessor
import net.ltgt.gradle.incap.IncrementalAnnotationProcessorType.ISOLATING
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic

/**
 * An annotation processor that reads Kotlin data classes and generates TikXml TypeAdapter for them.
 * This generates Kotlin code, and understands basic Kotlin language features like default values
 * and companion objects.
 */
@AutoService(Processor::class)
@IncrementalAnnotationProcessor(ISOLATING)
class XmlClassCodegenProcessor : AbstractProcessor() {
    private lateinit var types: Types
    private lateinit var elements: Elements
    private lateinit var filer: Filer
    private lateinit var messager: Messager
    private val annotation = XmlClass::class.java

    override fun getSupportedAnnotationTypes(): Set<String> = setOf(annotation.canonicalName)

    override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latest()

    override fun getSupportedOptions(): Set<String> = emptySet()

    override fun init(processingEnv: ProcessingEnvironment) {
        super.init(processingEnv)
        this.types = processingEnv.typeUtils
        this.elements = processingEnv.elementUtils
        this.filer = processingEnv.filer
        this.messager = processingEnv.messager
    }

    @OptIn(KotlinPoetMetadataPreview::class)
    override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
        if (roundEnv.errorRaised()) {
            // An error was raised in the previous round. Don't try anything for now to avoid adding
            // possible more noise.
            return false
        }
        for (type in roundEnv.getElementsAnnotatedWith(annotation)) {
            if (type !is TypeElement) {
                messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@XmlClass can't be applied to $type: must be a Kotlin class",
                    type
                )
                continue
            }
            val xmlClass = type.getAnnotation(annotation)
            if (xmlClass.generateAdapter && xmlClass.generator.isEmpty()) {
                val typeMetadata = type.getAnnotation(Metadata::class.java) ?: continue
                val packageName = typeMetadata.packageName
                val kmClass = typeMetadata.toKmClass()
                val adapterGenerator = AdapterGenerator(kmClass, packageName, type)
                adapterGenerator.prepare().writeTo(filer)
            }
        }
        return false
    }
}
