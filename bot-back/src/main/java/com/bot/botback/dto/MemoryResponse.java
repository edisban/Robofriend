package com.bot.botback.dto;

import com.bot.botback.model.MemoryVault;

import java.time.OffsetDateTime;

/** A remembered fact, without its embedding vector. */
public record MemoryResponse(Long id, String fact, String key, OffsetDateTime createdAt) {

    public static MemoryResponse from(MemoryVault memory) {
        return new MemoryResponse(memory.getId(), memory.getFact(), memory.getFactKey(), memory.getCreatedAt());
    }
}
