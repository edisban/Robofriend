package com.bot.botback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Groq API ({@code groq.api.*}).
 *
 * @param key   API key (GROQ_API_KEY); without it RoboFriend answers with local fallbacks
 * @param model chat model (GROQ_API_MODEL)
 */
@ConfigurationProperties("groq.api")
public record GroqProperties(String key, String model) {

    public GroqProperties {
        if (model == null || model.isBlank()) {
            model = "openai/gpt-oss-120b";
        }
    }

    public boolean configured() {
        return key != null && !key.isBlank();
    }
}
