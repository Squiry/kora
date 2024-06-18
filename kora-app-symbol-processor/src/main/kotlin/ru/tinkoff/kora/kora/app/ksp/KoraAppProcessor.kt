package ru.tinkoff.kora.kora.app.ksp

import com.google.devtools.ksp.*
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.tinkoff.kora.application.graph.ApplicationGraphDraw
import ru.tinkoff.kora.kora.app.ksp.GraphBuilder.processProcessing
import ru.tinkoff.kora.kora.app.ksp.KoraAppUtils.timed
import ru.tinkoff.kora.kora.app.ksp.declaration.ComponentDeclaration
import ru.tinkoff.kora.kora.app.ksp.declaration.ComponentDeclaration.Companion.fromAnnotated
import ru.tinkoff.kora.kora.app.ksp.declaration.ModuleDeclaration
import ru.tinkoff.kora.kora.app.ksp.exception.NewRoundException
import ru.tinkoff.kora.ksp.common.AnnotationUtils.findAnnotation
import ru.tinkoff.kora.ksp.common.AnnotationUtils.isAnnotationPresent
import ru.tinkoff.kora.ksp.common.BaseSymbolProcessor
import ru.tinkoff.kora.ksp.common.CommonAopUtils.hasAopAnnotations
import ru.tinkoff.kora.ksp.common.CommonClassNames
import ru.tinkoff.kora.ksp.common.KotlinPoetUtils.controlFlow
import ru.tinkoff.kora.ksp.common.KspCommonUtils.generated
import ru.tinkoff.kora.ksp.common.exception.ProcessingErrorException
import ru.tinkoff.kora.ksp.common.makeTagAnnotationSpec
import ru.tinkoff.kora.ksp.common.parseTags
import java.io.IOException
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.*
import java.util.function.Supplier
import javax.annotation.processing.SupportedOptions

