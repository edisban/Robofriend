package com.bot.botback.service;

import com.bot.botback.model.Reminder;
import com.bot.botback.repository.ReminderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/** The user's reminders, for the side panel. */
@Service
public class ReminderService {

    private final ReminderRepository reminderRepository;

    public ReminderService(ReminderRepository reminderRepository) {
        this.reminderRepository = reminderRepository;
    }

    @Transactional(readOnly = true)
    public List<Reminder> remindersOf(Long userId) {
        return reminderRepository.findByUserIdOrderByScheduledAtAsc(userId);
    }

    @Transactional
    public Reminder updateStatus(Long userId, Long reminderId, String status) {
        Reminder reminder = owned(userId, reminderId);
        reminder.setStatus(status.toUpperCase(Locale.ROOT));
        return reminderRepository.save(reminder);
    }

    @Transactional
    public void delete(Long userId, Long reminderId) {
        reminderRepository.delete(owned(userId, reminderId));
    }

    private Reminder owned(Long userId, Long reminderId) {
        return reminderRepository.findById(reminderId)
                .filter(r -> userId.equals(r.getUserId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
