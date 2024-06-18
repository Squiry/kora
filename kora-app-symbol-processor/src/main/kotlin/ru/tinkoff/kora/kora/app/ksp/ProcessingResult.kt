package ru.tinkoff.kora.kora.app.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName
import ru.tinkoff.kora.kora.app.ksp.component.ComponentDependency
import ru.tinkoff.kora.kora.app.ksp.component.ComponentDependencyHelper
import ru.tinkoff.kora.kora.app.ksp.component.DependencyClaim
import ru.tinkoff.kora.kora.app.ksp.component.ResolvedComponent
import ru.tinkoff.kora.kora.app.ksp.declaration.ComponentDeclaration
import ru.tinkoff.kora.ksp.common.exception.ProcessingErrorException
import java.util.*

sealed interface ProcessingResult {
    sealed interface ResolutionFrame {
        data class Root(val rootIndex: Int) : ResolutionFrame
        data class Component(
            val declaration: ComponentDeclaration,
            val dependenciesToFind: List<DependencyClaim> = ComponentDependencyHelper.parseDependencyClaim(declaration),
            val resolvedDependencies: MutableList<ComponentDependency> = ArrayList(dependenciesToFind.size),
            val currentDependency: Int = 0
        ) : ResolutionFrame
    }

    data class Processing(
        val root: KSClassDeclaration,
        val allModules: List<KSClassDeclaration>,
        val sourceDeclarations: MutableList<ComponentDeclaration>,
        val interceptorDeclaration: MutableList<ComponentDeclaration>,
        val templateDeclarations: MutableList<ComponentDeclaration>,
        val rootSet: List<ComponentDeclaration>,
        val resolvedComponents: MutableList<ResolvedComponent>,
        val resolutionStack: Deque<ResolutionFrame>
    ) : ProcessingResult {
        fun findResolvedComponent(declaration: ComponentDeclaration) = resolvedComponents.asSequence().filter { it.declaration === declaration }.firstOrNull()

        fun ProcessingContext.addSourceDeclaration(c: ComponentDeclaration) {
            sourceDeclarations.add(c)
            if (serviceTypesHelper.isInterceptor(c.type)) {
                interceptorDeclaration.add(c)
            }
        }

    }

    data class NewRoundRequired(val source: Any, val type: KSType, val tag: Set<String>, val processing: Processing) : ProcessingResult

    data class Failed(val exception: ProcessingErrorException, val resolutionStack: Deque<ResolutionFrame>) : ProcessingResult


    data class Ok(val root: List<KSFile>, val allModules: List<ClassName>, val components: List<FinalComponent>) : ProcessingResult {
        data class ComponentName(var holderName: String, var fieldName: String)
        data class FinalComponent(var typeName: TypeName, var name: ComponentName, var tags: Set<String>, var code: CodeBlock, var interceptors: List<ComponentName>, var dependencyNodes: List<CodeBlock>)
    }

}
