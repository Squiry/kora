package ru.tinkoff.kora.http.server.common.telemetry;

import ru.tinkoff.kora.http.common.HttpResultCode;
import ru.tinkoff.kora.http.common.header.HttpHeaders;
import ru.tinkoff.kora.telemetry.common.Observation;

public interface HttpServerObservation extends Observation {
    HttpServerObservation withCode(int code);

    HttpServerObservation withHeaders(HttpHeaders headers);

    HttpServerObservation withResultCode(HttpResultCode resultCode);

    HttpServerObservation withError(Throwable exception);
}
