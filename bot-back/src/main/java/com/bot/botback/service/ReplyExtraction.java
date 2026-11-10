package com.bot.botback.service;

import com.bot.botback.model.Reminder;
import com.bot.botback.model.ReminderStatus;
import com.bot.botback.repository.ReminderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the hidden metadata in a reply into saved facts, reminders and list changes.
 *
 * <p>The metadata is model output, and the model can be talked into writing anything, so it is
 * treated as untrusted input: only the known fields are read, each with its expected type and a
 * size limit, and anything that doesn't fit is dropped. Nothing here ever throws into the chat flow.
 */
@Component
public class ReplyExtraction {

    private static final Logger log = LoggerFactory.getLogger(ReplyExtraction.class);

    static final int MAX_FACTS = 5;
    static final int MAX_FACT_LENGTH = 300;
    static final int MAX_REMINDER_TITLE_LENGTH = 120;
    static final int MAX_LIST_OPS = 10;
    static final int MAX_ITEMS_PER_OP = 30;
    static final int MAX_ITEM_LENGTH = 300;
    static final int MAX_LIST_NAME_LENGTH = 60;
    /** A reply takes a few seconds to write, so "in one minute" can be just past by the time it's parsed. */
    static final Duration REMINDER_PAST_GRACE = Duration.ofMinutes(2);
    static final Duration REMINDER_MAX_AHEAD = Duration.ofDays(2 * 366);

    private final MemoryService memoryService;
    private final ReminderRepository reminderRepository;
    private final ListService listService;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public ReplyExtraction(MemoryService memoryService, ReminderRepository reminderRepository,
                           ListService listService) {
        this(memoryService, reminderRepository, listService, Clock.systemUTC());
    }

    ReplyExtraction(MemoryService memoryService, ReminderRepository reminderRepository,
                    ListService listService, Clock clock) {
        this.memoryService = memoryService;
        this.reminderRepository = reminderRepository;
        this.listService = listService;
        this.clock = clock;
    }

    /**
     * Reads the facts, reminder and list changes from the hidden part of a reply. If the model wrote
     * the marker more than once, only the block after the last one counts.
     */
    ExtractionResult parseMeta(String metaText) {
        if (metaText == null || metaText.isBlank()) {
            return ExtractionResult.EMPTY;
        }
        int lastMarker = metaText.lastIndexOf(ReplySplitter.META_MARKER);
        String block = lastMarker >= 0 ? metaText.substring(lastMarker + ReplySplitter.META_MARKER.length()) : metaText;

        JsonNode root;
        try {
            root = objectMapper.readTree(extractJson(block));
        } catch (Exception e) {
            log.warn("Could not parse facts/reminder from model output: {}", e.getMessage());
            return ExtractionResult.EMPTY;
        }
        if (root == null || !root.isObject()) {
            log.warn("Model metadata is not a JSON object; ignoring it");
            return ExtractionResult.EMPTY;
        }
        return new ExtractionResult(parseFacts(root.path("factsToRemember")),
                parseReminder(root.path("reminder")),
                parseListOps(root.path("listOps")));
    }

    private List<FactToSave> parseFacts(JsonNode node) {
        List<FactToSave> facts = new ArrayList<>();
        if (!node.isArray()) {
            return facts;
        }
        if (node.size() > MAX_FACTS) {
            log.warn("Model sent {} facts (max {}); ignoring them", node.size(), MAX_FACTS);
            return facts;
        }
        for (JsonNode factNode : node) {
            String fact;
            String key = null;
            if (factNode.isTextual()) {
                fact = factNode.asText();
            } else if (factNode.isObject() && factNode.path("fact").isTextual()) {
                fact = factNode.get("fact").asText();
                if (factNode.path("key").isTextual()) {
                    key = normalizeKey(factNode.get("key").asText());
                }
            } else {
                continue;
            }
            fact = fact.strip();
            if (!fact.isEmpty() && fact.length() <= MAX_FACT_LENGTH) {
                facts.add(new FactToSave(fact, key));
            }
        }
        return facts;
    }

