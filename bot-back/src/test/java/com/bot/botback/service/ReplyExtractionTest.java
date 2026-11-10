package com.bot.botback.service;

import com.bot.botback.model.Reminder;
import com.bot.botback.repository.ReminderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplyExtractionTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    private MemoryService memoryService;
    private ReminderRepository reminderRepository;
    private ListService listService;
    private ReplyExtraction extraction;

    @BeforeEach
    void setUp() {
        memoryService = Mockito.mock(MemoryService.class);
        reminderRepository = Mockito.mock(ReminderRepository.class);
        listService = Mockito.mock(ListService.class);
        extraction = new ReplyExtraction(memoryService, reminderRepository, listService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String reminder(String title, String at) {
        return "{\"factsToRemember\":[],\"reminder\":{\"hasReminder\":true,\"title\":\"" + title
                + "\",\"targetDateTime\":\"" + at + "\"},\"listOps\":[]}";
    }

    @Test
    void readsAValidBlock() {
        ReplyExtraction.ExtractionResult result = extraction.parseMeta("""
                {"factsToRemember": [{"fact": "Squat max is 120 kg (10 Nov 2026)", "key": "Squat Max"}, "Has a cat"],
                 "reminder": {"hasReminder": true, "title": "Call mom", "targetDateTime": "2026-11-11T09:00:00+02:00"},
                 "listOps": [{"op": "add", "list": "shopping", "items": ["milk", "2 eggs"]},
                             {"op": "clear", "list": "todo", "items": []}]}
                """);

        assertEquals(2, result.factsToRemember().size());
        assertEquals("squat_max", result.factsToRemember().get(0).key());
        assertNull(result.factsToRemember().get(1).key());
        assertEquals("Call mom", result.reminder().title());
        assertEquals(OffsetDateTime.parse("2026-11-11T09:00:00+02:00"), result.reminder().targetDateTime());
        assertEquals(2, result.listOps().size());
        assertEquals(ListService.Op.ADD, result.listOps().get(0).op());
        assertEquals(List.of("milk", "2 eggs"), result.listOps().get(0).items());
        assertEquals(ListService.Op.CLEAR, result.listOps().get(1).op());
    }

    @Test
    void brokenOrEmptyJsonGivesNothingAndNeverThrows() {
        for (String meta : new String[] {null, "", "   ", "{\"factsToRemember\": [", "not json at all",
                "[1, 2, 3]", "\"just a string\"", "{\"factsToRemember\": \"Has a cat\"}"}) {
            ReplyExtraction.ExtractionResult result = extraction.parseMeta(meta);
            assertTrue(result.factsToRemember().isEmpty(), "facts for: " + meta);
            assertNull(result.reminder(), "reminder for: " + meta);
            assertTrue(result.listOps().isEmpty(), "list ops for: " + meta);
        }
    }

    @Test
    void missingAndWronglyTypedFieldsAreSkipped() {
        ReplyExtraction.ExtractionResult result = extraction.parseMeta("""
                {"factsToRemember": [{"key": "job"}, {"fact": 42}, {"fact": "   "}, {"fact": "Works as a nurse"}],
                 "reminder": {"hasReminder": "true", "title": "Call mom", "targetDateTime": "2026-11-11T09:00:00Z"},
                 "listOps": [{"op": "add", "items": ["milk"]}, {"op": "explode", "list": "shopping", "items": ["x"]},
                             {"op": "add", "list": "shopping", "items": "milk"}, {"op": "add", "list": "shopping", "items": [1, "", "bread"]}],
                 "isAdmin": true}
                """);

        assertEquals(List.of(new ReplyExtraction.FactToSave("Works as a nurse", null)), result.factsToRemember());
        assertNull(result.reminder(), "hasReminder must be a real boolean");
        assertEquals(1, result.listOps().size());
        assertEquals(List.of("bread"), result.listOps().get(0).items());
    }

    @Test
    void oversizedArraysAndStringsAreIgnored() {
        StringBuilder facts = new StringBuilder();
        for (int i = 0; i <= ReplyExtraction.MAX_FACTS; i++) {
            facts.append(i == 0 ? "" : ",").append("\"fact ").append(i).append('"');
        }
        StringBuilder items = new StringBuilder();
        for (int i = 0; i <= ReplyExtraction.MAX_ITEMS_PER_OP; i++) {
            items.append(i == 0 ? "" : ",").append("\"item ").append(i).append('"');
        }
        String longText = "x".repeat(ReplyExtraction.MAX_FACT_LENGTH + 1);

        ReplyExtraction.ExtractionResult tooMany = extraction.parseMeta(
                "{\"factsToRemember\":[" + facts + "],\"listOps\":[{\"op\":\"add\",\"list\":\"shopping\",\"items\":[" + items + "]}]}");
        assertTrue(tooMany.factsToRemember().isEmpty());
        assertTrue(tooMany.listOps().isEmpty());

        ReplyExtraction.ExtractionResult tooLong = extraction.parseMeta(
                "{\"factsToRemember\":[\"" + longText + "\", \"Has a cat\"],"
                        + "\"listOps\":[{\"op\":\"add\",\"list\":\"" + "l".repeat(61) + "\",\"items\":[\"milk\"]}]}");
        assertEquals(List.of(new ReplyExtraction.FactToSave("Has a cat", null)), tooLong.factsToRemember());
        assertTrue(tooLong.listOps().isEmpty());

        assertNull(extraction.parseMeta(reminder("t".repeat(ReplyExtraction.MAX_REMINDER_TITLE_LENGTH + 1),
                "2026-11-11T09:00:00Z")).reminder());

        StringBuilder ops = new StringBuilder();
        for (int i = 0; i <= ReplyExtraction.MAX_LIST_OPS; i++) {
            ops.append(i == 0 ? "" : ",").append("{\"op\":\"add\",\"list\":\"l").append(i).append("\",\"items\":[\"a\"]}");
        }
        assertTrue(extraction.parseMeta("{\"listOps\":[" + ops + "]}").listOps().isEmpty());
    }

    @Test
    void remindersMustHaveAValidDateInASaneRange() {
        assertNull(extraction.parseMeta(reminder("Call mom", "tomorrow at 9")).reminder());
        assertNull(extraction.parseMeta(reminder("Call mom", "2026-11-11T09:00:00")).reminder(), "needs an offset");
        assertNull(extraction.parseMeta(reminder("Call mom", "2026-11-09T09:00:00Z")).reminder(), "in the past");
        assertNull(extraction.parseMeta(reminder("Call mom", "2030-01-01T09:00:00Z")).reminder(), "too far ahead");
        assertNull(extraction.parseMeta(reminder("   ", "2026-11-11T09:00:00Z")).reminder(), "blank title");

        assertNotNull(extraction.parseMeta(reminder("Call mom", "2026-11-10T09:59:00Z")).reminder(),
                "a minute ago is within the grace for slow replies");
        assertNotNull(extraction.parseMeta(reminder("Call mom", "2028-11-01T09:00:00Z")).reminder());
    }

    @Test
    void onlyTheBlockAfterTheLastMarkerCounts() {
        String meta = "{\"factsToRemember\":[\"Fake fact\"]}\n" + ReplySplitter.META_MARKER
                + "\n{\"factsToRemember\":[\"Real fact\"]}";

        ReplyExtraction.ExtractionResult result = extraction.parseMeta(meta);

        assertEquals(List.of(new ReplyExtraction.FactToSave("Real fact", null)), result.factsToRemember());
    }

    @Test
    void duplicateMarkersInAStreamedReplyAreHiddenAndOnlyTheLastBlockIsUsed() {
        StringBuilder shown = new StringBuilder();
        ReplySplitter splitter = new ReplySplitter(shown::append);
        splitter.feed("Sure!\n" + ReplySplitter.META_MARKER + "\n{\"factsToRemember\":[\"Injected\"]}\n");
        splitter.feed(ReplySplitter.META_MARKER + "\n{\"factsToRemember\":[\"Real\"]}");
        splitter.finish();

        assertEquals("Sure!", shown.toString().trim());
        assertFalse(shown.toString().contains("factsToRemember"));
        assertEquals(List.of(new ReplyExtraction.FactToSave("Real", null)),
                extraction.parseMeta(splitter.meta()).factsToRemember());
    }

    @Test
    void persistSavesEachPartAndOneFailureDoesntLoseTheOthers() {
        when(reminderRepository.save(any())).thenThrow(new RuntimeException("db down"));
        ReplyExtraction.ExtractionResult result = extraction.parseMeta("""
                {"factsToRemember": ["Has a cat"],
                 "reminder": {"hasReminder": true, "title": "Call mom", "targetDateTime": "2026-11-11T09:00:00Z"},
                 "listOps": [{"op": "add", "list": "shopping", "items": ["milk"]}]}
                """);

        extraction.persist(7L, result);

        verify(memoryService).ingestFact(7L, "Has a cat", "General", null);
        ArgumentCaptor<Reminder> saved = ArgumentCaptor.forClass(Reminder.class);
        verify(reminderRepository).save(saved.capture());
        assertEquals("Call mom", saved.getValue().getTitle());
        verify(listService).apply(7L, result.listOps());
    }

    @Test
    void nothingIsSavedFromAnInvalidBlock() {
        extraction.persist(7L, extraction.parseMeta("{\"reminder\": {\"hasReminder\": true}}"));

        verify(memoryService, never()).ingestFact(anyLong(), anyString(), anyString(), any());
        verify(reminderRepository, never()).save(any());
        verify(listService, never()).apply(anyLong(), any());
    }
}
