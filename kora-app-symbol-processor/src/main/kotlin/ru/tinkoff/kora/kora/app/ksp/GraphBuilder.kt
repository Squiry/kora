package ru.tinkoff.kora.kora.app.ksp

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.isOpen
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.*
import ru.tinkoff.kora.kora.app.ksp.GraphResolutionHelper.findDependenciesForAllOf
import ru.tinkoff.kora.kora.app.ksp.GraphResolutionHelper.findDependency
import ru.tinkoff.kora.kora.app.ksp.GraphResolutionHelper.findDependencyDeclaration
import ru.tinkoff.kora.kora.app.ksp.GraphResolutionHelper.findDependencyDeclarationFromTemplate
import ru.tinkoff.kora.kora.app.ksp.GraphResolutionHelper.findDependencyDeclarations
import ru.tinkoff.kora.kora.app.ksp.GraphResolutionHelper.findDependencyDeclarationsFromTemplate
import ru.tinkoff.kora.kora.app.ksp.GraphResolutionHelper.findFinalDependency
import ru.tinkoff.kora.kora.app.ksp.GraphResolutionHelper.findInterceptorDeclarations
import ru.tinkoff.kora.kora.app.ksp.KoraAppUtils.timed
import ru.tinkoff.kora.kora.app.ksp.component.ComponentDependency
import ru.tinkoff.kora.kora.app.ksp.component.ComponentDependencyHelper
import ru.tinkoff.kora.kora.app.ksp.component.DependencyClaim
import ru.tinkoff.kora.kora.app.ksp.component.DependencyClaim.DependencyClaimType.*
import ru.tinkoff.kora.kora.app.ksp.component.ResolvedComponent
import ru.tinkoff.kora.kora.app.ksp.declaration.ComponentDeclaration
import ru.tinkoff.kora.kora.app.ksp.declaration.ModuleDeclaration
import ru.tinkoff.kora.kora.app.ksp.exception.CircularDependencyException
import ru.tinkoff.kora.kora.app.ksp.exception.NewRoundException
import ru.tinkoff.kora.kora.app.ksp.exception.UnresolvedDependencyException
import ru.tinkoff.kora.kora.app.ksp.extension.ExtensionResult
import ru.tinkoff.kora.kora.app.ksp.interceptor.ComponentInterceptors
import ru.tinkoff.kora.ksp.common.CommonClassNames
import ru.tinkoff.kora.ksp.common.KotlinPoetUtils.controlFlow
import ru.tinkoff.kora.ksp.common.KspCommonUtils.generated
import ru.tinkoff.kora.ksp.common.exception.ProcessingErrorException
import ru.tinkoff.kora.ksp.common.getOuterClassesAsPrefix
import java.util.*
import java.util.stream.Collectors

