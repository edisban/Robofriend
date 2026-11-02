package com.bot.botback.service;

import com.bot.botback.model.Reminder;
import com.bot.botback.repository.ReminderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Turns the hidden metadata in a reply into saved facts, reminders and list changes. */
@Component
public class ReplyExtraction {

    private static final Logger log = LoggerFactory.getLogger(ReplyExtraction.class);

    private final MemoryService memoryService;
    private final ReminderRepository reminderRepository;
    private final ListService listService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReplyExtraction(MemoryService memoryService, ReminderRepository reminderRepository,
                           ListService listService) {
        this.memoryService = memoryService;
        this.reminderRepository = reminderRepository;
        this.listService = listService;
    }

    /** Reads the facts, reminder and list changes from the hidden part of a reply. */
    ExtractionResult parseMeta(String metaText) {
        List<FactToSave> facts = new ArrayList<>();
        ReminderInfo reminder = new ReminderInfo(false, null, null);
        List<ListService.ListOp> listOps = new ArrayList<>();
        if (metaText == null || metaText.isBlank()) {
            return new ExtractionResult(facts, reminder, listOps);
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(metaText));
            for (JsonNode factNode : root.path("factsToRemember")) {
                if (factNode.isTextual()) {
                    facts.add(new FactToSave(factNode.asText(), null));
                } else if (factNode.path("fact").isTextual()) {
                    String key = factNode.path("key").isTextual() ? normalizeKey(factNode.get("key").asText()) : null;
                    facts.add(new FactToSave(factNode.get("fact").asText(), key));
                }
            }
            JsonNode reminderNode = root.path("reminder");
            if (!reminderNode.isMissingNode()) {
                boolean hasReminder = reminderNode.path("hasReminder").asBoolean(false);
                String title = reminderNode.path("title").isTextual() ? reminderNode.get("title").asText() : null;
                String target = reminderNode.path("targetDateTime").isTextual()
                        ? reminderNode.get("targetDateTime").asText() : null;
                reminder = new ReminderInfo(hasReminder, title, target);
            }
            for (JsonNode opNode : root.path("listOps")) {
                ListService.ListOp op = parseListOp(opNode);
                if (op != null) {
                    listOps.add(op);
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse facts/reminder from model output: {}", e.getMessage());
        }
        return new ExtractionResult(facts, reminder, listOps);
    }

    static ListService.ListOp parseListOp(JsonNode node) {
        ListService.Op op;
        try {
            op = ListService.Op.valueOf(node.path("op").asText("").trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String list = node.path("list").asText(null);
        List<String> items = new ArrayList<>();
        for (JsonNode item : node.path("items")) {
            if (item.isTextual() && !item.asText().isBlank()) {
                items.add(item.asText());
            }
        }
        if (list == null || (items.isEmpty() && op != ListService.Op.CLEAR)) {
            return null;
        }
        return new ListService.ListOp(op, list, items);
    }

    private String extractJson(String text) {
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    /** Saves what was extracted; each part fails on its own without losing the others. */
    void persist(Long userId, ExtractionResult result) {
        try {
            saveFacts(userId, result.factsToRemember());
        } catch (Exception e) {
            log.error("Failed to persist extracted facts: {}", e.getMessage());
        }
        try {
            saveReminderIfPresent(userId, result.reminder());
        } catch (Exception e) {
            log.error("Failed to persist extracted reminder: {}", e.getMessage());
        }
        if (!result.listOps().isEmpty()) {
            try {
                listService.apply(userId, result.listOps());
            } catch (Exception e) {
                log.error("Failed to update lists for user {}: {}", userId, e.getMessage());
            }
        }
    }

    private void saveFacts(Long userId, List<FactToSave> facts) {
        for (FactToSave fact : facts) {
            if (fact.fact() == null || fact.fact().isBlank()) {
                continue;
            }
            memoryService.ingestFact(userId, fact.fact(), "General", fact.key());
            log.info("Saved fact for user {}{}: {}", userId,
                    fact.key() == null ? "" : " [" + fact.key() + "]", fact.fact());
        }
    }

    static String normalizeKey(String raw) {
        String key = raw.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (key.isEmpty()) {
            return null;
        }
        return key.length() > 60 ? key.substring(0, 60) : key;
    }

    private void saveReminderIfPresent(Long userId, ReminderInfo reminder) {
        if (reminder == null || !reminder.hasReminder() || reminder.targetDateTime() == null
                || reminder.title() == null || reminder.title().isBlank()) {
            return;
        }
        try {
            OffsetDateTime scheduledAt = OffsetDateTime.parse(reminder.targetDateTime());
            Reminder entity = new Reminder();
            entity.setUserId(userId);
            entity.setTitle(reminder.title());
            entity.setScheduledAt(scheduledAt);
            entity.setStatus("PENDING");
            entity.setNotes("Auto-extracted from conversation");
            reminderRepository.save(entity);
            log.info("Saved reminder for user {}: {} at {}", userId, reminder.title(), scheduledAt);
        } catch (Exception e) {
            log.error("Failed to save reminder: {}", e.getMessage());
        }
    }

    record FactToSave(String fact, String key) {
    }

    record ExtractionResult(List<FactToSave> factsToRemember, ReminderInfo reminder,
                            List<ListService.ListOp> listOps) {
    }

    record ReminderInfo(boolean hasReminder, String title, String targetDateTime) {
    }
}
