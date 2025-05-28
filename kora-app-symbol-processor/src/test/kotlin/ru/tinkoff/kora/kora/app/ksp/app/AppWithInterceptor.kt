package ru.tinkoff.kora.kora.app.ksp.app

import ru.tinkoff.kora.application.graph.GraphInterceptor
import ru.tinkoff.kora.common.KoraApp
import ru.tinkoff.kora.common.annotation.Root

@KoraApp
interface AppWithInterceptor {
    fun class1(): Class1 {
        return Class1()
    }

    fun interceptor(): Interceptor {
        return Interceptor()
    }

    @Root
    fun lifecycle(o: Interface1): Any {
        return Any()
    }

    class Class1
    class Interceptor : GraphInterceptor<Class1> {
        override fun init(value: Class1): Class1 {
            return value
        }

        override fun release(value: Class1): Class1 {
            return value
        }
    }

    interface Interface1
}