object GraphBuilder {
    fun ProcessingContext.processProcessing(p: ProcessingResult.Processing, forClaim: DependencyClaim? = null): ProcessingResult {
        if (p.rootSet.isEmpty()) {
            return ProcessingResult.Failed(
                ProcessingErrorException(
                    "@KoraApp has no root components, expected at least one component annotated with @Root",
                    p.root
                ),
                p.resolutionStack
            )
        }
        var processing = p
        var stack = processing.resolutionStack
        timed("loop") {
            frame@ while (stack.isNotEmpty()) {
                val frame = stack.removeLast()
                if (frame is ProcessingResult.ResolutionFrame.Root) {
                    val declaration = processing.rootSet[frame.rootIndex]
                    val resolved = timed("findResolved") {
                        processing.findResolvedComponent(declaration)
                    }
                    if (resolved != null) {
                        continue
                    }
                    stack.addLast(ProcessingResult.ResolutionFrame.Component(declaration))
                    stack.addAll(timed("findInterceptors") { findInterceptors(processing, declaration) })
                    continue
                }
                frame as ProcessingResult.ResolutionFrame.Component
                val declaration = frame.declaration
                val dependenciesToFind = frame.dependenciesToFind
                val resolvedDependencies = frame.resolvedDependencies
                if (checkCycle(processing, declaration)) {
                    continue
                }

                dependency@ for (currentDependency in frame.currentDependency until dependenciesToFind.size) {
                    val dependencyClaim = dependenciesToFind[currentDependency]
                    kspLogger.info("Resolving ${dependencyClaim.typeName} for ${declaration.source}")
                    if (dependencyClaim.claimType in listOf(ALL, ALL_OF_PROMISE, ALL_OF_VALUE)) {
                        val allOfDependency = processAllOf(this, processing, frame, currentDependency)
                        if (allOfDependency == null) {
                            continue@frame
                        } else {
                            resolvedDependencies.add(allOfDependency)
                            continue@dependency
                        }
                    }
                    if (dependencyClaim.claimType == TYPE_REF) {
                        resolvedDependencies.add(ComponentDependency.TypeOfDependency(dependencyClaim))
                        continue@dependency
                    }
                    val dependencyComponent = this.findDependency(declaration, processing.resolvedComponents, dependencyClaim)
                    if (dependencyComponent != null) {
                        resolvedDependencies.add(dependencyComponent)
                        continue@dependency
                    }
                    val dependencyDeclaration = this.findDependencyDeclaration(declaration, processing.sourceDeclarations, dependencyClaim)
                    if (dependencyDeclaration != null) {
                        stack.addLast(frame.copy(currentDependency = currentDependency))
                        stack.addLast(ProcessingResult.ResolutionFrame.Component(dependencyDeclaration))
                        stack.addAll(findInterceptors(processing, dependencyDeclaration))
                        continue@frame
                    }
                    val templates = this.findDependencyDeclarationsFromTemplate(declaration, processing.templateDeclarations, dependencyClaim)
                    if (templates.isNotEmpty()) {
                        if (templates.size == 1) {
                            val template = templates[0]
                            with(processing) { addSourceDeclaration(template) }
                            stack.addLast(frame.copy(currentDependency = currentDependency))
                            stack.addLast(ProcessingResult.ResolutionFrame.Component(template))
                            stack.addAll(findInterceptors(processing, template))
                            continue@frame
                        }
                        val results = ArrayList<ProcessingResult>(templates.size)
                        var exception: UnresolvedDependencyException? = null
                        for (template in templates) {
                            val newProcessing: ProcessingResult.Processing = ProcessingResult.Processing(
                                processing.root,
                                processing.allModules,
                                ArrayList(processing.sourceDeclarations),
                                ArrayList(processing.interceptorDeclaration),
                                ArrayList(processing.templateDeclarations),
                                processing.rootSet,
                                ArrayList(processing.resolvedComponents),
                                ArrayDeque(processing.resolutionStack)
                            )
                            with(newProcessing) {
                                addSourceDeclaration(template)
                            }
                            newProcessing.resolutionStack.addLast(frame.copy(currentDependency = currentDependency))
                            newProcessing.resolutionStack.addLast(ProcessingResult.ResolutionFrame.Component(template))
                            newProcessing.resolutionStack.addAll(findInterceptors(processing, template))
                            try {
                                results.add(processProcessing(newProcessing, dependencyClaim))
                            } catch (e: NewRoundException) {
                                results.add(ProcessingResult.NewRoundRequired(
                                    e.source,
                                    e.type,
                                    e.tag,
                                    e.resolving
                                ))
                            } catch (e: UnresolvedDependencyException) {
                                if (exception != null) {
                                    exception.addSuppressed(e)
                                } else {
                                    exception = e
                                }
                            }
                        }
                        if (results.size == 1) {
                            val result = results[0]
                            if (result is ProcessingResult.Processing) {
                                stack = result.resolutionStack
                                processing = result
                                continue@frame
                            } else {
                                return result
                            }
                        }
                        if (results.size > 1) {
                            val deps = templates.stream().map { Objects.toString(it) }
                                .collect(Collectors.joining("\n"))
                                .prependIndent("  ")
                            throw ProcessingErrorException(
                                """
                            More than one component matches dependency claim ${dependencyClaim.type}:
                            $deps
                            """.trimIndent(), declaration.source
                            )
                        }
                        throw exception!!
                    }
                    val optionalDependency = findOptionalDependency(dependencyClaim)
                    if (optionalDependency != null) {
                        resolvedDependencies.add(optionalDependency)
                        continue@dependency
                    }
                    if (dependencyClaim.type.declaration.qualifiedName!!.asString() == "java.util.Optional") {
                        // todo just add predefined template
                        val optionalDeclaration = ComponentDeclaration.OptionalComponent(dependencyClaim.type, dependencyClaim.tags)
                        with(processing) { addSourceDeclaration(optionalDeclaration) }
                        stack.addLast(frame.copy(currentDependency = currentDependency))
                        val type = dependencyClaim.type.arguments[0].type!!.resolve().makeNullable()
                        val claim = ComponentDependencyHelper.parseClaim(type, dependencyClaim.tags, declaration.source)
                        stack.addLast(
                            ProcessingResult.ResolutionFrame.Component(
                                optionalDeclaration,
                                listOf(
                                    claim
                                )
                            )
                        )
                        continue@frame
                    }
                    val finalClassComponent = this.findFinalDependency(dependencyClaim)
                    if (finalClassComponent != null) {
                        with(processing) { addSourceDeclaration(finalClassComponent) }
                        stack.addLast(frame.copy(currentDependency = currentDependency))
                        stack.addLast(ProcessingResult.ResolutionFrame.Component(finalClassComponent))
                        stack.addAll(findInterceptors(processing, finalClassComponent))
                        continue@frame
                    }
                    val extension = extensions.findExtension(resolver, dependencyClaim.type, dependencyClaim.tags)
                    if (extension != null) {
                        val extensionResult = extension()
                        if (extensionResult is ExtensionResult.RequiresCompilingResult) {
                            stack.addLast(frame.copy(currentDependency = currentDependency))
                            throw NewRoundException(processing, extension, dependencyClaim.type, dependencyClaim.tags)
                        } else if (extensionResult is ExtensionResult.CodeBlockResult) {
                            val extensionComponent = ComponentDeclaration.fromExtension(extensionResult)
                            if (extensionComponent.isTemplate()) {
                                processing.templateDeclarations.add(extensionComponent)
                            } else {
                                with(processing) { addSourceDeclaration(extensionComponent) }
                            }
                            stack.addLast(frame.copy(currentDependency = currentDependency))
                            continue@frame
                        } else {
                            extensionResult as ExtensionResult.GeneratedResult
                            val extensionComponent = ComponentDeclaration.fromExtension(this, extensionResult)
                            if (extensionComponent.isTemplate()) {
                                processing.templateDeclarations.add(extensionComponent)
                            } else {
                                with(processing) { addSourceDeclaration(extensionComponent) }
                            }
                            stack.addLast(frame.copy(currentDependency = currentDependency))
                            continue@frame
                        }
                    }
                    val hints = dependencyHintProvider.findHints(dependencyClaim.type, dependencyClaim.tags)
                    val msg = if (dependencyClaim.tags.isEmpty()) {
                        StringBuilder(
                            "Required dependency type was not found and can't be auto created: ${dependencyClaim.type.toTypeName()}.\n" +
                                "Please check class for @${CommonClassNames.component.canonicalName} annotation or that required module with component is plugged in."
                        )
                    } else {
                        val tagMsg = dependencyClaim.tags.joinToString(", ", "@Tag(", ")")
                        StringBuilder(
                            "Required dependency type was not found and can't be auto created: ${dependencyClaim.type.toTypeName()} with tag ${tagMsg}.\n" +
                                "Please check class for @${CommonClassNames.component.canonicalName} annotation or that required module with component is plugged in."
                        )
                    }
                    for (hint in hints) {
                        msg.append("\n  Hint: ").append(hint.message())
                    }
                    throw UnresolvedDependencyException(
                        msg.toString(),
                        declaration.source,
                        dependencyClaim.type,
                        dependencyClaim.tags
                    )
                }
                processing.resolvedComponents.add(
                    ResolvedComponent(
                        processing.resolvedComponents.size,
                        declaration,
                        declaration.type,
                        declaration.type.toTypeName(),
                        declaration.tags,
                        listOf(),
                        resolvedDependencies
                    )
                )
                if (forClaim != null) {
                    if (forClaim.tagsMatches(declaration.tags) && forClaim.type.isAssignableFrom(declaration.type)) {
                        return processing
                    }
                }
            }
        }
        timed("render") {
            val components = ArrayList(processing.resolvedComponents)
            val interceptors: ComponentInterceptors = ComponentInterceptors.parseInterceptors(this, components)
            for (c in components) {
                for (d in c.dependencies) {
                    d.lateInit(this, processing.resolvedComponents)
                }
                c.lateInit(interceptors)
            }

            val finalComponents = mutableListOf<ProcessingResult.Ok.FinalComponent>()
            for (c in components) {
                val name = ProcessingResult.Ok.ComponentName(c.holderName, c.fieldName)
                val decl = c.declaration
                c.dependencies.forEach { it.lateInit(this, processing.resolvedComponents) }
                val dependencies = c.dependencies.foldIndexed(CodeBlock.builder()) { i, b, d ->
                    if (i > 0) {
                        b.add(", ")
                    }
                    b.add(d.write())
                }.build()
                val componentInterceptors = c.interceptors.map { ProcessingResult.Ok.ComponentName(it.component.holderName, it.component.fieldName) }

                val dependencyNodes = c.dependencies.flatMap { dependency ->
                    when {
                        dependency is ComponentDependency.AllOfDependency && dependency.claim.claimType != ALL_OF_PROMISE -> {
                            this.findDependenciesForAllOf(dependency.claim, components).asSequence().mapNotNull {
                                it.component?.let { dComponent ->
                                    val node = if (dComponent.holderName == c.holderName) {
                                        CodeBlock.of("%N", dComponent.fieldName)
                                    } else {
                                        CodeBlock.of("%N.%N", dComponent.holderName, dComponent.fieldName)
                                    }
                                    if (dependency.claim.claimType == ALL_OF_VALUE) {
                                        CodeBlock.of("%L.valueOf()", node)
                                    } else {
                                        node
                                    }
                                }
                            }
                        }

                        dependency is ComponentDependency.SingleDependency && dependency.component != null -> {
                            val dComponent = dependency.component!!
                            val node = if (dComponent.holderName == c.holderName) {
                                CodeBlock.of("%N", dComponent.fieldName)
                            } else {
                                CodeBlock.of("%N.%N", dComponent.holderName, dComponent.fieldName)
                            }
                            if (dependency is ComponentDependency.ValueOfDependency) {
                                sequenceOf(CodeBlock.of("%L.valueOf()", node))
                            } else {
                                sequenceOf(node)
                            }
                        }

                        else -> emptySequence()
                    }
                }

                val cb = when (decl) {
                    is ComponentDeclaration.AnnotatedComponent -> CodeBlock.of("%T(%L)", decl.classDeclaration.toClassName(), dependencies)
                    is ComponentDeclaration.DiscoveredAsDependencyComponent -> CodeBlock.of("%T(%L)", decl.classDeclaration.toClassName(), dependencies)
                    is ComponentDeclaration.FromExtensionComponent -> decl.generator(dependencies)
                    is ComponentDeclaration.FromModuleComponent -> if (decl.module is ModuleDeclaration.AnnotatedModule) {
                        CodeBlock.of("impl.module%L.%N(%L)", processing.allModules.indexOf(decl.module.element), decl.method.simpleName.asString(), dependencies)
                    } else {
                        CodeBlock.of("impl.%N(%L)", decl.method.simpleName.asString(), dependencies)
                    }

                    is ComponentDeclaration.OptionalComponent -> CodeBlock.of("%T.ofNullable(%L)", Optional::class.asClassName(), dependencies)
                    is ComponentDeclaration.PromisedProxyComponent -> CodeBlock.of("%T(%L)", decl.className, dependencies)
                }

                finalComponents.add(ProcessingResult.Ok.FinalComponent(c.typeName, name, c.tags, cb, componentInterceptors, dependencyNodes))
            }

            return ProcessingResult.Ok(listOfNotNull(processing.root.containingFile), processing.allModules.map { it.toClassName() }, finalComponents)
        }
    }


