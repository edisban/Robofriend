package com.bot.botback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReminderStatusRequest {

    @NotBlank(message = "Status is required.")
    @Pattern(regexp = "(?i)PENDING|SENT|DONE", message = "Invalid reminder status.")
    private String status;
}
