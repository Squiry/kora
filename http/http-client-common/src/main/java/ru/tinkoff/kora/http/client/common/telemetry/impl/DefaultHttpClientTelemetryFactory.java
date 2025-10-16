package ru.tinkoff.kora.http.client.common.telemetry.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.opentelemetry.api.trace.Tracer;
import jakarta.annotation.Nullable;
import org.slf4j.LoggerFactory;
import ru.tinkoff.kora.http.client.common.telemetry.HttpClientTelemetry;
import ru.tinkoff.kora.http.client.common.telemetry.HttpClientTelemetryConfig;
import ru.tinkoff.kora.http.client.common.telemetry.HttpClientTelemetryFactory;

import java.util.Objects;

public final class DefaultHttpClientTelemetryFactory implements HttpClientTelemetryFactory {

    private final HttpClientTelemetryConfig config;
    @Nullable
    private final Tracer tracer;
    private final MeterRegistry meterRegistry;

    public DefaultHttpClientTelemetryFactory(HttpClientTelemetryConfig config, @Nullable Tracer tracer, @Nullable MeterRegistry meterRegistry) {
        this.config = config;
        this.tracer = tracer;
        this.meterRegistry = Objects.requireNonNullElse(meterRegistry, Metrics.globalRegistry);
    }

    @Override
    public HttpClientTelemetry get(HttpClientTelemetryConfig config, String clientName) {
        if (!config.metrics().enabled() && !config.tracing().enabled() && !config.logging().enabled()) {
            return HttpClientTelemetry.NOOP;
        }
        var requestLog = LoggerFactory.getLogger(clientName + ".request");
        var responseLog = LoggerFactory.getLogger(clientName + ".response");

        return new DefaultHttpClientTelemetry(
            this.config, tracer, new DefaultHttpClientLogger(requestLog, responseLog, this.config.logging()), new DefaultHttpClientMetrics(this.meterRegistry, config.metrics())
        );
    }
}
