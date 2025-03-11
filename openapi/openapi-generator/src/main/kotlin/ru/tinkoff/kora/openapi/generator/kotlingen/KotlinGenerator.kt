package ru.tinkoff.kora.openapi.generator.kotlingen

import com.squareup.kotlinpoet.FileSpec

interface KotlinGenerator<T> {
    fun generate(ctx: T): FileSpec
}