    private fun findOptionalDependency(dependencyClaim: DependencyClaim): ComponentDependency? {
        if (dependencyClaim.claimType == NULLABLE_ONE) {
            return ComponentDependency.NullDependency(dependencyClaim)
        }
        if (dependencyClaim.claimType == NULLABLE_VALUE_OF) {
            return ComponentDependency.NullDependency(dependencyClaim)
        }
        if (dependencyClaim.claimType == NULLABLE_PROMISE_OF) {
            return ComponentDependency.NullDependency(dependencyClaim)
        }
        return null
    }

    private fun processAllOf(ctx: ProcessingContext, processing: ProcessingResult.Processing, componentFrame: ProcessingResult.ResolutionFrame.Component, currentDependency: Int): ComponentDependency? {
        val dependencyClaim = componentFrame.dependenciesToFind[currentDependency]
        val dependencies = ctx.findDependencyDeclarations(processing.sourceDeclarations, dependencyClaim)
        for (dependency in dependencies) {
            if (dependency.isDefault()) {
                continue
            }
            val resolved = processing.findResolvedComponent(dependency)
            if (resolved != null) {
                continue
            }
            processing.resolutionStack.addLast(componentFrame.copy(currentDependency = currentDependency))
            processing.resolutionStack.addLast(ProcessingResult.ResolutionFrame.Component(dependency))
            processing.resolutionStack.addAll(ctx.findInterceptors(processing, dependency))
            return null
        }
        if (dependencyClaim.claimType == ALL || dependencyClaim.claimType == ALL_OF_VALUE || dependencyClaim.claimType == ALL_OF_PROMISE) {
            return ComponentDependency.AllOfDependency(dependencyClaim)
        }
        throw IllegalStateException()
    }

