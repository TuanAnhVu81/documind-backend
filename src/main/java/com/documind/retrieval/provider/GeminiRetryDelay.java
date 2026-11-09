package com.documind.retrieval.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import tools.jackson.databind.json.JsonMapper;

/** Extracts only retry metadata; provider messages and document text are never retained. */
final class GeminiRetryDelay {
    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    private GeminiRetryDelay() { }

    static Duration from(int status, String header, String body, JsonMapper json) {
        return from(status, header, body, json, Clock.systemUTC());
    }

    static Duration from(int status, String header, String body, JsonMapper json, Clock clock) {
        Instant now = clock.instant();
        Duration result = headerDelay(header, now);
        boolean daily = false, shortQuota = false;
        try {
            if (body != null && !body.isBlank()) {
                var details = json.readTree(body).path("error").path("details");
                if (details.isArray()) for (var detail : details) {
                    String type = detail.path("@type").asString("");
                    if (type.endsWith("google.rpc.RetryInfo"))
                        result = max(result, secondsDelay(detail.path("retryDelay").asString(""), true));
                    if (type.endsWith("google.rpc.QuotaFailure")) {
                        var violations = detail.path("violations");
                        if (violations.isArray()) for (var violation : violations) {
                            String id = (violation.path("quotaId").asString("") + " " + violation.path("quotaMetric").asString(""))
                                    .toLowerCase(Locale.ROOT).replace("_", "");
                            daily |= id.contains("perday");
                            shortQuota |= id.contains("perminute") || id.contains("persecond");
                        }
                    }
                }
            }
        } catch (RuntimeException invalid) { /* Ignore malformed provider metadata, never log response. */ }
        if (status == 429 && (daily || ((result == null || result.isZero()) && !shortQuota))) {
            Instant reset = ZonedDateTime.ofInstant(now, PACIFIC).toLocalDate().plusDays(1).atStartOfDay(PACIFIC).toInstant();
            result = max(result, Duration.between(now, reset));
        }
        return result;
    }

    private static Duration headerDelay(String header, Instant now) {
        if (header == null || header.isBlank()) return null;
        Duration numeric = secondsDelay(header.trim(), false);
        if (numeric != null) return numeric;
        try {
            Duration delay = Duration.between(now, ZonedDateTime.parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (RuntimeException invalid) { return null; }
    }

    private static Duration secondsDelay(String value, boolean suffix) {
        try {
            String s = value.trim();
            if (suffix) { if (!s.endsWith("s")) return null; s = s.substring(0, s.length() - 1); }
            else if (!s.matches("[0-9]+")) return null;
            BigDecimal seconds = new BigDecimal(s);
            if (seconds.signum() < 0 || seconds.compareTo(BigDecimal.valueOf(31_536_000L)) > 0) return null;
            return Duration.ofMillis(seconds.movePointRight(3).setScale(0, RoundingMode.CEILING).longValueExact());
        } catch (RuntimeException invalid) { return null; }
    }

    private static Duration max(Duration a, Duration b) {
        return a == null ? b : b == null || a.compareTo(b) >= 0 ? a : b;
    }
}
