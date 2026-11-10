package com.bot.botback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Sign-up form. Stricter than login, which must keep accepting existing accounts. */
@Getter
@Setter
public class RegisterRequest {

    @NotBlank(message = "Please choose a username.")
    @Size(min = 3, max = 30, message = "Username must be 3-30 characters.")
    @Pattern(regexp = "[A-Za-z0-9_.-]+", message = "Username can only use letters, numbers and _ . -")
    private String username;

    /** BCrypt only uses the first 72 bytes of a password. */
    @NotBlank(message = "Please choose a password.")
    @Size(min = 8, max = 128, message = "Password must be 8-128 characters.")
    private String password;
}
