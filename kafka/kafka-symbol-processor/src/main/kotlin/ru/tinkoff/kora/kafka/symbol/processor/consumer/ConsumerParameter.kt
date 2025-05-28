package ru.tinkoff.kora.kafka.symbol.processor.consumer

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*
import ru.tinkoff.kora.kafka.symbol.processor.KafkaUtils.isAnyException
import ru.tinkoff.kora.kafka.symbol.processor.KafkaUtils.isConsumer
import ru.tinkoff.kora.kafka.symbol.processor.KafkaUtils.isConsumerRecord
import ru.tinkoff.kora.kafka.symbol.processor.KafkaUtils.isConsumerRecords
import ru.tinkoff.kora.kafka.symbol.processor.KafkaUtils.isKeyDeserializationException
import ru.tinkoff.kora.kafka.symbol.processor.KafkaUtils.isRecordsTelemetry
import ru.tinkoff.kora.kafka.symbol.processor.KafkaUtils.isValueDeserializationException

sealed interface ConsumerParameter {
    val parameter: KSValueParameter

    data class Consumer(override val parameter: KSValueParameter, val key: KSType, val value: KSType) : ConsumerParameter

    data class Records(override val parameter: KSValueParameter, val key: KSType, val value: KSType) : ConsumerParameter

    data class Exception(override val parameter: KSValueParameter) : ConsumerParameter

    data class KeyDeserializationException(override val parameter: KSValueParameter) : ConsumerParameter

    data class ValueDeserializationException(override val parameter: KSValueParameter) : ConsumerParameter

    data class Record(override val parameter: KSValueParameter, val key: KSType, val value: KSType) : ConsumerParameter

    data class RecordsTelemetry(override val parameter: KSValueParameter, val key: KSType, val value: KSType) : ConsumerParameter

    data class Unknown(override val parameter: KSValueParameter) : ConsumerParameter

    companion object {
        context(r: Resolver)
        fun parseParameters(function: KSFunctionDeclaration) = function.parameters.map {
            val type = it.type.resolve()
            fun KSTypeArgument.resolveGenericSafe(): KSType {
                if (this.variance == Variance.STAR) {
                    require(this.type == null)
                    ByteArray::class
                    return r.getClassDeclarationByName<ByteArray>()!!.asType(listOf())
                }
                return this.type!!.resolve()
            }
            when {
                type.isConsumerRecord() -> Record(it, type.arguments[0].resolveGenericSafe(), type.arguments[1].type!!.resolve())
                type.isConsumerRecords() -> Records(it, type.arguments[0].resolveGenericSafe(), type.arguments[1].resolveGenericSafe())
                type.isConsumer() -> Consumer(it, type.arguments[0].resolveGenericSafe(), type.arguments[1].resolveGenericSafe())
                type.isRecordsTelemetry() -> RecordsTelemetry(it, type.arguments[0].resolveGenericSafe(), type.arguments[1].resolveGenericSafe())
                type.isKeyDeserializationException() -> KeyDeserializationException(it)
                type.isValueDeserializationException() -> ValueDeserializationException(it)
                type.isAnyException() -> Exception(it)
                else -> Unknown(it)
            }
        }
    }
}
