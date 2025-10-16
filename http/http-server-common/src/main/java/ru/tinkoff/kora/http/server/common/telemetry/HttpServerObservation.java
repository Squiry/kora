package ru.tinkoff.kora.http.server.common.telemetry;

import ru.tinkoff.kora.http.common.HttpResultCode;
import ru.tinkoff.kora.http.server.common.HttpServerRequest;
import ru.tinkoff.kora.http.server.common.HttpServerResponse;
import ru.tinkoff.kora.telemetry.common.Observation;

public interface HttpServerObservation extends Observation {
    void recordResultCode(HttpResultCode resultCode);

    void recordException(Throwable exception);

    HttpServerRequest observeRequest(HttpServerRequest rq);

    HttpServerResponse observeResponse(HttpServerResponse rs);
}
