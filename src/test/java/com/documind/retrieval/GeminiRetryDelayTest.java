package com.documind.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GeminiRetryDelayTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T08:30:00Z"), ZoneOffset.UTC);

    @Test void dailyQuotaWaitsForPacificResetIncludingDaylightSaving() {
        String body = """
                {"error":{"details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure",
                "violations":[{"quotaId":"EmbedContentRequestsPerDayPerProjectPerModel-FreeTier"}]},
                {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"120.5s"}]}}
                """;
        assertThat(GeminiRetryDelay.from(429, null, body, json, clock)).isEqualTo(Duration.ofHours(23).plusMinutes(30));
    }

    @Test void honorsLongerProviderDelayAndUsesConservativeFallback() {
        String body = """
                {"error":{"details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"120.25s"}]}}
                """;
        assertThat(GeminiRetryDelay.from(429, "90", body, json, clock)).isEqualTo(Duration.ofMillis(120250));
        assertThat(GeminiRetryDelay.from(429, null, "invalid", json, clock)).isEqualTo(Duration.ofHours(23).plusMinutes(30));
        assertThat(GeminiRetryDelay.from(429, "0", "{}", json, clock)).isEqualTo(Duration.ofHours(23).plusMinutes(30));
        assertThat(GeminiRetryDelay.from(503, "90", "{}", json, clock)).isEqualTo(Duration.ofSeconds(90));
        assertThat(GeminiRetryDelay.from(503, "Sun, 01 Nov 2026 08:32:00 GMT", "{}", json, clock)).isEqualTo(Duration.ofMinutes(2));
        String minute = """
                {"error":{"details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure",
                "violations":[{"quotaId":"EmbedRequestsPerMinute"}]}]}}
                """;
        assertThat(GeminiRetryDelay.from(429, null, minute, json, clock)).isNull();
        assertThat(GeminiRetryDelay.from(503, null, "invalid", json, clock)).isNull();
    }
}
