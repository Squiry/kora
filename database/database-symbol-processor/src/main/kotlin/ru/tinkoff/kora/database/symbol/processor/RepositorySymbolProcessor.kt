package ru.tinkoff.kora.database.symbol.processor

import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.ksp.writeTo
import ru.tinkoff.kora.ksp.common.BaseSymbolProcessor
import ru.tinkoff.kora.ksp.common.exception.ProcessingError
import ru.tinkoff.kora.ksp.common.exception.ProcessingErrorException

class RepositorySymbolProcessor(
    environment: SymbolProcessorEnvironment
) : BaseSymbolProcessor(environment) {
    private val codeGenerator: CodeGenerator = environment.codeGenerator
    private lateinit var repositoryBuilder: RepositoryBuilder

    override fun processRound(resolver: Resolver): List<KSAnnotated> {
        repositoryBuilder = RepositoryBuilder(resolver, kspLogger)

        val nonProcessed = arrayListOf<KSAnnotated>()

        for (annotatedClass in resolver.getSymbolsWithAnnotation(DbUtils.repositoryAnnotation.canonicalName)) {
            val valid = try {
                annotatedClass.validate()
            } catch (e: Exception) {
                kspLogger.error(e.toString() + "\n" + e.stackTraceToString())
                nonProcessed.add(annotatedClass)
                false
            }
            when {
                !valid -> nonProcessed.add(annotatedClass)
                annotatedClass !is KSClassDeclaration -> kspLogger.error("@Repository should be placed on class or interface")
                else -> {
                    try {
                        this.processClass(annotatedClass)
                    } catch (e: ProcessingErrorException) {
                        e.printError(kspLogger)
                    }
                }
            }
        }
        return nonProcessed
    }

    private fun processClass(declaration: KSClassDeclaration) {
        if (declaration.classKind != ClassKind.INTERFACE && !(declaration.classKind == ClassKind.CLASS && declaration.isAbstract())) {
            throw ProcessingErrorException(
                listOf(
                    ProcessingError(
                        "@Repository is only applicable to interfaces and abstract classes",
                        declaration
                    )
                )
            )
        }
        val typeSpec = repositoryBuilder.build(declaration) ?: return
        val fileSpec = FileSpec.builder(declaration.packageName.asString(), typeSpec.name!!).addType(typeSpec)
        fileSpec.build().writeTo(codeGenerator, false)
    }
}

class RepositorySymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return RepositorySymbolProcessor(environment)
    }
}


