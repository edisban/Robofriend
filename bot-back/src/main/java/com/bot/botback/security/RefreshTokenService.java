package com.bot.botback.security;

import com.bot.botback.config.AuthProperties;
import com.bot.botback.model.RefreshToken;
import com.bot.botback.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Long-lived sign-in sessions behind the refresh cookie.
 * <ul>
 *   <li>Tokens are random 256-bit values; only their SHA-256 hash is stored.</li>
 *   <li>Every refresh rotates the token: the old one is revoked and a new one issued.</li>
 *   <li>A revoked token presented again means it was copied, so all of that user's sessions end.</li>
 * </ul>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A new refresh token for {@code userId}; {@code token} is what goes in the cookie. */
    public record Issued(Long userId, String token) {
    }

    private final RefreshTokenRepository repository;
    private final AuthProperties properties;
    private final Clock clock;

    @Autowired
    public RefreshTokenService(RefreshTokenRepository repository, AuthProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    RefreshTokenService(RefreshTokenRepository repository, AuthProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Issued issue(Long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken entity = new RefreshToken();
        entity.setUserId(userId);
        entity.setTokenHash(hash(token));
        entity.setExpiresAt(now().plus(properties.refreshTokenTtl()));
        repository.save(entity);
        return new Issued(userId, token);
    }

    /** Swaps a valid token for a new one; empty if it is unknown, expired, or was already used. */
    @Transactional
    public Optional<Issued> rotate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<RefreshToken> found = repository.findByTokenHash(hash(token));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        RefreshToken current = found.get();
        if (current.getRevokedAt() != null) {
            log.warn("Revoked refresh token reused for user {}; ending all their sessions", current.getUserId());
            repository.revokeAllForUser(current.getUserId(), now());
            return Optional.empty();
        }
        if (current.getExpiresAt().isBefore(now())) {
            return Optional.empty();
        }
        current.setRevokedAt(now());
        return Optional.of(issue(current.getUserId()));
    }

    /** Logout: the token stops working. Unknown tokens are ignored. */
    @Transactional
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        repository.findByTokenHash(hash(token))
                .filter(t -> t.getRevokedAt() == null)
                .ifPresent(t -> t.setRevokedAt(now()));
    }

    /** Expired tokens are useless; keep a day of history so reuse detection still works briefly. */
    @Scheduled(cron = "0 17 3 * * *")
    @Transactional
    public void deleteExpired() {
        int deleted = repository.deleteExpiredBefore(now().minusDays(1));
        if (deleted > 0) {
            log.info("Deleted {} expired refresh tokens", deleted);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
