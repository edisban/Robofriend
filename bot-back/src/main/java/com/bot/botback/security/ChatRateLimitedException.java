package com.bot.botback.security;

/** Thrown when a user asks for more AI replies than their limit allows; answered with 429. */
public class ChatRateLimitedException extends RuntimeException {

    private final ChatRateLimiter.Blocked blocked;

    public ChatRateLimitedException(ChatRateLimiter.Blocked blocked) {
        super("Chat rate limit reached (" + blocked.limit() + ")");
        this.blocked = blocked;
    }

    public ChatRateLimiter.Blocked blocked() {
        return blocked;
    }
}
