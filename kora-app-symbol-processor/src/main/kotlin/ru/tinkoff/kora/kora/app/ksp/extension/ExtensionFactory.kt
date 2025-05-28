package ru.tinkoff.kora.kora.app.ksp.extension

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver

interface ExtensionFactory {

    fun create(resolver: Resolver, kspLogger: KSPLogger): KoraExtension?
}
