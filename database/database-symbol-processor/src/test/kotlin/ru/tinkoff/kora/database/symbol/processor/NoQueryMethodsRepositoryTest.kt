package ru.tinkoff.kora.database.symbol.processor

import ru.tinkoff.kora.database.symbol.processor.jdbc.MockJdbcExecutor

class NoQueryMethodsRepositoryTest {
    private val executor: MockJdbcExecutor = MockJdbcExecutor()
//    private val repository: NoQueryMethodsRepository = DbTestUtils.compile(
//        NoQueryMethodsRepository::class, executor
//    ) TODO

//    @Test
//    fun testCompiles() {
//        assertThat(repository).isNotNull
//    }
}