    private fun ProcessingContext.findInterceptors(processing: ProcessingResult.Processing, declaration: ComponentDeclaration): List<ProcessingResult.ResolutionFrame.Component> {
        return this.findInterceptorDeclarations(processing.interceptorDeclaration, declaration.type)
            .asSequence()
            .filter { id -> processing.resolvedComponents.none { it.declaration === id } && processing.resolutionStack.none { it is ProcessingResult.ResolutionFrame.Component && it.declaration == id } }
            .map { ProcessingResult.ResolutionFrame.Component(it) }
            .toList()

    }

    private fun generatePromisedProxy(ctx: ProcessingContext, claimTypeDeclaration: KSClassDeclaration): ComponentDeclaration {
        val resultClassName = claimTypeDeclaration.getOuterClassesAsPrefix() + claimTypeDeclaration.simpleName.asString() + "_PromisedProxy"
        val packageName = claimTypeDeclaration.packageName.asString()
        val alreadyGenerated = ctx.resolver.getClassDeclarationByName("$packageName.$resultClassName")
        if (alreadyGenerated != null) {
            return ComponentDeclaration.PromisedProxyComponent(
                claimTypeDeclaration.asType(listOf()), // some weird behaviour here: asType with empty list returns type with type parameters as type, no other way to get them
                claimTypeDeclaration,
                ClassName(packageName, resultClassName)
            )
        }

        val typeTpr = claimTypeDeclaration.typeParameters.toTypeParameterResolver()
        val typeParameters = claimTypeDeclaration.typeParameters.map { it.toTypeVariableName(typeTpr) }
        val typeName = if (typeParameters.isEmpty()) claimTypeDeclaration.toClassName() else claimTypeDeclaration.toClassName().parameterizedBy(typeParameters)
        val promiseType = CommonClassNames.promiseOf.parameterizedBy(WildcardTypeName.producerOf(typeName))
        val type = TypeSpec.classBuilder(resultClassName)
            .generated(GraphBuilder::class)
            .addProperty("promise", promiseType, KModifier.PRIVATE, KModifier.FINAL)
            .addProperty(PropertySpec.builder("delegate", typeName.copy(true), KModifier.PRIVATE).mutable(true).initializer("null").build())
            .addSuperinterface(CommonClassNames.promisedProxy.parameterizedBy(typeName))
            .addSuperinterface(CommonClassNames.refreshListener)
            .addFunction(
                FunSpec.constructorBuilder()
                    .addParameter("promise", promiseType)
                    .addStatement("this.promise = promise")
                    .build()
            )
            .addFunction(
                FunSpec.builder("graphRefreshed")
                    .addModifiers(KModifier.OVERRIDE)
                    .addStatement("this.delegate = null")
                    .addStatement("this.getDelegate()")
                    .build()
            )
            .addFunction(FunSpec.builder("getDelegate")
                .addModifiers(KModifier.PRIVATE)
                .returns(typeName)
                .addCode(CodeBlock.builder()
                    .addStatement("var delegate = this.delegate")
                    .controlFlow("if (delegate == null)") {
                        addStatement("delegate = this.promise.get().get()!!")
                        addStatement("this.delegate = delegate")
                    }
                    .addStatement("return delegate")
                    .build()
                )
                .build()
            )
        for (typeParameter in claimTypeDeclaration.typeParameters) {
            type.addTypeVariable(typeParameter.toTypeVariableName(typeTpr))
        }
        if (claimTypeDeclaration.classKind == ClassKind.INTERFACE) {
            type.addSuperinterface(typeName)
        } else {
            type.superclass(typeName)
        }

        for (fn in claimTypeDeclaration.getAllFunctions()) {
            if (!fn.isOpen()) {
                continue
            }
            if (fn.simpleName.asString() in setOf("equals", "hashCode", "toString")) {
                continue // todo figure out a better way to handle this
            }
            val funTpr = fn.typeParameters.toTypeParameterResolver(typeTpr)
            val method = FunSpec.builder(fn.simpleName.getShortName())
                .addModifiers(KModifier.OVERRIDE)
                .returns(fn.returnType!!.resolve().toTypeName(funTpr))
            method.addCode("return this.getDelegate().%L(", fn.simpleName.getShortName())
            for ((i, param) in fn.parameters.withIndex()) {
                if (i > 0) {
                    method.addCode(", ")
                }
                method.addCode("%N", param.name!!.getShortName())
                method.addParameter(param.name!!.getShortName(), param.type.toTypeName(funTpr))
            }
            method.addCode(")\n")
            type.addFunction(method.build())
        }

        val file = FileSpec.builder(packageName, resultClassName)
            .addType(type.build())
            .build()
        file.writeTo(ctx.codeGenerator, true)

        return ComponentDeclaration.PromisedProxyComponent(
            claimTypeDeclaration.asType(listOf()), // some weird behaviour here: asType with empty list returns type with type parameters as type, no other way to get them
            claimTypeDeclaration,
            ClassName(packageName, resultClassName)
        )
    }

