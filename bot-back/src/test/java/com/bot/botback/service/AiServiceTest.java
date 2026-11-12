package com.bot.botback.service;

import com.bot.botback.config.GroqProperties;
import com.bot.botback.repository.ChatMessageRepository;
import com.bot.botback.repository.ReminderRepository;
import com.bot.botback.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiServiceTest {

    private MemoryService memoryService;
    private ListService listService;
    private PromptBuilder promptBuilder;
    private ReplyExtraction extraction;

    @BeforeEach
    void setUp() {
        memoryService = Mockito.mock(MemoryService.class);
        ReminderRepository reminderRepo = Mockito.mock(ReminderRepository.class);
        listService = Mockito.mock(ListService.class);
        promptBuilder = new PromptBuilder(memoryService, reminderRepo, Mockito.mock(ChatMessageRepository.class),
                listService, Mockito.mock(UserRepository.class));
        extraction = new ReplyExtraction(memoryService, reminderRepo, listService);
    }

    @Test
    void saysSoWhenTheAiIsNotConfigured() {
        AiService service = new AiService(memoryService, new GroqClient(new GroqProperties("", "test-model")),
                promptBuilder, extraction);

        AiService.Reply reply = service.generateReply(1L, "Hello there", ZoneOffset.UTC, token -> { });

        assertTrue(reply.text().contains("can't reach my AI"));
        assertFalse(reply.fromModel(), "a fallback message isn't read for things to save");
    }

    @Test
    void rememberTurnSavesWhatTheExtractionCallReturns() throws Exception {
        GroqClient groq = Mockito.mock(GroqClient.class);
        when(groq.isConfigured()).thenReturn(true);
        when(groq.completeJson(anyList(), anyString(), any())).thenReturn("""
                {"facts": [{"fact": "Works as a nurse", "key": "job"}], "reminders": [],
                 "listOps": [{"op": "add", "list": "shopping", "items": ["milk"]}]}
                """);
        AiService service = new AiService(memoryService, groq, promptBuilder, extraction);

        service.rememberTurn(7L, "I'm a nurse, and put milk on the list", "Done!", ZoneOffset.UTC);

        verify(groq).completeJson(anyList(), eq("chat_turn_extraction"), eq(ReplyExtraction.SCHEMA));
        verify(memoryService).ingestFact(7L, "Works as a nurse", "job");
        verify(listService).apply(eq(7L), anyList());
    }

    @Test
    void aFailedExtractionCallSavesNothingAndDoesntThrow() throws Exception {
        GroqClient groq = Mockito.mock(GroqClient.class);
        when(groq.isConfigured()).thenReturn(true);
        when(groq.completeJson(anyList(), anyString(), any()))
                .thenThrow(new java.io.IOException("connection reset"));
        AiService service = new AiService(memoryService, groq, promptBuilder, extraction);

        assertDoesNotThrow(() -> service.rememberTurn(7L, "I'm a nurse", "Nice!", ZoneOffset.UTC));
        verify(memoryService, never()).ingestFact(anyLong(), anyString(), any());
    }
}
