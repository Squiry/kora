package ru.tinkoff.kora.kora.app.ksp

import com.google.devtools.ksp.isOpen
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import com.squareup.kotlinpoet.ksp.toTypeVariableName
import ru.tinkoff.kora.kora.app.ksp.GraphResolutionHelper.findDependency
import ru.tinkoff.kora.kora.app.ksp.component.ComponentDependency
import ru.tinkoff.kora.kora.app.ksp.component.ComponentDependencyHelper
import ru.tinkoff.kora.kora.app.ksp.component.DependencyClaim
import ru.tinkoff.kora.kora.app.ksp.component.DependencyClaim.DependencyClaimType.*
import ru.tinkoff.kora.kora.app.ksp.component.ResolvedComponent
import ru.tinkoff.kora.kora.app.ksp.declaration.ComponentDeclaration
import ru.tinkoff.kora.kora.app.ksp.exception.CircularDependencyException
import ru.tinkoff.kora.kora.app.ksp.exception.UnresolvedDependencyException
import ru.tinkoff.kora.kora.app.ksp.extension.ExtensionResult
import ru.tinkoff.kora.ksp.common.CommonClassNames
import ru.tinkoff.kora.ksp.common.KotlinPoetUtils.controlFlow
import ru.tinkoff.kora.ksp.common.KspCommonUtils.generated
import ru.tinkoff.kora.ksp.common.exception.ProcessingErrorException
import java.util.*
import java.util.stream.Collectors

object GraphBuilder {
    data class Graph(val root: KSClassDeclaration, val allModules: List<KSClassDeclaration>, val components: List<ResolvedComponent>, val promisedProxies: List<TypeSpec>)

    fun List<ResolvedComponent>.findResolvedComponent(declaration: ComponentDeclaration) = asSequence().filter { it.declaration === declaration }.firstOrNull()
    sealed interface ResolutionFrame {
        data class Root(val rootIndex: Int) : ResolutionFrame
        data class Component(
            val declaration: ComponentDeclaration,
            val dependenciesToFind: List<DependencyClaim> = ComponentDependencyHelper.parseDependencyClaim(declaration),
            val resolvedDependencies: MutableList<ComponentDependency> = ArrayList(dependenciesToFind.size),
            val currentDependency: Int = 0
        ) : ResolutionFrame
    }