    private ReminderInfo parseReminder(JsonNode node) {
        if (!node.isObject() || !node.path("hasReminder").isBoolean() || !node.get("hasReminder").asBoolean()) {
            return null;
        }
        if (!node.path("title").isTextual() || !node.path("targetDateTime").isTextual()) {
            return null;
        }
        String title = node.get("title").asText().strip();
        if (title.isEmpty() || title.length() > MAX_REMINDER_TITLE_LENGTH) {
            return null;
        }
        OffsetDateTime at;
        try {
            // Needs an explicit UTC offset; a bare local time would be ambiguous.
            at = OffsetDateTime.parse(node.get("targetDateTime").asText().strip());
        } catch (DateTimeParseException e) {
            log.warn("Ignoring reminder with an invalid date");
            return null;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (at.isBefore(now.minus(REMINDER_PAST_GRACE)) || at.isAfter(now.plus(REMINDER_MAX_AHEAD))) {
            log.warn("Ignoring reminder outside the allowed time range");
            return null;
        }
        return new ReminderInfo(title, at);
    }

    private List<ListService.ListOp> parseListOps(JsonNode node) {
        List<ListService.ListOp> ops = new ArrayList<>();
        if (!node.isArray()) {
            return ops;
        }
        if (node.size() > MAX_LIST_OPS) {
            log.warn("Model sent {} list changes (max {}); ignoring them", node.size(), MAX_LIST_OPS);
            return ops;
        }
        for (JsonNode opNode : node) {
            ListService.ListOp op = parseListOp(opNode);
            if (op != null) {
                ops.add(op);
            }
        }
        return ops;
    }

    static ListService.ListOp parseListOp(JsonNode node) {
        if (!node.isObject() || !node.path("op").isTextual() || !node.path("list").isTextual()) {
            return null;
        }
        ListService.Op op;
        try {
            op = ListService.Op.valueOf(node.get("op").asText().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String list = node.get("list").asText().strip();
        if (list.isEmpty() || list.length() > MAX_LIST_NAME_LENGTH) {
            return null;
        }
        JsonNode itemsNode = node.path("items");
        if (!itemsNode.isMissingNode() && !itemsNode.isNull() && !itemsNode.isArray()) {
            return null;
        }
        if (itemsNode.size() > MAX_ITEMS_PER_OP) {
            return null;
        }
        List<String> items = new ArrayList<>();
        for (JsonNode item : itemsNode) {
            if (item.isTextual()) {
                String text = item.asText().strip();
                if (!text.isEmpty() && text.length() <= MAX_ITEM_LENGTH) {
                    items.add(text);
                }
            }
        }
        if (items.isEmpty() && op != ListService.Op.CLEAR) {
            return null;
        }
        return new ListService.ListOp(op, list, items);
    }

    private static String extractJson(String text) {
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
            memoryService.ingestFact(userId, fact.fact(), "General", fact.key());
            log.info("Saved fact for user {}{}", userId, fact.key() == null ? "" : " [" + fact.key() + "]");
            log.debug("Fact for user {}: {}", userId, fact.fact());
        }
    }

    static String normalizeKey(String raw) {
        String key = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (key.isEmpty()) {
            return null;
        }
        return key.length() > 60 ? key.substring(0, 60) : key;
    }

    private void saveReminderIfPresent(Long userId, ReminderInfo reminder) {
        if (reminder == null) {
            return;
        }
        Reminder entity = new Reminder();
        entity.setUserId(userId);
        entity.setTitle(reminder.title());
        entity.setScheduledAt(reminder.targetDateTime());
        entity.setStatus(ReminderStatus.PENDING);
        entity.setNotes("Auto-extracted from conversation");
        reminderRepository.save(entity);
        log.info("Saved reminder for user {} at {}", userId, reminder.targetDateTime());
        log.debug("Reminder title for user {}: {}", userId, reminder.title());
    }

    record FactToSave(String fact, String key) {
    }

    /** What to save from one reply; {@code reminder} is null when there is no valid reminder. */
    record ExtractionResult(List<FactToSave> factsToRemember, ReminderInfo reminder,
                            List<ListService.ListOp> listOps) {
        static final ExtractionResult EMPTY = new ExtractionResult(List.of(), null, List.of());
    }

    record ReminderInfo(String title, OffsetDateTime targetDateTime) {
    }
}
