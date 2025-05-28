package ru.tinkoff.kora.ksp.common

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSNode

class KspCollectingLogger : KSPLogger {
    val messages = mutableListOf<String>()
    override fun error(message: String, symbol: KSNode?) {
        messages.add("error: $message ${symbol?.location}")
    }

    override fun exception(e: Throwable) {
        messages.add("exception: ${e.message}")
    }

    override fun info(message: String, symbol: KSNode?) {
        messages.add("info: $message ${symbol?.location}")
    }

    override fun logging(message: String, symbol: KSNode?) {
        messages.add("logging: $message ${symbol?.location}")
    }

    override fun warn(message: String, symbol: KSNode?) {
        messages.add("warn: $message ${symbol?.location}")
    }
}
