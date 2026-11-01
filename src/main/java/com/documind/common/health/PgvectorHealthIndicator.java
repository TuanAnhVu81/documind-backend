package com.documind.common.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component("pgvector")
public class PgvectorHealthIndicator implements HealthIndicator {
    private final JdbcClient jdbc;

    public PgvectorHealthIndicator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        try {
            boolean available = jdbc.sql("SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector')")
                    .query(Boolean.class).single();
            return available ? Health.up().build() : Health.down().build();
        } catch (RuntimeException exception) {
            // Never expose a JDBC URL, exception message or database password in health.
            return Health.down().build();
        }
    }
}
