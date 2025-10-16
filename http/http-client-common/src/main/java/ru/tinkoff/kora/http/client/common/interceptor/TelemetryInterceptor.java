package ru.tinkoff.kora.http.client.common.interceptor;

import ru.tinkoff.kora.common.Context;
import ru.tinkoff.kora.http.client.common.request.HttpClientRequest;
import ru.tinkoff.kora.http.client.common.response.HttpClientResponse;
import ru.tinkoff.kora.http.client.common.telemetry.HttpClientTelemetry;
import ru.tinkoff.kora.opentelemetry.common.OpentelemetryContext;
import ru.tinkoff.kora.telemetry.common.Observation;

public class TelemetryInterceptor implements HttpClientInterceptor {

    private final HttpClientTelemetry telemetry;

    public TelemetryInterceptor(HttpClientTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    public HttpClientResponse processRequest(Context ctx, InterceptChain chain, HttpClientRequest request) throws Exception {
        var observation = this.telemetry.observe(request);
        return ScopedValue.where(OpentelemetryContext.VALUE, io.opentelemetry.context.Context.current().with(observation.span()))
            .where(Observation.VALUE, observation)
            .call(() -> {
                try {
                    var observedRequest = observation.observeRequest(request);
                    var rs = chain.process(ctx, observedRequest);
                    return observation.observeResponse(rs);
                } catch (Throwable t) {
                    observation.recordException(t);
                    throw t;
                } finally {
                    observation.end();
                }
            });
    }
}
