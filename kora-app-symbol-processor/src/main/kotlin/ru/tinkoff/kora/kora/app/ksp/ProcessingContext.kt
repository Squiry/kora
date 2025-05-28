package ru.tinkoff.kora.kora.app.ksp

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.squareup.kotlinpoet.TypeSpec
import ru.tinkoff.kora.kora.app.ksp.component.ResolvedComponent
import ru.tinkoff.kora.kora.app.ksp.declaration.ComponentDeclaration
import ru.tinkoff.kora.kora.app.ksp.extension.Extensions
import java.util.*

class ProcessingContext(
    val resolver: Resolver,
    val kspLogger: KSPLogger,
    val src: GraphSrc,
    val serviceTypesHelper: ServiceTypesHelper = ServiceTypesHelper(resolver),
    val extensions: Extensions = Extensions.load(ProcessingContext::class.java.classLoader, resolver, kspLogger),
    val dependencyHintProvider: DependencyModuleHintProvider = DependencyModuleHintProvider(resolver),
    val stack: Deque<GraphBuilder.ResolutionFrame> = ArrayDeque<GraphBuilder.ResolutionFrame>(),
    val sourceDeclarations: MutableList<ComponentDeclaration> = ArrayList(src.sourceDeclarations),
    val templateDeclarations: MutableList<ComponentDeclaration> = ArrayList(src.templateDeclarations),
    val components: MutableList<ResolvedComponent> = ArrayList(),
    val promisedProxies: MutableList<TypeSpec> = ArrayList()
) {

    fun fork() = ProcessingContext(
        resolver,
        kspLogger,
        src,
        serviceTypesHelper,
        extensions,
        dependencyHintProvider,
        ArrayDeque(stack),
        ArrayList(sourceDeclarations),
        ArrayList(templateDeclarations),
        ArrayList(components),
        ArrayList(promisedProxies)
    )
}
