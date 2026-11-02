package com.bot.botback.service;

import com.bot.botback.model.ChatMessage;
import com.bot.botback.model.Reminder;
import com.bot.botback.model.ReminderStatus;
import com.bot.botback.model.Sender;
import com.bot.botback.repository.ChatMessageRepository;
import com.bot.botback.repository.ReminderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds the instructions RoboFriend gets for each reply. */
@Component
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    private static final int HISTORY_MESSAGES = 12;
    private static final DateTimeFormatter HUMAN_TIME =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm");
    private static final DateTimeFormatter MEMORY_DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy");

    private final MemoryService memoryService;
    private final ReminderRepository reminderRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ListService listService;

    public PromptBuilder(MemoryService memoryService,
                         ReminderRepository reminderRepository,
                         ChatMessageRepository chatMessageRepository,
                         ListService listService) {
        this.memoryService = memoryService;
        this.reminderRepository = reminderRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.listService = listService;
    }

    /** The system prompt: persona, rules, and everything RoboFriend knows that's relevant now. */
    public String systemPrompt(Long userId, String userMessage, List<MemoryService.Memory> memories,
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
                        userId, ReminderStatus.PENDING, OffsetDateTime.now(ZoneOffset.UTC));
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
                recentConversation(userId, userMessage), ReplySplitter.META_MARKER)
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
                    && recent.get(0).getSender() == Sender.USER
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
                    .map(m -> (m.getSender() == Sender.USER ? "Them: " : "You: ") + m.getContent())
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.warn("Chat history lookup failed for user {}: {}", userId, e.getMessage());
            return "(unavailable)";
        }
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

    private static String formatMemory(MemoryService.Memory memory, ZoneId zone) {
        if (memory.savedAt() == null) {
            return "- " + memory.fact();
        }
        return "- [learned " + memory.savedAt().atZoneSameInstant(zone).format(MEMORY_DATE) + "] " + memory.fact();
    }
}
