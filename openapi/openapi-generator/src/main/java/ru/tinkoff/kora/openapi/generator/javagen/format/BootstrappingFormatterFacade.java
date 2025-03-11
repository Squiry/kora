package ru.tinkoff.kora.openapi.generator.javagen.format;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.palantir.goethe.Goethe;
import com.palantir.goethe.GoetheException;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

public final class BootstrappingFormatterFacade implements FormatterFacade {

    static final ImmutableList<String> REQUIRED_EXPORTS = ImmutableList.of(
        "jdk.compiler/com.sun.tools.javac.api",
        "jdk.compiler/com.sun.tools.javac.file",
        "jdk.compiler/com.sun.tools.javac.parser",
        "jdk.compiler/com.sun.tools.javac.tree",
        "jdk.compiler/com.sun.tools.javac.util");

    static final ImmutableList<String> EXPORTS = REQUIRED_EXPORTS.stream()
        .map(value -> String.format("--add-exports=%s=ALL-UNNAMED", value))
        .collect(ImmutableList.toImmutableList());

    @Override
    public String formatSource(String className, String unformattedSource) throws GoetheException {
        try {
            Process process = new ProcessBuilder(ImmutableList.<String>builder()
                .add(new File(System.getProperty("java.home"), "bin/java").getAbsolutePath())
                .addAll(EXPORTS)
                .add( // Classpath
                    "-cp",
                    getClasspath(),
                    // Main class
                    "com.palantir.goethe.GoetheMain",
                    // Args
                    className)
                .build())
                .start();
            try (OutputStream outputStream = process.getOutputStream()) {
                outputStream.write(unformattedSource.getBytes(StandardCharsets.UTF_8));
            }
            byte[] data;
            try (InputStream inputStream = process.getInputStream()) {
                data = ByteStreams.toByteArray(inputStream);
            }
            int exitStatus = process.waitFor();
            if (exitStatus != 0) {
                throw new RuntimeException(String.format(
                    "Formatter exited non-zero (%d) formatting class %s:\n%s",
                    exitStatus, className, getErrorOutput(process)));
            }
            return new String(data, StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to bootstrap jdk", e);
        }
    }

    private static String getErrorOutput(Process process) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream inputStream = process.getErrorStream()) {
            inputStream.transferTo(baos);
        } catch (IOException | RuntimeException e) {
            String diagnostic = "<failed to read process stream: " + e + ">";
            try {
                baos.write(diagnostic.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // should not happen
            }
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static String getClasspath() {
        return getPath(Goethe.class);
    }

    private static String getPath(Class<?> clazz) {
        try {
            return new File(clazz.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI())
                .getAbsolutePath();
        } catch (URISyntaxException e) {
            throw new RuntimeException("Failed to locate the jar providing " + clazz, e);
        }
    }
}
