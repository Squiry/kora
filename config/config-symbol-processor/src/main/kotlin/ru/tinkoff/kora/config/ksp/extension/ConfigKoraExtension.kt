package ru.tinkoff.kora.config.ksp.extension

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Nullability
import ru.tinkoff.kora.config.ksp.ConfigClassNames
import ru.tinkoff.kora.kora.app.ksp.extension.ExtensionResult
import ru.tinkoff.kora.kora.app.ksp.extension.KoraExtension
import ru.tinkoff.kora.ksp.common.AnnotationUtils.isAnnotationPresent

class ConfigKoraExtension(resolver: Resolver) : KoraExtension {
    private val configValueExtractorTypeErasure = resolver.getClassDeclarationByName(ConfigClassNames.configValueExtractor.canonicalName)!!.asStarProjectedType()

    override fun getDependencyGenerator(resolver: Resolver, type: KSType, tags: Set<String>): (() -> ExtensionResult)? {
        if (tags.isNotEmpty()) return null
        val actualType = if (type.nullability == Nullability.PLATFORM) type.makeNotNullable() else type
        if (actualType.starProjection() != configValueExtractorTypeErasure) {
            return null
        }
        val typeArguments = actualType.arguments
        if (typeArguments.isEmpty()) {
            return null
        }

        val paramTypeArgument = typeArguments.first()

        val configType = paramTypeArgument.type!!.resolve()
        val declaration = configType.declaration
        if (declaration !is KSClassDeclaration) {
            return null
        }
        if (declaration.isAnnotationPresent(ConfigClassNames.configSourceAnnotation) || declaration.isAnnotationPresent(ConfigClassNames.configValueExtractorAnnotation)) {
            return generatedByProcessor(resolver, type, declaration, ConfigClassNames.configValueExtractor.simpleName)
        }
        return null
    }
}
