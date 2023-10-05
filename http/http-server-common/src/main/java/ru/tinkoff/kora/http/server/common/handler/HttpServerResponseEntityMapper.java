package ru.tinkoff.kora.http.server.common.handler;

import ru.tinkoff.kora.common.Context;
import ru.tinkoff.kora.http.common.header.HttpHeaders;
import ru.tinkoff.kora.http.server.common.HttpServerRequest;
import ru.tinkoff.kora.http.server.common.HttpServerResponse;
import ru.tinkoff.kora.http.server.common.HttpServerResponseEntity;

import java.io.IOException;

public class HttpServerResponseEntityMapper<T> implements HttpServerResponseMapper<HttpServerResponseEntity<T>> {
    private final HttpServerResponseMapper<T> delegate;

    public HttpServerResponseEntityMapper(HttpServerResponseMapper<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public HttpServerResponse apply(Context ctx, HttpServerRequest request, HttpServerResponseEntity<T> result) throws IOException {
        var response = this.delegate.apply(ctx, request, result.body());

        if (result.headers().isEmpty()) {
            return HttpServerResponse.of(result.code(), response.headers(), response.body());
        } else if (response.headers().isEmpty()) {
            return HttpServerResponse.of(result.code(), result.headers(), response.body());
        }
        var headers = HttpHeaders.of();
        for (var header : response.headers()) {
            headers.set(header.getKey(), header.getValue());
        }
        for (var header : result.headers()) {
            headers.add(header.getKey(), header.getValue());
        }

        return HttpServerResponse.of(
            result.code(),
            headers,
            response.body()
        );

    }
}
