package com.bot.botback.controller;

import com.bot.botback.dto.MessageRequest;
import com.bot.botback.model.ChatMessage;
import com.bot.botback.repository.ChatMessageRepository;
import com.bot.botback.security.CurrentUser;
import com.bot.botback.service.AiService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173", "http://localhost:5174", "http://127.0.0.1:5174"})
public class ChatController {

    private final ChatMessageRepository chatRepo;
    private final AiService aiService;
    private final CurrentUser currentUser;

    public ChatController(ChatMessageRepository chatRepo, AiService aiService, CurrentUser currentUser) {
        this.chatRepo = chatRepo;
        this.aiService = aiService;
        this.currentUser = currentUser;
    }

    @GetMapping("/history/{userId}")
    public List<ChatMessage> getHistory(@PathVariable Long userId) {
        currentUser.requireSelf(userId);
        return chatRepo.findByUserIdOrderByCreatedAtAsc(userId);
    }

    @PostMapping("/send")
    public Map<String, String> sendMessage(@RequestBody MessageRequest req) {
        if (req == null || req.getMessage() == null || req.getMessage().isBlank()) {
            return Map.of("reply", "Please provide a message to continue.");
        }

        Long userId = currentUser.id();

        ChatMessage userMessage = new ChatMessage();
        userMessage.setUserId(userId);
        userMessage.setSender("USER");
        userMessage.setContent(req.getMessage());
        chatRepo.save(userMessage);

        String reply;
        try {
            reply = aiService.generateReply(userId, req.getMessage());
        } catch (Exception ex) {
            reply = "I'm having trouble connecting right now. Can you try that again?";
        }

        ChatMessage botReply = new ChatMessage();
        botReply.setUserId(userId);
        botReply.setSender("BOT");
        botReply.setContent(reply);
        chatRepo.save(botReply);

        return Map.of("reply", reply);
    }
}
