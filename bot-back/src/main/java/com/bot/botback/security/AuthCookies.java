package com.bot.botback.security;

import com.bot.botback.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * The two session cookies. Both are HttpOnly (page scripts can't read them) and SameSite=Strict
 * (never sent with requests started by other websites).
 * <ul>
 *   <li>{@value #ACCESS}: short-lived JWT, sent with every API call</li>
 *   <li>{@value #REFRESH}: long-lived session token, only sent to /api/auth</li>
 * </ul>
 */
@Component
public class AuthCookies {

    public static final String ACCESS = "rf_access";
    public static final String REFRESH = "rf_refresh";
    private static final String REFRESH_PATH = "/api/auth";

    private final AuthProperties properties;

    public AuthCookies(AuthProperties properties) {
        this.properties = properties;
    }

    /** Adds both session cookies to a response. */
    public ResponseEntity.BodyBuilder withSession(ResponseEntity.BodyBuilder response, String accessToken,
                                                  String refreshToken) {
        return response
                .header(HttpHeaders.SET_COOKIE, cookie(ACCESS, accessToken, "/", properties.accessTokenTtl()).toString())
                .header(HttpHeaders.SET_COOKIE, cookie(REFRESH, refreshToken, REFRESH_PATH, properties.refreshTokenTtl()).toString());
    }

    /** Removes both session cookies. */
    public ResponseEntity.HeadersBuilder<?> cleared(ResponseEntity.HeadersBuilder<?> response) {
        return response
                .header(HttpHeaders.SET_COOKIE, cookie(ACCESS, "", "/", Duration.ZERO).toString())
                .header(HttpHeaders.SET_COOKIE, cookie(REFRESH, "", REFRESH_PATH, Duration.ZERO).toString());
    }

    public static Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> name.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank())
                .map(c -> c.getValue())
                .findFirst();
    }

    private ResponseCookie cookie(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(properties.secureCookies())
                .sameSite("Strict")
                .path(path)
                .maxAge(maxAge)
                .build();
    }
}
