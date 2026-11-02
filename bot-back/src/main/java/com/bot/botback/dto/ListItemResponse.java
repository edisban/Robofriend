package com.bot.botback.dto;

import com.bot.botback.model.ListItem;

import java.time.OffsetDateTime;

public record ListItemResponse(Long id, String listName, String text, boolean done,
                               OffsetDateTime createdAt, OffsetDateTime doneAt) {

    public static ListItemResponse from(ListItem item) {
        return new ListItemResponse(item.getId(), item.getListName(), item.getText(), item.isDone(),
                item.getCreatedAt(), item.getDoneAt());
    }
}
