package com.bot.botback.dto;

import com.bot.botback.model.User;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** The signed-in user's profile, as the profile page shows it. */
public record ProfileResponse(String username, String displayName, LocalDate birthday, OffsetDateTime memberSince) {

    public static ProfileResponse of(User user) {
        return new ProfileResponse(user.getUsername(), user.getDisplayName(), user.getBirthday(), user.getCreatedAt());
    }
}
