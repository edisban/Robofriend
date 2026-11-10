package com.bot.botback.service;

import com.bot.botback.model.MemoryVault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Memory storage and semantic search against a real PostgreSQL + pgvector, with the embedding
 * service mocked. Skipped when Docker isn't available.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "app.auth.jwt-secret=integration-test-secret-0123456789-abcdef",
        "groq.api.key=",
        "spring.task.scheduling.enabled=false"
})
class MemorySearchIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @MockitoBean private EmbeddingClient embeddingClient;
    @Autowired private MemoryService memoryService;
    @Autowired private EmbeddingMaintenance maintenance;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private Long alice;
    private Long bob;

    /** A unit vector along {@code axis}, tilted slightly towards {@code tiltAxis}. */
    private static float[] vector(int axis, int tiltAxis, float tilt) {
        float[] v = new float[EmbeddingClient.DIMENSION];
        v[axis] = 1f;
        v[tiltAxis] += tilt;
        return v;
    }

    /** The query direction (axis 0) plus random noise of the given spread on every other axis. */
    private static float[] noisy(Random random, double spread) {
        float[] v = new float[EmbeddingClient.DIMENSION];
        v[0] = 1f;
        for (int d = 1; d < v.length; d++) {
            v[d] = (float) (random.nextGaussian() * spread);
        }
        return v;
    }

    private void store(Long userId, String fact, float[] embedding) {
        Long id = jdbc.queryForObject(
                "insert into memory_vault (user_id, fact) values (?, ?) returning id", Long.class, userId, fact);
        jdbc.update("update memory_vault set embedding = cast(? as vector) where id = ?",
                EmbeddingClient.toVectorLiteral(embedding), id);
    }

    private Long user(String name) {
        return jdbc.queryForObject(
                "insert into users (username, password_hash) values (?, 'x') returning id", Long.class, name);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("delete from memory_vault");
        jdbc.update("delete from users");
        jdbc.update("delete from embedding_meta");
        alice = user("alice");
        bob = user("bob");
    }

    @Test
    void returnsTheUsersNearestFactsInOrder() {
        store(alice, "Has a cat called Miso", vector(0, 1, 0.1f));
        store(alice, "Works as a nurse", vector(1, 0, 0.0f));
        store(alice, "Loves hiking", vector(2, 0, 0.3f));
        store(bob, "Bob's fact right on the query", vector(0, 1, 0.0f));
        when(embeddingClient.embed(anyString())).thenReturn(vector(0, 1, 0.0f));

        List<MemoryService.Memory> found = memoryService.searchMemories(alice, "tell me about my pet", 5);

        assertEquals(List.of("Has a cat called Miso", "Loves hiking", "Works as a nurse"),
                found.stream().map(MemoryService.Memory::fact).toList(), "only Alice's facts, nearest first");
        assertNotNull(found.get(0).savedAt());
    }

    /**
     * Hundreds of another user's facts sit closer to the query than any of Alice's. Like real
     * sentence embeddings, every fact points its own way around a shared direction; how far it
     * strays decides how similar it is to the query.
     */
    private String storeAliceAmongManyCloserFacts() {
        Random random = new Random(42);
        store(alice, "Alice nearest", noisy(random, 0.03));
        store(alice, "Alice second", noisy(random, 0.045));
        store(alice, "Alice third", noisy(random, 0.06));
        for (int i = 0; i < 300; i++) {
            store(bob, "bob " + i, noisy(random, 0.012));
        }
        jdbc.execute("analyze memory_vault");
        return EmbeddingClient.toVectorLiteral(vector(0, 1, 0.0f));
    }

    private String plan(String query) {
        return String.join("\n", jdbc.queryForList("""
                explain select * from memory_vault where user_id = ? and embedding is not null
                order by embedding <=> cast(? as vector) limit 5
                """, String.class, alice, query));
    }

    @Test
    void searchIsExactForAUserAmongManyCloserFactsOfOthers() {
        String query = storeAliceAmongManyCloserFacts();

        // One user's facts are a small slice of the table, so the planner reads them through the
        // user_id index and ranks them exactly, rather than walking the (approximate) HNSW graph.
        assertTrue(plan(query).contains("idx_memory_vault_user"), plan(query));
        assertEquals(List.of("Alice nearest", "Alice second", "Alice third"),
                memoryService.searchByVector(alice, query, 5).stream().map(MemoryVault::getFact).toList());
    }

    @Test
    void theHnswIndexCanServeTheSearch() {
        String query = storeAliceAmongManyCloserFacts();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // What the planner does when a user's slice is large: walk the HNSW index.
            jdbc.queryForObject("select set_config('enable_seqscan', 'off', true)", String.class);
            jdbc.queryForObject("select set_config('enable_sort', 'off', true)", String.class);
            assertTrue(plan(query).contains("idx_memory_vault_embedding"), plan(query));

            // HNSW is approximate, so only check it returns Alice's facts, nearest first.
            List<String> found = memoryService.searchByVector(alice, query, 5).stream()
                    .map(MemoryVault::getFact).toList();
            assertTrue(!found.isEmpty() && found.stream().allMatch(f -> f.startsWith("Alice")), found.toString());
            List<String> inOrder = List.of("Alice nearest", "Alice second", "Alice third").stream()
                    .filter(found::contains).toList();
            assertEquals(inOrder, found);
        });
    }

    @Test
    void keyedFactsReplaceTheOlderValue() {
        when(embeddingClient.embed(anyString())).thenReturn(vector(0, 1, 0f));

        memoryService.ingestFact(alice, "Squat max is 100 kg", "squat_max");
        memoryService.ingestFact(alice, "Squat max is 120 kg", "squat_max");
        memoryService.ingestFact(alice, "Has a cat", null);

        assertEquals(List.of("Squat max is 120 kg"),
                memoryService.profileFacts(alice).stream().map(MemoryService.Memory::fact).toList());
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from memory_vault where user_id = ? and embedding is not null", Integer.class, alice));
    }

    @Test
    void factsSavedWhileTheEmbeddingServiceIsDownAreEmbeddedLater() {
        when(embeddingClient.embed(anyString())).thenThrow(new IllegalStateException("service down"));
        memoryService.ingestFact(alice, "Has a cat", null);
        assertNull(jdbc.queryForObject("select embedding::text from memory_vault where user_id = ?", String.class, alice),
                "the fact is kept even without a vector");

        when(embeddingClient.embed(anyList())).thenAnswer(call -> new EmbeddingClient.Embeddings("m1",
                ((List<?>) call.getArgument(0)).stream().map(t -> vector(0, 1, 0f)).toList()));
        assertEquals(1, maintenance.backfill());

        assertEquals(1, jdbc.queryForObject(
                "select count(*) from memory_vault where user_id = ? and embedding is not null", Integer.class, alice));
    }

    @Test
    void aModelChangeClearsTheVectorsOnce() {
        store(alice, "Has a cat", vector(0, 1, 0f));

        maintenance.resetIfModelChanged("model-a");
        assertEquals(1, jdbc.queryForObject("select count(embedding) from memory_vault", Integer.class),
                "the first recorded model keeps existing vectors");

        maintenance.resetIfModelChanged("model-a");
        assertEquals(1, jdbc.queryForObject("select count(embedding) from memory_vault", Integer.class));

        maintenance.resetIfModelChanged("model-b");
        assertEquals(0, jdbc.queryForObject("select count(embedding) from memory_vault", Integer.class));
        assertEquals("model-b", jdbc.queryForObject("select model from embedding_meta", String.class));
    }
}
