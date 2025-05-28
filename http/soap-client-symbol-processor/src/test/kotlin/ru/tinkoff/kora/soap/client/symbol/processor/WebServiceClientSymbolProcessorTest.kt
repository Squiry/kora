package ru.tinkoff.kora.soap.client.symbol.processor

import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.jupiter.api.Test
import ru.tinkoff.kora.annotation.processor.common.TestUtils
import ru.tinkoff.kora.ksp.common.AbstractSymbolProcessorTest.Companion.classpath
import ru.tinkoff.kora.ksp.common.KspCollectingLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class WebServiceClientSymbolProcessorTest {

    @Test
    fun testGenerate() {
        compileKotlin("build/generated/wsdl-jakarta-simple-service/")
        compileKotlin("build/generated/wsdl-javax-simple-service/")
        compileKotlin("build/generated/wsdl-jakarta-service-with-multipart-response/")
        compileKotlin("build/generated/wsdl-javax-service-with-multipart-response/")
        compileKotlin("build/generated/wsdl-jakarta-service-with-rpc/")
        compileKotlin("build/generated/wsdl-javax-service-with-rpc/")
    }

    private fun compileKotlin(targetDir: String) {
        val k2JvmArgs = K2JVMCompilerArguments()
        val kotlinOutPath = Path.of("build/in-test-generated-ksp/ksp/sources/kotlin").toAbsolutePath()
        Files.walk(kotlinOutPath.resolveSibling("in-test-generated-kspOutputDir")).filter { it.toFile().isFile }.forEach { it.toFile().delete() }
        val srcFiles = Files.walk(Path.of(targetDir)).use { it.filter { it.toFile().isFile }.map { it.toString() }.toList() }
        k2JvmArgs.noReflect = true
        k2JvmArgs.noStdlib = true
        k2JvmArgs.noJdk = false
        k2JvmArgs.includeRuntime = false
        k2JvmArgs.script = false
        k2JvmArgs.disableStandardScript = true
        k2JvmArgs.help = false
        k2JvmArgs.expression = null
        k2JvmArgs.destination = "$kotlinOutPath/kotlin-classes"
        k2JvmArgs.jvmTarget = "17"
        k2JvmArgs.jvmDefault = "all"
        k2JvmArgs.compileJava = true
        k2JvmArgs.verbose = true
        k2JvmArgs.javaSourceRoots = srcFiles.toTypedArray()
        k2JvmArgs.freeArgs = listOf("build/tmp/empty.kt")
        k2JvmArgs.classpath = java.lang.String.join(File.pathSeparator, TestUtils.classpath)
        k2JvmArgs.jdkHome = System.getProperty("java.home")


        val kotlinOutputDir = kotlinOutPath.resolveSibling("in-test-generated-kspOutputDir")
        val kspArgs = com.google.devtools.ksp.processing.KSPJvmConfig.Builder()
        kspArgs.kotlinOutputDir = kotlinOutputDir.toFile()
        kspArgs.classOutputDir = kotlinOutPath.resolveSibling("in-test-generated-classOutputDir").toFile()
        kspArgs.outputBaseDir = kotlinOutPath.resolveSibling("in-test-generated-kspOutputDir").toFile()
        kspArgs.incremental = false
        kspArgs.javaOutputDir = kotlinOutPath.resolveSibling("in-test-generated-javaOutputDir").toFile()
        kspArgs.projectBaseDir = Path.of(".").toAbsolutePath().toFile()
        kspArgs.resourceOutputDir = kotlinOutPath.resolveSibling("in-test-generated-resourceOutputDir").toFile()
        kspArgs.cachesDir = kotlinOutPath.resolveSibling("in-test-generated-cachesDir").toFile()
        kspArgs.jvmTarget = "17"
        kspArgs.moduleName = "test"
        kspArgs.javaSourceRoots = srcFiles.map { File(it) }
        kspArgs.languageVersion = "2.1.21"
        kspArgs.apiVersion = "2.1.21"
        kspArgs.libraries = classpath.map { File(it) }
        kspArgs.jdkHome = File(System.getProperty("java.home"))
        kspArgs.sourceRoots = listOf()


        val kspLogger = KspCollectingLogger()
        val start = System.currentTimeMillis()
        val exitCode = KotlinSymbolProcessing(kspArgs.build(), listOf(WebServiceClientSymbolProcessorProvider()), kspLogger).execute()
        val kspTook = System.currentTimeMillis() - start
        println("KSP took $kspTook ms")
        if (exitCode.code != 0) {
            throw RuntimeException("KSP failed with code $exitCode")
        }
        k2JvmArgs.freeArgs = kotlinOutputDir.toFile().walkTopDown().filter { it.isFile }.map { it.toString() }.toList()



        Files.writeString(
            Path.of("build/tmp/empty.kt"),
            "fun test() { }",
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.CREATE
        )
        val sw = ByteArrayOutputStream()
        val collector = PrintingMessageCollector(
            PrintStream(sw, true, StandardCharsets.UTF_8), MessageRenderer.SYSTEM_INDEPENDENT_RELATIVE_PATHS, false
        )
        val co = K2JVMCompiler()
        val code = co.exec(collector, Services.EMPTY, k2JvmArgs)
        if (code != org.jetbrains.kotlin.cli.common.ExitCode.OK) {
            throw RuntimeException(sw.toString(StandardCharsets.UTF_8))
        }
        println(sw.toString(StandardCharsets.UTF_8))
    }

}
