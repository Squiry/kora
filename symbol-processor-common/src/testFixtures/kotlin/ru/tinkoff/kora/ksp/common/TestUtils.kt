package ru.tinkoff.kora.ksp.common

import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.processing.impl.MessageCollectorBasedKSPLogger
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.jupiter.api.fail
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URL
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively

object TestUtils {
    sealed interface ProcessingResult {
        data class Success(val classLoader: ClassLoader) : ProcessingResult {
//            fun loadClass(className: String): Class<*> {
//                return classLoader.loadClass("$testPackage.$className")!!
//            }
        }

        data class Failure(val messages: List<String>) : ProcessingResult

        fun assertSuccess(): Success {
            when (this) {
                is Failure -> throw messages.asCompilationException()
                is Success -> return this
            }
        }

        fun assertFailure(): Failure {
            if (this is Failure) {
                return this
            }
            fail { "Expected failure, got success" }
        }
    }

    fun runProcessing(processors: List<SymbolProcessorProvider>, srcFiles: List<Path>): ProcessingResult {
        val start = System.currentTimeMillis()
        try {
            val finalGeneratedSourcesPath = Path.of("build/in-test-generated-ksp").resolve("sources")
            finalGeneratedSourcesPath.createDirectories()
            val kspOut = Path.of("build/ksp-out").resolve("kotlinOutputDir")

            val generatedFiles = symbolProcessFiles(processors, srcFiles)
            val kspTime = System.currentTimeMillis() - start
            println("KSP took ${kspTime}ms")
            for (generatedFile in generatedFiles) {
                val relativePath = kspOut.relativize(generatedFile)
                val finalPath = finalGeneratedSourcesPath.resolve(relativePath)
                finalPath.parent.createDirectories()
                Files.copy(generatedFile, finalPath, StandardCopyOption.REPLACE_EXISTING)
            }

            val filesToCompile = srcFiles + generatedFiles
            val cl = kotlinCompileFiles(filesToCompile)
            println("KOTLIN took ${System.currentTimeMillis() - start - kspTime}ms")

            return ProcessingResult.Success(cl)
        } catch (e: CompilationErrorException) {
            return ProcessingResult.Failure(e.messages)
        }
//        catch (e: Exception) {
//            return ProcessingResult.Failure(listOf(e.message ?: e.toString()))
//        }
    }

    @OptIn(ExperimentalPathApi::class)
    fun symbolProcessFiles(processors: List<SymbolProcessorProvider>, srcFiles: List<Path>): List<Path> {
        val out = Path.of("build/ksp-out")
        out.createDirectories()
        out.resolve("kotlinOutputDir").deleteRecursively()

        val pluginClassPath = AbstractSymbolProcessorTest.classpath.asSequence()
            .filter { !it.contains("gradle") }
            .filter { !it.contains("symbol-processing") }
            .map { File(it) }
            .toList()
        val sw = ByteArrayOutputStream();
        val collector = PrintingMessageCollector(
            PrintStream(sw, true, StandardCharsets.UTF_8), MessageRenderer.PLAIN_FULL_PATHS, true
        )
        val kspConfig = com.google.devtools.ksp.processing.KSPJvmConfig.Builder().apply {
            moduleName = "main"
            jvmTarget = "17"
            languageVersion = "2.0"
            apiVersion = "2.0"

            jdkHome = File(System.getProperty("java.home"))
            mapAnnotationArgumentsInJava = true
            libraries = pluginClassPath
            sourceRoots = srcFiles.map { it.toFile() }
            kotlinOutputDir = out.resolve("kotlinOutputDir").toFile()
            javaOutputDir = out.resolve("javaOutputDir").toFile()
            projectBaseDir = out.resolve("projectBaseDir").toFile()
            outputBaseDir = out.resolve("outputBaseDir").toFile()
            cachesDir = out.resolve("cachesDir").toFile()
            classOutputDir = out.resolve("classOutputDir").toFile()
            resourceOutputDir = out.resolve("resourceOutputDir").toFile()

        }.build()
        val l = MessageCollectorBasedKSPLogger(collector, collector, false)
        val exitCode = KotlinSymbolProcessing(kspConfig, processors, l).execute()
        l.reportAll()
        val messages = sw.toString().split("\n")
        if (exitCode != KotlinSymbolProcessing.ExitCode.OK || messages.any { it.startsWith("error: [ksp]") }) {
            throw messages.asCompilationException()
        }

        return kspConfig.kotlinOutputDir.walk().filter { it.isFile }.map { it.toPath() }.toList()
    }


    fun kotlinCompileFiles(files: List<Path>): ClassLoader {
        val kotlinOutPath = Path.of("build/in-test-generated-ksp").toAbsolutePath();
        val inTestGeneratedDestination = kotlinOutPath.resolveSibling("in-test-generated-destination")

        val k2JvmArgs = K2JVMCompilerArguments();
        k2JvmArgs.noReflect = true;
        k2JvmArgs.noStdlib = true;
        k2JvmArgs.noJdk = false;
        k2JvmArgs.includeRuntime = false;
        k2JvmArgs.script = false;
        k2JvmArgs.disableStandardScript = true;
        k2JvmArgs.help = false;
        k2JvmArgs.compileJava = false;
        k2JvmArgs.allowNoSourceFiles = true
        k2JvmArgs.expression = null;
        k2JvmArgs.destination = inTestGeneratedDestination.toString();
        k2JvmArgs.jvmTarget = "17";
        k2JvmArgs.jvmDefault = "all";
        k2JvmArgs.jdkHome = System.getProperty("java.home")
        k2JvmArgs.freeArgs = files.map { it.toString() }
        k2JvmArgs.classpath = AbstractSymbolProcessorTest.classpath.joinToString(File.pathSeparator);

        val co = K2JVMCompiler()

        val sw = ByteArrayOutputStream();
        val collector = PrintingMessageCollector(
            PrintStream(sw, true, StandardCharsets.UTF_8), MessageRenderer.SYSTEM_INDEPENDENT_RELATIVE_PATHS, false
        )

        val code = co.exec(collector, Services.EMPTY, k2JvmArgs)
        if (code != ExitCode.OK) {
            throw sw.toString().split("\n").asCompilationException()
        }
        if (collector.hasErrors()) {
            throw sw.toString().split("\n").asCompilationException()
        }
        return URLClassLoader("test-cl", arrayOf(URL("file://$inTestGeneratedDestination/")), Thread.currentThread().contextClassLoader)
    }

    class CompilationErrorException(var messages: List<String>) : RuntimeException(messages.joinToString("\n"))

    fun List<String>.asCompilationException(): CompilationErrorException {
        val errorMessages = mutableListOf<String>()
        val builder = StringBuilder()
        for (message in this) {
            if (message.contains("error: [") || message.contains("warn: [") || message.contains("info: [") || message.contains("logging: [")) {
                errorMessages.add(builder.toString())
                builder.clear().append(message)
            } else {
                builder.append('\n').append(message)
            }
        }
        if (builder.isNotEmpty()) {
            errorMessages.add(builder.toString())
        }


        val indexOfFirst = this.indexOfFirst { it.contains("error: ") }
        if (indexOfFirst >= 0) {
            errorMessages.add(this[indexOfFirst])
            for (i in indexOfFirst + 1 until this.size) {
                val message = this[i]
                if (message.contains("error: [") || message.contains("warn: [") || message.contains("info: [") || message.contains("logging: [")) {
                    break
                } else {
                    errorMessages.add(message)
                }
            }
        }
        return CompilationErrorException(errorMessages)
    }
}



