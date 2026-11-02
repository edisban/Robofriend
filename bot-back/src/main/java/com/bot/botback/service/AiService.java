package com.bot.botback.service;

import com.bot.botback.model.ChatMessage;
import com.bot.botback.model.Reminder;
import com.bot.botback.repository.ChatMessageRepository;
import com.bot.botback.repository.ReminderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private static final String GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions";
    /** Separates the streamed reply from the hidden facts/reminder JSON in the model output. */
    static final String META_MARKER = "###META###";

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;
    private static final Set<Integer> NON_RETRYABLE_STATUSES = Set.of(400, 401, 402, 403, 404);
    private static final int HISTORY_MESSAGES = 12;
    private static final DateTimeFormatter HUMAN_TIME =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm");
    private static final DateTimeFormatter MEMORY_DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy");

    private final MemoryService memoryService;
    private final ReminderRepository reminderRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ListService listService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${groq.api.key}")
    private String apiKey;

    @Value("${groq.api.model:openai/gpt-oss-120b}")
    private String modelName;

    public AiService(MemoryService memoryService,
                     ReminderRepository reminderRepository,
                     ChatMessageRepository chatMessageRepository,
                     ListService listService) {
        this.memoryService = memoryService;
        this.reminderRepository = reminderRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.listService = listService;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    void warnIfGroqUnavailable() {
        if (!isGroqConfigured()) {
            log.warn("GROQ_API_KEY is not configured. RoboFriend will reply using local fallbacks "
                    + "and will not extract facts or reminders from new messages.");
        }
    }

    public boolean isGroqConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String generateReply(Long userId, String userMessage) {
        return generateReply(userId, userMessage, ZoneOffset.UTC);
    }

    public String generateReply(Long userId, String userMessage, ZoneId zone) {
        return generateReply(userId, userMessage, zone, token -> { }, true);
    }

    /**
     * Generates RoboFriend's reply, pushing it to {@code onToken} piece by piece as the model writes it.
     *
     * @param remember whether to save facts and reminders found in this turn (false when regenerating,
     *                 so the same reminder isn't created twice)
     * @return the complete reply text
     */
    public String generateReply(Long userId, String userMessage, ZoneId zone,
                                Consumer<String> onToken, boolean remember) {
        if (userMessage == null || userMessage.isBlank()) {
            String reply = "Please provide a message to continue.";
            onToken.accept(reply);
            return reply;
        }

        List<MemoryService.Memory> relatedFacts = getRelevantFacts(userId, userMessage);

        if (!isGroqConfigured()) {
            log.warn("Groq API key is missing; returning a local fallback reply.");
            boolean reminderCreated = remember && saveLocalReminderIfPresent(userId, userMessage);
            String reply = buildLocalReply(relatedFacts, null, reminderCreated);
            onToken.accept(reply);
            return reply;
        }

        ReplySplitter splitter = new ReplySplitter(onToken);
        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", buildSystemPrompt(userId, userMessage, relatedFacts, zone)),
                    Map.of("role", "user", "content", userMessage));
            streamWithRetry(messages, 0.8, splitter);

            String reply = splitter.reply().trim();
            if (reply.isEmpty()) {
                throw new IllegalStateException("Model returned an empty reply");
            }
            if (remember) {
                if (splitter.meta().isBlank()) {
                    log.warn("Model reply had no {} section; no facts or reminder saved this turn", META_MARKER);
                } else {
                    log.info("Model metadata for user {}: {}", userId, splitter.meta().strip());
                }
                persistExtraction(userId, parseMeta(splitter.meta()));
            }
            return reply;
        } catch (Exception e) {
            int status = e instanceof GroqHttpException http ? http.status : -1;
            log.warn("Groq API failed (HTTP {}); falling back. {}", status, e.getMessage());
            if (splitter.emittedAny()) {
                // Part of the answer already reached the user; keep it rather than swapping in an error.
                return splitter.reply().trim();
            }
            boolean reminderCreated = remember && saveLocalReminderIfPresent(userId, userMessage);
            String reply = buildLocalReply(relatedFacts, describeFailure(status), reminderCreated);
            onToken.accept(reply);
            return reply;
        }
    }

    /** One complete answer from the model for a self-contained prompt (no streaming to a client). */
    public String complete(String prompt, double temperature) throws Exception {
        if (!isGroqConfigured()) {
            throw new IllegalStateException("Groq API key is not configured");
        }
        ReplySplitter splitter = new ReplySplitter(token -> { });
        streamWithRetry(List.of(Map.of("role", "user", "content", prompt)), temperature, splitter);
        return splitter.reply().trim();
    }

    /** A short, friendly chat message for a reminder that just came due. */
    public String reminderNudge(String title) {
        if (isGroqConfigured()) {
            try {
                String prompt = """
                        You are RoboFriend, the user's close friend. One of their reminders just came due:
                        "%s"
                        Text them a short, warm, casual nudge about it (max 20 words), like a friend would.
                        Start with ⏰. Write in the same language as the reminder. Output only the message.
                        """.formatted(title);
                ReplySplitter splitter = new ReplySplitter(token -> { });
                streamWithRetry(List.of(Map.of("role", "user", "content", prompt)), 0.9, splitter);
                String text = splitter.reply().trim();
                if (!text.isEmpty()) {
                    return text;
                }
            } catch (Exception e) {
                log.warn("Reminder nudge generation failed: {}", e.getMessage());
            }
        }
        List<String> templates = List.of(
                "⏰ Hey, it's time: %s",
                "⏰ Quick nudge — %s",
                "⏰ Don't forget: %s 🙂");
        return templates.get(ThreadLocalRandom.current().nextInt(templates.size())).formatted(title);
    }

    // ---------------------------------------------------------------- prompt

    private String buildSystemPrompt(Long userId, String userMessage, List<MemoryService.Memory> memories,
                                     ZoneId zone) {
        List<MemoryService.Memory> profile = memoryService.profileFacts(userId);
        java.util.Set<String> profileFacts = profile.stream().map(m -> m.fact())
                .collect(Collectors.toSet());
        String profileContext = profile.isEmpty()
                ? "Nothing yet"
                : profile.stream().map(m -> "- " + m.fact()).collect(Collectors.joining("\n"));
        List<MemoryService.Memory> searched = memories.stream()
                .filter(m -> !profileFacts.contains(m.fact()))
                .toList();
        String memoryContext = searched.isEmpty()
                ? "Nothing yet"
                : searched.stream().map(m -> formatMemory(m, zone)).collect(Collectors.joining("\n"));

        ZonedDateTime now = ZonedDateTime.now(zone);
        String localNow = now.format(HUMAN_TIME) + " (" + zone.getId() + ", UTC" + now.getOffset() + ")";

        List<Reminder> upcomingReminders = reminderRepository
                .findByUserIdAndStatusAndScheduledAtAfterOrderByScheduledAtAsc(
                        userId, "PENDING", OffsetDateTime.now(ZoneOffset.UTC));
        String remindersContext = upcomingReminders.isEmpty()
                ? "None"
                : upcomingReminders.stream()
                        .map(r -> "- " + r.getTitle() + " at " + r.getScheduledAt().atZoneSameInstant(zone).format(HUMAN_TIME))
                        .collect(Collectors.joining("\n"));

        return """
                You are RoboFriend, the user's close friend. You chat the way a real, caring friend texts:
                relaxed, warm, curious about their life, sometimes playful. You are not a coach, assistant or
                wellness app.

                How to talk:
                - Match their energy and length. A quick "good afternoon" gets a quick, friendly greeting back
                  (maybe a light question about their day), not a speech.
                - Usually 1-3 short sentences. Go longer only when they ask for detail or are opening up.
                - Be genuinely interested: react to what they said, ask natural follow-up questions, remember
                  and bring up things they told you before (like a friend would, not by listing facts).
                - Use everyday language and contractions. An emoji now and then is fine; don't overdo it.
                - Vary how you phrase things. Avoid stock lines like "Hope your day is going well!".
                - Don't just echo their words back; add something of your own (a reaction, a thought, a question).
                - Never mention being an AI or a language model unless they ask.

                Advice:
                - Give advice, tips or checklists ONLY when they ask for help, share a problem, or tell you about
                  a plan where a tip is clearly useful (e.g. "going for a run" -> maybe "got water?").
                - Never tack on unrequested wellness tips (hydrate, stretch, take a break, get sleep...).
                - When they're venting, listen and empathise first; don't jump to fixing.

                The user's local time right now: %s
                Use this for greetings and for understanding "today", "tonight", "tomorrow" etc.

                Their current stats and profile (always the latest values; trust these over older memories):
                %s

                What you remember about them, each with the date you learned it:
                %s
                Time words inside a memory ("tomorrow", "next week", "this weekend") are relative to the date
                you learned it, NOT to today. Work out the real date before using it: e.g. "birthday tomorrow"
                learned a week ago means the birthday was six days ago, so it's in the past now.

                Their upcoming reminders (only bring up if relevant):
                %s

                Their lists (open items only; you keep these lists for them):
                %s

                Recent conversation, oldest first (for context; don't repeat yourself):
                %s

                Output format (important):
                1. First write your message to them as plain text, exactly as you'd text it.
                   No JSON, no labels, no quotes around it.
                   Language: reply in the language of their NEW message below, whatever language earlier
                   messages or their location suggest. English message -> English reply; Greek message ->
                   Greek reply.
                2. Then a new line containing exactly: %s
                3. Then one JSON object and nothing after it:
                {"factsToRemember": [{"fact": "<a new fact they told you>", "key": "<snake_case key or null>"}],
                 "reminder": {"hasReminder": false, "title": null, "targetDateTime": null},
                 "listOps": [{"op": "<add|done|remove|clear>", "list": "<list name>", "items": ["<item>"]}]}
                Only include facts THEY actually said in this conversation. Never copy examples from these
                instructions; if they told you nothing new, use "factsToRemember": [].

                Rules for the JSON:
                - factsToRemember: only lasting facts (people, preferences, habits, goals), achievements and
                  personal records, and upcoming events or plans that matter to them (exams, trips,
                  appointments, birthdays, parties), with dates. Not small talk or moods of the moment.
                  [] if nothing new.
                - "key": a short snake_case name for a value that changes over time, so the new fact replaces
                  the old one: personal records and stats (squat_max, bench_max, deadlift_max, run_5k_best,
                  body_weight), job, city, relationship_status, etc. Use the same key as in their stats above
                  when updating. null for everything else.
                - Personal records: only save a new value when it beats the current one in their stats, and
                  include the date. A lighter set is not a new max.
                - Write facts so they stay true later: replace relative time words with real dates, using
                  their local time above (e.g. "Birthday is on 24 October", "Party planned for Saturday
                  24 October 2026"), never "tomorrow" or "next week".
                - listOps: changes to their lists, [] if none.
                  * add: things they want to buy or do and keep track of ("I need milk and eggs",
                    "put bread on the list", "I have to finish the report"). Keep items short, in their
                    language, with quantities ("2 litres milk").
                  * done: things they bought or finished ("got the milk", "πήρα το ψωμί").
                  * remove: items they no longer want on the list.
                  * clear: they finished the whole list ("I did the shopping"); items can be [].
                  * List names: lowercase English. Reuse an existing list name. "shopping" for groceries,
                    supermarket and market; "todo" for general tasks; a specific name ("uni", "work",
                    "gym") when they ask for one or the tasks clearly belong there.
                  * Don't put appointments with a time on a list; that's a reminder.
                - When they ask to see a list ("give me my market list"), answer with exactly the open items
                  shown above as a short "- item" list. Never invent items. If it's empty, say so.
                - Only create a reminder when they ask to be reminded or clearly schedule something; then set
                  hasReminder=true, a short title (e.g. "Call mom") and targetDateTime in ISO 8601 with their
                  UTC offset (e.g. 2026-11-06T09:00:00+02:00), interpreting times in their local timezone.
                """.formatted(localNow, profileContext, memoryContext, remindersContext, listsContext(userId),
                recentConversation(userId, userMessage), META_MARKER)
                + languageDirective(userMessage);
    }

    /**
     * Models drift into the conversation's earlier language, so when the new message is clearly
     * Greek we say so explicitly.
     */
    static String languageDirective(String userMessage) {
        long greek = userMessage.codePoints()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.GREEK)
                .count();
        long letters = userMessage.codePoints().filter(Character::isLetter).count();
        if (letters > 0 && greek * 2 >= letters) {
            return "\nIMPORTANT: their new message is in Greek, so write your reply in Greek.\n";
        }
        return "\nIMPORTANT: write your reply in the same language as their new message.\n";
    }

    /** Last few messages as a transcript, excluding the message being answered right now. */
    private String recentConversation(Long userId, String currentMessage) {
        try {
            List<ChatMessage> recent = new ArrayList<>(
                    chatMessageRepository.findTop13ByUserIdOrderByCreatedAtDesc(userId));
            if (!recent.isEmpty()
                    && "USER".equals(recent.get(0).getSender())
                    && currentMessage.equals(recent.get(0).getContent())) {
                recent.remove(0);
            }
            if (recent.size() > HISTORY_MESSAGES) {
                recent = new ArrayList<>(recent.subList(0, HISTORY_MESSAGES));
            }
            if (recent.isEmpty()) {
                return "(this is the start of your conversation)";
            }
            Collections.reverse(recent);
            return recent.stream()
                    .map(m -> ("USER".equals(m.getSender()) ? "Them: " : "You: ") + m.getContent())
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("Chat history lookup failed for user {}: {}", userId, e.getMessage());
            return "(unavailable)";
        }
    }

    // ------------------------------------------------------------- streaming

    private void streamWithRetry(List<Map<String, String>> messages, double temperature, ReplySplitter splitter)
            throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                streamGroq(messages, temperature, splitter);
                return;
            } catch (GroqHttpException | IOException e) {
                lastException = e;
                int status = e instanceof GroqHttpException http ? http.status : -1;
                log.warn("Groq attempt {}/{} failed: {}", attempt, MAX_RETRIES, e.getMessage());
                // Once text has been shown we can't silently start over.
                if (splitter.emittedAny() || NON_RETRYABLE_STATUSES.contains(status)) {
                    throw e;
                }
                if (attempt < MAX_RETRIES) {
                    sleepQuietly(RETRY_DELAY_MS * attempt);
                }
            }
        }
        throw lastException != null ? lastException : new IOException("Groq call failed");
    }

    private void streamGroq(List<Map<String, String>> messages, double temperature, ReplySplitter splitter)
            throws IOException, InterruptedException, GroqHttpException {
        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("max_completion_tokens", 1024);
        body.put("stream", true);
        if (modelName.startsWith("openai/gpt-oss")) {
            body.put("reasoning_effort", "low");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(GROQ_CHAT_URL))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() >= 400) {
                throw new GroqHttpException(response.statusCode(), lines.collect(Collectors.joining("\n")));
            }
            lines.forEach(line -> {
                if (!line.startsWith("data:")) {
                    return;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    return;
                }
                try {
                    JsonNode content = objectMapper.readTree(data)
                            .path("choices").path(0).path("delta").path("content");
                    if (content.isTextual()) {
                        splitter.feed(content.asText());
                    }
                } catch (IOException e) {
                    log.debug("Skipping unparseable stream chunk: {}", data);
                }
            });
        }
        splitter.finish();
    }

    /**
     * Forwards the reply part of the model output as it streams, and holds back everything after
     * {@link #META_MARKER} so the facts/reminder JSON never reaches the user.
     */
    static final class ReplySplitter {
        private final Consumer<String> onToken;
        private final StringBuilder reply = new StringBuilder();
        private final StringBuilder meta = new StringBuilder();
        private final StringBuilder pending = new StringBuilder();
        private boolean inMeta;

        ReplySplitter(Consumer<String> onToken) {
            this.onToken = onToken;
        }

        void feed(String chunk) {
            if (inMeta) {
                meta.append(chunk);
                return;
            }
            pending.append(chunk);
            int idx = pending.indexOf(META_MARKER);
            if (idx >= 0) {
                emit(pending.substring(0, idx));
                meta.append(pending.substring(idx + META_MARKER.length()));
                pending.setLength(0);
                inMeta = true;
                return;
            }
            // Keep a tail that could be the start of a marker split across chunks.
            int safe = pending.length() - (META_MARKER.length() - 1);
            if (safe > 0) {
                emit(pending.substring(0, safe));
                pending.delete(0, safe);
            }
        }

        void finish() {
            if (!inMeta) {
                emit(pending.toString());
                pending.setLength(0);
            }
        }

        boolean emittedAny() {
            return !reply.toString().isBlank();
        }

        String reply() {
            return reply.toString();
        }

        String meta() {
            return meta.toString();
        }

        private void emit(String text) {
            if (text.isEmpty()) {
                return;
            }
            // Don't show the blank lines the model writes right before the marker or at the start.
            if (reply.length() == 0) {
                text = text.stripLeading();
                if (text.isEmpty()) {
                    return;
                }
            }
            reply.append(text);
            onToken.accept(text);
        }
    }

    static final class GroqHttpException extends Exception {
        final int status;

        GroqHttpException(int status, String body) {
            super("HTTP " + status + ": " + body);
            this.status = status;
        }
    }

    // ------------------------------------------------------------ extraction

    private ExtractionResult parseMeta(String metaText) {
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

    private String listsContext(Long userId) {
        try {
            Map<String, List<String>> lists = listService.openItems(userId);
            if (lists.isEmpty()) {
                return "No lists yet";
            }
            return lists.entrySet().stream()
                    .map(e -> e.getKey() + ":\n" + e.getValue().stream().map(i -> "  - " + i)
                            .collect(Collectors.joining("\n")))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("List lookup failed for user {}: {}", userId, e.getMessage());
            return "(unavailable)";
        }
    }

    private String extractJson(String text) {
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1);
        }
        return text;
    }

    private void persistExtraction(Long userId, ExtractionResult result) {
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

    // -------------------------------------------------------------- fallback

    private String describeFailure(int status) {
        return switch (status) {
            case 402 -> "AI service unavailable due to insufficient provider credits. Please try again later.";
            case 429 -> "AI service is rate-limited. Please try again in a moment.";
            default -> "AI service unavailable right now. Please try again later.";
        };
    }

    private boolean saveLocalReminderIfPresent(Long userId, String userMessage) {
        try {
            LocalReminderParser.ParsedReminder parsed = LocalReminderParser.parse(userMessage).orElse(null);
            if (parsed == null) {
                return false;
            }
            Reminder entity = new Reminder();
            entity.setUserId(userId);
            entity.setTitle(parsed.title());
            entity.setScheduledAt(parsed.scheduledAt().atOffset(ZoneOffset.UTC));
            entity.setStatus("PENDING");
            entity.setNotes("Auto-extracted locally (AI provider unavailable)");
            reminderRepository.save(entity);
            log.info("Saved local reminder for user {}: {} at {}", userId, parsed.title(), parsed.scheduledAt());
            return true;
        } catch (Exception e) {
            log.error("Failed to save locally extracted reminder: {}", e.getMessage());
            return false;
        }
    }

    private List<MemoryService.Memory> getRelevantFacts(Long userId, String userMessage) {
        try {
            return memoryService.searchMemories(userId, userMessage, 5);
        } catch (Exception e) {
            log.warn("Memory lookup failed for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    private static String formatMemory(MemoryService.Memory memory, ZoneId zone) {
        if (memory.savedAt() == null) {
            return "- " + memory.fact();
        }
        return "- [learned " + memory.savedAt().atZoneSameInstant(zone).format(MEMORY_DATE) + "] " + memory.fact();
    }

    private String buildLocalReply(List<MemoryService.Memory> memories, String failureNote, boolean reminderCreated) {
        List<String> facts = memories.stream().map(m -> m.fact()).toList();
        if (failureNote != null && !failureNote.isBlank()) {
            return reminderCreated ? "I set that reminder for you. " + failureNote : failureNote;
        }
        StringBuilder response = new StringBuilder();
        if (facts != null && !facts.isEmpty()) {
            response.append("Here's what I know about you: ").append(String.join("; ", facts)).append(". ");
        }
        if (reminderCreated) {
            response.append("I set that reminder for you. ");
        }
        response.append("I'm not sure how to help with that just yet. Could you tell me more?");
        return response.toString();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
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
