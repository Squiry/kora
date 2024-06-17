package ru.tinkoff.kora.kora.app.ksp

import org.junit.jupiter.api.Test

class ConflictResolutionTest : AbstractKoraAppProcessorTest() {
    @Test
    fun testMultipleComponentSameType() {
        compile0(listOf(KoraAppProcessorProvider()),
            """
            interface TestInterface            
            """.trimIndent(),
            """
            class TestImpl1 : TestInterface {}
            """.trimIndent(),
            """
            class TestImpl2 : TestInterface {}
            """.trimIndent(),
            """
            @KoraApp
            interface ExampleApplication {
                @Root
                fun root(t: TestInterface) = ""
                fun testImpl1() = TestImpl1()
                fun testImpl2() = TestImpl2()
            }
            
            """.trimIndent()
        )

        compileResult.assertFailure()
    }

    @Test
    fun testDefaultComponentOverride() {
        compile0(listOf(KoraAppProcessorProvider()),
            """
            interface TestInterface            
            """.trimIndent(),
            """
            class TestImpl1 : TestInterface {}
            """.trimIndent(),
            """
            class TestImpl2 : TestInterface {}
            """.trimIndent(),
            """
            @KoraApp
            interface ExampleApplication {
                @Root
                fun root(t: TestInterface) = ""
            
                fun testImpl1() = TestImpl1()
            
                @DefaultComponent
                fun testImpl2() = TestImpl2()
            }
            """.trimIndent()
        )

        compileResult.assertSuccess()
    }

    @Test
    fun testDefaultComponentTemplateOverride() {
        compile0(listOf(KoraAppProcessorProvider()),
            """
            interface TestInterface <T>
            """.trimIndent(),
            """
            class TestImpl1 <T> : TestInterface <T> {}
            """.trimIndent(),
            """
            class TestImpl2 <T> : TestInterface <T> {}
            """.trimIndent(),
            """
            @KoraApp
            interface ExampleApplication {
                @Root
                fun root(t: TestInterface<String>) = ""
            
                fun <T> testImpl1() = TestImpl1<T>()
            
                @DefaultComponent
                fun <T> testImpl2() = TestImpl2<T>()
            }
            """.trimIndent()
        )

        compileResult.assertSuccess()
    }
}
