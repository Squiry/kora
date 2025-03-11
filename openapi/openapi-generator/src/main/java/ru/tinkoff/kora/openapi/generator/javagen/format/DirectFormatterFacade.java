package ru.tinkoff.kora.openapi.generator.javagen.format;

import com.palantir.goethe.GoetheException;
import shadow.com.palantir.goethe.goethe.com.google.common.base.CharMatcher;
import shadow.com.palantir.goethe.goethe.com.google.common.base.Splitter;
import shadow.com.palantir.goethe.goethe.com.palantir.javaformat.java.Formatter;
import shadow.com.palantir.goethe.goethe.com.palantir.javaformat.java.FormatterDiagnostic;
import shadow.com.palantir.goethe.goethe.com.palantir.javaformat.java.FormatterException;
import shadow.com.palantir.goethe.goethe.com.palantir.javaformat.java.JavaFormatterOptions;
import shadow.com.palantir.goethe.goethe.com.palantir.javaformat.java.JavaFormatterOptions.Style;

import java.util.List;

public class DirectFormatterFacade implements FormatterFacade {

    private final Formatter formatter;

    public DirectFormatterFacade() {
        this.formatter = Formatter.createFormatter(JavaFormatterOptions.builder().style(Style.PALANTIR).formatJavadoc(true).build());
    }

    public String formatSource(String className, String unformattedSource) throws GoetheException {
        try {
            return this.formatter.formatSource(unformattedSource);
        } catch (FormatterException e) {
            throw new RuntimeException(generateMessage(className, unformattedSource, e.diagnostics()), e);
        }
    }

    private static String generateMessage(String className, String unformattedSource, List<FormatterDiagnostic> formatterDiagnostics) {
        try {
            var lines = Splitter.on('\n').splitToList(unformattedSource);
            var failureText = new StringBuilder();
            failureText.append("Failed to format '").append(className).append("'\n");

            for (var formatterDiagnostic : formatterDiagnostics) {
                failureText.append(formatterDiagnostic.message()).append("\n").append((String) lines.get(formatterDiagnostic.line() - 1)).append('\n').append(" ".repeat(Math.max(0, formatterDiagnostic.column() - 2))).append("^\n\n");
            }

            return CharMatcher.is('\n').trimFrom(failureText.toString());
        } catch (RuntimeException var7) {
            return "Failed to format:\n" + unformattedSource;
        }
    }
}
