package com.bot.botback;

import com.bot.botback.service.ReminderNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reminder delivery against a real PostgreSQL: overlapping notifier runs (as with two backend
 * instances) deliver each due reminder exactly once. Skipped when Docker isn't available.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "app.auth.jwt-secret=integration-test-secret-0123456789-abcdef",
        "groq.api.key=",
        "memory.service.url=http://127.0.0.1:9",
        "spring.task.scheduling.enabled=false"
})
class ReminderDeliveryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ReminderNotifier notifier;

    @Test
    void concurrentRunsDeliverADueReminderExactlyOnce() throws Exception {
        Long userId = jdbc.queryForObject(
                "insert into users (username, password_hash) values ('it_reminders', 'x') returning id", Long.class);
        jdbc.update("""
                insert into reminders (user_id, title, scheduled_at, status)
                values (?, 'call mom', now() - interval '1 minute', 'PENDING')
                """, userId);

        int runs = 4;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            results.add(pool.submit(() -> {
                start.await();
                notifier.deliverDueReminders();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> result : results) {
            result.get();
        }
        pool.shutdown();

        Integer messages = jdbc.queryForObject(
                "select count(*) from chat_messages where user_id = ? and sender = 'BOT'", Integer.class, userId);
        assertEquals(1, messages);
        assertEquals("SENT", jdbc.queryForObject(
                "select status from reminders where user_id = ?", String.class, userId));
    }
}
