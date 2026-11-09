package com.documind.document.service;

import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Per-user sliding window for one instance, including uploads currently being processed. */
@Component
public class UploadRateLimiter {
    private static final int MAX_USERS = 10_000;
    private final Clock clock;
    private final int limit;
    private final long windowMillis;
    private final Map<UUID, Window> windows = new HashMap<>();
    private io.micrometer.core.instrument.Counter rejected;
    private static final class Window { final ArrayDeque<Long> completed = new ArrayDeque<>(); int inFlight; }

    public UploadRateLimiter(Clock clock, @Value("${documind.upload.max-per-window:10}") int limit,
            @Value("${documind.upload.window-seconds:60}") long windowSeconds) {
        if (limit < 1 || windowSeconds < 1 || windowSeconds > 86400) throw new IllegalArgumentException("Invalid upload rate limit");
        this.clock = clock; this.limit = limit; this.windowMillis = windowSeconds * 1000;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void configureMetrics(io.micrometer.core.instrument.MeterRegistry registry) {
        rejected = registry.counter("documind.rate_limit.rejected", "operation", "upload");
    }

    private Limited rejected(long retryAfter) {
        if (rejected != null) rejected.increment();
        return new Limited(retryAfter);
    }

    public static final class Limited extends AppException {
        private final long retryAfter;
        Limited(long retryAfter) { super(ErrorCode.RATE_LIMITED); this.retryAfter = retryAfter; }
        public long retryAfter() { return retryAfter; }
    }

    public synchronized Reservation reserve(UUID owner) {
        long now = clock.millis();
        windows.values().forEach(window -> expire(window, now));
        windows.entrySet().removeIf(entry -> entry.getValue().inFlight == 0 && entry.getValue().completed.isEmpty());
        Window window = windows.get(owner);
        if (window == null && windows.size() >= MAX_USERS) throw rejected(1);
        if (window == null) { window = new Window(); windows.put(owner, window); }
        if (window.completed.size() + window.inFlight >= limit) {
            long retry = window.completed.isEmpty() ? 1 : Math.max(1, (window.completed.getFirst() + windowMillis - now + 999) / 1000);
            throw rejected(retry);
        }
        window.inFlight++;
        return new Reservation(owner);
    }

    private void expire(Window window, long now) {
        while (!window.completed.isEmpty() && window.completed.getFirst() <= now - windowMillis) window.completed.removeFirst();
    }

    public final class Reservation implements AutoCloseable {
        private final UUID owner;
        private boolean done;
        private Reservation(UUID owner) { this.owner = owner; }
        public void success() { finish(true); }
        @Override public void close() { finish(false); }
        private void finish(boolean success) {
            synchronized (UploadRateLimiter.this) {
                if (done) return;
                done = true;
                Window window = windows.get(owner);
                window.inFlight--;
                if (success) window.completed.addLast(clock.millis());
                if (window.inFlight == 0 && window.completed.isEmpty()) windows.remove(owner);
            }
        }
    }
}
