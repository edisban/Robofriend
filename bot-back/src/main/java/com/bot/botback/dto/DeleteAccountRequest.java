package com.bot.botback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Deleting the account needs the password again, so an unattended open session can't do it. */
public record DeleteAccountRequest(
        @NotBlank(message = "Enter your password to delete your account.")
        @Size(max = 200, message = "Wrong password.")
        String password) {
}
