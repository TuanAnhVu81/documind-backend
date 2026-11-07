package com.documind.common.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Aggregate-only operational metrics; no user or document identifiers are exposed. */
@Component
public class QueueMetrics {
    private final JdbcClient jdbc;

    public QueueMetrics(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        gauge(registry, "documind.jobs.pending", "SELECT count(*) FROM processing_jobs WHERE status='PENDING'");
        gauge(registry, "documind.jobs.retry", "SELECT count(*) FROM processing_jobs WHERE status='PENDING' AND attempts>0");
        gauge(registry, "documind.jobs.stuck", "SELECT count(*) FROM processing_jobs WHERE status='PROCESSING' AND lease_until < now()");
        gauge(registry, "documind.generations.active", "SELECT count(*) FROM chat_generations WHERE status='GENERATING'");
        Gauge.builder("documind.usage.estimated.cost.usd", this::knownCost).register(registry);
    }

    private void gauge(MeterRegistry registry, String name, String query) {
        Gauge.builder(name, () -> count(query)).register(registry);
    }

    private double count(String query) {
        try { return jdbc.sql(query).query(Long.class).single().doubleValue(); }
        catch (RuntimeException unavailable) { return Double.NaN; }
    }

    private double knownCost() {
        try {
            return jdbc.sql("""
                SELECT CASE WHEN EXISTS (SELECT 1 FROM usage_untracked_history) THEN NULL
                    WHEN count(*)=0 THEN 0 WHEN bool_or(estimated_cost_usd IS NULL) THEN NULL
                    ELSE sum(estimated_cost_usd) END FROM usage_logs
                """).query((rs, row) -> {
                    var value = rs.getBigDecimal(1);
                    return value == null ? Double.NaN : value.doubleValue();
                }).single();
        } catch (RuntimeException unavailable) { return Double.NaN; }
    }
}
