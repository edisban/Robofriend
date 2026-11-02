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

@Entity
@Table(name = "memory_vault")
@Getter
@Setter
public class MemoryVault {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String fact;

    @Column(columnDefinition = "TEXT")
    private String embedding;

    /** Set for facts that change over time (e.g. "squat_max"); a newer fact with the same key replaces it. */
    @Column(name = "fact_key", length = 80)
    private String factKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public MemoryVault() {
    }

    public MemoryVault(Long id, Long userId, String fact, String embedding, OffsetDateTime createdAt) {
        this.id = id;
        this.userId = userId;
        this.fact = fact;
        this.embedding = embedding;
        this.createdAt = createdAt;
    }

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
