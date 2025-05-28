package ru.tinkoff.kora.database.symbol.processor.entity

import ru.tinkoff.kora.common.Mapping
import ru.tinkoff.kora.database.symbol.processor.cassandra.TestEntityFieldCassandraParameterColumnMapper
import ru.tinkoff.kora.database.symbol.processor.cassandra.TestEntityFieldCassandraParameterColumnMapperNonFinal
import ru.tinkoff.kora.database.symbol.processor.cassandra.TestEntityFieldCassandraResultColumnMapper
import ru.tinkoff.kora.database.symbol.processor.cassandra.TestEntityFieldCassandraResultColumnMapperNonFinal

data class TestEntity(
    val field1: String,
    val field2: Int,
    val field3: Int?,
    val unknownTypeField: UnknownField,
    // mappers
    @Mapping(TestEntityFieldCassandraResultColumnMapper::class)
    @Mapping(TestEntityFieldCassandraParameterColumnMapper::class)
    val mappedField1: MappedField1,
    // mappers
    @Mapping(TestEntityFieldCassandraResultColumnMapperNonFinal::class)
    @Mapping(TestEntityFieldCassandraParameterColumnMapperNonFinal::class)
    val mappedField2: MappedField2
) {
    class UnknownField
    class MappedField1
    class MappedField2

    companion object {

        fun defaultData(): TestEntity {
            return TestEntity(
                "field1",
                42,
                43,
                UnknownField(),
                MappedField1(),
                MappedField2()
            )
        }
    }
}
