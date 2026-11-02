package com.bot.botback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Login/session settings ({@code app.auth.*}).
 *
 * @param jwtSecret       HMAC secret for access tokens (JWT_SECRET); validated by JwtUtil
 * @param accessTokenTtl  lifetime of the short-lived access token cookie
 * @param refreshTokenTtl lifetime of the refresh token, i.e. how long you stay signed in when idle
 * @param secureCookies   send cookies over HTTPS only (browsers also allow them on http://localhost)
 */
@ConfigurationProperties("app.auth")
public record AuthProperties(String jwtSecret,
                             Duration accessTokenTtl,
                             Duration refreshTokenTtl,
                             boolean secureCookies) {

    public AuthProperties {
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(15);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(30);
        }
    }
}
