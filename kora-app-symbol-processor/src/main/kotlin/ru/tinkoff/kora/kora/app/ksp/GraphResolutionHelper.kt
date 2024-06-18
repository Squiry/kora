package ru.tinkoff.kora.kora.app.ksp

import com.google.devtools.ksp.isOpen
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import ru.tinkoff.kora.kora.app.ksp.component.ComponentDependency.*
import ru.tinkoff.kora.kora.app.ksp.component.DependencyClaim
import ru.tinkoff.kora.kora.app.ksp.component.ResolvedComponent
import ru.tinkoff.kora.kora.app.ksp.declaration.ComponentDeclaration
import ru.tinkoff.kora.ksp.common.TagUtils
import ru.tinkoff.kora.ksp.common.exception.ProcessingErrorException

object GraphResolutionHelper {
    fun ProcessingContext.findDependency(forDeclaration: ComponentDeclaration, resolvedComponents: List<ResolvedComponent>, dependencyClaim: DependencyClaim): SingleDependency? {
        val dependencies = this.findDependencies(resolvedComponents, dependencyClaim)
        if (dependencies.size == 1) {
            return dependencies[0]
        }
        if (dependencies.isEmpty()) {
            return null
        }
        val deps = dependencies.joinToString("\n") { it.toString() }.prependIndent("  ")
        throw ProcessingErrorException(
            "More than one component matches dependency claim ${dependencyClaim.type.declaration.qualifiedName?.asString()} tag=${dependencyClaim.tags}:\n$deps",
            forDeclaration.source
        )
    }

    fun ProcessingContext.findDependencies(resolvedComponents: List<ResolvedComponent>, dependencyClaim: DependencyClaim): List<SingleDependency> {
        val result = ArrayList<SingleDependency>(4)
        for (resolvedComponent in resolvedComponents) {
            if (!dependencyClaim.tagsMatches(resolvedComponent.tags)) {
                continue
            }
            val isDirectAssignable = dependencyClaim.type.isAssignableFrom(resolvedComponent.type)
            val isWrappedAssignable = serviceTypesHelper.isAssignableToUnwrapped(resolvedComponent.type, dependencyClaim.type)
            if (!isDirectAssignable && !isWrappedAssignable) {
                continue
            }
            val targetDependency = if (isWrappedAssignable) WrappedTargetDependency(dependencyClaim, resolvedComponent) else TargetDependency(dependencyClaim, resolvedComponent)
            when (dependencyClaim.claimType) {
                DependencyClaim.DependencyClaimType.ONE_REQUIRED -> result.add(targetDependency)
                DependencyClaim.DependencyClaimType.NULLABLE_ONE -> result.add(targetDependency)
                DependencyClaim.DependencyClaimType.VALUE_OF -> result.add(ValueOfDependency(dependencyClaim, targetDependency))
                DependencyClaim.DependencyClaimType.NULLABLE_VALUE_OF -> result.add(ValueOfDependency(dependencyClaim, targetDependency))
                DependencyClaim.DependencyClaimType.PROMISE_OF -> result.add(PromiseOfDependency(dependencyClaim, targetDependency))
                DependencyClaim.DependencyClaimType.NULLABLE_PROMISE_OF -> result.add(PromiseOfDependency(dependencyClaim, targetDependency))
                else -> throw IllegalStateException()
            }
        }
        return result
    }

    fun ProcessingContext.findFinalDependency(dependencyClaim: DependencyClaim): ComponentDeclaration? {
        val declaration = dependencyClaim.type.declaration
        if (declaration !is KSClassDeclaration) {
            return null
        }
        if (declaration.isOpen()) {
            return null
        }
        if (declaration.packageName.asString() == "kotlin") {
            return null
        }
        if (declaration.typeParameters.isNotEmpty()) {
            return null
        }
        if (declaration.primaryConstructor == null) {
            return null
        }
        val tags = TagUtils.parseTagValue(declaration)
        if (dependencyClaim.tagsMatches(tags)) {
            return ComponentDeclaration.fromDependency(this, declaration)
        }
        return null
    }

