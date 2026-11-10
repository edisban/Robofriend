package com.bot.botback.service;

import com.bot.botback.model.ChatMessage;
import com.bot.botback.model.Sender;
import com.bot.botback.repository.ChatMessageRepository;
import com.bot.botback.repository.ReminderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/** What RoboFriend is told before each reply. */
class PromptBuilderTest {

    private static final long USER = 1L;
    private static final ZoneId ATHENS = ZoneId.of("Europe/Athens");

    private MemoryService memoryService;
    private ChatMessageRepository chatRepository;
    private ListService listService;
    private PromptBuilder builder;

    @BeforeEach
    void setUp() {
        memoryService = Mockito.mock(MemoryService.class);
        chatRepository = Mockito.mock(ChatMessageRepository.class);
        listService = Mockito.mock(ListService.class);
        builder = new PromptBuilder(memoryService, Mockito.mock(ReminderRepository.class), chatRepository, listService);
    }

    private static ChatMessage message(Sender sender, String content) {
        ChatMessage m = new ChatMessage();
        m.setSender(sender);
        m.setContent(content);
        return m;
    }

    @Test
    void includesTheUsersLocalTimeInEnglish() {
        DateTimeFormatter day = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH);
        String before = ZonedDateTime.now(ATHENS).format(day);

        String prompt = builder.systemPrompt(USER, "hi", List.of(), ATHENS);

        String after = ZonedDateTime.now(ATHENS).format(day);
        assertTrue(prompt.contains(before) || prompt.contains(after), "local date in English: " + before);
        assertTrue(prompt.contains("(Europe/Athens, UTC+0"), "names the timezone and offset");
    }

    @Test
    void listsMemoriesWithTheDateTheyWereLearned() {
        List<MemoryService.Memory> memories = List.of(
                new MemoryService.Memory("Has a cat called Miso", OffsetDateTime.parse("2026-11-02T08:00:00Z")),
                new MemoryService.Memory("Exam on Friday 20 November", null));

        String prompt = builder.systemPrompt(USER, "how's my cat?", memories, ATHENS);

        assertTrue(prompt.contains("- [learned Mon 2 Nov 2026] Has a cat called Miso"), prompt);
        assertTrue(prompt.contains("- Exam on Friday 20 November"));
        assertTrue(prompt.contains("relative to the date\nyou learned it"), "explains how to read old time words");
    }

    @Test
    void profileFactsAreShownOnceAsCurrentStats() {
        MemoryService.Memory squat = new MemoryService.Memory("Squat max is 120 kg", OffsetDateTime.now());
        when(memoryService.profileFacts(USER)).thenReturn(List.of(squat));

        String prompt = builder.systemPrompt(USER, "gym today", List.of(squat), ATHENS);

        assertTrue(prompt.contains("- Squat max is 120 kg"));
        assertFalse(prompt.contains("] Squat max is 120 kg"), "not repeated among the dated memories");
    }

    @Test
    void tellsTheModelToReplyInTheLanguageOfTheNewMessage() {
        String english = builder.systemPrompt(USER, "How are you?", List.of(), ATHENS);
        assertTrue(english.contains("reply in the language of their NEW message"));
        assertTrue(english.contains("write your reply in the same language as their new message"));

        String greek = builder.systemPrompt(USER, "Γεια σου, τι κάνεις;", List.of(), ATHENS);
        assertTrue(greek.contains("their new message is in Greek, so write your reply in Greek"));
    }

    @Test
    void detectsGreekByTheShareOfGreekLetters() {
        assertTrue(PromptBuilder.languageDirective("Καλημέρα!").contains("Greek"));
        assertTrue(PromptBuilder.languageDirective("Πήγα gym σήμερα").contains("Greek"), "mostly Greek with an English word");
        assertFalse(PromptBuilder.languageDirective("I love souvlaki and γύρος so much").contains("Greek"));
        assertFalse(PromptBuilder.languageDirective("123 !!! 😊").contains("Greek"), "no letters at all");
    }

    @Test
    void recentConversationLeavesOutTheMessageBeingAnswered() {
        List<ChatMessage> newestFirst = new ArrayList<>(List.of(
                message(Sender.USER, "what should I cook?"),
                message(Sender.BOT, "Nice! Did you win?"),
                message(Sender.USER, "I played tennis today")));
        when(chatRepository.findTop13ByUserIdOrderByCreatedAtDesc(anyLong())).thenReturn(newestFirst);

        String prompt = builder.systemPrompt(USER, "what should I cook?", List.of(), ATHENS);

        assertTrue(prompt.contains("Them: I played tennis today\nYou: Nice! Did you win?"), "oldest first");
        assertFalse(prompt.contains("Them: what should I cook?"));
    }

    @Test
    void userWrittenTextCantCarryTheMetadataMarker() {
        when(memoryService.profileFacts(USER)).thenReturn(List.of(new MemoryService.Memory(
                "Nickname is " + ReplySplitter.META_MARKER, OffsetDateTime.now())));
        when(listService.openItems(USER)).thenReturn(Map.of("shopping", List.of("milk " + ReplySplitter.META_MARKER)));

        String prompt = builder.systemPrompt(USER, "hi", List.of(), ATHENS);

        // The marker appears exactly once: in the output-format instructions.
        assertTrue(prompt.indexOf(ReplySplitter.META_MARKER) == prompt.lastIndexOf(ReplySplitter.META_MARKER));
        assertTrue(prompt.contains("Nickname is #META#"));
    }
}