@SupportedOptions("koraLogLevel")
class KoraAppProcessor(
    environment: SymbolProcessorEnvironment
) : BaseSymbolProcessor(environment) {
    companion object {
        const val COMPONENTS_PER_HOLDER_CLASS = 500
    }

    private val processedDeclarations = hashMapOf<ClassName, ProcessingResult>()

    private val codeGenerator: CodeGenerator = environment.codeGenerator
    private val log: Logger = LoggerFactory.getLogger(KoraAppProcessor::class.java)
    private val appParts = mutableSetOf<KSClassDeclaration>() // todo split in two
    private val annotatedModules = mutableListOf<KSClassDeclaration>()
    private val components = mutableSetOf<KSClassDeclaration>()

    override fun finish() {
        for ((declaration, processingResult) in processedDeclarations) {
            when (processingResult) {
                is ProcessingResult.Failed -> {
                    processingResult.exception.printError(kspLogger)
                    if (processingResult.resolutionStack.isNotEmpty()) {
                        val i = processingResult.resolutionStack.descendingIterator()
                        val frames = ArrayList<ProcessingResult.ResolutionFrame.Component>()
                        while (i.hasNext()) {
                            val frame = i.next()
                            if (frame is ProcessingResult.ResolutionFrame.Component) {
                                frames.add(0, frame)
                            } else {
                                break
                            }
                        }
                        val chain = frames.joinToString("\n            ^            \n            |            \n") {
                            it.declaration.declarationString() + "   " + it.dependenciesToFind[it.currentDependency]
                        }
                        kspLogger.warn("Dependency resolve process: $chain")
                    }
                }

                is ProcessingResult.NewRoundRequired -> {
                    val message = "Component was expected to be generated by extension %s but was not: %s/%s".format(processingResult.source, processingResult.type, processingResult.tag)
                    kspLogger.error(message)
                    throw RuntimeException(message)
                }

                is ProcessingResult.Ok -> this.write(declaration, processingResult.allModules, processingResult.components)
                else -> throw IllegalStateException()//todo
            }
        }
        try {
            generateAppParts()
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }


    override fun processRound(resolver: Resolver): List<KSAnnotated> {
        val ctx = ProcessingContext(resolver, kspLogger, codeGenerator)
        val modules = ctx.processModules()
        val components = ctx.processComponents()
        val submodules = ctx.processSubmodules()

        val results = linkedMapOf<ClassName, ProcessingResult>()

        val koraAppElements = timed("resolve") {
            resolver.getSymbolsWithAnnotation(CommonClassNames.koraApp.canonicalName).toList()
        }
        for (symbol in koraAppElements) {
            if (!symbol.validate()) {
                continue
            }
            if (symbol is KSClassDeclaration && symbol.classKind == ClassKind.INTERFACE) {
                val className = symbol.toClassName()
                if (processedDeclarations[className] == null) {
                    log.info("@KoraApp found: {}", className.canonicalName)
                }
            } else {
                kspLogger.error("@KoraApp can be placed only on interfaces", symbol)
                continue
            }

            try {
                val processing = timed("parse") {
                    ctx.parseProcessing(symbol)
                }
                results[symbol.toClassName()] = timed("process") {
                    ctx.processProcessing(processing)
                }
            } catch (e: NewRoundException) {
                results[symbol.toClassName()] = ProcessingResult.NewRoundRequired(
                    e.source,
                    e.type,
                    e.tag,
                    e.resolving
                )
            } catch (e: ProcessingErrorException) {
                results[symbol.toClassName()] = ProcessingResult.Failed(e, ArrayDeque()) // todo
            }
        }
        processedDeclarations.putAll(results)
        for (generatedFile in codeGenerator.generatedFile) {
            kspLogger.info("Generated by extension: ${generatedFile.canonicalPath}")
        }
        return koraAppElements + modules + components + koraAppElements + submodules
    }

    private fun ProcessingContext.parseProcessing(declaration: KSClassDeclaration): ProcessingResult.Processing {
        val rootErasure = declaration.asStarProjectedType()
        val rootModule = ModuleDeclaration.MixedInModule(declaration)
        val filterObjectMethods: (KSFunctionDeclaration) -> Boolean = {
            val name = it.simpleName.asString()
            !it.modifiers.contains(Modifier.PRIVATE)
                && name != "equals"
                && name != "hashCode"
                && name != "toString"// todo find out a better way to filter object methods
        }
        val mixedInComponents = declaration.getAllFunctions()
            .filter(filterObjectMethods)
            .toMutableList()

        val submodules = declaration.getAllSuperTypes()
            .map { it.declaration as KSClassDeclaration }
            .filter { it.findAnnotation(CommonClassNames.koraSubmodule) != null }
            .map { resolver.getKSNameFromString(it.qualifiedName!!.asString() + "SubmoduleImpl") }
            .map { resolver.getClassDeclarationByName(it) ?: throw java.lang.IllegalStateException("Declaration of ${it.asString()} was not found") }
            .toList()
        val allModules = (submodules + annotatedModules)
            .flatMap { it.getAllSuperTypes().map { it.declaration as KSClassDeclaration } + it }
            .filter { it.qualifiedName?.asString() != "kotlin.Any" }
            .toSet()
            .toList()

        val annotatedModules = allModules
            .filter { !it.asStarProjectedType().isAssignableFrom(rootErasure) }
            .map { ModuleDeclaration.AnnotatedModule(it) }
        val annotatedModuleComponentsTmp = annotatedModules
            .flatMap { it.element.getDeclaredFunctions().filter(filterObjectMethods).map { f -> ComponentDeclaration.fromModule(this, it, f) } }
        val annotatedModuleComponents = ArrayList(annotatedModuleComponentsTmp)
        for (annotatedComponent in annotatedModuleComponentsTmp) {
            if (annotatedComponent.method.modifiers.contains(Modifier.OVERRIDE)) {
                val overridee = annotatedComponent.method.findOverridee()
                annotatedModuleComponents.removeIf { it.method == overridee }
                mixedInComponents.remove(overridee)
            }
        }
        val allComponents = ArrayList<ComponentDeclaration>(annotatedModuleComponents.size + mixedInComponents.size + 200)
        for (componentClass in components) {
            allComponents.add(fromAnnotated(componentClass))
        }
        allComponents.addAll(mixedInComponents.asSequence().map { ComponentDeclaration.fromModule(this, rootModule, it) })
        allComponents.addAll(annotatedModuleComponents)
        allComponents.sortedBy { it.toString() }
        // todo modules from kora app part
        val templateComponents = ArrayList<ComponentDeclaration>(allComponents.size)
        val components = ArrayList<ComponentDeclaration>(allComponents.size)
        for (component in allComponents) {
            if (component.isTemplate()) {
                templateComponents.add(component)
            } else {
                components.add(component)
            }
        }
        val rootSet = components.filter {
            it.source.isAnnotationPresent(CommonClassNames.root)
                || it is ComponentDeclaration.AnnotatedComponent && it.classDeclaration.isAnnotationPresent(CommonClassNames.root)
        }
        val stack = ArrayDeque<ProcessingResult.ResolutionFrame>()
        for (i in rootSet.indices) {
            stack.addFirst(ProcessingResult.ResolutionFrame.Root(i))
        }
        val p = ProcessingResult.Processing(declaration, allModules, ArrayList(), ArrayList(), templateComponents, rootSet, ArrayList(), stack)
        for (c in components) {
            with(p) {
                addSourceDeclaration(c)
            }
        }
        return p
    }

    private fun ProcessingContext.processSubmodules(): List<KSAnnotated> {
        val submodules = resolver.getSymbolsWithAnnotation(CommonClassNames.koraSubmodule.canonicalName)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.INTERFACE }
            .toList()
        appParts.clear()
        appParts.addAll(submodules)

        return submodules
    }

    private fun ProcessingContext.processModules(): List<KSAnnotated> {
        val modules = resolver.getSymbolsWithAnnotation(CommonClassNames.module.canonicalName)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.INTERFACE }
            .toList()

        annotatedModules.clear()
        annotatedModules.addAll(modules)
        return modules
    }

    private fun ProcessingContext.processComponents(): List<KSAnnotated> {
        val componentOfSymbols = resolver.getSymbolsWithAnnotation(CommonClassNames.component.canonicalName)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.CLASS && !it.modifiers.contains(Modifier.ABSTRACT) }
            .filter { !it.hasAopAnnotations() }
            .toList()
        components.clear()
        components.addAll(componentOfSymbols)
        return componentOfSymbols
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

    private fun write(declaration: ClassName, allModules: List<ClassName>, components: List<ProcessingResult.Ok.FinalComponent>) {
        val applicationImplFile = this.generateImpl(declaration, allModules)
        val applicationGraphFile = this.generateApplicationGraph(declaration, allModules, components)
        applicationImplFile.writeTo(codeGenerator = codeGenerator, aggregating = true)
        applicationGraphFile.writeTo(codeGenerator = codeGenerator, aggregating = true)
    }

    private fun generateImpl(declaration: ClassName, modules: List<ClassName>): FileSpec {
        val packageName = declaration.packageName
        val moduleName = "${declaration.simpleName}Impl"

        val fileSpec = FileSpec.builder(
            packageName = packageName,
            fileName = moduleName
        )
        val classBuilder = TypeSpec.classBuilder(moduleName)
//            .addOriginatingKSFile(containingFile)
            .generated(KoraAppProcessor::class)
            .addModifiers(KModifier.PUBLIC, KModifier.OPEN)
            .addSuperinterface(declaration)

        for ((index, module) in modules.withIndex()) {
            classBuilder.addProperty(
                PropertySpec.builder("module$index", module)
                    .initializer("@%T(%S) object : %T {}", CommonClassNames.generated, KoraAppProcessor::class.qualifiedName, module)
                    .build()
            )
        }
        for (component in components) {
            classBuilder.addOriginatingKSFile(component.containingFile!!)
        }
        return fileSpec.addType(classBuilder.build()).build()
    }

    private fun generateAppParts() {
        for (appPart in this.appParts) {
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
            fileSpec.writeTo(codeGenerator, false)
        }
    }

    private fun generateApplicationGraph(
        declaration: ClassName,
        allModules: List<ClassName>,
        graph: List<ProcessingResult.Ok.FinalComponent>
    ): FileSpec {
        val packageName = declaration.packageName
        val graphName = "${declaration.simpleName}Graph"
        val graphTypeName = ClassName(packageName, graphName)

        val fileSpec = FileSpec.builder(
            packageName = packageName,
            fileName = graphName
        )

        val implClass = ClassName(packageName, "${declaration.simpleName}Impl")
        val supplierSuperInterface = Supplier::class.asClassName().parameterizedBy(CommonClassNames.applicationGraphDraw)
        val classBuilder = TypeSpec.classBuilder(graphName)
            .generated(KoraAppProcessor::class)
            .addSuperinterface(supplierSuperInterface)
            .addFunction(
                FunSpec.builder("get")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(CommonClassNames.applicationGraphDraw)
                    .addStatement("return graphDraw")
                    .build()
            )

        for (component in this.components) {
            classBuilder.addOriginatingKSFile(component.containingFile!!)
        }
        for (module in annotatedModules) {
            classBuilder.addOriginatingKSFile(module.containingFile!!)
        }
        val companion = TypeSpec.companionObjectBuilder()
            .generated(KoraAppProcessor::class)
            .addProperty("graphDraw", CommonClassNames.applicationGraphDraw)

        var currentClass: TypeSpec.Builder? = null
        var currentConstructor: FunSpec.Builder? = null
        var holders = 0

        for (i in graph.indices) {
            val componentNumber = i % COMPONENTS_PER_HOLDER_CLASS
            if (componentNumber == 0) {
                if (currentClass != null) {
                    currentClass.primaryConstructor(currentConstructor!!.build())
                    classBuilder.addType(currentClass.build())
                    val prevNumber = i / COMPONENTS_PER_HOLDER_CLASS - 1
                    companion.addProperty("holder$prevNumber", graphTypeName.nestedClass("ComponentHolder$prevNumber"))
                }
                holders++
                val className = graphTypeName.nestedClass("ComponentHolder" + i / COMPONENTS_PER_HOLDER_CLASS)
                currentClass = TypeSpec.classBuilder(className)
                    .generated(KoraAppProcessor::class)
                currentConstructor = FunSpec.constructorBuilder()
                    .addParameter("graphDraw", CommonClassNames.applicationGraphDraw)
                    .addParameter("impl", implClass)
                    .addStatement("val self = %T", graphTypeName)
                    .addStatement("val map = %T<%T, %T>()", HashMap::class.asClassName(), String::class.asClassName(), Type::class.asClassName())
                    .controlFlow("for (field in %T::class.java.declaredFields)", className) {
                        controlFlow("if (!field.name.startsWith(%S))", "component") { addStatement("continue") }
                        addStatement("map[field.name] = (field.genericType as %T).actualTypeArguments[0]", ParameterizedType::class.asClassName())
                    }
                for (j in 0 until i / COMPONENTS_PER_HOLDER_CLASS) {
                    currentConstructor.addParameter("ComponentHolder$j", graphTypeName.nestedClass("ComponentHolder$j"));
                }
            }
            val component = graph[i];
            currentClass!!.addProperty(component.name.fieldName, CommonClassNames.node.parameterizedBy(component.typeName))
            val statement = this.generateComponentStatement(component)
            currentConstructor!!.addCode(statement).addCode("\n")
        }
        if (graph.isNotEmpty()) {
            var lastComponentNumber = graph.size / COMPONENTS_PER_HOLDER_CLASS;
            if (graph.size % COMPONENTS_PER_HOLDER_CLASS == 0) {
                lastComponentNumber--;
            }
            currentClass!!.addFunction(currentConstructor!!.build());
            classBuilder.addType(currentClass.build())
            companion.addProperty("holder$lastComponentNumber", graphTypeName.nestedClass("ComponentHolder$lastComponentNumber"));
        }


        val initBlock = CodeBlock.builder()
            .addStatement("val self = %T", graphTypeName)
            .addStatement("val impl = %T()", implClass)
            .addStatement("graphDraw =  %T(%T::class.java)", ApplicationGraphDraw::class, declaration)
        for (i in 0 until holders) {
            initBlock.add("%N = %T(graphDraw, impl", "holder$i", graphTypeName.nestedClass("ComponentHolder$i"))
            for (j in 0 until i) {
                initBlock.add(", holder$j")
            }
            initBlock.add(")\n");
        }

        val supplierMethodBuilder = FunSpec.builder("graph")
            .returns(ApplicationGraphDraw::class)
            .addCode("\nreturn graphDraw\n", declaration.simpleName + "Graph")
        return fileSpec.addType(
            classBuilder
                .addType(companion.addInitializerBlock(initBlock.build()).addFunction(supplierMethodBuilder.build()).build())
                .addFunction(supplierMethodBuilder.build())
                .build()
        ).build()
    }

    private fun generateComponentStatement(component: ProcessingResult.Ok.FinalComponent): CodeBlock {
        val statement = CodeBlock.builder()
        statement.add("%N = graphDraw.addNode0(map[%S], ", component.name.fieldName, component.name.fieldName)
        statement.indent().add("\n")
        statement.add("arrayOf(")
        for (tag in component.tags) {
            statement.add("%L::class.java, ", tag)
        }
        statement.add("),\n")
        statement.add("{ ")
        statement.add(component.code)
        statement.add(" },\n")
        statement.add("listOf(")
        for ((i, interceptor) in component.interceptors.withIndex()) {
            if (i > 0) {
                statement.add(", ")
            }
            if (component.name.holderName == interceptor.holderName) {
                statement.add("%N", interceptor.fieldName)
            } else {
                statement.add("%N.%N", interceptor.holderName, interceptor.fieldName)
            }
        }
        statement.add(")")
        for (d in component.dependencyNodes) {
            statement.add(", ")
            statement.add(d)
        }
        statement.unindent()
        statement.add("\n)")
        return statement.add("\n").build()
    }
}