    context(ctx: ProcessingContext, resolver: Resolver)
    fun buildGraph(forClaim: DependencyClaim? = null): Graph {
        if (ctx.src.rootSet.isEmpty()) {
            throw ProcessingErrorException(
                "@KoraApp has no root components, expected at least one component annotated with @Root",
                ctx.src.root
            )
        }
        frame@ while (ctx.stack.isNotEmpty()) {
            val frame = ctx.stack.removeLast()
            if (frame is GraphBuilder.ResolutionFrame.Root) {
                val declaration = ctx.src.rootSet[frame.rootIndex]
                if (ctx.components.findResolvedComponent(declaration) != null) {
                    continue
                }
                ctx.stack.addLast(GraphBuilder.ResolutionFrame.Component(declaration))
                ctx.stack.addAll(findInterceptors(declaration))
                continue
            }
            frame as GraphBuilder.ResolutionFrame.Component
            val declaration = frame.declaration
            val dependenciesToFind = frame.dependenciesToFind
            val resolvedDependencies = frame.resolvedDependencies
            if (checkCycle(declaration)) {
                continue
            }

            dependency@ for (currentDependency in frame.currentDependency until dependenciesToFind.size) {
                val dependencyClaim = dependenciesToFind[currentDependency]
                ctx.kspLogger.info("Resolving ${dependencyClaim.type} for ${declaration.source}")
                if (dependencyClaim.claimType in listOf(ALL, ALL_OF_PROMISE, ALL_OF_VALUE)) {
                    val allOfDependency = processAllOf(frame, currentDependency)
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
                val dependencyComponent = findDependency(declaration, dependencyClaim)
                if (dependencyComponent != null) {
                    resolvedDependencies.add(dependencyComponent)
                    continue@dependency
                }
                val dependencyDeclaration = GraphResolutionHelper.findDependencyDeclaration(declaration, dependencyClaim)
                if (dependencyDeclaration != null) {
                    ctx.stack.addLast(frame.copy(currentDependency = currentDependency))
                    ctx.stack.addLast(GraphBuilder.ResolutionFrame.Component(dependencyDeclaration))
                    ctx.stack.addAll(findInterceptors(dependencyDeclaration))
                    continue@frame
                }
                val templates = GraphResolutionHelper.findDependencyDeclarationsFromTemplate(dependencyClaim)
                if (templates.isNotEmpty()) {
                    if (templates.size == 1) {
                        val template = templates[0]
                        ctx.sourceDeclarations.add(template)
                        ctx.stack.addLast(frame.copy(currentDependency = currentDependency))
                        ctx.stack.addLast(GraphBuilder.ResolutionFrame.Component(template))
                        ctx.stack.addAll(findInterceptors(template))
                        continue@frame
                    }
                    val results = ArrayList<Graph>(templates.size)
                    var exception: UnresolvedDependencyException? = null
                    for (template in templates) {
                        fun <A, R> context(a: A, block: context(A) () -> R): R {
                            return block(a)
                        }

                        val fork = ctx.fork()
                        fork.sourceDeclarations.add(template)
                        fork.stack.addLast(frame.copy(currentDependency = currentDependency))
                        fork.stack.addLast(GraphBuilder.ResolutionFrame.Component(template))
                        fork.stack.addAll(findInterceptors(template))
                        try {
                            context(fork) {
                                results.add(buildGraph(dependencyClaim))
                            }
                        } catch (e: UnresolvedDependencyException) {
                            if (exception != null) {
                                exception.addSuppressed(e)
                            } else {
                                exception = e
                            }
                        }
                    }
                    if (results.size == 1) {
                        return results[0]
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
                    ctx.sourceDeclarations.add(optionalDeclaration)
                    ctx.stack.addLast(frame.copy(currentDependency = currentDependency))
                    val type = dependencyClaim.type.arguments[0].type!!.resolve().makeNullable()
                    val claim = ComponentDependencyHelper.parseClaim(type, dependencyClaim.tags, declaration.source)
                    ctx.stack.addLast(
                        GraphBuilder.ResolutionFrame.Component(
                            optionalDeclaration, listOf(
                                claim
                            )
                        )
                    )
                    continue@frame
                }
                val finalClassComponent = GraphResolutionHelper.findFinalDependency(dependencyClaim)
                if (finalClassComponent != null) {
                    ctx.sourceDeclarations.add(finalClassComponent)
                    ctx.stack.addLast(frame.copy(currentDependency = currentDependency))
                    ctx.stack.addLast(GraphBuilder.ResolutionFrame.Component(finalClassComponent))
                    ctx.stack.addAll(findInterceptors(finalClassComponent))
                    continue@frame
                }
                val extension = ctx.extensions.findExtension(dependencyClaim.type, dependencyClaim.tags)
                if (extension != null) {
                    val extensionResult = extension()
                    when (extensionResult) {
                        is ExtensionResult.CodeBlockResult -> {
                            val extensionComponent = ComponentDeclaration.fromExtension(extensionResult)
                            if (extensionComponent.isTemplate()) {
                                ctx.templateDeclarations.add(extensionComponent)
                            } else {
                                ctx.sourceDeclarations.add(extensionComponent)
                            }
                            ctx.stack.addLast(frame.copy(currentDependency = currentDependency))
                            continue@frame
                        }

                        else -> {
                            extensionResult as ExtensionResult.GeneratedResult
                            val extensionComponent = ComponentDeclaration.fromExtension(extensionResult)
                            if (extensionComponent.isTemplate()) {
                                ctx.templateDeclarations.add(extensionComponent)
                            } else {
                                ctx.sourceDeclarations.add(extensionComponent)
                            }
                            ctx.stack.addLast(frame.copy(currentDependency = currentDependency))
                            continue@frame
                        }
                    }
                }
                val hints = ctx.dependencyHintProvider.findHints(dependencyClaim.type, dependencyClaim.tags)
                val msg = if (dependencyClaim.tags.isEmpty()) {
                    StringBuilder(
                        "Required dependency type wasn't found and can't be auto created: ${dependencyClaim.type.toTypeName()}.\n" +
                            "Please check class for @${CommonClassNames.component.canonicalName} annotation or that required module with component is plugged in."
                    )
                } else {
                    val tagMsg = dependencyClaim.tags.joinToString(", ", "@Tag(", ")")
                    StringBuilder(
                        "Required dependency type wasn't found and can't be auto created: ${dependencyClaim.type.toTypeName()} with tag ${tagMsg}.\n" +
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
            ctx.components.add(
                ResolvedComponent(
                    ctx.components.size,
                    declaration,
                    declaration.type,
                    declaration.tags,
                    listOf(),
                    resolvedDependencies
                )
            )
        }
        return Graph(ctx.src.root, ctx.src.allModules, ArrayList(ctx.components), ArrayList(ctx.promisedProxies))
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

    context(ctx: ProcessingContext)
    private fun processAllOf(componentFrame: GraphBuilder.ResolutionFrame.Component, currentDependency: Int): ComponentDependency? {
        val dependencyClaim = componentFrame.dependenciesToFind[currentDependency]
        val dependencies = GraphResolutionHelper.findDependencyDeclarations(dependencyClaim)
        for (dependency in dependencies) {
            if (dependency.isDefault()) {
                continue
            }
            val resolved = ctx.components.findResolvedComponent(dependency)
            if (resolved != null) {
                continue
            }
            ctx.stack.addLast(componentFrame.copy(currentDependency = currentDependency))
            ctx.stack.addLast(GraphBuilder.ResolutionFrame.Component(dependency))
            ctx.stack.addAll(this.findInterceptors(dependency))
            return null
        }
        if (dependencyClaim.claimType == ALL || dependencyClaim.claimType == ALL_OF_VALUE || dependencyClaim.claimType == ALL_OF_PROMISE) {
            return ComponentDependency.AllOfDependency(dependencyClaim)
        }
        throw IllegalStateException()
    }

    context(ctx: ProcessingContext)
    private fun findInterceptors(declaration: ComponentDeclaration): List<GraphBuilder.ResolutionFrame.Component> {
        return GraphResolutionHelper.findInterceptorDeclarations(declaration.type)
            .asSequence()
            .filter { id -> ctx.components.none { it.declaration === id } && ctx.stack.none { it is GraphBuilder.ResolutionFrame.Component && it.declaration == id } }
            .map { GraphBuilder.ResolutionFrame.Component(it) }
            .toList()

    }

    context(ctx: ProcessingContext)
    private fun generatePromisedProxy(claimTypeDeclaration: KSClassDeclaration): ComponentDeclaration {
        val graphName = "${ctx.src.root.simpleName.asString()}Graph"
        val resultClassName = ClassName(ctx.src.root.packageName.asString(), graphName, "PromisedProxy" + ctx.promisedProxies.size)
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
            .addFunction(
                FunSpec.builder("getDelegate")
                    .addModifiers(KModifier.PRIVATE)
                    .returns(typeName)
                    .addCode(
                        CodeBlock.builder()
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
            if (!fn.isOpen() || fn.modifiers.contains(Modifier.PRIVATE)) {
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
        for (allProperty in claimTypeDeclaration.getAllProperties()) {
            val prop = PropertySpec.builder(allProperty.simpleName.asString(), allProperty.type.resolve().toTypeName(), KModifier.OVERRIDE)
                .getter(
                    FunSpec.getterBuilder()
                        .addStatement("return this.getDelegate().%N", allProperty.simpleName.getShortName())
                        .build()
                )
                .build()
            type.addProperty(prop)
        }
        ctx.promisedProxies.add(type.build())

        return ComponentDeclaration.PromisedProxyComponent(
            claimTypeDeclaration.asType(listOf()), // some weird behaviour here: asType with empty list returns type with type parameters as type, no other way to get them
            claimTypeDeclaration,
            resultClassName
        )
    }

    context(ctx: ProcessingContext)
    private fun checkCycle(declaration: ComponentDeclaration): Boolean {
        val prevFrame = ctx.stack.peekLast()
        if (prevFrame !is GraphBuilder.ResolutionFrame.Component) {
            return false
        }
        if (prevFrame.dependenciesToFind.isEmpty()) {
            return false
        }
        val dependencyClaim = prevFrame.dependenciesToFind[prevFrame.currentDependency]
        val claimTypeDeclaration = dependencyClaim.type.declaration
        for (frame in ctx.stack) {
            if (frame !is GraphBuilder.ResolutionFrame.Component || frame.declaration !== declaration) {
                continue
            }
            val circularDependencyException = CircularDependencyException(listOf(prevFrame.declaration.toString(), declaration.toString()), frame.declaration)
            if (claimTypeDeclaration !is KSClassDeclaration) throw circularDependencyException
            if (claimTypeDeclaration.classKind != ClassKind.INTERFACE && !(claimTypeDeclaration.classKind == ClassKind.CLASS && claimTypeDeclaration.isOpen())) throw circularDependencyException
            val proxyDependencyClaim = DependencyClaim(
                dependencyClaim.type, setOf(CommonClassNames.promisedProxy.canonicalName), dependencyClaim.claimType
            )
            val alreadyGenerated = findDependency(prevFrame.declaration, proxyDependencyClaim)
            if (alreadyGenerated != null) {
                ctx.stack.removeLast()
                prevFrame.resolvedDependencies.add(alreadyGenerated)
                ctx.stack.addLast(prevFrame.copy(currentDependency = prevFrame.currentDependency + 1))
                return true
            }
            var proxyComponentDeclaration = GraphResolutionHelper.findDependencyDeclarationFromTemplate(declaration, proxyDependencyClaim)
            if (proxyComponentDeclaration == null) {
                proxyComponentDeclaration = generatePromisedProxy(claimTypeDeclaration)
                if (claimTypeDeclaration.typeParameters.isNotEmpty()) {
                    ctx.templateDeclarations.add(proxyComponentDeclaration)
                } else {
                    ctx.sourceDeclarations.add(proxyComponentDeclaration)
                }
            }
            val proxyResolvedComponent = ResolvedComponent(
                ctx.components.size,
                proxyComponentDeclaration,
                dependencyClaim.type,
                setOf(CommonClassNames.promisedProxy.canonicalName),
                emptyList(),
                listOf(
                    ComponentDependency.PromisedProxyParameterDependency(
                        declaration, DependencyClaim(
                            declaration.type,
                            declaration.tags,
                            ONE_REQUIRED
                        )
                    )
                )
            )
            ctx.components.add(proxyResolvedComponent)
            return true
        }
        return false
    }

}
