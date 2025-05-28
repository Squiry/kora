package ru.tinkoff.kora.validation.symbol.processor.extension

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Nullability
import ru.tinkoff.kora.kora.app.ksp.extension.ExtensionResult
import ru.tinkoff.kora.kora.app.ksp.extension.KoraExtension
import ru.tinkoff.kora.validation.symbol.processor.ValidTypes.VALIDATOR_TYPE

class ValidKoraExtension(resolver: Resolver) : KoraExtension {
    private val validatorType = resolver.getClassDeclarationByName(VALIDATOR_TYPE.canonicalName)!!.asStarProjectedType()

    override fun getDependencyGenerator(resolver: Resolver, type: KSType, tags: Set<String>): (() -> ExtensionResult)? {
        if (tags.isNotEmpty()) return null
        val actualType = if (type.nullability == Nullability.PLATFORM) type.makeNotNullable() else type
        val erasure = actualType.starProjection()
        if (erasure == validatorType) {
            val validType = type.arguments[0]
            val argumentType = validType.type!!.resolve()
            val argumentTypeClass = argumentType.declaration as KSClassDeclaration

            return generatedByProcessor(resolver, type, argumentTypeClass, "Validator")
        }

        return null
    }
}
