package com.documind.auth.security;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Bounded, per-process limit using the direct peer IP; forwarded headers are not trusted. */
@Component
public class AuthRateLimiter {
    private record Window(Instant end, int count) {}
    private static final int MAX_BUCKETS = 10_000;
    private final Map<String, Window> windows = new LinkedHashMap<>();
    private final Clock clock;
    private final AuthSettings settings;

    public AuthRateLimiter(Clock clock, AuthSettings settings) { this.clock = clock; this.settings = settings; }

    public synchronized long retryAfter(String bucket, int maxAttempts) {
        Instant now = clock.instant();
        Window window = windows.get(bucket);
        if (window == null || !window.end().isAfter(now)) {
            windows.entrySet().removeIf(entry -> !entry.getValue().end().isAfter(now));
            if (windows.size() >= MAX_BUCKETS) return 1;
            windows.put(bucket, new Window(now.plusSeconds(settings.loginWindowSeconds()), 1));
            return 0;
        }
        if (window.count() >= maxAttempts)
            return Math.max(1, window.end().getEpochSecond() - now.getEpochSecond());
        windows.put(bucket, new Window(window.end(), window.count() + 1));
        return 0;
    }
}
