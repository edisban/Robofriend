package com.bot.botback.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** One entry on a user's list (shopping, todo, uni, ...), kept by RoboFriend from the conversation. */
@Entity
@Table(name = "list_items")
@Getter
@Setter
public class ListItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Lowercase list name, e.g. "shopping" or "todo". */
    @Column(name = "list_name", nullable = false, length = 60)
    private String listName;

    @Column(nullable = false, length = 300)
    private String text;

    @Column(nullable = false)
    private boolean done;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "done_at")
    private OffsetDateTime doneAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
