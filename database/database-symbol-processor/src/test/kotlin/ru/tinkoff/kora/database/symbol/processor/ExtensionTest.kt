package ru.tinkoff.kora.database.symbol.processor

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import ru.tinkoff.kora.application.graph.ApplicationGraphDraw
import ru.tinkoff.kora.common.Tag
import ru.tinkoff.kora.database.symbol.processor.jdbc.AbstractJdbcRepositoryTest
import ru.tinkoff.kora.kora.app.ksp.KoraAppProcessorProvider
import java.lang.reflect.Constructor
import java.util.function.Supplier

class ExtensionTest : AbstractJdbcRepositoryTest() {

    @Test
    fun test() {
        compile0(listOf(KoraAppProcessorProvider(), RepositorySymbolProcessorProvider()), """
            @Repository
            interface TestRepository : JdbcRepository {
                @Query("INSERT INTO table(value) VALUES (:value)")
                fun abstractMethod(value: String?)
            }
        """.trimIndent(), """
            @KoraApp
            interface TestKoraApp {
                fun jdbcQueryExecutorAccessor(): JdbcConnectionFactory {
                    return org.mockito.Mockito.mock<JdbcConnectionFactory>(JdbcConnectionFactory::class.java)
                }
                @Root
                fun root(testRepository: TestRepository) = Any()
            }
        """.trimIndent())

        val clazz = loadClass("TestKoraAppGraph")
        val constructors = clazz.constructors as Array<Constructor<out Supplier<out ApplicationGraphDraw>>>
        val graphDraw: ApplicationGraphDraw = constructors[0].newInstance().get()
        Assertions.assertThat(graphDraw).isNotNull
        Assertions.assertThat(graphDraw.size()).isEqualTo(3)
    }

    @Test
    fun testTagged() {
        compile0(listOf(KoraAppProcessorProvider(), RepositorySymbolProcessorProvider()), """
            @Repository(executorTag = Tag(TestKoraApp::class))
            interface TestRepository : JdbcRepository {
                @Query("INSERT INTO table(value) VALUES (:value)")
                fun abstractMethod(value: String?)
            }
        """.trimIndent(), """
            @KoraApp
            interface TestKoraApp {
                @Tag(TestKoraApp::class)
                fun jdbcQueryExecutorAccessor(): JdbcConnectionFactory {
                    return org.mockito.Mockito.mock<JdbcConnectionFactory>(JdbcConnectionFactory::class.java)
                }
                @Root
                fun root(testRepository: TestRepository) = Any()
            }
        """.trimIndent())

        val clazz = loadClass("TestKoraAppGraph")
        val constructors = clazz.constructors as Array<Constructor<out Supplier<out ApplicationGraphDraw>>>
        val graphDraw: ApplicationGraphDraw = constructors[0].newInstance().get()
        Assertions.assertThat(graphDraw).isNotNull
        Assertions.assertThat(graphDraw.size()).isEqualTo(3)

        val repositoryClazz = loadClass("\$TestRepository_Impl")
        val constructor = repositoryClazz.constructors[0]
        constructor.parameters.forEach { p ->
            Assertions.assertThat(p.isAnnotationPresent(Tag::class.java)).isTrue
            val annotation = p.getAnnotation(Tag::class.java)
            Assertions.assertThat(annotation.value).hasSize(1)
            Assertions.assertThat(annotation.value[0].simpleName).isEqualTo("TestKoraApp")
        }
    }
}
