package com.bot.botback.dto;

import com.bot.botback.model.Reminder;

import java.time.OffsetDateTime;

public record ReminderResponse(Long id, String title, OffsetDateTime scheduledAt, String status, String notes) {

    public static ReminderResponse from(Reminder reminder) {
        return new ReminderResponse(reminder.getId(), reminder.getTitle(), reminder.getScheduledAt(),
                reminder.getStatus(), reminder.getNotes());
    }
}