    fun ProcessingContext.findDependenciesForAllOf(dependencyClaim: DependencyClaim, resolvedComponents: List<ResolvedComponent>): List<SingleDependency> {
        val result = mutableListOf<SingleDependency>()
        for (component in resolvedComponents) {
            if (!dependencyClaim.tagsMatches(component.tags)) {
                continue
            }
            if (dependencyClaim.type.isAssignableFrom(component.type)) {
                val targetDependency = TargetDependency(dependencyClaim, component)
                val dependency = when (dependencyClaim.claimType) {
                    DependencyClaim.DependencyClaimType.ALL -> targetDependency
                    DependencyClaim.DependencyClaimType.ALL_OF_VALUE -> ValueOfDependency(dependencyClaim, targetDependency)
                    DependencyClaim.DependencyClaimType.ALL_OF_PROMISE -> PromiseOfDependency(dependencyClaim, targetDependency)
                    else -> throw IllegalStateException()
                }
                result.add(dependency)
            }
            if (serviceTypesHelper.isAssignableToUnwrapped(component.type, dependencyClaim.type)) {
                val targetDependency = WrappedTargetDependency(dependencyClaim, component)
                val dependency = when (dependencyClaim.claimType) {
                    DependencyClaim.DependencyClaimType.ALL -> targetDependency
                    DependencyClaim.DependencyClaimType.ALL_OF_VALUE -> ValueOfDependency(dependencyClaim, targetDependency)
                    DependencyClaim.DependencyClaimType.ALL_OF_PROMISE -> PromiseOfDependency(dependencyClaim, targetDependency)
                    else -> throw IllegalStateException()
                }
                result.add(dependency)
            }
        }
        return result
    }


    fun ProcessingContext.findDependencyDeclarationFromTemplate(
        forDeclaration: ComponentDeclaration,
        templateDeclarations: List<ComponentDeclaration>,
        dependencyClaim: DependencyClaim
    ): ComponentDeclaration? {
        val result = this.findDependencyDeclarationsFromTemplate(forDeclaration, templateDeclarations, dependencyClaim)
        if (result.isEmpty()) {
            return null
        }
        // todo exact match
        if (result.size == 1) {
            return result[0]
        }
        val deps = result.asSequence().map { it.toString() }.joinToString("\n").prependIndent("  ")
        throw ProcessingErrorException(
            "More than one component matches dependency claim ${dependencyClaim.type.declaration.qualifiedName?.asString()} tag=${dependencyClaim.tags}:\n$deps",
            forDeclaration.source
        )
    }


    fun ProcessingContext.findDependencyDeclarationsFromTemplate(
        @Suppress("UNUSED_PARAMETER")
        forDeclaration: ComponentDeclaration,
        templateDeclarations: List<ComponentDeclaration>,
        dependencyClaim: DependencyClaim
    ): List<ComponentDeclaration> {
        val result = arrayListOf<ComponentDeclaration>()
        for (template in templateDeclarations) {
            if (!dependencyClaim.tagsMatches(template.tags)) {
                continue
            }
            when (template) {
                is ComponentDeclaration.FromModuleComponent -> {
                    val match = ComponentTemplateHelper.match(this, template.method.typeParameters, template.type, dependencyClaim.type)
                    if (match !is ComponentTemplateHelper.TemplateMatch.Some) {
                        continue
                    }
                    val map = match.map
                    val realReturnType = ComponentTemplateHelper.replace(resolver, template.type, map)!!
                    if (!dependencyClaim.type.isAssignableFrom(realReturnType)) {
                        continue
                    }

                    val realParams = mutableListOf<KSType>()
                    for (methodParameterType in template.methodParameterTypes) {
                        realParams.add(ComponentTemplateHelper.replace(resolver, methodParameterType, map)!!)
                    }
                    val realTypeParameters = mutableListOf<KSTypeArgument>()
                    for (typeParameter in template.method.typeParameters) {
                        realTypeParameters.add(ComponentTemplateHelper.replace(resolver, typeParameter, map)!!)
                    }
                    result.add(
                        ComponentDeclaration.FromModuleComponent(
                            realReturnType,
                            template.module,
                            template.tags,
                            template.method,
                            realParams,
                            realTypeParameters
                        )
                    )
                }

                is ComponentDeclaration.AnnotatedComponent -> {
                    val match = ComponentTemplateHelper.match(this, template.classDeclaration.typeParameters, template.type, dependencyClaim.type)
                    if (match !is ComponentTemplateHelper.TemplateMatch.Some) {
                        continue
                    }
                    val map = match.map
                    val realReturnType = ComponentTemplateHelper.replace(resolver, template.type, map)!!
                    if (!dependencyClaim.type.isAssignableFrom(realReturnType)) {
                        continue
                    }

                    val realParams = mutableListOf<KSType>()
                    for (methodParameterType in template.methodParameterTypes) {
                        realParams.add(ComponentTemplateHelper.replace(resolver, methodParameterType, map)!!)
                    }
                    val realTypeParameters = mutableListOf<KSTypeArgument>()
                    for (typeParameter in template.classDeclaration.typeParameters) {
                        realTypeParameters.add(ComponentTemplateHelper.replace(resolver, typeParameter, map)!!)
                    }
                    result.add(
                        ComponentDeclaration.AnnotatedComponent(
                            realReturnType,
                            template.classDeclaration,
                            template.tags,
                            template.constructor,
                            realParams,
                            realTypeParameters
                        )
                    )
                }

                is ComponentDeclaration.PromisedProxyComponent -> {
                    val match = ComponentTemplateHelper.match(this, template.classDeclaration.typeParameters, template.type, dependencyClaim.type)
                    if (match !is ComponentTemplateHelper.TemplateMatch.Some) {
                        continue
                    }
                    val map = match.map
                    val realReturnType = ComponentTemplateHelper.replace(resolver, template.type, map)!!
                    if (!dependencyClaim.type.isAssignableFrom(realReturnType)) {
                        continue
                    }

                    result.add(template.copy(type = realReturnType))
                }

                is ComponentDeclaration.FromExtensionComponent -> {
                    val sourceMethod = template.source
                    if (sourceMethod !is KSFunctionDeclaration) {
                        continue
                    }

                    val classDeclaration = sourceMethod.returnType!!.resolve().declaration
                    val match = ComponentTemplateHelper.match(this, classDeclaration.typeParameters, template.type, dependencyClaim.type)
                    if (match !is ComponentTemplateHelper.TemplateMatch.Some) {
                        continue
                    }
                    val map = match.map
                    val realReturnType = ComponentTemplateHelper.replace(resolver, template.type, map)!!
                    if (!dependencyClaim.type.isAssignableFrom(realReturnType)) {
                        continue
                    }

                    val realParams = mutableListOf<KSType>()
                    for (methodParameterType in template.methodParameterTypes) {
                        realParams.add(ComponentTemplateHelper.replace(resolver, methodParameterType, map)!!)
                    }
                    result.add(ComponentDeclaration.FromExtensionComponent(
                        realReturnType,
                        sourceMethod,
                        realParams,
                        template.methodParameterTags,
                        template.tags,
                        template.generator
                    ))
                }

                is ComponentDeclaration.DiscoveredAsDependencyComponent -> throw IllegalStateException()
                is ComponentDeclaration.OptionalComponent -> throw IllegalStateException()
            }
        }
        if (result.isEmpty()) {
            return result
        }
        if (result.size == 1) {
            return result
        }
        val exactMatch = result.filter { it.type == dependencyClaim.type }
        if (exactMatch.isNotEmpty()) {
            return exactMatch
        }
        val nonDefault = result.filter { !it.isDefault() }
        if (nonDefault.isNotEmpty()) {
            return nonDefault
        }
        return result
    }


