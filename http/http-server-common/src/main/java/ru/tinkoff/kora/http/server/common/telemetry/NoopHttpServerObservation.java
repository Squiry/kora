package ru.tinkoff.kora.http.server.common.telemetry;

import io.opentelemetry.api.trace.Span;
import ru.tinkoff.kora.http.common.HttpResultCode;
import ru.tinkoff.kora.http.common.header.HttpHeaders;

final class NoopHttpServerObservation implements HttpServerObservation {
    static final HttpServerObservation INSTANCE = new NoopHttpServerObservation();

    @Override
    public HttpServerObservation withCode(int code) {
        return this;
    }

    @Override
    public HttpServerObservation withHeaders(HttpHeaders headers) {
        return this;
    }

    @Override
    public HttpServerObservation withResultCode(HttpResultCode resultCode) {
        return this;
    }

    @Override
    public HttpServerObservation withError(Throwable exception) {
        return this;
    }

    @Override
    public Span span() {
        return Span.getInvalid();
    }

    @Override
    public void end() {

    }
}
