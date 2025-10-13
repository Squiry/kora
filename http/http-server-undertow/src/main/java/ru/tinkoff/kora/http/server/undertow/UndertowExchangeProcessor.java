package ru.tinkoff.kora.http.server.undertow;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.undertow.UndertowMessages;
import io.undertow.io.BufferWritableOutputStream;
import io.undertow.io.IoCallback;
import io.undertow.io.Sender;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import ru.tinkoff.kora.http.common.HttpResultCode;
import ru.tinkoff.kora.http.common.header.HttpHeaders;
import ru.tinkoff.kora.http.server.common.HttpServer;
import ru.tinkoff.kora.http.server.common.HttpServerResponse;
import ru.tinkoff.kora.http.server.common.router.PublicApiHandler;
import ru.tinkoff.kora.http.server.common.telemetry.HttpServerObservation;
import ru.tinkoff.kora.http.server.common.telemetry.HttpServerTelemetry;
import ru.tinkoff.kora.http.server.undertow.request.UndertowPublicApiRequest;
import ru.tinkoff.kora.opentelemetry.common.OpentelemetryContext;
import ru.tinkoff.kora.telemetry.common.Observation;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Objects;

public class UndertowExchangeProcessor implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(HttpServer.class);

    private final HttpServerTelemetry telemetry;
    private final UndertowContext context;
    private final PublicApiHandler publicApiHandler;

    public UndertowExchangeProcessor(HttpServerTelemetry telemetry, UndertowContext context, PublicApiHandler publicApiHandler) {
        this.telemetry = telemetry;
        this.context = context;
        this.publicApiHandler = publicApiHandler;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        var rootCtx = W3CTraceContextPropagator.getInstance().extract(Context.root(), exchange.getRequestHeaders(), HttpServerExchangeMapGetter.INSTANCE);
        ScopedValue
            .where(UndertowContext.VALUE, this.context)
            .where(ru.tinkoff.kora.logging.common.MDC.VALUE, new ru.tinkoff.kora.logging.common.MDC())
            .where(OpentelemetryContext.VALUE, rootCtx)
            .run(() -> {
                MDC.clear();
                try {
                    exchange.startBlocking();
                    var request = new UndertowPublicApiRequest(exchange);
                    var invocation = this.publicApiHandler.route(request);
                    var observation = this.telemetry.observe(request, invocation.request);
                    var ctx = rootCtx.with(observation.span());
                    W3CTraceContextPropagator.getInstance().inject(
                        ctx,
                        exchange.getResponseHeaders(),
                        HttpServerExchangeMapGetter.INSTANCE
                    );
                    exchange.addExchangeCompleteListener((e, nextListener) -> {
                        observation.end();
                        nextListener.proceed();
                    });
                    ScopedValue
                        .where(OpentelemetryContext.VALUE, ctx)
                        .where(Observation.VALUE, observation)
                        .run(() -> {
                            try {
                                var response = invocation.proceed();
                                this.sendResponse(observation, exchange, response);
                            } catch (Throwable e) {
                                this.sendException(observation, exchange, e);
                            }
                        });
                } catch (Throwable exception) {
                    log.warn("Error dropped", exception);
                    exchange.setStatusCode(500);
                    exchange.getResponseSender().send(StandardCharsets.UTF_8.encode(Objects.requireNonNullElse(exception.getMessage(), "no message")));
                } finally {
                    exchange.endExchange();
                }
            });

    }


    private void sendResponse(HttpServerObservation observation, HttpServerExchange exchange, HttpServerResponse httpResponse) {
        var headers = httpResponse.headers();
        exchange.setStatusCode(httpResponse.code());
        exchange.getResponseHeaders().put(Headers.SERVER, "kora/undertow");
        observation
            .withCode(httpResponse.code())
            .withHeaders(httpResponse.headers());
        var body = httpResponse.body();
        if (body == null) {
            this.setHeaders(exchange.getResponseHeaders(), headers, null);
            return;
        }
        try (body) {
            var contentType = body.contentType();
            this.setHeaders(exchange.getResponseHeaders(), headers, contentType);
            if (contentType != null) {
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
            }
            var contentLength = body.contentLength();
            if (contentLength >= 0) {
                exchange.setResponseContentLength(contentLength);
            }
            try (var os = exchange.getOutputStream()) {
                var full = body.getFullContentIfAvailable();
                if (full != null) {
                    this.writeBuffer(exchange, os, full);
                    return;
                }
                body.write(os);
            }
        } catch (Throwable e) {
            if (!exchange.isResponseStarted()) {
                observation.withCode(500)
                    .withResultCode(HttpResultCode.SERVER_ERROR)
                    .withError(e);
                exchange.setStatusCode(500);
                exchange.getResponseHeaders().remove(Headers.CONTENT_LENGTH);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                exchange.getResponseSender().send(Objects.requireNonNullElse(e.getMessage(), ""));
            } else {
                observation
                    .withResultCode(HttpResultCode.CONNECTION_ERROR)
                    .withError(e);
            }
        }
    }

    private void setHeaders(HeaderMap responseHeaders, HttpHeaders headers, @Nullable String contentType) {
        for (var header : headers) {
            var key = header.getKey();
            if (key.equals("server")) {
                continue;
            }
            if (key.equals("content-type") && contentType != null) {
                continue;
            }
            if (key.equals("content-length")) {
                continue;
            }
            if (key.equals("transfer-encoding")) {
                continue;
            }
            responseHeaders.addAll(HttpString.tryFromString(key), header.getValue());
        }
    }

    private void writeBuffer(HttpServerExchange exchange, OutputStream outputStream, ByteBuffer buffer) throws IOException {
        if (outputStream instanceof BufferWritableOutputStream bufferWritableOutputStream) {
            //fast path, if the stream can take a buffer directly just write to it
            bufferWritableOutputStream.write(buffer);
            return;
        }
        if (buffer.hasArray()) {
            outputStream.write(buffer.array(), buffer.arrayOffset(), buffer.remaining());
            return;
        }
        try (var pooled = exchange.getConnection().getByteBufferPool().getArrayBackedPool().allocate()) {
            if (pooled == null) {
                throw UndertowMessages.MESSAGES.failedToAllocateResource();
            }
            while (buffer.hasRemaining()) {
                var toRead = Math.min(buffer.remaining(), pooled.getBuffer().remaining());
                buffer.get(pooled.getBuffer().array(), pooled.getBuffer().arrayOffset(), toRead);
                outputStream.write(pooled.getBuffer().array(), pooled.getBuffer().arrayOffset(), toRead);
            }
        }
    }

    private void sendException(HttpServerObservation observation, HttpServerExchange exchange, Throwable error) {
        if (error instanceof HttpServerResponse rs) {
            observation.withError(error);
            this.sendResponse(observation, exchange, rs);
            return;
        }
        exchange.setStatusCode(500);
        observation
            .withError(error)
            .withCode(500);
        exchange.getResponseSender().send(Objects.requireNonNullElse(error.getMessage(), "Unknown error"), StandardCharsets.UTF_8, new IoCallback() {
            @Override
            public void onComplete(HttpServerExchange exchange, Sender sender) {
                observation.end();
                IoCallback.END_EXCHANGE.onComplete(exchange, sender);
            }

            @Override
            public void onException(HttpServerExchange exchange, Sender sender, IOException exception) {
                error.addSuppressed(exception);
                observation.withResultCode(HttpResultCode.CONNECTION_ERROR).end();
                IoCallback.END_EXCHANGE.onException(exchange, sender, exception);
            }
        });
    }

    private static class HttpServerExchangeMapGetter implements TextMapGetter<HeaderMap>, TextMapSetter<HeaderMap> {
        private static final HttpServerExchangeMapGetter INSTANCE = new HttpServerExchangeMapGetter();

        @Override
        public Iterable<String> keys(HeaderMap header) {
            return () -> new Iterator<>() {
                final Iterator<HeaderValues> i = header.iterator();

                @Override
                public boolean hasNext() {
                    return i.hasNext();
                }

                @Override
                public String next() {
                    return i.next().getHeaderName().toString();
                }
            };
        }

        @Override
        public String get(HeaderMap headers, String key) {
            return headers.getFirst(key);
        }

        @Override
        public void set(HeaderMap headers, String key, String value) {
            headers.add(HttpString.tryFromString(key), value);
        }
    }

}
