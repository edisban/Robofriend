package com.bot.botback.service;

import com.bot.botback.model.ChatMessage;
import com.bot.botback.model.Reminder;
import com.bot.botback.repository.ChatMessageRepository;
import com.bot.botback.repository.ReminderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Delivers due reminders as chat messages from RoboFriend, so they show up in the conversation
 * the way a friend's text would.
 */
@Component
public class ReminderNotifier {

    private static final Logger log = LoggerFactory.getLogger(ReminderNotifier.class);

    private final ReminderRepository reminderRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AiService aiService;

    public ReminderNotifier(ReminderRepository reminderRepository,
                            ChatMessageRepository chatMessageRepository,
                            AiService aiService) {
        this.reminderRepository = reminderRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.aiService = aiService;
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 20_000)
    public void deliverDueReminders() {
        List<Reminder> due;
        try {
            due = reminderRepository.findByStatusAndScheduledAtBeforeOrderByScheduledAtAsc(
                    "PENDING", OffsetDateTime.now(ZoneOffset.UTC));
        } catch (Exception e) {
            log.warn("Could not look up due reminders: {}", e.getMessage());
            return;
        }

        for (Reminder reminder : due) {
            try {
                // Mark first so a slow nudge can't make the next run deliver it twice.
                reminder.setStatus("SENT");
                reminderRepository.save(reminder);

                ChatMessage message = new ChatMessage();
                message.setUserId(reminder.getUserId());
                message.setSender("BOT");
                message.setContent(aiService.reminderNudge(reminder.getTitle()));
                chatMessageRepository.save(message);
                log.info("Delivered reminder {} to user {}", reminder.getId(), reminder.getUserId());
            } catch (Exception e) {
                log.error("Failed to deliver reminder {}: {}", reminder.getId(), e.getMessage());
            }
        }
    }
}
