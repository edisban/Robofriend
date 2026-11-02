package com.bot.botback.controller;

import com.bot.botback.config.PasswordEncoderConfig;
import com.bot.botback.config.StreamingConfig;
import com.bot.botback.config.WebConfig;
import com.bot.botback.model.User;
import com.bot.botback.repository.UserRepository;
import com.bot.botback.security.CurrentUser;
import com.bot.botback.security.LoginRateLimiter;
import com.bot.botback.security.SecurityConfig;
import com.bot.botback.service.AiService;
import com.bot.botback.service.ChatService;
import com.bot.botback.service.CheckInService;
import com.bot.botback.service.ListService;
import com.bot.botback.service.MemoryService;
import com.bot.botback.service.ReminderService;
import com.bot.botback.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The web layer with the real security setup (JWT filter, CORS, validation, error handling) and
 * mocked services: who may call what, and how bad input is answered.
 */
@WebMvcTest(controllers = {ChatController.class, ReminderController.class, AuthController.class})
@Import({SecurityConfig.class, WebConfig.class, PasswordEncoderConfig.class, StreamingConfig.class,
        JwtUtil.class, CurrentUser.class, LoginRateLimiter.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-0123456789-abcdefghijklmnop",
        "app.cors.allowed-origins=http://localhost:5173"
})
class ApiSecurityTest {

    private static final long ELENI_ID = 5L;
    private static final long SOMEONE_ELSE_ID = 6L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ChatService chatService;
    @MockitoBean private AiService aiService;
    @MockitoBean private CheckInService checkInService;
    @MockitoBean private ReminderService reminderService;
    @MockitoBean private ListService listService;
    @MockitoBean private MemoryService memoryService;

    private String eleniToken;

    @BeforeEach
    void setUp() {
        User eleni = new User();
        eleni.setId(ELENI_ID);
        eleni.setUsername("eleni");
        when(userRepository.findByUsername("eleni")).thenReturn(Optional.of(eleni));
        when(chatService.history(ELENI_ID, null)).thenReturn(List.of());
        eleniToken = "Bearer " + jwtUtil.generate("eleni");
    }

    @Test
    void requestsWithoutTokenAreRejected() throws Exception {
        mvc.perform(get("/api/chat/history/" + ELENI_ID)).andExpect(status().isForbidden());
    }

    @Test
    void forgedTokenIsRejected() throws Exception {
        String forged = new JwtUtil("some-other-secret-0123456789-abcdefgh", 60_000).generate("eleni");
        mvc.perform(get("/api/chat/history/" + ELENI_ID).header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isForbidden());
    }

    @Test
    void usersCanReadTheirOwnHistory() throws Exception {
        mvc.perform(get("/api/chat/history/" + ELENI_ID).header(HttpHeaders.AUTHORIZATION, eleniToken))
                .andExpect(status().isOk());
    }

    @Test
    void usersCannotReadSomeoneElsesData() throws Exception {
        mvc.perform(get("/api/chat/history/" + SOMEONE_ELSE_ID).header(HttpHeaders.AUTHORIZATION, eleniToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reminders/" + SOMEONE_ELSE_ID).header(HttpHeaders.AUTHORIZATION, eleniToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void registrationEnforcesPasswordRules() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Password must be 8-72 characters."));
    }

    @Test
    void malformedJsonIsABadRequestNotAServerError() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\": oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed request."));
    }

    @Test
    void overlongMessagesAreRejected() throws Exception {
        String message = "x".repeat(4001);
        mvc.perform(post("/api/chat/stream").header(HttpHeaders.AUTHORIZATION, eleniToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + message + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidReminderStatusIsRejected() throws Exception {
        mvc.perform(patch("/api/reminders/1").header(HttpHeaders.AUTHORIZATION, eleniToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid reminder status."));
    }

    @Test
    void corsAllowsTheConfiguredFrontendOnly() throws Exception {
        mvc.perform(options("/api/chat/stream")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
        mvc.perform(options("/api/chat/stream")
                        .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }
}
