package com.bot.botback;

import com.bot.botback.security.AuthCookies;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The profile page's API against a real PostgreSQL: editing the profile, and deleting the account
 * with everything in it. Skipped when Docker isn't available.
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
class ProfileIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final List<String> USER_TABLES =
            List.of("chat_messages", "memory_vault", "reminders", "list_items", "refresh_tokens");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    private Cookie[] session;

    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger();

    private Cookie[] register(String username) throws Exception {
        // Each sign-up from its own address, so the sign-up rate limit (5 per hour per address) isn't hit.
        MvcResult result = mvc.perform(post("/api/auth/register").with(csrf())
                        .header("X-Real-IP", "10.0.0." + NEXT_ADDRESS.incrementAndGet())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"correct-horse-1\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return Arrays.stream(result.getResponse().getCookies())
                .filter(c -> c.getName().equals(AuthCookies.ACCESS) || c.getName().equals(AuthCookies.REFRESH))
                .toArray(Cookie[]::new);
    }

    private Long idOf(String username) {
        return jdbc.queryForObject("select id from users where username = ?", Long.class, username);
    }

    /** One row in every table that belongs to a user. */
    private void giveData(Long userId) {
        jdbc.update("insert into chat_messages (user_id, sender, content) values (?, 'USER', 'hi')", userId);
        jdbc.update("insert into memory_vault (user_id, fact) values (?, 'Has a cat')", userId);
        jdbc.update("insert into reminders (user_id, title, scheduled_at) values (?, 'Call mom', now() + interval '1 day')", userId);
        jdbc.update("insert into list_items (user_id, list_name, text, done, created_at) values (?, 'shopping', 'milk', false, now())", userId);
    }

    private int rowsOf(Long userId) {
        return USER_TABLES.stream()
                .mapToInt(table -> jdbc.queryForObject("select count(*) from " + table + " where user_id = ?", Integer.class, userId))
                .sum();
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from users");
        session = register("profile_owner");
    }

    @Test
    void readsAndUpdatesTheProfile() throws Exception {
        mvc.perform(get("/api/profile").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("profile_owner"))
                .andExpect(jsonPath("$.displayName").doesNotExist())
                .andExpect(jsonPath("$.memberSince").exists());

        mvc.perform(put("/api/profile").with(csrf()).cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"  Lena \",\"birthday\":\"1996-10-24\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Lena"))
                .andExpect(jsonPath("$.birthday").value("1996-10-24"));

        mvc.perform(put("/api/profile").with(csrf()).cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"\",\"birthday\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").doesNotExist())
                .andExpect(jsonPath("$.birthday").doesNotExist());
    }

    @Test
    void rejectsInvalidProfileDetails() throws Exception {
        for (String body : List.of(
                "{\"birthday\":\"2999-01-01\"}",
                "{\"birthday\":\"1850-01-01\"}",
                "{\"birthday\":\"not a date\"}",
                "{\"displayName\":\"" + "x".repeat(51) + "\"}",
                "{\"displayName\":\"two\\nlines\"}")) {
            mvc.perform(put("/api/profile").with(csrf()).cookie(session).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void wrongPasswordDeletesNothing() throws Exception {
        Long id = idOf("profile_owner");
        giveData(id);

        mvc.perform(delete("/api/profile").with(csrf()).cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong-horse-1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Wrong password."));

        assertEquals(1, jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, id));
        assertEquals(5, rowsOf(id), "4 data rows + the session");
    }

    @Test
    void deletingTheAccountRemovesEverythingOfTheirsAndSignsOut() throws Exception {
        Long id = idOf("profile_owner");
        giveData(id);
        register("someone_else");
        Long other = idOf("someone_else");
        giveData(other);

        mvc.perform(delete("/api/profile").with(csrf()).cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"correct-horse-1\"}"))
                .andExpect(status().isNoContent())
                .andExpect(header().stringValues("Set-Cookie", hasItem(startsWith(AuthCookies.ACCESS + "=;"))));

        assertEquals(0, jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, id));
        assertEquals(0, rowsOf(id), "messages, memories, reminders, lists and sessions are gone");
        assertEquals(5, rowsOf(other), "other users keep their data");
        // The old session no longer works, and the refresh cookie can't revive it.
        mvc.perform(get("/api/profile").cookie(session)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void aDeletedAccountsTokenCantOpenANewAccountWithTheSameName() throws Exception {
        mvc.perform(delete("/api/profile").with(csrf()).cookie(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"correct-horse-1\"}"))
                .andExpect(status().isNoContent());

        // Someone else signs up with the freed username while the old access token hasn't expired yet.
        Cookie[] newcomer = register("profile_owner");
        mvc.perform(get("/api/profile").cookie(newcomer)).andExpect(status().isOk());

        mvc.perform(get("/api/profile").cookie(session)).andExpect(status().isUnauthorized());
    }
}
