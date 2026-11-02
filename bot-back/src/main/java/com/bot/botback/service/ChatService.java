package com.bot.botback.service;

import com.bot.botback.dto.MessageRequest;
import com.bot.botback.model.ChatMessage;
import com.bot.botback.model.Sender;
import com.bot.botback.repository.ChatMessageRepository;
import com.bot.botback.repository.MemoryVaultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * The conversation's history and its changes. Database work happens in short transactions; the
 * slow AI call is never made inside one, so a reply in progress doesn't hold a connection.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** What RoboFriend should answer, and whether facts/reminders from it may be saved. */
    public record Turn(String message, Long userMessageId, boolean remember) {
    }

    public record Deleted(int messages, int memories) {
    }

    private final ChatMessageRepository chatRepo;
    private final MemoryVaultRepository memoryVaultRepository;

    public ChatService(ChatMessageRepository chatRepo, MemoryVaultRepository memoryVaultRepository) {
        this.chatRepo = chatRepo;
        this.memoryVaultRepository = memoryVaultRepository;
    }

    @Transactional(readOnly = true)
    public List<ChatMessage> history(Long userId, Long afterId) {
        return afterId == null
                ? chatRepo.findByUserIdOrderByCreatedAtAsc(userId)
                : chatRepo.findByUserIdAndIdGreaterThanOrderByCreatedAtAsc(userId, afterId);
    }

    /**
     * Prepares the conversation for a reply, atomically:
     * <ul>
     *   <li>regenerate: drop the replies after the last user message and answer it again
     *       (without saving its facts/reminder a second time)</li>
     *   <li>edit ({@code replaceFromId}): delete that message and everything after, then save the new one</li>
     *   <li>otherwise: save the new user message</li>
     * </ul>
     */
    @Transactional
    public Turn startTurn(Long userId, MessageRequest req) {
        if (req.isRegenerate()) {
            ChatMessage lastUserMessage = chatRepo.findTop13ByUserIdOrderByCreatedAtDesc(userId).stream()
                    .filter(m -> m.getSender() == Sender.USER)
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to regenerate"));
            chatRepo.deleteFrom(userId, lastUserMessage.getId() + 1);
            return new Turn(lastUserMessage.getContent(), lastUserMessage.getId(), false);
        }
        if (req.getMessage() == null || req.getMessage().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message is empty");
        }
        if (req.getReplaceFromId() != null) {
            ChatMessage edited = owned(userId, req.getReplaceFromId());
            if (edited.getSender() != Sender.USER) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only your own messages can be edited");
            }
            chatRepo.deleteFrom(userId, edited.getId());
        }
        ChatMessage saved = save(userId, Sender.USER, req.getMessage());
        return new Turn(req.getMessage(), saved.getId(), true);
    }

    @Transactional
    public ChatMessage save(Long userId, Sender sender, String content) {
        ChatMessage message = new ChatMessage();
        message.setUserId(userId);
        message.setSender(sender);
        message.setContent(content);
        return chatRepo.save(message);
    }

    @Transactional
    public void deleteMessage(Long userId, Long messageId) {
        chatRepo.delete(owned(userId, messageId));
    }

    /** Deletes the whole conversation, and optionally everything RoboFriend learned, all or nothing. */
    @Transactional
    public Deleted deleteConversation(Long userId, boolean forgetMemories) {
        int messages = chatRepo.deleteAllByUser(userId);
        int memories = forgetMemories ? memoryVaultRepository.deleteAllByUser(userId) : 0;
        log.info("User {} deleted their conversation ({} messages, {} memories)", userId, messages, memories);
        return new Deleted(messages, memories);
    }

    private ChatMessage owned(Long userId, Long messageId) {
        return chatRepo.findById(messageId)
                .filter(m -> userId.equals(m.getUserId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
