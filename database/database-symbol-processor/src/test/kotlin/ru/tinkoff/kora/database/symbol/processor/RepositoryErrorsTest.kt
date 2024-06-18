package ru.tinkoff.kora.database.symbol.processor

import org.assertj.core.api.Assertions
import org.assertj.core.api.SoftAssertions
import org.junit.jupiter.api.Test
import ru.tinkoff.kora.database.symbol.processor.jdbc.AbstractJdbcRepositoryTest
import ru.tinkoff.kora.ksp.common.TestUtils.CompilationErrorException

class RepositoryErrorsTest : AbstractJdbcRepositoryTest() {
    @Test
    fun testParameterUsage() {
        Assertions.assertThatThrownBy {
            compile(listOf(), """
            @Repository
            interface InvalidParameterUsage : JdbcRepository {
                @Query("SELECT * FROM table WHERE field3 = :param1.field3")
                fun wrongFieldUsedInTemplate(param1: Dto?, param2: String?): String?
                class Dto
            }
            """.trimIndent())
        }
            .isInstanceOfSatisfying(CompilationErrorException::class.java) { e: CompilationErrorException ->
                SoftAssertions.assertSoftly { s: SoftAssertions ->
                    s.assertThat(e.messages).anyMatch { it.contains("Parameter usage was not found in sql: param2") }
                }
            }
    }

}
