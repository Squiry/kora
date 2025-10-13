package ru.tinkoff.kora.http.server.common.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.ServerAttributes;
import io.opentelemetry.semconv.UrlAttributes;
import ru.tinkoff.kora.http.server.common.HttpServerRequest;
import ru.tinkoff.kora.http.server.common.router.PublicApiRequest;

import java.util.concurrent.TimeUnit;

public final class DefaultHttpServerTelemetry implements HttpServerTelemetry {

    private final Tracer tracer;
    private final DefaultHttpServerMetrics metrics;
    private final DefaultHttpServerLogger logger;

    public DefaultHttpServerTelemetry(HttpServerTelemetryConfig config, MeterRegistry meterRegistry, Tracer tracer) {
        if (config.metrics().enabled() == Boolean.TRUE) {
            this.metrics = new DefaultHttpServerMetrics(meterRegistry, null, config.metrics());
        } else {
            this.metrics = null;
        }
        if (config.logging().enabled() == Boolean.TRUE) {
            this.logger = new DefaultHttpServerLogger(config.logging());
        } else {
            this.logger = null;
        }
        if (config.tracing().enabled() == Boolean.TRUE) {
            this.tracer = tracer;
        } else {
            this.tracer = null;
        }
    }

    @Override
    public HttpServerObservation observe(PublicApiRequest publicApiRequest, HttpServerRequest request) {
        var metrics = this.metrics;
        var logger = this.logger;
        var method = request.method();
        if (metrics != null) {
            metrics.requestStarted(publicApiRequest, request);
        }

        var span = request.route() == null || this.tracer == null
            ? Span.getInvalid()
            : this.createSpan(request.route(), publicApiRequest);
        if (request.route() != null) {
            if (logger != null) {
                logger.logStart(method, request.path(), request.route(), request.queryParams(), request.headers());
            }
        }
        return new DefaultHttpServerObservation(publicApiRequest, request, span, logger, metrics);
    }

    public Span createSpan(String template, PublicApiRequest routerRequest) {
        return this.tracer
            .spanBuilder(routerRequest.method() + " " + template)
            .setSpanKind(SpanKind.SERVER)
            .setAttribute(HttpAttributes.HTTP_REQUEST_METHOD, routerRequest.method())
            .setAttribute(UrlAttributes.URL_SCHEME, routerRequest.scheme())
            .setAttribute(ServerAttributes.SERVER_ADDRESS, routerRequest.hostName())
            .setAttribute(UrlAttributes.URL_PATH, routerRequest.path())
            .setAttribute(HttpAttributes.HTTP_ROUTE, template)
            .setStartTimestamp(routerRequest.requestStartTime(), TimeUnit.NANOSECONDS)
            .startSpan();
    }

}
