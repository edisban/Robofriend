package com.bot.botback.util;

import com.bot.botback.config.AuthProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;

/** Issues and checks the short-lived access tokens kept in the HttpOnly access cookie. */
@Component
public class JwtUtil {

    /** HS256 needs at least 256 bits of key material. */
    static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration accessTokenTtl;

    @Autowired
    public JwtUtil(AuthProperties properties) {
        this(properties.jwtSecret(), properties.accessTokenTtl());
    }

    JwtUtil(String secret, Duration accessTokenTtl) {
        this.key = Keys.hmacShaKeyFor(requireStrongSecret(secret).getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = accessTokenTtl;
    }

    /**
     * Refuses to start with a missing, short or example secret: anyone who knows the secret can
     * sign a token for any user.
     */
    static String requireStrongSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET is not set. Generate one, e.g. `openssl rand -base64 48`, "
                    + "and put it in .env as JWT_SECRET=...");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must be at least " + MIN_SECRET_BYTES + " characters long.");
        }
        if (secret.toLowerCase().contains("change-me")) {
            throw new IllegalStateException("JWT_SECRET is still the example value; replace it with a random secret.");
        }
        return secret;
    }

    public Duration accessTokenTtl() {
        return accessTokenTtl;
    }

    /**
     * An access token for the user. The subject is the user id, not the username: ids are never
     * reused, so a token can't outlive a deleted account and sign in to a new one with the same name.
     */
    public String generate(Long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenTtl.toMillis()))
                .signWith(key)
                .compact();
    }

    /** The user id in a valid token; empty for tokens from before ids were used (they held a username). */
    public Optional<Long> extractUserId(String token) {
        String subject = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload().getSubject();
        try {
            return Optional.of(Long.parseLong(subject));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public boolean isValid(String token) {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
