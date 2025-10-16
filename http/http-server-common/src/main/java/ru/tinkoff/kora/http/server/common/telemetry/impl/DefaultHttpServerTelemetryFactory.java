package ru.tinkoff.kora.http.server.common.telemetry.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.Nullable;
import ru.tinkoff.kora.http.server.common.telemetry.HttpServerTelemetry;
import ru.tinkoff.kora.http.server.common.telemetry.HttpServerTelemetryConfig;
import ru.tinkoff.kora.http.server.common.telemetry.HttpServerTelemetryFactory;

public final class DefaultHttpServerTelemetryFactory implements HttpServerTelemetryFactory {
    private final MeterRegistry meterRegistry;
    @Nullable
    private final HttpServerMetricsTagsProvider tagProvider;
    private final Tracer tracer;

    public DefaultHttpServerTelemetryFactory(MeterRegistry meterRegistry, @Nullable HttpServerMetricsTagsProvider tagProvider, @Nullable Tracer tracer) {
        this.meterRegistry = meterRegistry;
        this.tagProvider = tagProvider;
        this.tracer = tracer;
    }

    @Override
    public HttpServerTelemetry get(HttpServerTelemetryConfig config) {
        if (!config.logging().enabled() && !config.metrics().enabled() && !config.tracing().enabled()) {
            return HttpServerTelemetry.NOOP;
        }

        return new DefaultHttpServerTelemetry(config, meterRegistry, tracer, tagProvider);
    }
}
