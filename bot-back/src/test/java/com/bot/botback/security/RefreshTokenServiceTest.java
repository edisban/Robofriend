package com.bot.botback.security;

import com.bot.botback.config.AuthProperties;
import com.bot.botback.model.RefreshToken;
import com.bot.botback.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    private RefreshTokenRepository repository;
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(RefreshTokenRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AuthProperties properties = new AuthProperties("x".repeat(40), Duration.ofMinutes(15), Duration.ofDays(30), true);
        service = new RefreshTokenService(repository, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void storesOnlyAHashOfTheToken() {
        RefreshTokenService.Issued issued = service.issue(7L);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        assertNotEquals(issued.token(), saved.getValue().getTokenHash());
        assertEquals(RefreshTokenService.hash(issued.token()), saved.getValue().getTokenHash());
        assertEquals(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(30), saved.getValue().getExpiresAt());
    }

    @Test
    void rotationRevokesTheOldTokenAndIssuesANewOne() {
        RefreshToken current = token(7L, null, NOW.plusSeconds(3600));
        when(repository.findByTokenHash(RefreshTokenService.hash("old"))).thenReturn(Optional.of(current));

        Optional<RefreshTokenService.Issued> rotated = service.rotate("old");

        assertTrue(rotated.isPresent());
        assertNotEquals("old", rotated.get().token());
        assertNotNull(current.getRevokedAt());
    }

    @Test
    void reusingARevokedTokenEndsAllSessionsOfThatUser() {
        RefreshToken used = token(7L, OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC), NOW.plusSeconds(3600));
        when(repository.findByTokenHash(RefreshTokenService.hash("copied"))).thenReturn(Optional.of(used));

        assertFalse(service.rotate("copied").isPresent());
        verify(repository).revokeAllForUser(eq(7L), any());
    }

    @Test
    void expiredAndUnknownTokensAreRefused() {
        when(repository.findByTokenHash(RefreshTokenService.hash("expired")))
                .thenReturn(Optional.of(token(7L, null, NOW.minusSeconds(1))));
        assertFalse(service.rotate("expired").isPresent());
        assertFalse(service.rotate("never-issued").isPresent());
        assertFalse(service.rotate(null).isPresent());
    }

    private static RefreshToken token(Long userId, OffsetDateTime revokedAt, Instant expiresAt) {
        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setRevokedAt(revokedAt);
        token.setExpiresAt(OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        return token;
    }
}
