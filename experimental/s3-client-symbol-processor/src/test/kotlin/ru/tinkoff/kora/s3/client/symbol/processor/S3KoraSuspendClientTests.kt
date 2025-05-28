package ru.tinkoff.kora.s3.client.symbol.processor

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import ru.tinkoff.kora.ksp.common.AbstractSymbolProcessorTest

class S3KoraSuspendClientTests : AbstractSymbolProcessorTest() {
    val processors = listOf(S3ClientSymbolProcessorProvider())

    override fun commonImports(): String {
        return super.commonImports() + """
            import java.nio.ByteBuffer;
            import java.io.InputStream;
            import ru.tinkoff.kora.s3.client.annotation.*;
            import ru.tinkoff.kora.s3.client.annotation.S3.*;
            import ru.tinkoff.kora.s3.client.model.*;
            import ru.tinkoff.kora.s3.client.*;
            import ru.tinkoff.kora.s3.client.model.S3Object;
            import software.amazon.awssdk.services.s3.model.*;
            """.trimIndent()
    }

    @Test
    fun clientConfig() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Get
                        suspend fun get(key: String): S3ObjectMeta
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()

        val config = compileResult.loadClass("\$Client_ClientConfigModule")
        assertThat(config).isNotNull()
    }

    // Get
    @Test
    fun clientGetMeta() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Get
                        suspend fun get(key: String): S3ObjectMeta
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientGetObject() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Get
                        suspend fun get(key: String): S3Object
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientGetManyMetas() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Get
                        suspend fun get(keys: Collection<String>): List<S3ObjectMeta>
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientGetManyObjects() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Get
                        suspend fun get(key: List<String>): List<S3Object>
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientGetKeyConcat() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Get("{key1}-{key2}")
                        suspend fun get(key1: String, key2: Long): S3ObjectMeta
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientGetKeyMissing() {
        val result = compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Get("{key1}-{key12345}")
                        suspend fun get(key1: String): S3ObjectMeta
                    }
                    """.trimIndent()
            )
        )
        assertThat(result.isFailed()).isTrue()
    }

    @Test
    fun clientGetKeyConst() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Get("const-key")
                        suspend fun get(): S3ObjectMeta
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientGetKeyUnused() {
        val result = compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Get("const-key")
                        suspend fun get(key: String): S3ObjectMeta
                    }
                    """.trimIndent()
            )
        )
        assertThat(result.isFailed()).isTrue()
    }

    // List
    @Test
    fun clientListMeta() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.List
                        suspend fun list(): S3ObjectMetaList
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientListMetaWithPrefix() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.List
                        suspend fun list(prefix: String): S3ObjectMetaList
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientListObjectsWithPrefix() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.List
                        suspend fun list(prefix: String): S3ObjectList
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientListLimit() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.List(limit = 100)
                        suspend fun list(prefix: String): S3ObjectList
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientListKeyConcat() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.List("{key1}-{key2}")
                        suspend fun list(key1: String, key2: Long): S3ObjectList
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientListKeyAndDelimiter() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.List(value = "some/path/to/{key1}/object", delimiter = "/")
                        suspend fun list(key1: String): S3ObjectList
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientListKeyMissing() {
        val result = compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.List("{key1}-{key12345}")
                        suspend fun list(key1: String): S3ObjectList
                    }
                    """.trimIndent()
            )
        )
        assertThat(result.isFailed()).isTrue()
    }

    @Test
    fun clientListKeyConst() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.List("const-key")
                        suspend fun list(): S3ObjectList
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientListKeyUnused() {
        val result = compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.List("const-key")
                        suspend fun list(key: String): S3ObjectList
                    }
                    """.trimIndent()
            )
        )
        assertThat(result.isFailed()).isTrue()
    }

    // Delete
    @Test
    fun clientDelete() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Delete
                        suspend fun delete(key: String)
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientDeleteKeyConcat() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Delete("{key1}-{key2}")
                        suspend fun delete(key1: String, key2: Long)
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientDeleteKeyMissing() {
        val result = compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Delete("{key1}-{key12345}")
                        suspend fun delete(key1: String)
                    }
                    """.trimIndent()
            )
        )
        assertThat(result.isFailed()).isTrue()
    }

    @Test
    fun clientDeleteKeyConst() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Delete("const-key")
                        suspend fun delete()
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientDeleteKeyUnused() {
        val result = compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Delete("const-key")
                        suspend fun delete(key: String)
                    }
                    """.trimIndent()
            )
        )
        assertThat(result.isFailed()).isTrue()
    }

    // Deletes
    @Test
    fun clientDeletes() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Delete
                        suspend fun delete(key: List<String>)
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    // Put
    @Test
    fun clientPutBody() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Put
                        suspend fun put(key: String, value: S3Body)
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientPutBodyReturnUpload() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Put
                        suspend fun put(key: String, value: S3Body): S3ObjectUpload
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientPutBytes() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Put
                        suspend fun put(key: String, value: ByteArray)
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientPutBuffer() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Put
                        suspend fun put(key: String, value: ByteBuffer)
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientPutBodyAndType() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Put(type = "type")
                        suspend fun put(key: String, value: S3Body)
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientPutBodyAndEncoding() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Put(encoding = "encoding")
                        suspend fun put(key: String, value: S3Body)
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientPutBodyAndTypeAndEncoding() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Put(type = "type", encoding = "encoding")
                        suspend fun put(key: String, value: S3Body)
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientPutKeyConcat() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Put("{key1}-{key2}")
                        suspend fun put(key1: String, key2: Long, value: S3Body)
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientPutKeyMissing() {
        val result = compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Put("{key1}-{key12345}")
                        suspend fun put(key1: String, value: S3Body)
                    }
                    """.trimIndent()
            )
        )
        assertThat(result.isFailed()).isTrue()
    }

    @Test
    fun clientPutKeyConst() {
        compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Put("const-key")
                        suspend fun put(value: S3Body)
                    }
                    """.trimIndent()
            )
        )
        compileResult.assertSuccess()
        val clazz = compileResult.loadClass("\$Client_Impl")
        assertThat(clazz).isNotNull()
    }

    @Test
    fun clientPutKeyUnused() {
        val result = compile0(
            processors, *arrayOf<String>(
                """
                    @S3.Client("my")
                    interface Client {
                                
                        @S3.Put("const-key")
                        suspend fun put(key: String, value: S3Body)
                    }
                    """.trimIndent()
            )
        )
        assertThat(result.isFailed()).isTrue()
    }
}
