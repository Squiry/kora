package ru.tinkoff.kora.http.client.common.telemetry;

import ru.tinkoff.kora.http.client.common.request.HttpClientRequest;
import ru.tinkoff.kora.http.client.common.telemetry.impl.NoopObservation;

public interface HttpClientTelemetry {
    ScopedValue<HttpClientTelemetry> VALUE = ScopedValue.newInstance();
    HttpClientTelemetry NOOP = _ -> NoopObservation.INSTANCE;

    HttpClientObservation observe(HttpClientRequest request);
}
