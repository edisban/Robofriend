package com.bot.botback.security;

import com.bot.botback.config.ChatRateLimitProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits how many AI replies (messages, regenerates, edits, check-ins) each user can ask for per
 * minute and per day. Kept in memory, so with several backend instances each one counts on its own.
 */
@Component
public class ChatRateLimiter {

    static final Duration MINUTE = Duration.ofMinutes(1);
    static final Duration DAY = Duration.ofDays(1);

    /** Which limit was hit, and when the user may try again. */
    public record Blocked(Limit limit, Duration retryAfter) {
    }

    public enum Limit { MINUTE, DAY }

    private final Map<Long, Deque<Instant>> requests = new ConcurrentHashMap<>();
    private final ChatRateLimitProperties properties;
    private final Clock clock;

    @Autowired
    public ChatRateLimiter(ChatRateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ChatRateLimiter(ChatRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Counts one reply for the user if they are under both limits. Checking and counting happen
     * together, so parallel requests can't slip past the limit.
     */
    public Optional<Blocked> tryAcquire(Long userId) {
        Deque<Instant> times = requests.computeIfAbsent(userId, id -> new ArrayDeque<>());
        Instant now = clock.instant();
        synchronized (times) {
            while (!times.isEmpty() && !times.peekFirst().isAfter(now.minus(DAY))) {
                times.pollFirst();
            }
            if (times.size() >= properties.perDay()) {
                return Optional.of(new Blocked(Limit.DAY, untilFree(times.peekFirst(), DAY, now)));
            }
            Instant minuteAgo = now.minus(MINUTE);
            int inLastMinute = 0;
            Instant oldestInMinute = null;
            for (Iterator<Instant> it = times.descendingIterator(); it.hasNext(); ) {
                Instant at = it.next();
                if (!at.isAfter(minuteAgo)) {
                    break;
                }
                inLastMinute++;
                oldestInMinute = at;
            }
            if (inLastMinute >= properties.perMinute()) {
                return Optional.of(new Blocked(Limit.MINUTE, untilFree(oldestInMinute, MINUTE, now)));
            }
            times.addLast(now);
            return Optional.empty();
        }
    }

    /** Drops users with nothing in the last day so the map doesn't grow forever. */
    @Scheduled(fixedDelay = 15 * 60 * 1000)
    public void evictExpired() {
        Instant cutoff = clock.instant().minus(DAY);
        requests.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                entry.getValue().removeIf(at -> !at.isAfter(cutoff));
                return entry.getValue().isEmpty();
            }
        });
    }

    private static Duration untilFree(Instant oldest, Duration window, Instant now) {
        Duration wait = Duration.between(now, oldest.plus(window));
        return wait.isNegative() || wait.isZero() ? Duration.ofSeconds(1) : wait;
    }
}
