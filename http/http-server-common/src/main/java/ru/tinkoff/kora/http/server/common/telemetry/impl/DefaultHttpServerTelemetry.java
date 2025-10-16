package ru.tinkoff.kora.http.server.common.telemetry.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.ServerAttributes;
import io.opentelemetry.semconv.UrlAttributes;
import ru.tinkoff.kora.http.server.common.HttpServerRequest;
import ru.tinkoff.kora.http.server.common.router.PublicApiRequest;
import ru.tinkoff.kora.http.server.common.telemetry.HttpServerObservation;
import ru.tinkoff.kora.http.server.common.telemetry.HttpServerTelemetry;
import ru.tinkoff.kora.http.server.common.telemetry.HttpServerTelemetryConfig;

public final class DefaultHttpServerTelemetry implements HttpServerTelemetry {
    private final HttpServerTelemetryConfig config;
    private final Tracer tracer;
    private final DefaultHttpServerMetrics metrics;
    private final DefaultHttpServerLogger logger;

    public DefaultHttpServerTelemetry(HttpServerTelemetryConfig config, Tracer tracer, DefaultHttpServerMetrics metrics, DefaultHttpServerLogger logger) {
        this.config = config;
        this.tracer = tracer;
        this.metrics = metrics;
        this.logger = logger;
    }

    public DefaultHttpServerTelemetry(HttpServerTelemetryConfig config, MeterRegistry meterRegistry, Tracer tracer, HttpServerMetricsTagsProvider tagProvider) {
        var metrics = new DefaultHttpServerMetrics(meterRegistry, tagProvider, config.metrics());
        var logger = new DefaultHttpServerLogger(config.logging());
        this(config, tracer, metrics, logger);
    }

    @Override
    public HttpServerObservation observe(PublicApiRequest publicApiRequest, HttpServerRequest request) {
        var span = this.createSpan(request.route(), publicApiRequest);
        return new DefaultHttpServerObservation(config, publicApiRequest, request, span, logger, this.metrics);
    }

    private Span createSpan(String template, PublicApiRequest routerRequest) {
        if (template == null || !this.config.tracing().enabled()) {
            return Span.getInvalid();
        }
        var span = this.tracer
            .spanBuilder(routerRequest.method() + " " + template)
            .setSpanKind(SpanKind.SERVER)
            .setAttribute(HttpAttributes.HTTP_REQUEST_METHOD, routerRequest.method())
            .setAttribute(UrlAttributes.URL_SCHEME, routerRequest.scheme())
            .setAttribute(ServerAttributes.SERVER_ADDRESS, routerRequest.hostName())
            .setAttribute(UrlAttributes.URL_PATH, routerRequest.path())
            .setAttribute(HttpAttributes.HTTP_ROUTE, template);
        for (var attribute : config.tracing().attributes().entrySet()) {
            span.setAttribute(attribute.getKey(), attribute.getValue());
        }
        return span.startSpan();
    }

}
