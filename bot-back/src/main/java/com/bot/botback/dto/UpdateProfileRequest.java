package com.bot.botback.dto;

import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * New profile details; an empty name or a null birthday clears them.
 *
 * @param displayName what RoboFriend should call them (one line, at most 50 characters)
 * @param birthday    their date of birth (yyyy-MM-dd), not in the future
 */
public record UpdateProfileRequest(
        @Size(max = 50, message = "Your name can be at most 50 characters.")
        @Pattern(regexp = "[^\\r\\n]*", message = "Your name must be on one line.")
        String displayName,
        @PastOrPresent(message = "Your birthday can't be in the future.")
        LocalDate birthday) {
}
