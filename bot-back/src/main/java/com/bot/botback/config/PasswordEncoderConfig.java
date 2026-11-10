package com.bot.botback.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

/**
 * Passwords are hashed with Argon2id, stored as {@code {argon2}$argon2id$...}.
 *
 * <p>Accounts created earlier have bare BCrypt hashes ({@code $2a$...}). Those can still sign in,
 * and their password is re-hashed with Argon2id when they do (see AuthController). BCrypt stays
 * only to check those old hashes; it can be removed once none are left (the backend logs how many
 * remain at startup).
 */
@Configuration
public class PasswordEncoderConfig {

    static final String ARGON2 = "argon2";

    /** OWASP's recommended Argon2id settings: 19 MiB of memory, 2 passes, 1 lane. */
    static Argon2PasswordEncoder argon2id() {
        return new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        BCryptPasswordEncoder legacyBcrypt = new BCryptPasswordEncoder();
        DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder(ARGON2, Map.of(
                ARGON2, argon2id(),
                "bcrypt", legacyBcrypt));
        // Old hashes were stored without an {id} prefix.
        encoder.setDefaultPasswordEncoderForMatches(legacyBcrypt);
        return encoder;
    }
}
