package com.bot.botback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Cross-origin access ({@code app.cors.*}).
 *
 * @param allowedOrigins front-ends allowed to call the API from another origin (CORS_ALLOWED_ORIGINS,
 *                       comma-separated)
 */
@ConfigurationProperties("app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
