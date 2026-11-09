package com.documind.chat.service;

import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.LongFunction;
import org.springframework.stereotype.Component;

/** Single-instance sliding window. Reserve only after the creation transaction commits. */
@Component
public class ChatRateLimiter {
    private static final long WINDOW_MS = 60_000;
    private static final int LIMIT = 20;
    private static final int MAX_USERS = 10_000;
    private final Clock clock;
    private final Map<UUID, ArrayDeque<Long>> accepted = new HashMap<>();
    private io.micrometer.core.instrument.Counter rejected;

    public ChatRateLimiter(Clock clock) { this.clock = clock; }

    @org.springframework.beans.factory.annotation.Autowired
    void configureMetrics(io.micrometer.core.instrument.MeterRegistry registry) {
        rejected = registry.counter("documind.rate_limit.rejected", "operation", "chat");
    }

    public static final class Limited extends AppException {
        private final long retryAfter;
        public Limited(long retryAfter) { super(ErrorCode.RATE_LIMITED); this.retryAfter = retryAfter; }
        public long retryAfter() { return retryAfter; }
    }

    public synchronized <T> T accept(UUID owner, Supplier<T> createAndCommit) {
        return admit(owner, retry -> {
            if (retry > 0) throw new Limited(retry);
            return new Decision<>(createAndCommit.get(), true);
        });
    }

    public record Decision<T>(T value, boolean created) {}

    /** The callback checks replay first and must reject a new request if retryAfter is nonzero. */
    public synchronized <T> T admit(UUID owner, LongFunction<Decision<T>> callback) {
        long now = clock.millis();
        accepted.values().forEach(window -> expire(window, now));
        accepted.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        var window = accepted.get(owner);
        long retry = window == null && accepted.size() >= MAX_USERS ? 1
                : window != null && window.size() >= LIMIT ? Math.max(1, (window.getFirst() + WINDOW_MS - now + 999) / 1000) : 0;
        Decision<T> result;
        try { result = callback.apply(retry); }
        catch (Limited limited) {
            if (rejected != null) rejected.increment();
            throw limited;
        }
        if (result.created()) {
            if (retry > 0) throw new IllegalStateException("Admission callback ignored rate limit");
            accepted.computeIfAbsent(owner, ignored -> new ArrayDeque<>()).addLast(clock.millis());
        }
        return result.value();
    }

    private static void expire(ArrayDeque<Long> window, long now) {
        while (!window.isEmpty() && window.getFirst() <= now - WINDOW_MS) window.removeFirst();
    }
}
