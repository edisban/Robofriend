package com.bot.botback.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginRateLimiterTest {

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-02T10:00:00Z");

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void blocksAfterTooManyFailedLoginsThenRecovers() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);

        for (int i = 0; i < LoginRateLimiter.MAX_FAILED_LOGINS; i++) {
            assertFalse(limiter.loginBlockedFor("1.2.3.4", "eleni").isPresent());
            limiter.recordFailedLogin("1.2.3.4", "eleni");
        }
        assertTrue(limiter.loginBlockedFor("1.2.3.4", "Eleni").isPresent(), "username match is case-insensitive");
        assertFalse(limiter.loginBlockedFor("5.6.7.8", "eleni").isPresent(), "other addresses are not affected");

        clock.now = clock.now.plus(LoginRateLimiter.LOGIN_WINDOW).plus(Duration.ofSeconds(1));
        assertFalse(limiter.loginBlockedFor("1.2.3.4", "eleni").isPresent());
    }

    @Test
    void successfulLoginClearsFailures() {
        LoginRateLimiter limiter = new LoginRateLimiter(new MutableClock());
        for (int i = 0; i < LoginRateLimiter.MAX_FAILED_LOGINS - 1; i++) {
            limiter.recordFailedLogin("1.2.3.4", "eleni");
        }
        limiter.resetLogin("1.2.3.4", "eleni");
        limiter.recordFailedLogin("1.2.3.4", "eleni");
        assertFalse(limiter.loginBlockedFor("1.2.3.4", "eleni").isPresent());
    }

    @Test
    void limitsRegistrationsPerAddress() {
        LoginRateLimiter limiter = new LoginRateLimiter(new MutableClock());
        for (int i = 0; i < LoginRateLimiter.MAX_REGISTRATIONS; i++) {
            limiter.recordRegistration("1.2.3.4");
        }
        assertTrue(limiter.registerBlockedFor("1.2.3.4").isPresent());
    }
}
