package ru.tinkoff.kora.database.symbol.processor.cassandra

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ClassAssert
import org.assertj.core.api.Condition
import org.junit.jupiter.api.Test
import ru.tinkoff.kora.database.cassandra.mapper.parameter.CassandraParameterColumnMapper
import ru.tinkoff.kora.database.cassandra.mapper.result.CassandraRowColumnMapper
import ru.tinkoff.kora.database.symbol.processor.AbstractRepositoryTest
import ru.tinkoff.kora.database.symbol.processor.cassandra.udt.CassandraUdtSymbolProcessorProvider
import kotlin.reflect.KClass

class CassandraUdtTest : AbstractRepositoryTest() {
    @Test
    fun testUdt() {
        val cl = compile0(listOf(CassandraUdtSymbolProcessorProvider()), """
        import ru.tinkoff.kora.database.cassandra.annotation.UDT
        class Udt {
            @UDT
            data class UdtEntity(val string: String, val innerUdt: InnerUdt)
            @UDT
            data class InnerUdt(val id: Int, val deep: DeepUdt)
            @UDT
            data class DeepUdt(val doubleValue: Double?)
        }
        """.trimIndent())
        assertThat(cl.loadClass("\$Udt_UdtEntity_CassandraRowColumnMapper"))
            .isNotNull
            .implements(CassandraRowColumnMapper::class)
        assertThat(cl.loadClass("\$Udt_UdtEntity_List_CassandraRowColumnMapper"))
            .isNotNull
            .implements(CassandraRowColumnMapper::class)
        assertThat(cl.loadClass("\$Udt_UdtEntity_CassandraParameterColumnMapper"))
            .isNotNull
            .implements(CassandraParameterColumnMapper::class)
        assertThat(cl.loadClass("\$Udt_UdtEntity_List_CassandraParameterColumnMapper"))
            .isNotNull
            .implements(CassandraParameterColumnMapper::class)
        assertThat(cl.loadClass("\$Udt_InnerUdt_CassandraRowColumnMapper"))
            .isNotNull
            .implements(CassandraRowColumnMapper::class)
        assertThat(cl.loadClass("\$Udt_InnerUdt_CassandraParameterColumnMapper"))
            .isNotNull
            .implements(CassandraParameterColumnMapper::class)
        assertThat(cl.loadClass("\$Udt_DeepUdt_CassandraRowColumnMapper"))
            .isNotNull
            .implements(CassandraRowColumnMapper::class)
        assertThat(cl.loadClass("\$Udt_DeepUdt_CassandraParameterColumnMapper"))
            .isNotNull
            .implements(CassandraParameterColumnMapper::class)
    }

    private fun ClassAssert.implements(expectedSuperinterface: KClass<*>) {
        this.`is`(
            Condition({
                for (superinterface in it.interfaces) {
                    if (superinterface.canonicalName == expectedSuperinterface.qualifiedName) {
                        return@Condition true
                    }
                }
                false
            }, "Must implement interface ${expectedSuperinterface.qualifiedName}")
        )
    }

    override fun commonImports(): String {
        return super.commonImports() + """
            import ru.tinkoff.kora.database.cassandra.*;
            import ru.tinkoff.kora.database.cassandra.mapper.result.*;
            import ru.tinkoff.kora.database.cassandra.mapper.parameter.*;
        """.trimIndent()
    }

    @Test
    fun testUdtExtension() {
        compile0(listOf(CassandraUdtSymbolProcessorProvider()),
            """
            @ru.tinkoff.kora.database.cassandra.annotation.UDT
            data class UdtEntity(val value1: String, val value2: String)
            """.trimIndent(), """
            @KoraApp
            interface Application {
                @Root
                fun entityParameterMapper(m1: CassandraParameterColumnMapper<UdtEntity>) = ""
                @Root
                fun entityListParameterMapper(m1: CassandraParameterColumnMapper<List<UdtEntity>>) = ""
                @Root
                fun entityResultMapper(m: CassandraRowColumnMapper<UdtEntity>) = ""
                @Root
                fun entityListResultMapper(m: CassandraRowColumnMapper<List<UdtEntity>>) = ""
            }
            """.trimIndent()
        )

        compileResult.assertSuccess()
    }
}
