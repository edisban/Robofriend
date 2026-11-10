package com.bot.botback.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordEncoderConfigTest {

    private final PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();

    @Test
    void newPasswordsAreHashedWithArgon2idUsingOwaspSettings() {
        String hash = encoder.encode("correct-horse-1");

        assertTrue(hash.startsWith("{argon2}$argon2id$v=19$m=19456,t=2,p=1$"), hash);
        assertTrue(encoder.matches("correct-horse-1", hash));
        assertFalse(encoder.matches("wrong-horse-1", hash));
        assertNotEquals(hash, encoder.encode("correct-horse-1"), "every hash gets its own salt");
        assertFalse(encoder.upgradeEncoding(hash));
    }

    @Test
    void oldBcryptHashesStillMatchAndAskToBeUpgraded() {
        // Stored before the switch: a bare BCrypt hash with no {id} prefix.
        String legacy = new BCryptPasswordEncoder().encode("old-password-1");

        assertTrue(encoder.matches("old-password-1", legacy));
        assertFalse(encoder.matches("wrong", legacy));
        assertTrue(encoder.upgradeEncoding(legacy));
    }

    @Test
    void passwordsLongerThanBcryptsLimitAreFullyChecked() {
        // BCrypt ignores everything after 72 bytes; Argon2id doesn't.
        String base = "a".repeat(80);
        String hash = encoder.encode(base + "1");

        assertFalse(encoder.matches(base + "2", hash));
    }
}
