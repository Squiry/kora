package ru.tinkoff.kora.ksp.common

import com.google.devtools.ksp.processing.SymbolProcessorProvider
import io.github.classgraph.ClassGraph
import kotlinx.coroutines.runBlocking
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInfo
import org.junit.jupiter.api.TestInstance
import reactor.core.publisher.Mono
import ru.tinkoff.kora.common.Context
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.Future
import kotlin.reflect.KClass
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.memberFunctions

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
abstract class AbstractSymbolProcessorTest {
    protected lateinit var testInfo: TestInfo
    protected lateinit var compileResult: TestUtils.ProcessingResult

    @BeforeEach
    fun beforeEach(testInfo: TestInfo) {
        this.testInfo = testInfo
        val testClass: Class<*> = this.testInfo.testClass.get()
        val testMethod: Method = this.testInfo.testMethod.get()
        val sources = Paths.get(".", "build", "in-test-generated-ksp", "sources")
        val path = sources
            .resolve(testClass.getPackage().name.replace('.', '/'))
            .resolve("packageFor" + testClass.simpleName)
            .resolve(testMethod.name)
        path.toFile().deleteRecursively()
        Files.createDirectories(path)
    }

    @AfterEach
    fun afterEach() {
        if (this::compileResult.isInitialized) {
            this.compileResult.let { cr ->
                if (cr is TestUtils.ProcessingResult.Success) {
                    cr.classLoader.let { if (it is AutoCloseable) it.close() }
                }
            }
        }
    }

    protected fun loadClass(className: String) = this.compileResult.loadClass(className)

    protected fun testPackage(): String {
        val testClass: Class<*> = testInfo.testClass.get()
        val testMethod: Method = testInfo.testMethod.get()
        return testClass.packageName + ".packageFor" + testClass.simpleName + "." + testMethod.name
    }

    protected open fun commonImports(): String {
        return """
            import ru.tinkoff.kora.common.annotation.*;
            import ru.tinkoff.kora.common.*;
            import jakarta.annotation.Nullable;
            
            """.trimIndent()
    }

    protected fun compile0(processors: List<SymbolProcessorProvider>, @Language("kotlin") vararg sources: String): TestUtils.ProcessingResult {
        val testPackage = testPackage()
        val testClass: Class<*> = testInfo.testClass.get()
        val testMethod: Method = testInfo.testMethod.get()
        val commonImports = commonImports()
        val header = "package $testPackage;\n$commonImports\n/**\n* @see ${testClass.canonicalName}.${testMethod.name} \n*/\n"
        val sourceList = sources.asSequence()
            .map { s: String -> header + s }
            .map { s ->
                val firstClass = s.indexOf("class ") to "class ".length
                val firstInterface = s.indexOf("interface ") to "interface ".length
                val classNameLocation = sequenceOf(firstClass, firstInterface)
                    .filter { it.first >= 0 }
                    .map { it.first + it.second }
                    .flatMap {
                        sequenceOf(
                            s.indexOf(" ", it + 1),
                            s.indexOf("(", it + 1),
                            s.indexOf("{", it + 1),
                            s.indexOf(":", it + 1),
                        )
                            .map { it1 -> it to it1 }
                    }
                    .filter { it.second >= 0 }
                    .minBy { it.second }
                val className = s.substring(classNameLocation.first - 1, classNameLocation.second).trim()
                val fileName = "build/in-test-generated-ksp/sources/${testPackage.replace('.', '/')}/$className.kt"
                val path = Paths.get(fileName)
                Files.createDirectories(path.parent)
                Files.deleteIfExists(path)
                Files.writeString(path, s, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)
                path
            }
            .toList()
        compileResult = TestUtils.runProcessing(processors, sourceList)
        return compileResult
    }

    protected fun TestUtils.ProcessingResult.loadClass(className: String): Class<*> {
        val testPackage = testPackage()
        this as TestUtils.ProcessingResult.Success
        return classLoader.loadClass("$testPackage.$className")!!
    }

    protected fun new(name: String, vararg args: Any?) = compileResult.loadClass(name).constructors[0].newInstance(*args)!!

    interface GeneratedObject<T> : () -> T

    protected fun newGenerated(name: String, vararg args: Any?) = object : GeneratedObject<Any> {
        override fun invoke() = compileResult.loadClass(name).constructors[0].newInstance(*args)!!
    }

    class TestObject(
        val objectClass: KClass<*>,
        val objectInstance: Any
    ) {

        @SuppressWarnings("unchecked")
        inline fun <reified T> invoke(method: String, vararg args: Any?): T? {
            for (testObjectMethod in objectClass.memberFunctions) {
                if (testObjectMethod.name == method && testObjectMethod.parameters.size == args.size + 1) {
                    try {
                        val realArgs = Array(args.size + 1) {
                            if (it == 0) {
                                objectInstance
                            } else {
                                args[it - 1]
                            }
                        }

                        val result = if (testObjectMethod.isSuspend) {
                            runBlocking(Context.Kotlin.asCoroutineContext(Context.current())) { testObjectMethod.callSuspend(*realArgs) }
                        } else {
                            testObjectMethod.call(*realArgs)
                        }
                        return when (result) {
                            is Mono<*> -> result.block()
                            is Future<*> -> result.get()
                            else -> result
                        } as T?
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            }
            throw IllegalArgumentException()
        }
    }


    companion object {
        var classpath: List<String>

        init {

            val classGraph = ClassGraph()
                .enableSystemJarsAndModules()
                .removeTemporaryFilesAfterScan()

            val classpaths = classGraph.classpathFiles;
            val modules = classGraph.modules
                .asSequence()
                .filterNotNull()
                .map { it.locationFile };

            classpath = (classpaths.asSequence() + modules)
                .filterNotNull()
                .map { it.toString() }
                .distinct()
                .toList();
        }
    }


}
