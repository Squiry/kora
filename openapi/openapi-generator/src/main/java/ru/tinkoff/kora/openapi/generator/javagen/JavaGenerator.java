package ru.tinkoff.kora.openapi.generator.javagen;

import com.palantir.javapoet.JavaFile;

public interface JavaGenerator<T> {
    JavaFile generate(T ctx);
}
