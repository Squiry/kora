package ru.tinkoff.kora.http.server.common.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.Nullable;

import java.util.Objects;

public final class DefaultHttpServerTelemetryFactory implements HttpServerTelemetryFactory {
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    public DefaultHttpServerTelemetryFactory(MeterRegistry meterRegistry, @Nullable Tracer tracer) {
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
    }

    @Override
    public HttpServerTelemetry get(HttpServerTelemetryConfig config) {
        var logging = Objects.requireNonNullElse(config.logging().enabled(), false);
        var metrics = Objects.requireNonNullElse(config.metrics().enabled(), true);
        var tracing = Objects.requireNonNullElse(config.tracing().enabled(), true);
        if (!logging && !metrics && !tracing) {
            return HttpServerTelemetry.NOOP;
        }

        return new DefaultHttpServerTelemetry(config, meterRegistry, tracer);
    }
}
