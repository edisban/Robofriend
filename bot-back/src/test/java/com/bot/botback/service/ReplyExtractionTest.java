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
        return "{\"facts\":[],\"reminders\":[{\"title\":\"" + title
                + "\",\"targetDateTime\":\"" + at + "\"}],\"listOps\":[]}";
    }

    private List<ReplyExtraction.ReminderInfo> reminders(String title, String at) {
        return extraction.parse(reminder(title, at)).reminders();
    }

    @Test
    void readsAValidBlock() {
        ReplyExtraction.ExtractionResult result = extraction.parse("""
                {"facts": [{"fact": "Squat max is 120 kg (10 Nov 2026)", "key": "Squat Max"},
                           {"fact": "Has a cat", "key": null}],
                 "reminders": [{"title": "Call mom", "targetDateTime": "2026-11-11T09:00:00+02:00"},
                               {"title": "Dentist", "targetDateTime": "2026-11-12T17:30:00+02:00"}],
                 "listOps": [{"op": "add", "list": "shopping", "items": ["milk", "2 eggs"]},
                             {"op": "clear", "list": "todo", "items": []}]}
                """);

        assertEquals(2, result.facts().size());
        assertEquals("squat_max", result.facts().get(0).key());
        assertNull(result.facts().get(1).key());
        assertEquals(2, result.reminders().size());
        assertEquals("Call mom", result.reminders().get(0).title());
        assertEquals(OffsetDateTime.parse("2026-11-11T09:00:00+02:00"), result.reminders().get(0).targetDateTime());
        assertEquals(2, result.listOps().size());
        assertEquals(ListService.Op.ADD, result.listOps().get(0).op());
        assertEquals(List.of("milk", "2 eggs"), result.listOps().get(0).items());
        assertEquals(ListService.Op.CLEAR, result.listOps().get(1).op());
    }

    @Test
    void brokenOrEmptyJsonGivesNothingAndNeverThrows() {
        for (String json : new String[] {null, "", "   ", "{\"facts\": [", "not json at all",
                "[1, 2, 3]", "\"just a string\"", "{\"facts\": \"Has a cat\", \"reminders\": {}}"}) {
            ReplyExtraction.ExtractionResult result = extraction.parse(json);
            assertTrue(result.facts().isEmpty(), "facts for: " + json);
            assertTrue(result.reminders().isEmpty(), "reminders for: " + json);
            assertTrue(result.listOps().isEmpty(), "list ops for: " + json);
        }
    }

    @Test
    void missingAndWronglyTypedFieldsAreSkipped() {
        ReplyExtraction.ExtractionResult result = extraction.parse("""
                {"facts": [{"key": "job"}, {"fact": 42}, {"fact": "   "}, {"fact": "Works as a nurse"}],
                 "reminders": [{"title": 7, "targetDateTime": "2026-11-11T09:00:00Z"}, {"title": "Call mom"}],
                 "listOps": [{"op": "add", "items": ["milk"]}, {"op": "explode", "list": "shopping", "items": ["x"]},
                             {"op": "add", "list": "shopping", "items": "milk"}, {"op": "add", "list": "shopping", "items": [1, "", "bread"]}],
                 "isAdmin": true}
                """);

        assertEquals(List.of(new ReplyExtraction.FactToSave("Works as a nurse", null)), result.facts());
        assertTrue(result.reminders().isEmpty(), "a reminder needs a text title and a date");
        assertEquals(1, result.listOps().size());
        assertEquals(List.of("bread"), result.listOps().get(0).items());
    }

    @Test
    void oversizedArraysAndStringsAreIgnored() {
        StringBuilder facts = new StringBuilder();
        for (int i = 0; i <= ReplyExtraction.MAX_FACTS; i++) {
            facts.append(i == 0 ? "" : ",").append("{\"fact\":\"fact ").append(i).append("\",\"key\":null}");
        }
        StringBuilder items = new StringBuilder();
        for (int i = 0; i <= ReplyExtraction.MAX_ITEMS_PER_OP; i++) {
            items.append(i == 0 ? "" : ",").append("\"item ").append(i).append('"');
        }
        String longText = "x".repeat(ReplyExtraction.MAX_FACT_LENGTH + 1);

        ReplyExtraction.ExtractionResult tooMany = extraction.parse(
                "{\"facts\":[" + facts + "],\"listOps\":[{\"op\":\"add\",\"list\":\"shopping\",\"items\":[" + items + "]}]}");
        assertTrue(tooMany.facts().isEmpty());
        assertTrue(tooMany.listOps().isEmpty());

        ReplyExtraction.ExtractionResult tooLong = extraction.parse(
                "{\"facts\":[{\"fact\":\"" + longText + "\"}, {\"fact\":\"Has a cat\"}],"
                        + "\"listOps\":[{\"op\":\"add\",\"list\":\"" + "l".repeat(61) + "\",\"items\":[\"milk\"]}]}");
        assertEquals(List.of(new ReplyExtraction.FactToSave("Has a cat", null)), tooLong.facts());
        assertTrue(tooLong.listOps().isEmpty());

        assertTrue(reminders("t".repeat(ReplyExtraction.MAX_REMINDER_TITLE_LENGTH + 1),
                "2026-11-11T09:00:00Z").isEmpty());

        StringBuilder manyReminders = new StringBuilder();
        for (int i = 0; i <= ReplyExtraction.MAX_REMINDERS; i++) {
            manyReminders.append(i == 0 ? "" : ",").append("{\"title\":\"r").append(i)
                    .append("\",\"targetDateTime\":\"2026-11-11T09:00:00Z\"}");
        }
        assertTrue(extraction.parse("{\"reminders\":[" + manyReminders + "]}").reminders().isEmpty());

        StringBuilder ops = new StringBuilder();
        for (int i = 0; i <= ReplyExtraction.MAX_LIST_OPS; i++) {
            ops.append(i == 0 ? "" : ",").append("{\"op\":\"add\",\"list\":\"l").append(i).append("\",\"items\":[\"a\"]}");
        }
        assertTrue(extraction.parse("{\"listOps\":[" + ops + "]}").listOps().isEmpty());
    }

    @Test
    void remindersMustHaveAValidDateInASaneRange() {
        assertTrue(reminders("Call mom", "tomorrow at 9").isEmpty());
        assertTrue(reminders("Call mom", "2026-11-11T09:00:00").isEmpty(), "needs an offset");
        assertTrue(reminders("Call mom", "2026-11-09T09:00:00Z").isEmpty(), "in the past");
        assertTrue(reminders("Call mom", "2030-01-01T09:00:00Z").isEmpty(), "too far ahead");
        assertTrue(reminders("   ", "2026-11-11T09:00:00Z").isEmpty(), "blank title");

        assertFalse(reminders("Call mom", "2026-11-10T09:59:00Z").isEmpty(),
                "a minute ago is within the grace for slow replies");
        assertFalse(reminders("Call mom", "2028-11-01T09:00:00Z").isEmpty());
    }

    @Test
    void persistSavesEachPartAndOneFailureDoesntLoseTheOthers() {
        when(reminderRepository.save(any())).thenThrow(new RuntimeException("db down")).thenReturn(null);
        ReplyExtraction.ExtractionResult result = extraction.parse("""
                {"facts": [{"fact": "Has a cat", "key": null}],
                 "reminders": [{"title": "Call mom", "targetDateTime": "2026-11-11T09:00:00Z"},
                               {"title": "Dentist", "targetDateTime": "2026-11-11T17:00:00Z"}],
                 "listOps": [{"op": "add", "list": "shopping", "items": ["milk"]}]}
                """);

        extraction.persist(7L, result);

        verify(memoryService).ingestFact(7L, "Has a cat", null);
        ArgumentCaptor<Reminder> saved = ArgumentCaptor.forClass(Reminder.class);
        verify(reminderRepository, Mockito.times(2)).save(saved.capture());
        assertEquals("Dentist", saved.getAllValues().get(1).getTitle(), "one failed reminder doesn't stop the next");
        verify(listService).apply(7L, result.listOps());
    }

    @Test
    void nothingIsSavedFromAnInvalidBlock() {
        extraction.persist(7L, extraction.parse("{\"reminders\": [{\"title\": \"Call mom\"}]}"));

        verify(memoryService, never()).ingestFact(anyLong(), anyString(), any());
        verify(reminderRepository, never()).save(any());
        verify(listService, never()).apply(anyLong(), any());
    }
}