    private fun ProcessingContext.checkCycle(processing: ProcessingResult.Processing, declaration: ComponentDeclaration): Boolean {
        val prevFrame = processing.resolutionStack.peekLast()
        if (prevFrame !is ProcessingResult.ResolutionFrame.Component) {
            return false
        }
        if (prevFrame.dependenciesToFind.isEmpty()) {
            return false
        }
        val dependencyClaim = prevFrame.dependenciesToFind[prevFrame.currentDependency]
        val claimTypeDeclaration = dependencyClaim.type.declaration
        for (frame in processing.resolutionStack) {
            if (frame !is ProcessingResult.ResolutionFrame.Component || frame.declaration !== declaration) {
                continue
            }
            val circularDependencyException = CircularDependencyException(listOf(prevFrame.declaration.toString(), declaration.toString()), frame.declaration)
            if (claimTypeDeclaration !is KSClassDeclaration) throw circularDependencyException
            if (claimTypeDeclaration.classKind != ClassKind.INTERFACE && !(claimTypeDeclaration.classKind == ClassKind.CLASS && claimTypeDeclaration.isOpen())) throw circularDependencyException
            val proxyDependencyClaim = DependencyClaim(
                dependencyClaim.type, setOf(CommonClassNames.promisedProxy.canonicalName), dependencyClaim.claimType
            )
            val alreadyGenerated = this.findDependency(prevFrame.declaration, processing.resolvedComponents, proxyDependencyClaim)
            if (alreadyGenerated != null) {
                processing.resolutionStack.removeLast()
                prevFrame.resolvedDependencies.add(alreadyGenerated)
                processing.resolutionStack.addLast(prevFrame.copy(currentDependency = prevFrame.currentDependency + 1))
                return true
            }
            var proxyComponentDeclaration = this.findDependencyDeclarationFromTemplate(declaration, processing.templateDeclarations, proxyDependencyClaim)
            if (proxyComponentDeclaration == null) {
                proxyComponentDeclaration = generatePromisedProxy(this, claimTypeDeclaration)
                if (claimTypeDeclaration.typeParameters.isNotEmpty()) {
                    processing.templateDeclarations.add(proxyComponentDeclaration)
                } else {
                    with(processing) { addSourceDeclaration(proxyComponentDeclaration) }
                }
            }
            val proxyResolvedComponent = ResolvedComponent(
                processing.resolvedComponents.size,
                proxyComponentDeclaration,
                dependencyClaim.type,
                dependencyClaim.type.toTypeName(),
                setOf(CommonClassNames.promisedProxy.canonicalName),
                emptyList(),
                listOf(
                    ComponentDependency.PromisedProxyParameterDependency(
                        declaration,
                        DependencyClaim(
                            declaration.type,
                            declaration.tags,
                            ONE_REQUIRED
                        )
                    )
                )
            )
            processing.resolvedComponents.add(proxyResolvedComponent)
            return true
        }
        return false
    }

}
