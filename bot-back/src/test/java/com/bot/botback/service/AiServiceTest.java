package com.bot.botback.service;

import com.bot.botback.config.GroqProperties;
import com.bot.botback.repository.ChatMessageRepository;
import com.bot.botback.repository.ReminderRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiServiceTest {

    @Test
    void saysSoWhenTheAiIsNotConfigured() {
        MemoryService memoryService = Mockito.mock(MemoryService.class);
        ReminderRepository reminderRepo = Mockito.mock(ReminderRepository.class);
        ChatMessageRepository chatRepo = Mockito.mock(ChatMessageRepository.class);
        ListService listService = Mockito.mock(ListService.class);

        AiService service = new AiService(memoryService,
                new GroqClient(new GroqProperties("", "test-model")),
                new PromptBuilder(memoryService, reminderRepo, chatRepo, listService,
                        Mockito.mock(com.bot.botback.repository.UserRepository.class)),
                new ReplyExtraction(memoryService, reminderRepo, listService));

        String reply = service.generateReply(1L, "Hello there", ZoneOffset.UTC, token -> { }, true);

        assertTrue(reply.contains("can't reach my AI"));
    }
}
