package ru.tinkoff.kora.openapi.generator.kotlingen

import com.squareup.kotlinpoet.ClassName
import ru.tinkoff.kora.openapi.generator.javagen.JavaClassNames

object KotlinClassNames {
    val jsonAnnotation = JavaClassNames.JSON_ANNOTATION.asKt()
    val jsonWriterAnnotation = JavaClassNames.JSON_WRITER_ANNOTATION.asKt()
    val jsonReaderAnnotation = JavaClassNames.JSON_READER_ANNOTATION.asKt()
    val jsonFieldAnnotation = JavaClassNames.JSON_FIELD_ANNOTATION.asKt()
    val jsonIncludeAnnotation = JavaClassNames.JSON_INCLUDE_ANNOTATION.asKt()
    val jsonDiscriminatorFieldAnnotation = JavaClassNames.JSON_DISCRIMINATOR_FIELD_ANNOTATION.asKt()
    val jsonDiscriminatorValueAnnotation = JavaClassNames.JSON_DISCRIMINATOR_VALUE_ANNOTATION.asKt()

    val validAnnotation = JavaClassNames.VALID_ANNOTATION.asKt()
    val rangeAnnotation = JavaClassNames.RANGE_ANNOTATION.asKt()
    val sizeAnnotation = JavaClassNames.SIZE_ANNOTATION.asKt()
    val patternAnnotation = JavaClassNames.PATTERN_ANNOTATION.asKt()

    val jsonNullable = JavaClassNames.JSON_NULLABLE.asKt()
    val jsonWriter = JavaClassNames.JSON_WRITER.asKt()
    val jsonReader = JavaClassNames.JSON_READER.asKt()
    val jsonGenerator = JavaClassNames.JSON_GENERATOR.asKt()
    val jsonParser = JavaClassNames.JSON_PARSER.asKt()

    val enumJsonWriter = JavaClassNames.ENUM_JSON_WRITER.asKt()
    val enumJsonReader = JavaClassNames.ENUM_JSON_READER.asKt()

    val stringParameterReader = JavaClassNames.STRING_PARAMETER_READER.asKt()
    val enumStringParameterReader = JavaClassNames.ENUM_STRING_PARAMETER_READER.asKt()
    val stringParameterConverter = JavaClassNames.STRING_PARAMETER_CONVERTER.asKt()
    val enumStringParameterConverter = JavaClassNames.ENUM_STRING_PARAMETER_CONVERTER.asKt()

    fun com.palantir.javapoet.ClassName.asKt() = ClassName(packageName(), simpleNames())
}
