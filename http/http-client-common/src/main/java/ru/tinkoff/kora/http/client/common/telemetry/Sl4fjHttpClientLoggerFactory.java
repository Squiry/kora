package ru.tinkoff.kora.http.client.common.telemetry;

import org.slf4j.LoggerFactory;
import ru.tinkoff.kora.http.client.common.telemetry.impl.DefaultHttpClientLogger;
import ru.tinkoff.kora.telemetry.common.TelemetryConfig;

import java.util.Objects;
import java.util.Set;

public class Sl4fjHttpClientLoggerFactory implements HttpClientLoggerFactory {

    private final HttpClientLoggerConfig loggerConfig;

    public Sl4fjHttpClientLoggerFactory(HttpClientTelemetryConfig config) {
        this.loggerConfig = config.logging();
    }

    @Override
    public HttpClientLogger get(TelemetryConfig.LoggingConfig logging, String clientName) {
        if (Objects.requireNonNullElse(logging.enabled(), false)) {
            var requestLog = LoggerFactory.getLogger(clientName + ".request");
            var responseLog = LoggerFactory.getLogger(clientName + ".response");
            if (logging instanceof HttpClientLoggerConfig config) {
                return new DefaultHttpClientLogger(requestLog, responseLog, config.maskQueries(), config.maskHeaders(), config.mask(), config.pathTemplate());
            } else {
                final Set<String> maskedQueryParams = loggerConfig.maskQueries();
                final Set<String> maskedHeaders = loggerConfig.maskHeaders();
                final String mask = loggerConfig.mask();
                final Boolean pathTemplate = loggerConfig.pathTemplate();
                return new DefaultHttpClientLogger(requestLog, responseLog, maskedQueryParams, maskedHeaders, mask, pathTemplate);
            }
        } else {
            return null;
        }
    }
}
