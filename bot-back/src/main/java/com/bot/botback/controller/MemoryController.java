package com.bot.botback.controller;

import com.bot.botback.dto.MemoryResponse;
import com.bot.botback.service.MemoryService;
import com.bot.botback.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryService memoryService;
    private final CurrentUser currentUser;

    public MemoryController(MemoryService memoryService, CurrentUser currentUser) {
        this.memoryService = memoryService;
        this.currentUser = currentUser;
    }

    @GetMapping("/{userId}")
    public List<MemoryResponse> getMemory(@PathVariable Long userId) {
        currentUser.requireSelf(userId);
        return memoryService.memoriesOf(userId).stream()
                .map(memory -> MemoryResponse.from(memory))
                .toList();
    }
}
