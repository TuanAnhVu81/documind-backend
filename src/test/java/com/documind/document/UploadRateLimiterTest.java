package com.documind.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UploadRateLimiterTest {
    private static final class TestClock extends Clock {
        private Instant time = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time; }
        void advance(long seconds) { time = time.plusSeconds(seconds); }
    }

    @Test void countsSuccessfulAndInFlightUploadsSeparatelyPerUserAndRefundsFailures() {
        var clock = new TestClock();
        var limiter = new UploadRateLimiter(clock, 2, 60);
        var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        limiter.configureMetrics(metrics);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        try (var first = limiter.reserve(a); var pending = limiter.reserve(a); var other = limiter.reserve(b)) {
            first.success(); other.success();
            assertThatThrownBy(() -> limiter.reserve(a)).isInstanceOf(UploadRateLimiter.Limited.class)
                    .satisfies(error -> assertThat(((UploadRateLimiter.Limited) error).retryAfter()).isEqualTo(60));
        }
        try (var retry = limiter.reserve(a)) { retry.success(); }
        assertThatThrownBy(() -> limiter.reserve(a)).isInstanceOf(UploadRateLimiter.Limited.class);
        assertThat(metrics.counter("documind.rate_limit.rejected", "operation", "upload").count()).isEqualTo(2);
        clock.advance(60);
        try (var afterWindow = limiter.reserve(a)) { afterWindow.success(); }
    }
}
