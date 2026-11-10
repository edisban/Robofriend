package com.bot.botback;

import com.bot.botback.security.AuthCookies;
import com.bot.botback.util.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Paging through a long conversation against a real PostgreSQL: the latest page first, then older
 * pages before a cursor, with no message skipped or repeated. Skipped when Docker isn't available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "app.auth.jwt-secret=integration-test-secret-0123456789-abcdef",
        "groq.api.key=",
        "memory.service.url=http://127.0.0.1:9",
        "spring.task.scheduling.enabled=false"
})
class ChatHistoryPagingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final int MESSAGES = 120;

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtUtil jwtUtil;
    private final ObjectMapper json = new ObjectMapper();

    private Long userId;
    private Cookie session;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from users");
        userId = jdbc.queryForObject(
                "insert into users (username, password_hash) values ('pager', 'x') returning id", Long.class);
        // Every message gets the same timestamp, so only the id can order them reliably.
        for (int i = 1; i <= MESSAGES; i++) {
            jdbc.update("insert into chat_messages (user_id, sender, content, created_at) values (?, ?, ?, '2026-11-10T10:00:00Z')",
                    userId, i % 2 == 1 ? "USER" : "BOT", "message " + i);
        }
        Long other = jdbc.queryForObject(
                "insert into users (username, password_hash) values ('someone_else', 'x') returning id", Long.class);
        jdbc.update("insert into chat_messages (user_id, sender, content) values (?, 'USER', 'not yours')", other);
        session = new Cookie(AuthCookies.ACCESS, jwtUtil.generate(userId));
    }

    private List<String> page(String query) throws Exception {
        String body = mvc.perform(get("/api/chat/history/" + userId + query).cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> contents = new ArrayList<>();
        for (JsonNode node : json.readTree(body)) {
            contents.add(node.get("content").asText());
        }
        return contents;
    }

    private Long idOf(String content) {
        return jdbc.queryForObject("select id from chat_messages where content = ?", Long.class, content);
    }

    @Test
    void pagesBackThroughTheWholeConversationOldestFirstWithinEachPage() throws Exception {
        List<String> latest = page("");
        assertEquals(50, latest.size(), "the app opens with the latest 50");
        assertEquals("message 71", latest.get(0));
        assertEquals("message 120", latest.get(49));

        List<String> older = page("?beforeId=" + idOf(latest.get(0)));
        assertEquals(50, older.size());
        assertEquals("message 21", older.get(0));
        assertEquals("message 70", older.get(49));

        List<String> oldest = page("?beforeId=" + idOf(older.get(0)));
        assertEquals(20, oldest.size(), "a short page means the start of the conversation");
        assertEquals("message 1", oldest.get(0));

        assertEquals(List.of(), page("?beforeId=" + idOf("message 1")));
    }

    @Test
    void newMessagesAfterACursorAndCustomPageSizes() throws Exception {
        assertEquals(List.of("message 119", "message 120"), page("?afterId=" + idOf("message 118")));
        assertEquals(List.of("message 118", "message 119", "message 120"), page("?limit=3"));
        assertEquals(100, page("?limit=500").size(), "page size is capped");
    }

    @Test
    void bothCursorsAtOnceIsABadRequest() throws Exception {
        mvc.perform(get("/api/chat/history/" + userId + "?afterId=1&beforeId=5").cookie(session))
                .andExpect(status().isBadRequest());
    }
}
