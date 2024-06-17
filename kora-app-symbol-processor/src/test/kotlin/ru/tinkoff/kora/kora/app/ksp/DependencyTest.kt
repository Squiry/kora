package ru.tinkoff.kora.kora.app.ksp

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

open class DependencyTest : AbstractKoraAppProcessorTest() {
    @Test
    open fun testValueOfComponents() {
        val draw = compile(
            """
            @KoraApp
            interface ExampleApplication {
                interface TestInterface
                class TestClass: TestInterface
                
                @Root
                fun test(valueOfClass: ValueOf<TestClass>, valueOfInterface: ValueOf<TestInterface>, valueOfClassN: ValueOf<TestClass?>, valueOfInterfaceN: ValueOf<TestInterface?>) = ""
            }
            """.trimIndent()
        )
        Assertions.assertThat(draw.nodes).hasSize(2)
        draw.init()
    }


    @Test
    open fun multimoduleDependencies() {
        val draw = compile(
            """
            @KoraApp
            interface ExampleApplication: Module1, Module2, Module3 {
              class Test1
              class Test2
            }
            """.trimIndent(),
            """
            @KoraApp
            interface Module1 {
              @Root
              fun test(test1: Test1, test2: Test2) = ""
            }
            class Test1
            class Test2
            """.trimIndent(),
            """
            interface Module2 {
              fun test1() = Test1()
            }
            """.trimIndent(),
            """
            interface Module3 {
              fun test2() = Test2()
            }
            """.trimIndent(),
        )
        Assertions.assertThat(draw.nodes).hasSize(3)
        draw.init()
    }

    @Test
    open fun testTaggedDependencyFound() {
        val draw = compile(
            """
            @KoraApp
            interface ExampleApplication {
                class TestClass
                
                @Tag(String::class)
                fun str() = TestClass()
                
                @Tag(Int::class)
                fun int() = TestClass()

                fun empty() = TestClass()
                
                @Root
                fun test(empty: TestClass, @Tag(String::class) str: TestClass, @Tag(Int::class) int: TestClass) = ""
            }
            """.trimIndent()
        )
        Assertions.assertThat(draw.nodes).hasSize(4)
        draw.init()
    }

    @Test
    open fun testNullableDependencyFound() {
        val draw = compile(
            """
            @KoraApp
            interface ExampleApplication {
                class TestClass
                
                @Root
                fun test(testClass: TestClass?) = ""
            }
            """.trimIndent()
        )
        Assertions.assertThat(draw.nodes).hasSize(1)
        draw.init()
    }

    @Test
    open fun testNullableDependencyNotFound() {
        val draw = compile(
            """
            @KoraApp
            interface ExampleApplication {
                open class TestClass
                
                @Root
                fun test(testClass: TestClass?) = ""
            }
            """.trimIndent()
        )
        Assertions.assertThat(draw.nodes).hasSize(1)
        draw.init()
    }

    @Test
    open fun testDependencyFoundByInterface() {
        val draw = compile(
            """
            @KoraApp
            interface ExampleApplication {
                interface TestInterface
                @Component
                class TestClass: TestInterface
                
                @Root
                fun test(testClass: TestInterface) = ""
            }
            """.trimIndent()
        )
        Assertions.assertThat(draw.nodes).hasSize(2)
        draw.init()
    }


    @Test
    open fun testDiscoveredFinalClassDependency() {
        val draw = compile(
            """
            @KoraApp
            interface ExampleApplication {
                class TestClass1
                
                @Root
                fun test(testClass: TestClass1) = ""
            }
            """.trimIndent()
        )
        Assertions.assertThat(draw.nodes).hasSize(2)
        draw.init()
    }

    @Test
    open fun testDiscoveredFinalClassDependencyWithTag() {
        val draw = compile(
            """
            @KoraApp
            interface ExampleApplication {
                @Tag(TestClass1::class)
                class TestClass1
                
                @Root
                fun test(@Tag(TestClass1::class) testClass: TestClass1) = ""
            }
            """.trimIndent()
        )
        Assertions.assertThat(draw.nodes).hasSize(2)
        draw.init()
    }

    @Test
    open fun testDiscoveredFinalClassDependencyTaggedDependencyNoTagOnClass() {
        Assertions.assertThatThrownBy {
            compile(
                """
                @KoraApp
                interface ExampleApplication {
                    class TestClass1 
                    
                    @Root
                    fun test(@Tag(TestClass1::class) testClass: TestClass1) = ""
                }
                """.trimIndent()
            )
        }
        compileResult.assertFailure()
//        Assertions.assertThat<Diagnostic<out JavaFileObject?>>(compileResult.errors()).hasSize(1)
//        Assertions.assertThat(compileResult.errors().get(0).getMessage(Locale.ENGLISH)).startsWith(
//            "Required dependency was not found: " +
//                "@Tag(ru.tinkoff.kora.kora.app.annotation.processor.packageForDependencyTest.testDiscoveredFinalClassDependencyTaggedDependencyNoTagOnClass.ExampleApplication.TestClass1) " +
//                "ru.tinkoff.kora.kora.app.annotation.processor.packageForDependencyTest.testDiscoveredFinalClassDependencyTaggedDependencyNoTagOnClass.ExampleApplication.TestClass1"
//        )
    }

    @Test
    fun testCycleInGraphResolvedWithProxy() {
        compile("""
            @KoraApp
            interface ExampleApplication {
                fun class1(promise: Interface1): Class1 {
                    return Class1()
                }

                fun class2(promise: PromiseOf<Class1>): Class2 {
                    return Class2()
                }

                @Root
                fun root(class2: Class2) = Any()

                interface Interface1 {
                    fun method() {}
                    fun methodWithReservedNameParameter(`is`: String) {}

                }
                class Class1
                class Class2 : Interface1
            }
        """.trimIndent())
    }

    @Test
    fun testOptionalValueOf() {
        val draw = compile(
            """
            @KoraApp
            interface ExampleApplication {
                fun component1() = "test"
                
                @Root
                fun root1(t: java.util.Optional<ru.tinkoff.kora.application.graph.ValueOf<String>>) = Any()
                @Root
                fun root2(t: java.util.Optional<ru.tinkoff.kora.application.graph.ValueOf<Int>>) = Any()

            }
            """.trimIndent()
        )
        Assertions.assertThat(draw.nodes).hasSize(5)
    }

    @Test
    fun testOptional() {
        val draw = compile(
            """
            @KoraApp
            interface ExampleApplication {
                fun component1() = "test"
                
                @Root
                fun root1(t: java.util.Optional<String>) = Any()
                @Root
                fun root2(t: java.util.Optional<Int>) = Any()

            }
            """.trimIndent()
        )
        Assertions.assertThat(draw.nodes).hasSize(5)
    }

}
