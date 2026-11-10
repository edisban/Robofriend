package com.bot.botback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-user limits on AI replies ({@code app.chat.rate-limit.*}). Every reply costs Groq tokens, so
 * one user (or a script) can't burn the whole API budget.
 *
 * @param perMinute replies allowed in any rolling minute
 * @param perDay    replies allowed in any rolling 24 hours
 */
@ConfigurationProperties("app.chat.rate-limit")
public record ChatRateLimitProperties(int perMinute, int perDay) {

    public ChatRateLimitProperties {
        if (perMinute <= 0) {
            perMinute = 20;
        }
        if (perDay <= 0) {
            perDay = 300;
        }
    }
}