    fun ProcessingContext.findDependencyDeclaration(
        forDeclaration: ComponentDeclaration,
        sourceDeclarations: List<ComponentDeclaration>,
        dependencyClaim: DependencyClaim
    ): ComponentDeclaration? {
        val claimType = dependencyClaim.claimType
        assert(claimType !in listOf(DependencyClaim.DependencyClaimType.ALL, DependencyClaim.DependencyClaimType.ALL_OF_PROMISE, DependencyClaim.DependencyClaimType.ALL_OF_VALUE))
        val declarations = this.findDependencyDeclarations(sourceDeclarations, dependencyClaim)
        if (declarations.size == 1) {
            return declarations[0]
        }
        if (declarations.isEmpty()) {
            return null
        }
        val nonDefaultComponents = declarations.filter { !it.isDefault() }
        if (nonDefaultComponents.size == 1) {
            return nonDefaultComponents[0]
        }

        val exactMatch = declarations.asSequence()
            .filter { it.type == dependencyClaim.type || serviceTypesHelper.isSameToUnwrapped(it.type, dependencyClaim.type) }
            .toList()
        if (exactMatch.size == 1) {
            return exactMatch[0]
        }

        val deps = declarations.asSequence().map { it.toString() }.joinToString("\n").prependIndent("  ")
        throw ProcessingErrorException(
            "More than one component matches dependency claim ${dependencyClaim.type.declaration.qualifiedName?.asString()} tag=${dependencyClaim.tags}:\n$deps",
            forDeclaration.source
        )
    }

    fun ProcessingContext.findDependencyDeclarations(sourceDeclarations: List<ComponentDeclaration>, dependencyClaim: DependencyClaim): List<ComponentDeclaration> {
        val result = mutableListOf<ComponentDeclaration>()
        for (sourceDeclaration in sourceDeclarations) {
            if (!dependencyClaim.tagsMatches(sourceDeclaration.tags)) {
                continue
            }
            if (dependencyClaim.type.isAssignableFrom(sourceDeclaration.type) || serviceTypesHelper.isAssignableToUnwrapped(sourceDeclaration.type, dependencyClaim.type)) {
                result.add(sourceDeclaration)
            }
        }
        return result
    }

    fun ProcessingContext.findInterceptorDeclarations(sourceDeclarations: List<ComponentDeclaration>, type: KSType): MutableList<ComponentDeclaration> {
        val result = mutableListOf<ComponentDeclaration>()
        for (sourceDeclaration in sourceDeclarations) {
            if (serviceTypesHelper.isInterceptorFor(sourceDeclaration.type, type)) {
                result.add(sourceDeclaration)
            }
        }
        return result
    }
}
