package com.bot.botback.controller;

import com.bot.botback.dto.ReminderResponse;
import com.bot.botback.dto.ReminderStatusRequest;
import com.bot.botback.model.Reminder;
import com.bot.botback.repository.ReminderRepository;
import com.bot.botback.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reminders")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173", "http://localhost:5174", "http://127.0.0.1:5174", "http://localhost:80"})
public class ReminderController {

    private final ReminderRepository reminderRepository;
    private final CurrentUser currentUser;

    public ReminderController(ReminderRepository reminderRepository, CurrentUser currentUser) {
        this.reminderRepository = reminderRepository;
        this.currentUser = currentUser;
    }

    private Reminder findOwned(Long reminderId) {
        Long userId = currentUser.id();
        return reminderRepository.findById(reminderId)
                .filter(r -> userId.equals(r.getUserId()))
                .orElse(null);
    }

    @GetMapping("/{userId}")
    public List<ReminderResponse> getReminders(@PathVariable Long userId) {
        currentUser.requireSelf(userId);
        return reminderRepository.findByUserIdOrderByScheduledAtAsc(userId).stream()
                .map(reminder -> ReminderResponse.from(reminder))
                .toList();
    }

    @PatchMapping("/{reminderId}")
    public ResponseEntity<Map<String, String>> updateStatus(@PathVariable Long reminderId,
                                                           @Valid @RequestBody ReminderStatusRequest body) {
        Reminder reminder = findOwned(reminderId);
        if (reminder == null) {
            return ResponseEntity.notFound().build();
        }
        reminder.setStatus(body.getStatus().toUpperCase());
        reminderRepository.save(reminder);
        return ResponseEntity.ok(Map.of("status", reminder.getStatus()));
    }

    @DeleteMapping("/{reminderId}")
    public ResponseEntity<Void> deleteReminder(@PathVariable Long reminderId) {
        if (findOwned(reminderId) == null) {
            return ResponseEntity.notFound().build();
        }
        reminderRepository.deleteById(reminderId);
        return ResponseEntity.noContent().build();
    }
}
