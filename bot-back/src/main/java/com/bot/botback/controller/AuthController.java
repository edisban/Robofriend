package com.bot.botback.controller;

import com.bot.botback.dto.LoginRequest;
import com.bot.botback.dto.RegisterRequest;
import com.bot.botback.dto.SessionResponse;
import com.bot.botback.model.User;
import com.bot.botback.repository.UserRepository;
import com.bot.botback.security.AuthCookies;
import com.bot.botback.security.CurrentUser;
import com.bot.botback.security.LoginRateLimiter;
import com.bot.botback.security.RefreshTokenService;
import com.bot.botback.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sign-up, sign-in and the cookie-based session: a short-lived access token plus a rotating
 * refresh token, both in HttpOnly cookies. Responses never contain tokens.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final LoginRateLimiter rateLimiter;
    private final RefreshTokenService refreshTokens;
    private final AuthCookies cookies;
    private final CurrentUser currentUser;
    private volatile String dummyHash;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil,
                          LoginRateLimiter rateLimiter, RefreshTokenService refreshTokens, AuthCookies cookies,
                          CurrentUser currentUser) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.rateLimiter = rateLimiter;
        this.refreshTokens = refreshTokens;
        this.cookies = cookies;
        this.currentUser = currentUser;
    }

    /** Hands the app its CSRF cookie before the first sign-in (Spring sets XSRF-TOKEN on the way out). */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(CsrfToken token) {
        token.getToken();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
        String ip = clientIp(http);
        Optional<Duration> blocked = rateLimiter.registerBlockedFor(ip);
        if (blocked.isPresent()) {
            return tooManyAttempts(blocked.get());
        }
        if (userRepository.findByUsername(req.getUsername()).isPresent()) {
            return error(HttpStatus.CONFLICT, "That username is taken.");
        }
        User user = new User();
        user.setUsername(req.getUsername());
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        userRepository.save(user);
        rateLimiter.recordRegistration(ip);
        return startSession(user);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        String ip = clientIp(http);
        String username = req.getUsername();
        Optional<Duration> blocked = rateLimiter.loginBlockedFor(ip, username);
        if (blocked.isPresent()) {
            return tooManyAttempts(blocked.get());
        }
        Optional<User> found = userRepository.findByUsername(username);
        // Check a password even for unknown usernames, so the response time doesn't reveal which exist.
        String hash = found.map(User::getPasswordHash).orElseGet(this::dummyHash);
        boolean matches = passwordEncoder.matches(req.getPassword(), hash);
        if (found.isEmpty() || !matches) {
            rateLimiter.recordFailedLogin(ip, username);
            return error(HttpStatus.UNAUTHORIZED, "Wrong username or password.");
        }
        rateLimiter.resetLogin(ip, username);
        User user = found.get();
        upgradeHashIfNeeded(user, req.getPassword());
        return startSession(user);
    }

    /** Re-hashes an old (BCrypt) password with Argon2id now that we briefly have it in plain text. */
    private void upgradeHashIfNeeded(User user, String password) {
        if (!passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            return;
        }
        try {
            user.setPasswordHash(passwordEncoder.encode(password));
            userRepository.save(user);
            log.info("Upgraded the password hash of user {} to Argon2id", user.getId());
        } catch (Exception e) {
            // Signing in matters more; it will be tried again next time.
            log.warn("Could not upgrade the password hash of user {}: {}", user.getId(), e.getMessage());
        }
    }

    /** A real hash to check against when the username doesn't exist; made once. */
    private String dummyHash() {
        String hash = dummyHash;
        if (hash == null) {
            hash = passwordEncoder.encode(UUID.randomUUID().toString());
            dummyHash = hash;
        }
        return hash;
    }

    /** Swaps the refresh cookie for a new pair of cookies; 401 when the session is over. */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest http) {
        Optional<RefreshTokenService.Issued> issued = AuthCookies.read(http, AuthCookies.REFRESH)
                .flatMap(token -> refreshTokens.rotate(token));
        Optional<User> user = issued.flatMap(i -> userRepository.findById(i.userId()));
        if (user.isEmpty()) {
            return cookies.cleared(ResponseEntity.status(HttpStatus.UNAUTHORIZED)).build();
        }
        return cookies.withSession(ResponseEntity.ok(), jwtUtil.generate(user.get().getUsername()), issued.get().token())
                .body(SessionResponse.of(user.get()));
    }

    /** Ends this session: the refresh token is revoked and both cookies are removed. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest http) {
        AuthCookies.read(http, AuthCookies.REFRESH).ifPresent(token -> refreshTokens.revoke(token));
        return cookies.cleared(ResponseEntity.noContent()).build();
    }

    /** Who is signed in; the app calls this on start instead of keeping the user in localStorage. */
    @GetMapping("/me")
    public SessionResponse me() {
        return userRepository.findById(currentUser.id())
                .map(user -> SessionResponse.of(user))
                .orElseThrow();
    }

    private ResponseEntity<?> startSession(User user) {
        String refreshToken = refreshTokens.issue(user.getId()).token();
        return cookies.withSession(ResponseEntity.ok(), jwtUtil.generate(user.getUsername()), refreshToken)
                .body(SessionResponse.of(user));
    }

    private static ResponseEntity<Map<String, String>> tooManyAttempts(Duration wait) {
        long minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(wait.toSeconds()))
                .body(Map.of("error", "Too many attempts. Try again in " + minutes + " minute" + (minutes == 1 ? "" : "s") + "."));
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    /** nginx passes the real client address in X-Real-IP. */
    private static String clientIp(HttpServletRequest http) {
        String realIp = http.getHeader("X-Real-IP");
        return realIp != null && !realIp.isBlank() ? realIp.trim() : http.getRemoteAddr();
    }
}
