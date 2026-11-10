package com.bot.botback;

import com.bot.botback.repository.ChatMessageRepository;
import com.bot.botback.repository.UserRepository;
import com.bot.botback.security.AuthCookies;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole application against a real PostgreSQL (the same pgvector image as production):
 * Flyway migrations, Hibernate schema validation, cookie sessions and cascading deletes.
 * Skipped when Docker isn't available.
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
class AuthFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private ChatMessageRepository chatMessageRepository;

    @Test
    void migrationsCreateTheFullSchema() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class);
        assertEquals(List.of("1", "2", "3", "4", "5", "6"), versions);
        Integer tables = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name in
                  ('users', 'chat_messages', 'memory_vault', 'reminders', 'list_items', 'refresh_tokens')
                """, Integer.class);
        assertEquals(6, tables);
    }

    @Test
    void sessionLifecycleWithRotationTheftDetectionAndLogout() throws Exception {
        MvcResult registered = mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"it_eleni\",\"password\":\"correct-horse-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("it_eleni"))
                .andReturn();
        Cookie access = cookie(registered, AuthCookies.ACCESS);
        Cookie refresh = cookie(registered, AuthCookies.REFRESH);

        mvc.perform(get("/api/auth/me").cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("it_eleni"));

        MvcResult rotated = mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(refresh))
                .andExpect(status().isOk())
                .andReturn();
        Cookie newRefresh = cookie(rotated, AuthCookies.REFRESH);
        assertNotEquals(refresh.getValue(), newRefresh.getValue());

        // A copy of the old token is used again: every session of the user ends.
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(refresh)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(newRefresh)).andExpect(status().isUnauthorized());

        MvcResult signedIn = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"it_eleni\",\"password\":\"correct-horse-1\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie loginRefresh = cookie(signedIn, AuthCookies.REFRESH);
        mvc.perform(post("/api/auth/logout").with(csrf()).cookie(loginRefresh)).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(loginRefresh)).andExpect(status().isUnauthorized());
    }

    @Test
    void messagesArePersistedAndPrivate() throws Exception {
        Cookie alice = register("it_alice");
        Cookie bob = register("it_bob");
        Long aliceId = userRepository.findByUsername("it_alice").orElseThrow().getId();

        // No Groq key in tests, so RoboFriend answers with its local fallback.
        chat(alice, "hello from an integration test");

        mvc.perform(get("/api/chat/history/" + aliceId).cookie(alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sender").value("USER"))
                .andExpect(jsonPath("$[1].sender").value("BOT"));
        mvc.perform(get("/api/chat/history/" + aliceId).cookie(bob)).andExpect(status().isForbidden());
    }

    @Test
    void deletingAUserRemovesAllTheirData() throws Exception {
        Cookie session = register("it_leaving");
        Long userId = userRepository.findByUsername("it_leaving").orElseThrow().getId();
        chat(session, "remember me");
        jdbc.update("insert into list_items(user_id, list_name, text, done) values (?, 'shopping', 'milk', false)", userId);

        userRepository.deleteById(userId);

        assertEquals(0, chatMessageRepository.findByUserIdOrderByCreatedAtAsc(userId).size());
        assertEquals(0, count("list_items", userId));
        assertEquals(0, count("refresh_tokens", userId));
    }

    private Cookie register(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"correct-horse-1\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return cookie(result, AuthCookies.ACCESS);
    }

    /** Sends a message through the streaming endpoint and waits for the reply to finish. */
    private void chat(Cookie session, String message) throws Exception {
        MvcResult started = mvc.perform(post("/api/chat/stream").with(csrf()).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + message + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk());
    }

    private int count(String table, Long userId) {
        Integer n = jdbc.queryForObject("select count(*) from " + table + " where user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    @Test
    void oldBcryptPasswordsStillSignInAndAreUpgradedToArgon2id() throws Exception {
        jdbc.update("insert into users (username, password_hash) values ('it_legacy', ?)",
                new BCryptPasswordEncoder().encode("old-password-1"));
        String login = "{\"username\":\"it_legacy\",\"password\":\"old-password-1\"}";

        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isOk());

        String hash = jdbc.queryForObject("select password_hash from users where username = 'it_legacy'", String.class);
        assertTrue(hash.startsWith("{argon2}$argon2id$"), hash);
        // Signing in keeps working with the new hash, and wrong passwords are still refused.
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"it_legacy\",\"password\":\"old-password-2\"}"))
                .andExpect(status().isUnauthorized());
        assertEquals(hash, jdbc.queryForObject(
                "select password_hash from users where username = 'it_legacy'", String.class), "upgraded only once");
    }

    @Test
    void unknownUsernamesAreRefusedLikeWrongPasswords() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody_here\",\"password\":\"whatever-1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Wrong username or password."));
    }

    private static Cookie cookie(MvcResult result, String name) {
        Cookie cookie = Arrays.stream(result.getResponse().getCookies())
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .orElse(null);
        assertNotNull(cookie, "missing cookie " + name);
        return cookie;
    }
}
