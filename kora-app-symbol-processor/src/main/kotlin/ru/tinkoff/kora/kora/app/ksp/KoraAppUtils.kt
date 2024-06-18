package ru.tinkoff.kora.kora.app.ksp

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.Resolver


object KoraAppUtils {
    val debug = System.getProperty("kora.ksp.debug", "false").toBoolean()

    inline fun <T> timed(name: String, f: () -> T): T {
        if (!debug) {
            return f()
        }
        val start = System.currentTimeMillis()
        try {
            return f()
        } finally {
            val end = System.currentTimeMillis()
            if (end - start >= 5) {
                println("$name took ${end - start} ms")
            }
        }
    }
}


fun isClassExists(resolver: Resolver, fullClassName: String): Boolean {
    val declaration = resolver.getClassDeclarationByName(fullClassName)
    return declaration != null
}


