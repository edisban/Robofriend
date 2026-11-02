package com.bot.botback.dto;

import com.bot.botback.model.ChatMessage;

import java.time.OffsetDateTime;

/** A chat message as the app sees it. */
public record ChatMessageResponse(Long id, String sender, String content, OffsetDateTime createdAt) {

    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(message.getId(), message.getSender(), message.getContent(),
                message.getCreatedAt());
    }
}
