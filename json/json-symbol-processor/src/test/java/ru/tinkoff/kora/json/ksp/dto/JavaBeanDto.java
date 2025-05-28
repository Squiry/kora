package ru.tinkoff.kora.json.ksp.dto;

import ru.tinkoff.kora.json.common.annotation.Json;

@Json
public class JavaBeanDto {
    private java.lang.String text;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public JavaBeanDto() {}
}
