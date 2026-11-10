package com.bot.botback.repository;

import com.bot.botback.model.MemoryVault;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Remembered facts. The {@code embedding} column (vector(384)) isn't mapped on the entity, so
 * loading facts doesn't drag their vectors along; it's written and searched with the native
 * queries below, which take vectors in pgvector's text form ({@code [0.1,0.2,...]}).
 */
public interface MemoryVaultRepository extends JpaRepository<MemoryVault, Long> {

    List<MemoryVault> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<MemoryVault> findTop150ByUserIdOrderByCreatedAtDesc(Long userId);
    List<MemoryVault> findByUserIdAndFactKeyIsNotNullOrderByFactKeyAsc(Long userId);

    @Transactional
    @Modifying
    @Query("delete from MemoryVault m where m.userId = :userId")
    int deleteAllByUser(@Param("userId") Long userId);

    /** Removes the older value of a keyed fact (e.g. the previous "squat_max"). */
    @Modifying
    @Query("delete from MemoryVault m where m.userId = :userId and m.factKey = :key")
    int deleteByUserAndKey(@Param("userId") Long userId, @Param("key") String key);

    @Modifying
    @Query(value = "update memory_vault set embedding = cast(:embedding as vector) where id = :id",
            nativeQuery = true)
    int setEmbedding(@Param("id") Long id, @Param("embedding") String embedding);

    /** The user's facts closest in meaning to the query vector (cosine distance, HNSW index). */
    @Query(value = """
            select * from memory_vault
            where user_id = :userId and embedding is not null
            order by embedding <=> cast(:query as vector)
            limit :limit
            """, nativeQuery = true)
    List<MemoryVault> findNearest(@Param("userId") Long userId, @Param("query") String query,
                                  @Param("limit") int limit);

    /**
     * Lets the HNSW scan keep going until it has found {@code limit} rows for this user, instead
     * of stopping after its first candidates, which may all belong to other users (pgvector 0.8+).
     * Only lasts until the end of the current transaction.
     */
    @Query(value = "select set_config('hnsw.iterative_scan', 'strict_order', true)", nativeQuery = true)
    String enableIterativeScan();

    @Query(value = "select extversion from pg_extension where extname = 'vector'", nativeQuery = true)
    String vectorExtensionVersion();

    interface FactToEmbed {
        Long getId();
        String getFact();
    }

    /** Facts that still need a vector: saved while the embedding service was down, or after a model change. */
    @Query(value = "select id, fact from memory_vault where embedding is null order by id limit :limit",
            nativeQuery = true)
    List<FactToEmbed> findWithoutEmbedding(@Param("limit") int limit);

    /** Fills in a missing vector; does nothing if another instance already did. */
    @Modifying
    @Query(value = "update memory_vault set embedding = cast(:embedding as vector) "
            + "where id = :id and embedding is null", nativeQuery = true)
    int fillEmbedding(@Param("id") Long id, @Param("embedding") String embedding);

    /** Locks the model marker so only one instance resets the vectors after a model change. */
    @Modifying
    @Query(value = "lock table embedding_meta in exclusive mode", nativeQuery = true)
    void lockEmbeddingMeta();

    @Query(value = "select model from embedding_meta limit 1", nativeQuery = true)
    String embeddingModel();

    @Modifying
    @Query(value = "update memory_vault set embedding = null", nativeQuery = true)
    int clearAllEmbeddings();

    @Modifying
    @Query(value = "delete from embedding_meta", nativeQuery = true)
    void clearEmbeddingModel();

    @Modifying
    @Query(value = "insert into embedding_meta (model) values (:model)", nativeQuery = true)
    void recordEmbeddingModel(@Param("model") String model);
}
