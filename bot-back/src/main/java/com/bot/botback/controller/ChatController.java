package com.bot.botback.controller;

import com.bot.botback.config.StreamingConfig;
import com.bot.botback.dto.ChatMessageResponse;
import com.bot.botback.dto.MessageRequest;
import com.bot.botback.model.ChatMessage;
import com.bot.botback.model.Sender;
import com.bot.botback.security.CurrentUser;
import com.bot.botback.service.AiService;
import com.bot.botback.service.ChatService;
import com.bot.botback.service.CheckInService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final long STREAM_TIMEOUT_MS = 180_000;
    private static final String CONNECTION_TROUBLE = "I'm having trouble connecting right now. Can you try that again?";

    private final ChatService chatService;
    private final AiService aiService;
    private final CurrentUser currentUser;
    private final CheckInService checkInService;
    private final TaskExecutor streamExecutor;

    public ChatController(ChatService chatService, AiService aiService, CurrentUser currentUser,
                          CheckInService checkInService,
                          @Qualifier(StreamingConfig.REPLY_STREAM_EXECUTOR) TaskExecutor streamExecutor) {
        this.chatService = chatService;
        this.aiService = aiService;
        this.currentUser = currentUser;
        this.checkInService = checkInService;
        this.streamExecutor = streamExecutor;
    }

    @GetMapping("/history/{userId}")
    public List<ChatMessageResponse> getHistory(@PathVariable Long userId,
                                                @RequestParam(required = false) Long afterId) {
        currentUser.requireSelf(userId);
        return chatService.history(userId, afterId).stream()
                .map(message -> ChatMessageResponse.from(message))
                .toList();
    }

    @PostMapping("/send")
    public Map<String, String> sendMessage(@Valid @RequestBody MessageRequest req) {
        if (req.getMessage() == null || req.getMessage().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message is empty");
        }
        Long userId = currentUser.id();
        chatService.save(userId, Sender.USER, req.getMessage());
        String reply;
        try {
            reply = aiService.generateReply(userId, req.getMessage(), zoneOf(req.getTimeZone()));
        } catch (Exception ex) {
            reply = CONNECTION_TROUBLE;
        }
        chatService.save(userId, Sender.BOT, reply);
        return Map.of("reply", reply);
    }

    /**
     * Streams RoboFriend's reply as server-sent events: {@code token} events carry text as it is
     * written, then one {@code done} event carries the final reply and the saved message ids.
     * Also handles regenerate ({@code regenerate=true}) and edit ({@code replaceFromId}).
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody MessageRequest req) {
        Long userId = currentUser.id();
        ZoneId zone = zoneOf(req.getTimeZone());
        ChatService.Turn turn = chatService.startTurn(userId, req);

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        try {
            streamExecutor.execute(() -> streamReply(emitter, userId, zone, turn));
        } catch (TaskRejectedException e) {
            log.warn("Reply stream pool is full; refusing a reply for user {}", userId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "RoboFriend is a bit busy right now. Try again in a moment.");
        }
        return emitter;
    }

    private void streamReply(SseEmitter emitter, Long userId, ZoneId zone, ChatService.Turn turn) {
        ClientChannel channel = new ClientChannel(emitter);
        String reply;
        try {
            reply = aiService.generateReply(userId, turn.message(), zone,
                    token -> channel.send("token", Map.of("t", token)), turn.remember());
        } catch (Exception e) {
            log.error("Reply generation failed for user {}: {}", userId, e.getMessage());
            reply = CONNECTION_TROUBLE;
            channel.send("token", Map.of("t", reply));
        }
        // Saved even if the user closed the tab mid-stream, so it's there next time.
        ChatMessage saved = chatService.save(userId, Sender.BOT, reply);

        Map<String, Object> done = new HashMap<>();
        done.put("reply", reply);
        done.put("botMessageId", saved.getId());
        done.put("userMessageId", turn.userMessageId());
        channel.send("done", done);
        emitter.complete();
    }

    /** Called when the app opens: RoboFriend's first message of the day, if it has something to say. */
    @PostMapping("/checkin")
    public ResponseEntity<ChatMessageResponse> checkIn(@Valid @RequestBody(required = false) MessageRequest req) {
        ZoneId zone = zoneOf(req == null ? null : req.getTimeZone());
        return checkInService.checkIn(currentUser.id(), zone)
                .map(message -> ResponseEntity.ok(ChatMessageResponse.from(message)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Permanently deletes the whole conversation. With {@code forgetMemories=true} RoboFriend also
     * forgets everything it learned about the user (facts and stats). Reminders are kept.
     */
    @DeleteMapping("/messages")
    public Map<String, Integer> deleteConversation(@RequestParam(defaultValue = "false") boolean forgetMemories) {
        ChatService.Deleted deleted = chatService.deleteConversation(currentUser.id(), forgetMemories);
        return Map.of("deletedMessages", deleted.messages(), "deletedMemories", deleted.memories());
    }

    @DeleteMapping("/messages/{messageId}")
    public ResponseEntity<Void> deleteMessage(@PathVariable Long messageId) {
        chatService.deleteMessage(currentUser.id(), messageId);
        return ResponseEntity.noContent().build();
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
