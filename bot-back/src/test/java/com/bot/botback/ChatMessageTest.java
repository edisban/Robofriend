package com.bot.botback;

import com.bot.botback.model.ChatMessage;
import com.bot.botback.model.Sender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatMessageTest {

    @Test
    void chatMessageStoresUserAndRoleContent() {
        ChatMessage message = new ChatMessage();
        message.setUserId(42L);
        message.setSender(Sender.USER);
        message.setContent("hello");

        assertEquals(42L, message.getUserId());
        assertEquals(Sender.USER, message.getSender());
        assertEquals("hello", message.getContent());
    }
}
