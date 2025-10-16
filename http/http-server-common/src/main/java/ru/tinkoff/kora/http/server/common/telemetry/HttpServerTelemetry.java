package ru.tinkoff.kora.http.server.common.telemetry;

import ru.tinkoff.kora.http.server.common.HttpServerRequest;
import ru.tinkoff.kora.http.server.common.router.PublicApiRequest;
import ru.tinkoff.kora.http.server.common.telemetry.impl.NoopHttpServerObservation;

public interface HttpServerTelemetry {
    HttpServerTelemetry NOOP = (_, _) -> NoopHttpServerObservation.INSTANCE;

    HttpServerObservation observe(PublicApiRequest publicApiRequest, HttpServerRequest request);
}
