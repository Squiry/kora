package ru.tinkoff.kora.http.server.common.telemetry.impl;

import io.micrometer.core.instrument.Tag;
import jakarta.annotation.Nullable;

public interface HttpServerMetricsTagsProvider {
    record ActiveRequestsKey(String method, String target, String host, String scheme) {}

    Iterable<Tag> getActiveRequestsTags(ActiveRequestsKey key);

    record DurationKey(int statusCode, String method, String route, String host, String scheme, @Nullable Class<? extends Throwable> errorType) {}

    Iterable<Tag> getDurationTags(DurationKey key);

}
