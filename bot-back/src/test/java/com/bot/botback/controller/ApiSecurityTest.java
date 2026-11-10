package com.bot.botback.controller;

import com.bot.botback.config.AuthProperties;
import com.bot.botback.config.PasswordEncoderConfig;
import com.bot.botback.config.StreamingConfig;
import com.bot.botback.model.User;
import com.bot.botback.repository.UserRepository;
import com.bot.botback.security.AuthCookies;
import com.bot.botback.security.ChatRateLimiter;
import com.bot.botback.security.CurrentUser;
import com.bot.botback.security.LoginRateLimiter;
import com.bot.botback.security.RefreshTokenService;
import com.bot.botback.security.SecurityConfig;
import com.bot.botback.service.AiService;
import com.bot.botback.service.ChatService;
import com.bot.botback.service.CheckInService;
import com.bot.botback.service.ListService;
import com.bot.botback.service.MemoryService;
import com.bot.botback.service.ReminderService;
import com.bot.botback.util.JwtUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The web layer with the real security setup (cookie JWT filter, CSRF, validation, error handling) and mocked services: who may call what, and how bad input is answered.
 */
@WebMvcTest(controllers = {ChatController.class, ReminderController.class, AuthController.class})
@Import({SecurityConfig.class, PasswordEncoderConfig.class, StreamingConfig.class,
        JwtUtil.class, CurrentUser.class, LoginRateLimiter.class, AuthCookies.class})
@EnableConfigurationProperties(AuthProperties.class)
@TestPropertySource(properties = {
        "app.auth.jwt-secret=test-secret-0123456789-abcdefghijklmnop",
        "app.auth.secure-cookies=true"
})
class ApiSecurityTest {

    private static final long ELENI_ID = 5L;
    private static final long SOMEONE_ELSE_ID = 6L;

    @Autowired private MockMvc mvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockitoBean private UserRepository userRepository;
    @MockitoBean private RefreshTokenService refreshTokenService;
    @MockitoBean private ChatService chatService;
    @MockitoBean private AiService aiService;
    @MockitoBean private CheckInService checkInService;
    @MockitoBean private ReminderService reminderService;
    @MockitoBean private ListService listService;
    @MockitoBean private MemoryService memoryService;
    @MockitoBean private ChatRateLimiter chatRateLimiter;

    private Cookie eleniSession;

    @BeforeEach
    void setUp() {
        User eleni = new User();
        eleni.setId(ELENI_ID);
        eleni.setUsername("eleni");
        eleni.setPasswordHash(passwordEncoder.encode("correct-horse-1"));
        when(userRepository.findByUsername("eleni")).thenReturn(Optional.of(eleni));
        when(userRepository.findById(ELENI_ID)).thenReturn(Optional.of(eleni));
        when(userRepository.existsById(ELENI_ID)).thenReturn(true);
        when(userRepository.findById(ELENI_ID)).thenReturn(Optional.of(eleni));
        when(chatService.history(ELENI_ID, null, null, 50)).thenReturn(List.of());
        when(refreshTokenService.issue(anyLong())).thenReturn(new RefreshTokenService.Issued(ELENI_ID, "refresh-123"));
        eleniSession = new Cookie(AuthCookies.ACCESS, jwtUtil.generate(ELENI_ID));
    }

    @Test
    void requestsWithoutSessionGet401() throws Exception {
        mvc.perform(get("/api/chat/history/" + ELENI_ID)).andExpect(status().isUnauthorized());
    }

    @Test
    void forgedAccessTokenIsRejected() throws Exception {
        JwtUtil other = new JwtUtil(new AuthProperties("some-other-secret-0123456789-abcdefgh",
                Duration.ofMinutes(5), Duration.ofDays(1), true));
        mvc.perform(get("/api/chat/history/" + ELENI_ID).cookie(new Cookie(AuthCookies.ACCESS, other.generate(ELENI_ID))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bearerHeaderIsNoLongerAccepted() throws Exception {
        mvc.perform(get("/api/chat/history/" + ELENI_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtUtil.generate(ELENI_ID)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usersCanReadTheirOwnHistory() throws Exception {
        mvc.perform(get("/api/chat/history/" + ELENI_ID).cookie(eleniSession)).andExpect(status().isOk());
    }

    @Test
    void usersCannotReadSomeoneElsesData() throws Exception {
        mvc.perform(get("/api/chat/history/" + SOMEONE_ELSE_ID).cookie(eleniSession))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reminders/" + SOMEONE_ELSE_ID).cookie(eleniSession))
                .andExpect(status().isForbidden());
    }

    @Test
    void stateChangingRequestsNeedACsrfToken() throws Exception {
        mvc.perform(patch("/api/reminders/1").cookie(eleniSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DONE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginSetsHttpOnlyCookiesAndReturnsNoToken() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"eleni\",\"password\":\"correct-horse-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(ELENI_ID))
                .andExpect(jsonPath("$.username").value("eleni"))
                .andExpect(content().string(not(containsString("token"))))
                .andReturn();

        List<String> setCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertTrue(setCookies.stream().anyMatch(c -> c.startsWith(AuthCookies.ACCESS + "=")
                && c.contains("HttpOnly") && c.contains("SameSite=Strict") && c.contains("Secure")));
        assertTrue(setCookies.stream().anyMatch(c -> c.startsWith(AuthCookies.REFRESH + "=refresh-123")
                && c.contains("HttpOnly") && c.contains("Path=/api/auth")));
    }

    @Test
    void refreshWithoutValidSessionIs401AndClearsCookies() throws Exception {
        when(refreshTokenService.rotate("stolen")).thenReturn(Optional.empty());
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(new Cookie(AuthCookies.REFRESH, "stolen")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.hasItem(containsString("Max-Age=0"))));
    }

    @Test
    void registrationEnforcesPasswordRules() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Password must be 8-128 characters."));
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"newuser\",\"password\":\"" + "x".repeat(129) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedJsonIsABadRequestNotAServerError() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Malformed request."));
    }

    @Test
    void overlongMessagesAreRejected() throws Exception {
        mvc.perform(post("/api/chat/stream").with(csrf()).cookie(eleniSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + "x".repeat(4001) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chatOverTheRateLimitIs429WithRetryAfterAndNothingIsSaved() throws Exception {
        when(chatRateLimiter.tryAcquire(ELENI_ID)).thenReturn(Optional.of(
                new ChatRateLimiter.Blocked(ChatRateLimiter.Limit.MINUTE, Duration.ofSeconds(42))));

        mvc.perform(post("/api/chat/stream").with(csrf()).cookie(eleniSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.limit").value("minute"))
                .andExpect(jsonPath("$.error").value(containsString("Slow down")));
        verify(chatService, never()).startTurn(anyLong(), any());

        mvc.perform(post("/api/chat/checkin").with(csrf()).cookie(eleniSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests());
        verify(checkInService, never()).checkIn(anyLong(), any());
    }

    @Test
    void invalidReminderStatusIsRejected() throws Exception {
        mvc.perform(patch("/api/reminders/1").with(csrf()).cookie(eleniSession)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LOL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid reminder status."));
    }

    @Test
    void unknownPathsAre404NotServerErrors() throws Exception {
        mvc.perform(get("/api/does-not-exist").cookie(eleniSession)).andExpect(status().isNotFound());
    }
}
