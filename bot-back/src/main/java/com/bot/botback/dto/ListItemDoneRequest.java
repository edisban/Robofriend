package com.bot.botback.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ListItemDoneRequest {

    @NotNull(message = "done is required.")
    private Boolean done;
}
