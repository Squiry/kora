package ru.tinkoff.kora.validation.symbol.processor.extension

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import ru.tinkoff.kora.kora.app.ksp.extension.ExtensionFactory
import ru.tinkoff.kora.kora.app.ksp.extension.KoraExtension
import ru.tinkoff.kora.validation.symbol.processor.ValidTypes.VALID_TYPE

class ValidKoraExtensionFactory : ExtensionFactory {
    override fun create(resolver: Resolver, kspLogger: KSPLogger): KoraExtension? {
        val valid = resolver.getClassDeclarationByName(VALID_TYPE.canonicalName)
        return if (valid == null) {
            null
        } else {
            ValidKoraExtension(resolver)
        }
    }
}
