package ru.tinkoff.kora.database.common.telemetry;

import jakarta.annotation.Nullable;
import ru.tinkoff.kora.telemetry.common.TelemetryConfig;

import java.util.Objects;

public interface DataBaseLoggerFactory {

    @Nullable
    DataBaseLogger get(TelemetryConfig.LoggingConfig logging, String poolName);

    final class DefaultDataBaseLoggerFactory implements DataBaseLoggerFactory {
        @Override
        @Nullable
        public DataBaseLogger get(TelemetryConfig.LoggingConfig logging, String poolName) {
            if (Objects.requireNonNullElse(logging.enabled(), false)) {
                return new DefaultDataBaseLogger(poolName);
            } else {
                return null;

            }
        }
    }
}
