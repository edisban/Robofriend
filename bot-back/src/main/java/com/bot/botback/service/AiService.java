package com.bot.botback.service;

import com.bot.botback.model.Reminder;
import com.bot.botback.repository.ReminderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Produces RoboFriend's replies: builds the prompt, streams the answer from Groq, saves what it
 * learned, and falls back to a local reply when the AI is unavailable.
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final MemoryService memoryService;
    private final GroqClient groqClient;
    private final PromptBuilder promptBuilder;
    private final ReplyExtraction extraction;
    private final ReminderRepository reminderRepository;

    public AiService(MemoryService memoryService,
                     GroqClient groqClient,
                     PromptBuilder promptBuilder,
                     ReplyExtraction extraction,
                     ReminderRepository reminderRepository) {
        this.memoryService = memoryService;
        this.groqClient = groqClient;
        this.promptBuilder = promptBuilder;
        this.extraction = extraction;
        this.reminderRepository = reminderRepository;
    }

    public boolean isGroqConfigured() {
        return groqClient.isConfigured();
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
                    Map.of("role", "system", "content", promptBuilder.systemPrompt(userId, userMessage, relatedFacts, zone)),
                    Map.of("role", "user", "content", userMessage));
            groqClient.stream(messages, 0.8, splitter);

            String reply = splitter.reply().trim();
            if (reply.isEmpty()) {
                throw new IllegalStateException("Model returned an empty reply");
            }
            if (remember) {
                if (splitter.meta().isBlank()) {
                    log.warn("Model reply had no {} section; no facts or reminder saved this turn", ReplySplitter.META_MARKER);
                } else {
                    log.info("Model metadata for user {}: {}", userId, splitter.meta().strip());
                }
                extraction.persist(userId, extraction.parseMeta(splitter.meta()));
            }
            return reply;
        } catch (Exception e) {
            int status = e instanceof GroqClient.GroqHttpException http ? http.status : -1;
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
                groqClient.stream(List.of(Map.of("role", "user", "content", prompt)), 0.9, splitter);
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
}
