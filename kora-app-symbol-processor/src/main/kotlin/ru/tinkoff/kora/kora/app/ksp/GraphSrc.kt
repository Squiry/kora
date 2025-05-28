package ru.tinkoff.kora.kora.app.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import ru.tinkoff.kora.kora.app.ksp.declaration.ComponentDeclaration

data class GraphSrc(
    val root: KSClassDeclaration,
    val allModules: List<KSClassDeclaration>,
    val sourceDeclarations: MutableList<ComponentDeclaration>,
    val templateDeclarations: MutableList<ComponentDeclaration>,
    val rootSet: List<ComponentDeclaration>
)
