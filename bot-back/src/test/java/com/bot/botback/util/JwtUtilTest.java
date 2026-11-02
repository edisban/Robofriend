package com.bot.botback.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtUtilTest {

    private static final String SECRET = "test-secret-0123456789-abcdefghijklmnop";

    @Test
    void generatesValidToken() {
        JwtUtil jwt = new JwtUtil(SECRET, 86400000L);
        String t = jwt.generate("user");
        assertTrue(jwt.isValid(t));
        assertEquals("user", jwt.extractUsername(t));
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String foreign = new JwtUtil("another-secret-0123456789-abcdefghijk", 86400000L).generate("user");
        assertFalse(new JwtUtil(SECRET, 86400000L).isValid(foreign));
    }

    @Test
    void refusesMissingShortOrExampleSecrets() {
        assertThrows(IllegalStateException.class, () -> new JwtUtil("", 1000L));
        assertThrows(IllegalStateException.class, () -> new JwtUtil("too-short", 1000L));
        assertThrows(IllegalStateException.class, () -> new JwtUtil("change-me-in-production-32-chars-min!", 1000L));
    }
}
