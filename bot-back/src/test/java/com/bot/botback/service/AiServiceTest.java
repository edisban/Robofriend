package com.bot.botback.service;

import com.bot.botback.repository.ChatMessageRepository;
import com.bot.botback.repository.ReminderRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiServiceTest {

    @Test
    void fallbackReplyWhenApiKeyMissing() {
        MemoryService memoryService = Mockito.mock(MemoryService.class);
        ReminderRepository reminderRepo = Mockito.mock(ReminderRepository.class);
        ChatMessageRepository chatRepo = Mockito.mock(ChatMessageRepository.class);
        ListService listService = Mockito.mock(ListService.class);
        Mockito.when(memoryService.searchMemories(Mockito.anyLong(), Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(List.of());

        AiService service = new AiService(memoryService,
                new GroqClient("", "test-model"),
                new PromptBuilder(memoryService, reminderRepo, chatRepo, listService),
                new ReplyExtraction(memoryService, reminderRepo, listService),
                reminderRepo);

        String reply = service.generateReply(1L, "Hello there");

        assertTrue(reply.contains("I'm not sure how to help") || reply.contains("Here's what I know about you"));
    }
}
