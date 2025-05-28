package ru.tinkoff.kora.kora.app.ksp

import org.intellij.lang.annotations.Language
import ru.tinkoff.kora.aop.symbol.processor.AopSymbolProcessorProvider
import ru.tinkoff.kora.application.graph.ApplicationGraphDraw
import ru.tinkoff.kora.ksp.common.AbstractSymbolProcessorTest

abstract class AbstractKoraAppProcessorTest : AbstractSymbolProcessorTest() {
    override fun commonImports() = super.commonImports() + """
        import ru.tinkoff.kora.application.graph.*;
        import java.util.Optional;
        
        """.trimIndent()


    protected fun compile(@Language("kotlin") vararg sources: String): ApplicationGraphDraw {
        val compileResult = compile0(listOf(KoraAppProcessorProvider(), AopSymbolProcessorProvider()), *sources)
        if (compileResult.isFailed()) {
            throw compileResult.compilationException()
        }

        val appClass = compileResult.loadClass("ExampleApplicationGraph")
        val graph = appClass.getConstructor().newInstance() as Function0<ApplicationGraphDraw>
        return graph()
    }
}
