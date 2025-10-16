package ru.tinkoff.kora.http.server.common.telemetry.impl;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.semconv.HttpAttributes;
import ru.tinkoff.kora.http.common.HttpResultCode;
import ru.tinkoff.kora.http.common.header.HttpHeaders;
import ru.tinkoff.kora.http.server.common.HttpServerRequest;
import ru.tinkoff.kora.http.server.common.HttpServerResponse;
import ru.tinkoff.kora.http.server.common.router.PublicApiRequest;
import ru.tinkoff.kora.http.server.common.telemetry.HttpServerObservation;
import ru.tinkoff.kora.http.server.common.telemetry.HttpServerTelemetryConfig;

import java.util.Objects;

public class DefaultHttpServerObservation implements HttpServerObservation {
    private int statusCode = 0;
    private HttpResultCode resultCode;
    private HttpHeaders httpHeaders;
    private Throwable exception;
    private final HttpServerTelemetryConfig config;
    private final PublicApiRequest publicApiRequest;
    private final HttpServerRequest request;
    private final Span span;
    private final DefaultHttpServerLogger logger;
    private final DefaultHttpServerMetrics metrics;

    public DefaultHttpServerObservation(HttpServerTelemetryConfig config, PublicApiRequest publicApiRequest, HttpServerRequest request, Span span, DefaultHttpServerLogger logger, DefaultHttpServerMetrics metrics) {
        this.config = config;
        this.publicApiRequest = publicApiRequest;
        this.request = request;
        this.span = span;
        this.logger = logger;
        this.metrics = metrics;
    }

    @Override
    public void recordResultCode(HttpResultCode resultCode) {
        this.resultCode = resultCode;
    }

    @Override
    public void recordException(Throwable exception) {
        this.exception = exception;
        this.span.recordException(exception);
        this.span.setStatus(StatusCode.ERROR);
    }

    @Override
    public HttpServerRequest observeRequest(HttpServerRequest rq) {
        var logger = this.logger;
        if (this.config.metrics().enabled()) {
            this.metrics.requestStarted(publicApiRequest, request);
        }
        if (request.route() != null && this.config.logging().enabled()) {
            logger.logStart(request);
        }
        return rq;
    }

    @Override
    public HttpServerResponse observeResponse(HttpServerResponse rs) {
        this.httpHeaders = rs.headers();
        if (this.statusCode == 0) {
            this.resultCode = HttpResultCode.fromStatusCode(rs.code());
        }
        this.statusCode = rs.code();
        return rs;
    }

    @Override
    public Span span() {
        return this.span;
    }

    @Override
    public void end() {
        var end = System.nanoTime();
        var processingTime = end - publicApiRequest.requestStartTime();
        if (this.metrics != null) {
            this.metrics.requestFinished(statusCode, publicApiRequest, request, processingTime, exception);
        }
        var resultCode = Objects.requireNonNullElse(this.resultCode, HttpResultCode.SERVER_ERROR);
        if (request.route() != null) {
            if (this.logger != null) {
                this.logger.logEnd(request, statusCode, resultCode, processingTime, httpHeaders, exception);
            }
            span.setAttribute("http.response.result_code", resultCode.string());
            if (statusCode >= 500 || resultCode == HttpResultCode.CONNECTION_ERROR) {
                span.setStatus(StatusCode.ERROR);
            } else if (exception == null) {
                span.setStatus(StatusCode.OK);
            }
            if (statusCode != 0) {
                span.setAttribute(HttpAttributes.HTTP_RESPONSE_STATUS_CODE, statusCode);
            }
            span.end();
        }
    }
}
