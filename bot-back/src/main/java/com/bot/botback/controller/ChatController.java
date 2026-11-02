package com.bot.botback.controller;

import com.bot.botback.dto.MessageRequest;
import com.bot.botback.model.ChatMessage;
import com.bot.botback.repository.ChatMessageRepository;
import com.bot.botback.repository.MemoryVaultRepository;
import com.bot.botback.security.CurrentUser;
import com.bot.botback.service.AiService;
import com.bot.botback.service.CheckInService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173", "http://localhost:5174", "http://127.0.0.1:5174"})
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final long STREAM_TIMEOUT_MS = 180_000;

    private final ChatMessageRepository chatRepo;
    private final AiService aiService;
    private final CurrentUser currentUser;
    private final CheckInService checkInService;
    private final MemoryVaultRepository memoryVaultRepository;
    private final ExecutorService streamExecutor = Executors.newFixedThreadPool(16);

    public ChatController(ChatMessageRepository chatRepo, AiService aiService, CurrentUser currentUser,
                          CheckInService checkInService, MemoryVaultRepository memoryVaultRepository) {
        this.chatRepo = chatRepo;
        this.aiService = aiService;
        this.currentUser = currentUser;
        this.checkInService = checkInService;
        this.memoryVaultRepository = memoryVaultRepository;
    }

    @PreDestroy
    void shutdown() {
        streamExecutor.shutdown();
    }

    @GetMapping("/history/{userId}")
    public List<ChatMessage> getHistory(@PathVariable Long userId,
                                        @RequestParam(required = false) Long afterId) {
        currentUser.requireSelf(userId);
        return afterId == null
                ? chatRepo.findByUserIdOrderByCreatedAtAsc(userId)
                : chatRepo.findByUserIdAndIdGreaterThanOrderByCreatedAtAsc(userId, afterId);
    }

    @PostMapping("/send")
    public Map<String, String> sendMessage(@RequestBody MessageRequest req) {
        if (req == null || req.getMessage() == null || req.getMessage().isBlank()) {
            return Map.of("reply", "Please provide a message to continue.");
        }

        Long userId = currentUser.id();
        saveMessage(userId, "USER", req.getMessage());

        String reply;
        try {
            reply = aiService.generateReply(userId, req.getMessage(), zoneOf(req.getTimeZone()));
        } catch (Exception ex) {
            reply = "I'm having trouble connecting right now. Can you try that again?";
        }
        saveMessage(userId, "BOT", reply);

        return Map.of("reply", reply);
    }

    /**
     * Streams RoboFriend's reply as server-sent events: {@code token} events carry text as it is
     * written, then one {@code done} event carries the final reply and the saved message ids.
     * <ul>
     *   <li>{@code regenerate=true}: drop the last reply and answer the last user message again.</li>
     *   <li>{@code replaceFromId}: edit — delete that user message and everything after it, then send.</li>
     * </ul>
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody MessageRequest req) {
        Long userId = currentUser.id();
        ZoneId zone = zoneOf(req.getTimeZone());

        String message;
        Long userMessageId;
        boolean remember;
        if (req.isRegenerate()) {
            ChatMessage lastUserMessage = dropRepliesAfterLastUserMessage(userId);
            message = lastUserMessage.getContent();
            userMessageId = lastUserMessage.getId();
            remember = false;
        } else {
            if (req.getMessage() == null || req.getMessage().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message is empty");
            }
            if (req.getReplaceFromId() != null) {
                ChatMessage edited = ownedMessage(userId, req.getReplaceFromId());
                if (!"USER".equals(edited.getSender())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only your own messages can be edited");
                }
                chatRepo.deleteFrom(userId, edited.getId());
            }
            message = req.getMessage();
            userMessageId = saveMessage(userId, "USER", message).getId();
            remember = true;
        }

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        streamExecutor.execute(() -> {
            ClientChannel channel = new ClientChannel(emitter);
            String reply;
            try {
                reply = aiService.generateReply(userId, message, zone,
                        token -> channel.send("token", Map.of("t", token)), remember);
            } catch (Exception e) {
                log.error("Reply generation failed for user {}: {}", userId, e.getMessage());
                reply = "I'm having trouble connecting right now. Can you try that again?";
                channel.send("token", Map.of("t", reply));
            }
            // Saved even if the user closed the tab mid-stream, so it's there next time.
            ChatMessage saved = saveMessage(userId, "BOT", reply);

            Map<String, Object> done = new HashMap<>();
            done.put("reply", reply);
            done.put("botMessageId", saved.getId());
            done.put("userMessageId", userMessageId);
            channel.send("done", done);
            emitter.complete();
        });
        return emitter;
    }

    /** Called when the app opens: RoboFriend's first message of the day, if it has something to say. */
    @PostMapping("/checkin")
    public ResponseEntity<ChatMessage> checkIn(@RequestBody(required = false) MessageRequest req) {
        ZoneId zone = zoneOf(req == null ? null : req.getTimeZone());
        return checkInService.checkIn(currentUser.id(), zone)
                .map(message -> ResponseEntity.ok(message))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Permanently deletes the whole conversation. With {@code forgetMemories=true} RoboFriend also
     * forgets everything it learned about the user (facts and stats). Reminders are kept.
     */
    @DeleteMapping("/messages")
    public Map<String, Integer> deleteConversation(@RequestParam(defaultValue = "false") boolean forgetMemories) {
        Long userId = currentUser.id();
        int messages = chatRepo.deleteAllByUser(userId);
        int memories = forgetMemories ? memoryVaultRepository.deleteAllByUser(userId) : 0;
        log.info("User {} deleted their conversation ({} messages, {} memories)", userId, messages, memories);
        return Map.of("deletedMessages", messages, "deletedMemories", memories);
    }

    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<Void> deleteMessage(@PathVariable Long messageId) {
        ChatMessage message = ownedMessage(currentUser.id(), messageId);
        chatRepo.delete(message);
        return ResponseEntity.noContent().build();
    }

    private ChatMessage dropRepliesAfterLastUserMessage(Long userId) {
        List<ChatMessage> recent = chatRepo.findTop13ByUserIdOrderByCreatedAtDesc(userId);
        for (ChatMessage m : recent) {
            if ("USER".equals(m.getSender())) {
                chatRepo.deleteFrom(userId, m.getId() + 1);
                return m;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to regenerate");
    }

    private ChatMessage ownedMessage(Long userId, Long messageId) {
        return chatRepo.findById(messageId)
                .filter(m -> userId.equals(m.getUserId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private ChatMessage saveMessage(Long userId, String sender, String content) {
        ChatMessage message = new ChatMessage();
        message.setUserId(userId);
        message.setSender(sender);
        message.setContent(content);
        return chatRepo.save(message);
    }

    private static ZoneId zoneOf(String timeZone) {
        try {
            return timeZone == null || timeZone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timeZone);
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }

    /** Sends SSE events, and stops trying (without failing the reply) once the client has gone. */
    private static final class ClientChannel {
        private final SseEmitter emitter;
        private boolean open = true;

        ClientChannel(SseEmitter emitter) {
            this.emitter = emitter;
        }

        void send(String event, Object data) {
            if (!open) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                open = false;
            }
        }
    }
}
