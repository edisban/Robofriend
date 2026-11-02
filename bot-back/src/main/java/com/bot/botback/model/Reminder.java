package com.bot.botback.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "reminders")
@Getter
@Setter
public class Reminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    private ReminderStatus status = ReminderStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Reminder() {
    }

    public Reminder(Long id, Long userId, String title, OffsetDateTime scheduledAt, ReminderStatus status, String notes, OffsetDateTime createdAt) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.scheduledAt = scheduledAt;
        this.status = status == null ? ReminderStatus.PENDING : status;
        this.notes = notes;
        this.createdAt = createdAt;
    }

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
        if (status == null) {
            status = ReminderStatus.PENDING;
        }
    }
}
