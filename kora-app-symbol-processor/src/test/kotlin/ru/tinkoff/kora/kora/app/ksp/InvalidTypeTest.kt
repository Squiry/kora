package ru.tinkoff.kora.kora.app.ksp

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.tinkoff.kora.ksp.common.AbstractSymbolProcessorTest

class InvalidTypeTest : AbstractSymbolProcessorTest() {
    @Test
    fun testUnknownTypeComponent() {
        compile0(
            listOf(KoraAppProcessorProvider()),
            """
                        @ru.tinkoff.kora.common.KoraApp
                        interface TestApp {
                            @Root
                            fun root() = Any()
                            fun unknownTypeComponent(): some.unknown.type.Component {
                                return null!!
                            }
                        }
                        
                        """.trimIndent()
        )


        assertThat(compileResult.isFailed()).isTrue
        assertThat(compileResult.messages).anyMatch { it.endsWith("error: unresolved reference 'some'.") }
    }

    @Test
    fun testUnknownTypeDependency() {
        compile0(
            listOf(KoraAppProcessorProvider()),
            """
                        @ru.tinkoff.kora.common.KoraApp
                        interface TestApp {
                            @Root
                            fun root(dependency: some.unknown.type.Component) = Any()
                        }
                        
                        """.trimIndent()

        )

        assertThat(compileResult.isFailed()).isTrue
        assertThat(compileResult.messages).anyMatch { it.endsWith("error: unresolved reference 'some'.") }
    }
}
