package ru.tinkoff.kora.mapstruct.ksp.extension

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import ru.tinkoff.kora.kora.app.ksp.extension.ExtensionFactory
import ru.tinkoff.kora.kora.app.ksp.extension.KoraExtension

class MapstructKoraExtensionFactory : ExtensionFactory {

    override fun create(resolver: Resolver, kspLogger: KSPLogger): KoraExtension? {
        return resolver.getClassDeclarationByName(MapstructKoraExtension.mapperAnnotation.canonicalName)
            ?.let { MapstructKoraExtension }
    }
}
