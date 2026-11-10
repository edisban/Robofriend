package com.bot.botback.service;

import com.bot.botback.model.MemoryVault;
import com.bot.botback.repository.MemoryVaultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * What RoboFriend remembers about each user. Facts live in memory_vault with a sentence vector
 * from the embedding service, so they can be searched by meaning with pgvector.
 *
 * <p>The slow embedding call is made outside any transaction. If the embedding service is down,
 * the fact is still saved and {@link EmbeddingMaintenance} adds its vector later.
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final MemoryVaultRepository memoryVaultRepository;
    private final EmbeddingClient embeddingClient;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnlyTransaction;
    private volatile Boolean iterativeScanSupported;

    public MemoryService(MemoryVaultRepository memoryVaultRepository, EmbeddingClient embeddingClient,
                         PlatformTransactionManager transactionManager) {
        this.memoryVaultRepository = memoryVaultRepository;
        this.embeddingClient = embeddingClient;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    /** A remembered fact plus when it was saved, so relative words like "tomorrow" can be dated. */
    public record Memory(String fact, OffsetDateTime savedAt) {
    }

    /** Facts that hold a current value (personal records, job, city...), one per key. */
    public List<Memory> profileFacts(Long userId) {
        try {
            return memoryVaultRepository.findByUserIdAndFactKeyIsNotNullOrderByFactKeyAsc(userId).stream()
                    .map(MemoryService::toMemory)
                    .toList();
        } catch (Exception e) {
            log.warn("Profile lookup failed for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /** Most recent memories, newest first; used for the daily check-in. */
    public List<Memory> recentMemories(Long userId) {
        try {
            return memoryVaultRepository.findTop150ByUserIdOrderByCreatedAtDesc(userId).stream()
                    .map(MemoryService::toMemory)
                    .toList();
        } catch (Exception e) {
            log.warn("Recent memory lookup failed for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /** The user's facts closest in meaning to {@code query}; empty when search isn't available. */
    public List<Memory> searchMemories(Long userId, String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            String vector = EmbeddingClient.toVectorLiteral(embeddingClient.embed(query));
            return searchByVector(userId, vector, limit).stream().map(MemoryService::toMemory).toList();
        } catch (Exception e) {
            log.warn("Memory search failed for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /** Nearest facts for a vector in pgvector's text form; exposed for tests. */
    List<MemoryVault> searchByVector(Long userId, String vector, int limit) {
        return readOnlyTransaction.execute(status -> {
            if (iterativeScanSupported()) {
                memoryVaultRepository.enableIterativeScan();
            }
            return memoryVaultRepository.findNearest(userId, vector, limit);
        });
    }

    /**
     * Saves a fact. A {@code key} (e.g. "squat_max") marks a value that changes over time: the new
     * fact replaces any earlier one with the same key.
     */
    public void ingestFact(Long userId, String fact, String key) {
        if (fact == null || fact.isBlank()) {
            return;
        }
        String vector = null;
        try {
            vector = EmbeddingClient.toVectorLiteral(embeddingClient.embed(fact));
        } catch (Exception e) {
            log.warn("Embedding failed for a fact of user {}; saving it and embedding later: {}",
                    userId, e.getMessage());
        }
        String embedding = vector;
        transaction.executeWithoutResult(status -> {
            if (key != null) {
                memoryVaultRepository.deleteByUserAndKey(userId, key);
            }
            MemoryVault row = new MemoryVault();
            row.setUserId(userId);
            row.setFact(fact);
            row.setFactKey(key);
            row = memoryVaultRepository.saveAndFlush(row);
            if (embedding != null) {
                memoryVaultRepository.setEmbedding(row.getId(), embedding);
            }
        });
        log.info("Saved fact for user {}", userId);
    }

    /** Iterative index scans arrived in pgvector 0.8; older versions just use the plain scan. */
    private boolean iterativeScanSupported() {
        Boolean supported = iterativeScanSupported;
        if (supported == null) {
            String version = memoryVaultRepository.vectorExtensionVersion();
            supported = version != null && atLeast(version, 0, 8);
            iterativeScanSupported = supported;
            if (!supported) {
                log.warn("pgvector {} has no iterative index scans; upgrade to 0.8+ for complete search "
                        + "results with many users", version);
            }
        }
        return supported;
    }

    static boolean atLeast(String version, int major, int minor) {
        String[] parts = version.split("\\.");
        try {
            int maj = Integer.parseInt(parts[0]);
            int min = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return maj > major || (maj == major && min >= minor);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Memory toMemory(MemoryVault row) {
        return new Memory(row.getFact(), row.getCreatedAt());
    }
}
