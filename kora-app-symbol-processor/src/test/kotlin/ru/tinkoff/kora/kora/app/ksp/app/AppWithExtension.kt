package ru.tinkoff.kora.kora.app.ksp.app

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSType
import ru.tinkoff.kora.common.KoraApp
import ru.tinkoff.kora.common.annotation.Root
import ru.tinkoff.kora.kora.app.ksp.extension.ExtensionFactory
import ru.tinkoff.kora.kora.app.ksp.extension.ExtensionResult
import ru.tinkoff.kora.kora.app.ksp.extension.KoraExtension

@KoraApp
interface AppWithExtension {
    // factory: generic component, accepts its genetic TypeRef as arguments
    @Root
    fun test1(class1: Interface1): Class1 {
        return Class1()
    }

    fun test2(): Class2 {
        return Class2()
    }

    interface Interface1
    class Interface1Imol : Interface1
    open class Class1
    open class Class2

    class TestExtension2ExtensionFactory : ExtensionFactory {
        override fun create(resolver: Resolver, kspLogger: KSPLogger): KoraExtension {
            return TestExtension2(resolver)
        }
    }

    class TestExtension2(val resolver: Resolver) : KoraExtension {
        private val interfaceDeclaration = resolver.getClassDeclarationByName(Interface1::class.qualifiedName!!)!!
        private val interfaceType = interfaceDeclaration.asStarProjectedType()

        override fun getDependencyGenerator(resolver: Resolver, type: KSType, tags: Set<String>): (() -> ExtensionResult)? {
            if (type != interfaceType) {
                return null
            }
            return lambda@{
                val clazz = resolver.getClassDeclarationByName(Interface1Imol::class.qualifiedName!!)!!

                return@lambda ExtensionResult.fromConstructor(clazz.primaryConstructor!!, clazz);
            }
        }
    }
}

