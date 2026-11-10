package com.bot.botback.service;

import com.bot.botback.repository.MemoryVaultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Keeps every remembered fact searchable:
 * <ul>
 *   <li>when the embedding model changes, old vectors are cleared (vectors from different models
 *       can't be compared), and</li>
 *   <li>facts without a vector (after a model change, or saved while the embedding service was
 *       down) get one, a batch at a time.</li>
 * </ul>
 * Safe with several backend instances: the model switch happens under a table lock, and a vector
 * is only filled in where it is still missing.
 */
@Component
public class EmbeddingMaintenance {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingMaintenance.class);
    static final int MAX_FACTS_PER_RUN = 2000;

    private final MemoryVaultRepository repository;
    private final EmbeddingClient embeddingClient;
    private final TransactionTemplate transaction;

    public EmbeddingMaintenance(MemoryVaultRepository repository, EmbeddingClient embeddingClient,
                                PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 5 * 60_000)
    public void run() {
        String model = embeddingClient.currentModel();
        if (model == null) {
            return; // Embedding service not up yet; try again next run.
        }
        try {
            resetIfModelChanged(model);
            int filled = backfill();
            if (filled > 0) {
                log.info("Embedded {} facts that had no vector", filled);
            }
        } catch (Exception e) {
            log.warn("Embedding maintenance failed: {}", e.getMessage());
        }
    }

    /** Clears all vectors once when the service starts using a different model. */
    void resetIfModelChanged(String model) {
        transaction.executeWithoutResult(status -> {
            repository.lockEmbeddingMeta();
            String stored = repository.embeddingModel();
            if (model.equals(stored)) {
                return;
            }
            int cleared = stored == null ? 0 : repository.clearAllEmbeddings();
            repository.clearEmbeddingModel();
            repository.recordEmbeddingModel(model);
            log.info("Embedding model is now {} (was {}); {} facts will be re-embedded", model, stored, cleared);
        });
    }

    /** Embeds facts that have no vector yet; returns how many were filled in. */
    int backfill() {
        int filled = 0;
        while (filled < MAX_FACTS_PER_RUN) {
            List<MemoryVaultRepository.FactToEmbed> batch = repository.findWithoutEmbedding(EmbeddingClient.MAX_BATCH);
            if (batch.isEmpty()) {
                break;
            }
            List<float[]> vectors = embeddingClient.embed(batch.stream().map(f -> f.getFact()).toList()).vectors();
            transaction.executeWithoutResult(status -> {
                for (int i = 0; i < batch.size(); i++) {
                    repository.fillEmbedding(batch.get(i).getId(), EmbeddingClient.toVectorLiteral(vectors.get(i)));
                }
            });
            filled += batch.size();
        }
        return filled;
    }
}
