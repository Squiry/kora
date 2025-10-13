package ru.tinkoff.kora.http.server.common.telemetry;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.semconv.HttpAttributes;
import jakarta.annotation.Nullable;
import ru.tinkoff.kora.http.common.HttpResultCode;
import ru.tinkoff.kora.http.common.header.HttpHeaders;
import ru.tinkoff.kora.http.server.common.HttpServerRequest;
import ru.tinkoff.kora.http.server.common.router.PublicApiRequest;

import java.util.concurrent.TimeUnit;

public class DefaultHttpServerObservation implements HttpServerObservation {
    private int statusCode = 0;
    private HttpResultCode resultCode;
    private HttpHeaders httpHeaders;
    private Throwable exception;
    private final PublicApiRequest publicApiRequest;
    private final HttpServerRequest request;
    private final Span span;
    @Nullable
    private final DefaultHttpServerLogger logger;
    @Nullable
    private final DefaultHttpServerMetrics metrics;

    public DefaultHttpServerObservation(PublicApiRequest publicApiRequest, HttpServerRequest request, Span span, DefaultHttpServerLogger logger, @Nullable DefaultHttpServerMetrics metrics) {
        this.publicApiRequest = publicApiRequest;
        this.request = request;
        this.span = span;
        this.logger = logger;
        this.metrics = metrics;
    }

    @Override
    public HttpServerObservation withCode(int code) {
        if (code == 0) {
            this.resultCode = HttpResultCode.fromStatusCode(code);
        }
        this.statusCode = code;
        return this;
    }

    @Override
    public HttpServerObservation withHeaders(HttpHeaders headers) {
        this.httpHeaders = headers;
        return this;
    }

    @Override
    public HttpServerObservation withResultCode(HttpResultCode resultCode) {
        this.resultCode = resultCode;
        return this;
    }

    @Override
    public HttpServerObservation withError(Throwable exception) {
        this.exception = exception;
        this.span.recordException(exception);
        this.span.setStatus(StatusCode.ERROR);
        return this;
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
            var pathTemplate = request.route() != null ? request.route() : DefaultHttpServerMetrics.UNMATCHED_ROUTE_TEMPLATE;
            this.metrics.requestFinished(statusCode, publicApiRequest, pathTemplate, processingTime, exception);
        }

        if (request.route() != null) {
            if (this.logger != null) {
                this.logger.logEnd(statusCode, resultCode, request.method(), request.path(), request.route(), processingTime, request.queryParams(), httpHeaders, exception);
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
            span.end(end, TimeUnit.NANOSECONDS);
        }
    }
}
