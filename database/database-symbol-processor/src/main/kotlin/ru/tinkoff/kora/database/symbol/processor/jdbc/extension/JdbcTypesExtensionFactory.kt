package ru.tinkoff.kora.database.symbol.processor.jdbc.extension

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import ru.tinkoff.kora.kora.app.ksp.extension.ExtensionFactory

class JdbcTypesExtensionFactory : ExtensionFactory {
    override fun create(resolver: Resolver, kspLogger: KSPLogger) = JdbcTypesExtension(resolver, kspLogger)
}
