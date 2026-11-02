package com.bot.botback.controller;

import com.bot.botback.dto.MemoryResponse;
import com.bot.botback.repository.MemoryVaultRepository;
import com.bot.botback.security.CurrentUser;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/memory")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173", "http://localhost:5174", "http://127.0.0.1:5174", "http://localhost:80"})
public class MemoryController {

    private final MemoryVaultRepository memoryVaultRepository;
    private final CurrentUser currentUser;

    public MemoryController(MemoryVaultRepository memoryVaultRepository, CurrentUser currentUser) {
        this.memoryVaultRepository = memoryVaultRepository;
        this.currentUser = currentUser;
    }

    @GetMapping("/{userId}")
    public List<MemoryResponse> getMemory(@PathVariable Long userId) {
        currentUser.requireSelf(userId);
        return memoryVaultRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(memory -> MemoryResponse.from(memory))
                .toList();
    }
}
