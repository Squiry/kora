package ru.tinkoff.kora.json.ksp.extension

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.getFunctionDeclarationsByName
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Variance
import ru.tinkoff.kora.json.ksp.JsonTypes
import ru.tinkoff.kora.json.ksp.isNativePackage
import ru.tinkoff.kora.kora.app.ksp.extension.ExtensionResult
import ru.tinkoff.kora.kora.app.ksp.extension.KoraExtension
import ru.tinkoff.kora.ksp.common.AnnotationUtils.isAnnotationPresent
import ru.tinkoff.kora.ksp.common.KspCommonUtils.parametrized

class JsonKoraExtension(
    private val resolver: Resolver,
    private val kspLogger: KSPLogger,
) : KoraExtension {
    private val jsonWriterErasure = resolver.getClassDeclarationByName(JsonTypes.jsonWriter.canonicalName)!!.asStarProjectedType()
    private val jsonReaderErasure = resolver.getClassDeclarationByName(JsonTypes.jsonReader.canonicalName)!!.asStarProjectedType()

    override fun getDependencyGenerator(resolver: Resolver, type: KSType, tags: Set<String>): (() -> ExtensionResult)? {
        if (tags.isNotEmpty()) return null
        val actualType = type.makeNotNullable()
        val erasure = actualType.starProjection()
        if (erasure == jsonWriterErasure) {
            val possibleJsonClass = type.arguments[0].type!!.resolve()
            if (possibleJsonClass.declaration.isNativePackage()) {
                return null
            }
            if (possibleJsonClass.isMarkedNullable) {
                val jsonWriterDecl = resolver.getClassDeclarationByName(JsonTypes.jsonWriter.canonicalName)!!
                val functionDecl = resolver.getFunctionDeclarationsByName("ru.tinkoff.kora.json.common.JsonKotlin.writerForNullable").first()
                val writerType = jsonWriterDecl.asType(
                    listOf(
                        resolver.getTypeArgument(resolver.createKSTypeReferenceFromKSType(possibleJsonClass), Variance.INVARIANT)
                    )
                )
                val delegateType = jsonWriterDecl.asType(
                    listOf(
                        resolver.getTypeArgument(resolver.createKSTypeReferenceFromKSType(possibleJsonClass.makeNotNullable()), Variance.INVARIANT)
                    )
                )
                val functionType = functionDecl.parametrized(writerType, listOf(delegateType))
                return { ExtensionResult.fromExecutable(functionDecl, functionType) }
            }
            val possibleJsonClassDeclaration = possibleJsonClass.declaration
            if (possibleJsonClassDeclaration !is KSClassDeclaration) {
                return null
            }
            if (possibleJsonClassDeclaration.isAnnotationPresent(JsonTypes.json) || possibleJsonClassDeclaration.isAnnotationPresent(JsonTypes.jsonWriterAnnotation)) {
                return generatedByProcessor(resolver, type, possibleJsonClassDeclaration, JsonTypes.jsonWriter)
            }
            return null
        }
        if (erasure == jsonReaderErasure) {
            val possibleJsonClass = type.arguments[0].type!!.resolve()
            if (possibleJsonClass.declaration.isNativePackage()) {
                return null
            }
            if (possibleJsonClass.isMarkedNullable) {
                val jsonReaderDecl = resolver.getClassDeclarationByName(JsonTypes.jsonReader.canonicalName)!!
                val functionDecl = resolver.getFunctionDeclarationsByName("ru.tinkoff.kora.json.common.JsonKotlin.readerForNullable").first()
                val readerType = jsonReaderDecl.asType(
                    listOf(
                        resolver.getTypeArgument(resolver.createKSTypeReferenceFromKSType(possibleJsonClass), Variance.INVARIANT)
                    )
                )
                val delegateType = jsonReaderDecl.asType(
                    listOf(
                        resolver.getTypeArgument(resolver.createKSTypeReferenceFromKSType(possibleJsonClass.makeNotNullable()), Variance.INVARIANT)
                    )
                )
                val functionType = functionDecl.parametrized(readerType, listOf(delegateType))
                return { ExtensionResult.fromExecutable(functionDecl, functionType) }
            }
            val possibleJsonClassDeclaration = possibleJsonClass.declaration
            if (possibleJsonClassDeclaration !is KSClassDeclaration) {
                return null
            }
            if (possibleJsonClassDeclaration.isAnnotationPresent(JsonTypes.json)
                || possibleJsonClassDeclaration.isAnnotationPresent(JsonTypes.jsonReaderAnnotation)
                || possibleJsonClassDeclaration.primaryConstructor?.isAnnotationPresent(JsonTypes.jsonReaderAnnotation) == true
            ) {
                return generatedByProcessor(resolver, type, possibleJsonClassDeclaration, JsonTypes.jsonReader)
            }
            return null
        }
        return null
    }
}
