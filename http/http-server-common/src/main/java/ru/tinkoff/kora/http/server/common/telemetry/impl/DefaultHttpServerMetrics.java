package ru.tinkoff.kora.http.server.common.telemetry.impl;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.semconv.ErrorAttributes;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.ServerAttributes;
import io.opentelemetry.semconv.UrlAttributes;
import jakarta.annotation.Nullable;
import ru.tinkoff.kora.http.server.common.HttpServerRequest;
import ru.tinkoff.kora.http.server.common.router.PublicApiRequest;
import ru.tinkoff.kora.http.server.common.telemetry.impl.HttpServerMetricsTagsProvider.ActiveRequestsKey;
import ru.tinkoff.kora.http.server.common.telemetry.impl.HttpServerMetricsTagsProvider.DurationKey;
import ru.tinkoff.kora.telemetry.common.TelemetryConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DefaultHttpServerMetrics {
    static final String UNMATCHED_ROUTE_TEMPLATE = "UNKNOWN_ROUTE";
    private final MeterRegistry meterRegistry;
    @Nullable
    private final HttpServerMetricsTagsProvider tagsProvider;
    private final ConcurrentHashMap<ActiveRequestsKey, AtomicInteger> requestCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<DurationKey, Timer> duration = new ConcurrentHashMap<>();
    private final TelemetryConfig.MetricsConfig config;

    public DefaultHttpServerMetrics(MeterRegistry meterRegistry, @Nullable HttpServerMetricsTagsProvider tagProvider, TelemetryConfig.MetricsConfig config) {
        this.meterRegistry = meterRegistry;
        this.tagsProvider = tagProvider;
        this.config = config;
    }

    public void requestStarted(PublicApiRequest publicApiRequest, HttpServerRequest request) {
        var method = request.method();
        var scheme = publicApiRequest.scheme();
        var host = publicApiRequest.hostName();
        var pathTemplate = request.route() != null ? request.route() : UNMATCHED_ROUTE_TEMPLATE;

        var counter = requestCounters.computeIfAbsent(new ActiveRequestsKey(method, pathTemplate, host, scheme), activeRequestsKey -> {
            var c = new AtomicInteger(0);
            this.registerActiveRequestsGauge(activeRequestsKey, c);
            return c;
        });
        counter.incrementAndGet();
    }

    public void requestFinished(int statusCode, PublicApiRequest publicApiRequest, HttpServerRequest request, long processingTimeNanos, Throwable exception) {
        var method = publicApiRequest.method();
        var scheme = publicApiRequest.scheme();
        var host = publicApiRequest.hostName();
        var pathTemplate = request.route() != null ? request.route() : UNMATCHED_ROUTE_TEMPLATE;
        var counter = requestCounters.computeIfAbsent(new ActiveRequestsKey(method, pathTemplate, host, scheme), activeRequestsKey -> {
            var c = new AtomicInteger(0);
            this.registerActiveRequestsGauge(activeRequestsKey, c);
            return c;
        });
        counter.decrementAndGet();
        var key = new DurationKey(statusCode, method, pathTemplate, host, scheme, exception == null ? null : exception.getClass());
        this.duration.computeIfAbsent(key, this::requestDuration)
            .record(processingTimeNanos, TimeUnit.NANOSECONDS);
    }

    private void registerActiveRequestsGauge(ActiveRequestsKey key, AtomicInteger counter) {
        var b = Gauge.builder("http.server.active_requests", counter, AtomicInteger::get)
            .tags(List.of(
                Tag.of(HttpAttributes.HTTP_ROUTE.getKey(), key.target()),
                Tag.of(HttpAttributes.HTTP_REQUEST_METHOD.getKey(), key.method()),
                Tag.of(ServerAttributes.SERVER_ADDRESS.getKey(), key.host()),
                Tag.of(UrlAttributes.URL_SCHEME.getKey(), key.scheme())
            ));
        if (tagsProvider != null) {
            b.tags(tagsProvider.getActiveRequestsTags(key));
        }
        b.register(this.meterRegistry);
    }

    private Timer requestDuration(DurationKey key) {
        var tags = new ArrayList<Tag>();
        if (key.errorType() != null) {
            tags.add(Tag.of(ErrorAttributes.ERROR_TYPE.getKey(), key.errorType().getCanonicalName()));
        } else {
            tags.add(Tag.of(ErrorAttributes.ERROR_TYPE.getKey(), ""));
        }
        tags.add(Tag.of(HttpAttributes.HTTP_REQUEST_METHOD.getKey(), key.method()));
        tags.add(Tag.of(HttpAttributes.HTTP_RESPONSE_STATUS_CODE.getKey(), Integer.toString(key.statusCode())));
        tags.add(Tag.of(HttpAttributes.HTTP_ROUTE.getKey(), key.route()));
        tags.add(Tag.of(UrlAttributes.URL_SCHEME.getKey(), key.scheme()));
        tags.add(Tag.of(ServerAttributes.SERVER_ADDRESS.getKey(), key.host()));
        var builder = Timer.builder("http.server.request.duration")
            .serviceLevelObjectives(this.config.slo())
            .tags(tags);
        if (tagsProvider != null) {
            builder.tags(this.tagsProvider.getDurationTags(key));
        }

        return builder.register(this.meterRegistry);
    }
}
