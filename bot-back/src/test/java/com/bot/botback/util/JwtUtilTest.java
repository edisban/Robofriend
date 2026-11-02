package com.bot.botback.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtUtilTest {

    private static final String SECRET = "test-secret-0123456789-abcdefghijklmnop";

    @Test
    void generatesValidToken() {
        JwtUtil jwt = new JwtUtil(SECRET, Duration.ofMinutes(15));
        String t = jwt.generate("user");
        assertTrue(jwt.isValid(t));
        assertEquals("user", jwt.extractUsername(t));
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String foreign = new JwtUtil("another-secret-0123456789-abcdefghijk", Duration.ofMinutes(15)).generate("user");
        assertFalse(new JwtUtil(SECRET, Duration.ofMinutes(15)).isValid(foreign));
    }

    @Test
    void refusesMissingShortOrExampleSecrets() {
        assertThrows(IllegalStateException.class, () -> new JwtUtil("", Duration.ofMinutes(1)));
        assertThrows(IllegalStateException.class, () -> new JwtUtil("too-short", Duration.ofMinutes(1)));
        assertThrows(IllegalStateException.class, () -> new JwtUtil("change-me-in-production-32-chars-min!", Duration.ofMinutes(1)));
    }
}
