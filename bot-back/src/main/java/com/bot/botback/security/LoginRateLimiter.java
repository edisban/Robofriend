package com.bot.botback.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing and account spam: too many failed logins for a username from one
 * address, or too many sign-ups from one address, are refused for a while. Kept in memory, which
 * is enough for a single backend instance.
 */
@Component
public class LoginRateLimiter {

    static final int MAX_FAILED_LOGINS = 5;
    static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    static final int MAX_REGISTRATIONS = 5;
    static final Duration REGISTER_WINDOW = Duration.ofHours(1);

    private final Map<String, Deque<Instant>> events = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginRateLimiter() {
        this(Clock.systemUTC());
    }

    LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** How long until this client may try to log in again, if it is blocked. */
    public Optional<Duration> loginBlockedFor(String clientIp, String username) {
        return blockedFor(loginKey(clientIp, username), MAX_FAILED_LOGINS, LOGIN_WINDOW);
    }

    public void recordFailedLogin(String clientIp, String username) {
        record(loginKey(clientIp, username));
    }

    public void resetLogin(String clientIp, String username) {
        events.remove(loginKey(clientIp, username));
    }

    public Optional<Duration> registerBlockedFor(String clientIp) {
        return blockedFor(registerKey(clientIp), MAX_REGISTRATIONS, REGISTER_WINDOW);
    }

    public void recordRegistration(String clientIp) {
        record(registerKey(clientIp));
    }

    /** Drops expired entries so the map doesn't grow forever. */
    @Scheduled(fixedDelay = 15 * 60 * 1000)
    public void evictExpired() {
        Instant cutoff = clock.instant().minus(REGISTER_WINDOW);
        events.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                entry.getValue().removeIf(at -> at.isBefore(cutoff));
                return entry.getValue().isEmpty();
            }
        });
    }

    private Optional<Duration> blockedFor(String key, int max, Duration window) {
        Deque<Instant> times = events.get(key);
        if (times == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        synchronized (times) {
            while (!times.isEmpty() && !times.peekFirst().isAfter(now.minus(window))) {
                times.pollFirst();
            }
            if (times.size() < max) {
                return Optional.empty();
            }
            return Optional.of(Duration.between(now, times.peekFirst().plus(window)));
        }
    }

    private void record(String key) {
        Deque<Instant> times = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            times.addLast(clock.instant());
        }
    }

    private static String loginKey(String clientIp, String username) {
        return "login|" + clientIp + "|" + (username == null ? "" : username.toLowerCase(Locale.ROOT));
    }

    private static String registerKey(String clientIp) {
        return "register|" + clientIp;
    }
}
