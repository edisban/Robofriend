package com.bot.botback.controller;

import com.bot.botback.dto.ReminderResponse;
import com.bot.botback.dto.ReminderStatusRequest;
import com.bot.botback.security.CurrentUser;
import com.bot.botback.service.ReminderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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
public class ReminderController {

    private final ReminderService reminderService;
    private final CurrentUser currentUser;

    public ReminderController(ReminderService reminderService, CurrentUser currentUser) {
        this.reminderService = reminderService;
        this.currentUser = currentUser;
    }

    @GetMapping("/{userId}")
    public List<ReminderResponse> getReminders(@PathVariable Long userId) {
        currentUser.requireSelf(userId);
        return reminderService.remindersOf(userId).stream()
                .map(reminder -> ReminderResponse.from(reminder))
                .toList();
    }

    @PatchMapping("/{reminderId}")
    public Map<String, String> updateStatus(@PathVariable Long reminderId,
                                            @Valid @RequestBody ReminderStatusRequest body) {
        String status = reminderService.updateStatus(currentUser.id(), reminderId, body.getStatus()).getStatus().name();
        return Map.of("status", status);
    }

    @DeleteMapping("/{reminderId}")
    public ResponseEntity<Void> deleteReminder(@PathVariable Long reminderId) {
        reminderService.delete(currentUser.id(), reminderId);
        return ResponseEntity.noContent().build();
    }
}
