package com.bot.botback.security;

import com.bot.botback.config.ChatRateLimitProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRateLimiterTest {

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-02T10:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void blocksAfterThePerMinuteLimitThenRecovers() {
        MutableClock clock = new MutableClock();
        ChatRateLimiter limiter = new ChatRateLimiter(new ChatRateLimitProperties(3, 100), clock);

        for (int i = 0; i < 3; i++) {
            assertFalse(limiter.tryAcquire(1L).isPresent());
            clock.now = clock.now.plusSeconds(10);
        }
        Optional<ChatRateLimiter.Blocked> blocked = limiter.tryAcquire(1L);
        assertTrue(blocked.isPresent());
        assertEquals(ChatRateLimiter.Limit.MINUTE, blocked.get().limit());
        // The first request was 30s ago, so it leaves the window in 30s.
        assertEquals(Duration.ofSeconds(30), blocked.get().retryAfter());
        assertFalse(limiter.tryAcquire(2L).isPresent(), "other users are not affected");

        clock.now = clock.now.plusSeconds(31);
        assertFalse(limiter.tryAcquire(1L).isPresent());
    }

    @Test
    void refusedRequestsDontCountAgainstTheLimit() {
        MutableClock clock = new MutableClock();
        ChatRateLimiter limiter = new ChatRateLimiter(new ChatRateLimitProperties(2, 100), clock);

        limiter.tryAcquire(1L);
        limiter.tryAcquire(1L);
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(1L).isPresent());
        }
        clock.now = clock.now.plus(Duration.ofMinutes(1)).plusSeconds(1);
        assertFalse(limiter.tryAcquire(1L).isPresent(), "hammering while blocked doesn't extend the block");
    }

    @Test
    void enforcesTheDailyCapAcrossManyMinutes() {
        MutableClock clock = new MutableClock();
        ChatRateLimiter limiter = new ChatRateLimiter(new ChatRateLimitProperties(20, 5), clock);

        for (int i = 0; i < 5; i++) {
            assertFalse(limiter.tryAcquire(1L).isPresent());
            clock.now = clock.now.plus(Duration.ofHours(1));
        }
        Optional<ChatRateLimiter.Blocked> blocked = limiter.tryAcquire(1L);
        assertTrue(blocked.isPresent());
        assertEquals(ChatRateLimiter.Limit.DAY, blocked.get().limit());
        assertEquals(Duration.ofHours(19), blocked.get().retryAfter());

        clock.now = clock.now.plus(Duration.ofHours(19)).plusSeconds(1);
        assertFalse(limiter.tryAcquire(1L).isPresent(), "the oldest request has left the 24h window");
    }

    @Test
    void evictionForgetsIdleUsers() {
        MutableClock clock = new MutableClock();
        ChatRateLimiter limiter = new ChatRateLimiter(new ChatRateLimitProperties(1, 1), clock);

        limiter.tryAcquire(1L);
        clock.now = clock.now.plus(Duration.ofDays(1)).plusSeconds(1);
        limiter.evictExpired();
        assertFalse(limiter.tryAcquire(1L).isPresent());
    }
}
