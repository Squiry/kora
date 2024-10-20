package ru.tinkoff.kora.database.cassandra;

import com.datastax.oss.driver.api.core.CqlSession;
import ru.tinkoff.kora.application.graph.Lifecycle;
import ru.tinkoff.kora.database.common.telemetry.DataBaseTelemetry;
import ru.tinkoff.kora.database.common.telemetry.DataBaseTelemetryFactory;

import java.util.Objects;
import java.util.Optional;

public final class CassandraDatabase implements CassandraConnectionFactory, Lifecycle {
    private final CassandraConfig config;
    private final DataBaseTelemetry telemetry;
    private volatile CqlSession cqlSession;

    public CassandraDatabase(CassandraConfig config, DataBaseTelemetryFactory telemetryFactory) {
        this.config = config;
        this.telemetry = Objects.requireNonNullElse(telemetryFactory.get(
            config.telemetry(),
            Objects.requireNonNullElse(config.basic().sessionName(), "cassandra"),
            "cassandra",
            "cassandra",
            Optional.ofNullable(config.auth()).map(CassandraConfig.CassandraCredentials::login).orElse("anonymous")
        ), DataBaseTelemetryFactory.EMPTY);
    }

    @Override
    public CqlSession currentSession() {
        return cqlSession;
    }

    @Override
    public DataBaseTelemetry telemetry() {
        return this.telemetry;
    }

    @Override
    public void init() {
        cqlSession = new CassandraSessionBuilder().build(config);
    }

    @Override
    public void release() {
        var s = cqlSession;
        if (s != null) {
            s.close();
            cqlSession = null;
        }
    }
}
