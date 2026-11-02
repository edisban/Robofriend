package com.bot.botback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest {

    @NotBlank(message = "Please enter your username.")
    @Size(max = 100, message = "Wrong username or password.")
    private String username;

    @NotBlank(message = "Please enter your password.")
    @Size(max = 200, message = "Wrong username or password.")
    private String password;
}
