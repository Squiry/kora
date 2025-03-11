package ru.tinkoff.kora.openapi.generator.javagen;

import com.palantir.javapoet.ClassName;

public class JavaClassNames {
    public static final ClassName JSON_ANNOTATION = ClassName.get("ru.tinkoff.kora.json.common.annotation", "Json");
    public static final ClassName JSON_WRITER_ANNOTATION = ClassName.get("ru.tinkoff.kora.json.common.annotation", "JsonWriter");
    public static final ClassName JSON_READER_ANNOTATION = ClassName.get("ru.tinkoff.kora.json.common.annotation", "JsonReader");
    public static final ClassName JSON_FIELD_ANNOTATION = ClassName.get("ru.tinkoff.kora.json.common.annotation", "JsonField");
    public static final ClassName JSON_INCLUDE_ANNOTATION = ClassName.get("ru.tinkoff.kora.json.common.annotation", "JsonInclude");
    public static final ClassName JSON_DISCRIMINATOR_FIELD_ANNOTATION = ClassName.get("ru.tinkoff.kora.json.common.annotation", "JsonDiscriminatorField");
    public static final ClassName JSON_DISCRIMINATOR_VALUE_ANNOTATION = ClassName.get("ru.tinkoff.kora.json.common.annotation", "JsonDiscriminatorValue");
    public static final ClassName JSON_NULLABLE = ClassName.get("ru.tinkoff.kora.json.common", "JsonNullable");
    public static final ClassName VALID_ANNOTATION = ClassName.get("ru.tinkoff.kora.validation.common.annotation", "Valid");
    public static final ClassName RANGE_ANNOTATION = ClassName.get("ru.tinkoff.kora.validation.common.annotation", "Range");

    public static final ClassName JSON_WRITER = ClassName.get("ru.tinkoff.kora.json.common", "JsonWriter");
    public static final ClassName JSON_READER = ClassName.get("ru.tinkoff.kora.json.common", "JsonReader");
    public static final ClassName JSON_GENERATOR = ClassName.get("com.fasterxml.jackson.core", "JsonGenerator");
    public static final ClassName JSON_PARSER = ClassName.get("com.fasterxml.jackson.core", "JsonParser");
}
