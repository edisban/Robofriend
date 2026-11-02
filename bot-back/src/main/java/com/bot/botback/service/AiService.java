package com.bot.botback.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Produces RoboFriend's replies: builds the prompt, streams the answer from Groq and saves what it
 * learned. When the AI can't be reached, it says so in a short message.
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private static final String AI_UNAVAILABLE = "I can't reach my AI brain right now. Can you try again in a moment?";
    private static final String AI_BUSY = "I'm getting a lot of messages right now. Give me a moment and try again?";

    private final MemoryService memoryService;
    private final GroqClient groqClient;
    private final PromptBuilder promptBuilder;
    private final ReplyExtraction extraction;

    public AiService(MemoryService memoryService, GroqClient groqClient, PromptBuilder promptBuilder,
                     ReplyExtraction extraction) {
        this.memoryService = memoryService;
        this.groqClient = groqClient;
        this.promptBuilder = promptBuilder;
        this.extraction = extraction;
    }

    /**
     * Generates RoboFriend's reply, pushing it to {@code onToken} piece by piece as the model writes it.
     *
     * @param remember whether to save facts, reminders and list changes from this turn (false when
     *                 regenerating, so the same reminder isn't created twice)
     * @return the complete reply text
     */
    public String generateReply(Long userId, String userMessage, ZoneId zone,
                                Consumer<String> onToken, boolean remember) {
        if (!groqClient.isConfigured()) {
            onToken.accept(AI_UNAVAILABLE);
            return AI_UNAVAILABLE;
        }

        ReplySplitter splitter = new ReplySplitter(onToken);
        try {
            List<MemoryService.Memory> memories = memoryService.searchMemories(userId, userMessage, 5);
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", promptBuilder.systemPrompt(userId, userMessage, memories, zone)),
                    Map.of("role", "user", "content", userMessage));
            groqClient.stream(messages, 0.8, splitter);

            String reply = splitter.reply().trim();
            if (reply.isEmpty()) {
                throw new IllegalStateException("Model returned an empty reply");
            }
            if (remember) {
                extraction.persist(userId, extraction.parseMeta(splitter.meta()));
            }
            return reply;
        } catch (Exception e) {
            log.warn("Reply generation failed for user {}: {}", userId, e.getMessage());
            if (splitter.emittedAny()) {
                // Part of the answer already reached the user; keep it rather than swapping in an error.
                return splitter.reply().trim();
            }
            boolean rateLimited = e instanceof GroqClient.GroqHttpException http && http.status == 429;
            String reply = rateLimited ? AI_BUSY : AI_UNAVAILABLE;
            onToken.accept(reply);
            return reply;
        }
    }

    /** A short, friendly chat message for a reminder that just came due. */
    public String reminderNudge(String title) {
        try {
            String text = groqClient.complete("""
                    You are RoboFriend, the user's close friend. One of their reminders just came due:
                    "%s"
                    Text them a short, warm, casual nudge about it (max 20 words), like a friend would.
                    Start with ⏰. Write in the same language as the reminder. Output only the message.
                    """.formatted(title), 0.9);
            if (!text.isEmpty()) {
                return text;
            }
        } catch (Exception e) {
            log.warn("Reminder nudge generation failed: {}", e.getMessage());
        }
        return "⏰ Don't forget: " + title;
    }
}
