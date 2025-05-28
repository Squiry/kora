package ru.tinkoff.kora.kora.app.ksp

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import ru.tinkoff.kora.ksp.common.AnnotationUtils.findAnnotation
import ru.tinkoff.kora.ksp.common.CommonClassNames
import ru.tinkoff.kora.ksp.common.KspCommonUtils.generated
import ru.tinkoff.kora.ksp.common.exception.ProcessingErrorException
import ru.tinkoff.kora.ksp.common.makeTagAnnotationSpec
import ru.tinkoff.kora.ksp.common.parseTags

class KoraAppPartProcessor(val environment: SymbolProcessorEnvironment) : SymbolProcessor {
    companion object {
        const val OPTION_SUBMODULE_GENERATION = "kora.app.submodule.enabled"
    }

    private lateinit var lastResolver: Resolver
    private val isKoraAppSubmoduleEnabled = environment.options.getOrDefault(OPTION_SUBMODULE_GENERATION, "false").toBoolean()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        this.lastResolver = resolver
        val deferred = mutableListOf<KSAnnotated>()
        fun deferAnnotated(cn: ClassName) {
            resolver.getSymbolsWithAnnotation(cn.canonicalName).filterIsInstance<KSClassDeclaration>().forEach { deferred.add(it) }
        }
        deferAnnotated(CommonClassNames.koraSubmodule)
        deferAnnotated(CommonClassNames.module)
        deferAnnotated(CommonClassNames.component)
        if (isKoraAppSubmoduleEnabled) {
            deferAnnotated(CommonClassNames.koraApp)
        }
        return deferred
    }

    override fun finish() {
        val appParts = mutableListOf<KSClassDeclaration>()
        lastResolver.getSymbolsWithAnnotation(CommonClassNames.koraSubmodule.canonicalName)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.INTERFACE }
            .forEach {
                if (appParts.none { a -> a.toClassName() == it.toClassName() }) {
                    appParts.add(it)
                }
            }
        if (isKoraAppSubmoduleEnabled) {
            lastResolver.getSymbolsWithAnnotation(CommonClassNames.koraApp.canonicalName)
                .filterIsInstance<KSClassDeclaration>()
                .filter { it.classKind == ClassKind.INTERFACE }
                .forEach {
                    if (appParts.none { a -> a.toClassName() == it.toClassName() }) {
                        appParts.add(it)
                    }
                }
        }
        val components = lastResolver.getSymbolsWithAnnotation(CommonClassNames.component.canonicalName)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.CLASS || it.classKind == ClassKind.OBJECT }
            .toList()
        val modules = lastResolver.getSymbolsWithAnnotation(CommonClassNames.component.canonicalName)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.INTERFACE }
            .toList()


        generateAppParts(appParts, components, modules)
    }


    private fun generateAppParts(appParts: List<KSClassDeclaration>, components: List<KSClassDeclaration>, annotatedModules: List<KSClassDeclaration>) {
        for (appPart in appParts) {
            val packageName = appPart.packageName.asString()
            val b = TypeSpec.interfaceBuilder(appPart.simpleName.asString() + "SubmoduleImpl")
                .generated(KoraAppProcessor::class)
            var componentCounter = 0
            for (component in components) {
                b.addOriginatingKSFile(component.containingFile!!)
                val constructor = findSinglePublicConstructor(component)
                val mb = FunSpec.builder("_component" + componentCounter++)
                    .returns(component.toClassName())
                mb.addCode("return %T(", component.toClassName())
                for (i in constructor.parameters.indices) {
                    val parameter = constructor.parameters[i]
                    val tag = parameter.parseTags()
                    val ps = ParameterSpec.builder(parameter.name!!.asString(), parameter.type.toTypeName())
                    if (tag.isNotEmpty()) {
                        ps.addAnnotation(tag.makeTagAnnotationSpec())
                    }
                    mb.addParameter(ps.build())
                    if (i > 0) {
                        mb.addCode(", ")
                    }
                    mb.addCode("%N", parameter.name?.asString())
                }
                val tag = component.parseTags()
                if (tag.isNotEmpty()) {
                    mb.addAnnotation(tag.makeTagAnnotationSpec())
                }
                if (component.findAnnotation(CommonClassNames.root) != null) {
                    mb.addAnnotation(CommonClassNames.root)
                }
                mb.addCode(")\n")
                b.addFunction(mb.build())
            }
            val companion = TypeSpec.companionObjectBuilder()
                .generated(KoraAppProcessor::class)

            for ((moduleCounter, module) in annotatedModules.withIndex()) {
                val moduleName = "_module$moduleCounter"
                val type = module.toClassName()
                companion.addProperty(PropertySpec.builder(moduleName, type).initializer("object : %T {}", type).build())
                for (component in module.getDeclaredFunctions()) {
                    val componentType = component.returnType!!.toTypeName()
                    val mb = FunSpec.builder("_component" + componentCounter++)
                        .returns(componentType)
                    mb.addCode("return %N.%N(", moduleName, component.simpleName.asString())
                    for (i in component.parameters.indices) {
                        val parameter = component.parameters[i]
                        val tag = parameter.parseTags()
                        val ps = ParameterSpec.builder(parameter.name!!.asString(), parameter.type.toTypeName())
                        if (tag.isNotEmpty()) {
                            ps.addAnnotation(tag.makeTagAnnotationSpec())
                        }
                        mb.addParameter(ps.build())
                        if (i > 0) {
                            mb.addCode(", ")
                        }
                        mb.addCode("%N", parameter.name?.asString())
                    }
                    val tag = component.parseTags()
                    if (tag.isNotEmpty()) {
                        mb.addAnnotation(tag.makeTagAnnotationSpec())
                    }
                    if (component.findAnnotation(CommonClassNames.defaultComponent) != null) {
                        mb.addAnnotation(CommonClassNames.defaultComponent)
                    }
                    if (component.findAnnotation(CommonClassNames.root) != null) {
                        mb.addAnnotation(CommonClassNames.root)
                    }
                    mb.addCode(")\n")
                    b.addFunction(mb.build())
                }
            }
            val typeSpec = b.addType(companion.build()).build()
            val fileSpec = FileSpec.builder(packageName, typeSpec.name!!).addType(typeSpec).build()
            fileSpec.writeTo(environment.codeGenerator, true)
        }
    }

    private fun findSinglePublicConstructor(declaration: KSClassDeclaration): KSFunctionDeclaration {
        val primaryConstructor = declaration.primaryConstructor
        if (primaryConstructor != null && primaryConstructor.isPublic()) return primaryConstructor

        val constructors = declaration.getConstructors()
            .filter { c -> c.isPublic() }
            .toList()
        if (constructors.isEmpty()) {
            throw ProcessingErrorException(
                "Type annotated with @Component has no public constructors", declaration
            )
        }
        if (constructors.size > 1) {
            throw ProcessingErrorException(
                "Type annotated with @Component has more then one public constructor", declaration
            )
        }
        return constructors[0]
    }

}
