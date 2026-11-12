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
 * Turns what the model extracted from a chat turn into saved facts, reminders and list changes.
 *
 * <p>The extraction is a separate model call whose answer Groq forces into {@link #SCHEMA}. It is
 * still model output, and the model can be talked into writing anything, so it is treated as
 * untrusted input: each field is checked for its type, size and (for reminders) a sane date, and
 * anything that doesn't fit is dropped. Nothing here ever throws into the chat flow.
 */
@Component
public class ReplyExtraction {

    private static final Logger log = LoggerFactory.getLogger(ReplyExtraction.class);

    static final int MAX_FACTS = 5;
    static final int MAX_FACT_LENGTH = 300;
    static final int MAX_REMINDERS = 3;
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

    /** The shape the extraction answer must have. Strict mode needs every field listed as required. */
    static final JsonNode SCHEMA = readSchema("""
            {"type": "object", "additionalProperties": false,
             "required": ["facts", "reminders", "listOps"],
             "properties": {
               "facts": {"type": "array", "items": {"type": "object", "additionalProperties": false,
                 "required": ["fact", "key"],
                 "properties": {"fact": {"type": "string"}, "key": {"type": ["string", "null"]}}}},
               "reminders": {"type": "array", "items": {"type": "object", "additionalProperties": false,
                 "required": ["title", "targetDateTime"],
                 "properties": {"title": {"type": "string"}, "targetDateTime": {"type": "string"}}}},
               "listOps": {"type": "array", "items": {"type": "object", "additionalProperties": false,
                 "required": ["op", "list", "items"],
                 "properties": {"op": {"type": "string", "enum": ["add", "done", "remove", "clear"]},
                                "list": {"type": "string"},
                                "items": {"type": "array", "items": {"type": "string"}}}}}}}
            """);

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

    /** Reads the facts, reminders and list changes from the extraction answer. */
    ExtractionResult parse(String json) {
        if (json == null || json.isBlank()) {
            return ExtractionResult.EMPTY;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("Could not parse the extraction answer: {}", e.getMessage());
            return ExtractionResult.EMPTY;
        }
        if (root == null || !root.isObject()) {
            log.warn("Extraction answer is not a JSON object; ignoring it");
            return ExtractionResult.EMPTY;
        }
        return new ExtractionResult(parseFacts(root.path("facts")),
                parseReminders(root.path("reminders")),
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

    private List<ReminderInfo> parseReminders(JsonNode node) {
        List<ReminderInfo> reminders = new ArrayList<>();
        if (!node.isArray()) {
            return reminders;
        }
        if (node.size() > MAX_REMINDERS) {
            log.warn("Model sent {} reminders (max {}); ignoring them", node.size(), MAX_REMINDERS);
            return reminders;
        }
        for (JsonNode reminderNode : node) {
            ReminderInfo reminder = parseReminder(reminderNode);
            if (reminder != null) {
                reminders.add(reminder);
            }
        }
        return reminders;
    }

    private ReminderInfo parseReminder(JsonNode node) {
        if (!node.isObject() || !node.path("title").isTextual() || !node.path("targetDateTime").isTextual()) {
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

    /** Saves what was extracted; each part fails on its own without losing the others. */
    void persist(Long userId, ExtractionResult result) {
        try {
            saveFacts(userId, result.facts());
        } catch (Exception e) {
            log.error("Failed to persist extracted facts: {}", e.getMessage());
        }
        for (ReminderInfo reminder : result.reminders()) {
            try {
                saveReminder(userId, reminder);
            } catch (Exception e) {
                log.error("Failed to persist extracted reminder: {}", e.getMessage());
            }
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
            memoryService.ingestFact(userId, fact.fact(), fact.key());
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

    private void saveReminder(Long userId, ReminderInfo reminder) {
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

    /** What to save from one chat turn; only entries that passed every check. */
    record ExtractionResult(List<FactToSave> facts, List<ReminderInfo> reminders,
                            List<ListService.ListOp> listOps) {
        static final ExtractionResult EMPTY = new ExtractionResult(List.of(), List.of(), List.of());
    }

    private static JsonNode readSchema(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid extraction schema", e);
        }
    }

    record ReminderInfo(String title, OffsetDateTime targetDateTime) {
    }
}
