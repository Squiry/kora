package ru.tinkoff.kora.http.client.common.telemetry;

import ru.tinkoff.kora.http.client.common.request.HttpClientRequest;
import ru.tinkoff.kora.http.client.common.response.HttpClientResponse;
import ru.tinkoff.kora.telemetry.common.Observation;

public interface HttpClientObservation extends Observation {
    void recordException(Throwable exception);

    HttpClientRequest observeRequest(HttpClientRequest rq);

    HttpClientResponse observeResponse(HttpClientResponse rs);

}
