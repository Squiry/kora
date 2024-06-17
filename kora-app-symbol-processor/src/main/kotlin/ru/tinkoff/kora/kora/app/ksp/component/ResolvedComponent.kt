package ru.tinkoff.kora.kora.app.ksp.component

import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.TypeName
import ru.tinkoff.kora.kora.app.ksp.KoraAppProcessor
import ru.tinkoff.kora.kora.app.ksp.declaration.ComponentDeclaration
import ru.tinkoff.kora.kora.app.ksp.interceptor.ComponentInterceptor
import ru.tinkoff.kora.kora.app.ksp.interceptor.ComponentInterceptors

data class ResolvedComponent(
    val index: Int,
    val declaration: ComponentDeclaration,
    val type: KSType,
    val typeName: TypeName,
    val tags: Set<String>,
    val templateParams: List<KSType>,
    val dependencies: List<ComponentDependency>
) {
    lateinit var interceptors: List<ComponentInterceptor>
    val fieldName = "component${index}"
    val holderName = "holder${index / KoraAppProcessor.COMPONENTS_PER_HOLDER_CLASS}"

    fun lateInit(interceptors: ComponentInterceptors) {
        this.interceptors = interceptors.interceptorsFor(declaration)
    }
}
