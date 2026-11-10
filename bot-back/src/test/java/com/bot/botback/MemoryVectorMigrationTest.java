package com.bot.botback;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * V5 on a database that already has memories stored the old way (embeddings as text): valid
 * vectors are converted, unusable ones are cleared for re-embedding, and no fact is lost.
 */
@Testcontainers(disabledWithoutDocker = true)
class MemoryVectorMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static String textVector(int dims, double value) {
        return "[" + String.join(",", Collections.nCopies(dims, Double.toString(value))) + "]";
    }

    @Test
    void convertsExistingTextEmbeddingsWithoutLosingFacts() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Flyway.configure().dataSource(dataSource).target("4").load().migrate();
        Long userId = jdbc.queryForObject(
                "insert into users (username, password_hash) values ('old_db', 'x') returning id", Long.class);
        String insert = "insert into memory_vault (user_id, fact, embedding) values (?, ?, ?)";
        jdbc.update(insert, userId, "valid", textVector(384, 0.01));
        jdbc.update(insert, userId, "valid with spaces", " [ " + textVector(384, -0.5).substring(1).replace(",", " , "));
        jdbc.update(insert, userId, "wrong dimension", textVector(3, 0.1));
        jdbc.update(insert, userId, "garbage", "not a vector");
        jdbc.update(insert, userId, "no embedding", null);

        Flyway.configure().dataSource(dataSource).load().migrate();

        assertEquals(5, jdbc.queryForObject("select count(*) from memory_vault", Integer.class), "no fact is lost");
        assertEquals("vector", jdbc.queryForObject("""
                select udt_name from information_schema.columns
                where table_name = 'memory_vault' and column_name = 'embedding'
                """, String.class));
        assertEquals(384, jdbc.queryForObject(
                "select vector_dims(embedding) from memory_vault where fact = 'valid'", Integer.class));
        assertNotNull(jdbc.queryForObject(
                "select embedding from memory_vault where fact = 'valid with spaces'", String.class));
        for (String fact : new String[] {"wrong dimension", "garbage", "no embedding"}) {
            assertNull(jdbc.queryForObject(
                    "select embedding from memory_vault where fact = ?", String.class, fact), fact);
        }
        assertEquals("hnsw", jdbc.queryForObject("""
                select am.amname from pg_index i
                join pg_class c on c.oid = i.indexrelid
                join pg_am am on am.oid = c.relam
                where c.relname = 'idx_memory_vault_embedding'
                """, String.class));
    }
}
