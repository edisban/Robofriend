package com.bot.botback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Python memory service ({@code memory.service.*}).
 *
 * @param url   base URL (MEMORY_SERVICE_URL)
 * @param token shared secret sent as X-Service-Token (MEMORY_SERVICE_TOKEN)
 */
@ConfigurationProperties("memory.service")
public record MemoryServiceProperties(String url, String token) {
}
