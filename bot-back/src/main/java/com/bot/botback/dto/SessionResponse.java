package com.bot.botback.dto;

import com.bot.botback.model.User;

/** Who is signed in. The tokens themselves only ever travel in HttpOnly cookies. */
public record SessionResponse(Long userId, String username) {

    public static SessionResponse of(User user) {
        return new SessionResponse(user.getId(), user.getUsername());
    }
}
