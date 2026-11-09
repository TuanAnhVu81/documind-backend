package com.documind.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChatRateLimiterTest {
    static final class MutableClock extends Clock {
        long millis;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }

    @Test void failedCreationDoesNotConsumeSlotAndWindowSlides() {
        var clock = new MutableClock(); var limiter = new ChatRateLimiter(clock); UUID owner = UUID.randomUUID();
        assertThatThrownBy(() -> limiter.accept(owner, () -> { throw new IllegalStateException(); })).isInstanceOf(IllegalStateException.class);
        for (int i = 0; i < 20; i++) limiter.accept(owner, () -> true);
        String replay = limiter.admit(owner, retry -> new ChatRateLimiter.Decision<>("replay", false));
        assertThat(replay).isEqualTo("replay");
        assertThatThrownBy(() -> limiter.accept(owner, () -> true)).isInstanceOf(ChatRateLimiter.Limited.class)
                .satisfies(error -> assertThat(((ChatRateLimiter.Limited) error).retryAfter()).isEqualTo(60));
        clock.millis = 60_000;
        assertThat(limiter.accept(owner, () -> true)).isTrue();
    }

    @Test void concurrentRequestsCannotExceedLimit() throws Exception {
        var limiter = new ChatRateLimiter(new MutableClock()); var successes = new AtomicInteger(); UUID owner = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 50).<java.util.concurrent.Callable<Void>>mapToObj(i -> () -> {
                try { limiter.accept(owner, () -> { successes.incrementAndGet(); return true; }); } catch (ChatRateLimiter.Limited ignored) { }
                return null;
            }).toList();
            for (var future : pool.invokeAll(tasks)) future.get();
        }
        assertThat(successes.get()).isEqualTo(20);
    }
}
