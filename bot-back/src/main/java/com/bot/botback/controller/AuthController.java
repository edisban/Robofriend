package com.bot.botback.controller;

import com.bot.botback.dto.LoginRequest;
import com.bot.botback.dto.RegisterRequest;
import com.bot.botback.model.User;
import com.bot.botback.repository.UserRepository;
import com.bot.botback.security.LoginRateLimiter;
import com.bot.botback.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final LoginRateLimiter rateLimiter;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil,
                          LoginRateLimiter rateLimiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
        String ip = clientIp(http);
        Optional<Duration> blocked = rateLimiter.registerBlockedFor(ip);
        if (blocked.isPresent()) {
            return tooManyAttempts(blocked.get());
        }
        String username = req.getUsername();
        String password = req.getPassword();
        if (userRepository.findByUsername(username).isPresent()) {
            return error(HttpStatus.CONFLICT, "That username is taken.");
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        userRepository.save(user);
        rateLimiter.recordRegistration(ip);
        return ResponseEntity.ok(session(user));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        String ip = clientIp(http);
        String username = req.getUsername();
        Optional<Duration> blocked = rateLimiter.loginBlockedFor(ip, username);
        if (blocked.isPresent()) {
            return tooManyAttempts(blocked.get());
        }
        Optional<User> user = userRepository.findByUsername(username)
                .filter(u -> passwordEncoder.matches(req.getPassword(), u.getPasswordHash()));
        if (user.isEmpty()) {
            rateLimiter.recordFailedLogin(ip, username);
            return error(HttpStatus.UNAUTHORIZED, "Wrong username or password.");
        }
        rateLimiter.resetLogin(ip, username);
        return ResponseEntity.ok(session(user.get()));
    }

    private Map<String, Object> session(User user) {
        return Map.of("token", jwtUtil.generate(user.getUsername()),
                "userId", user.getId(),
                "username", user.getUsername());
    }

    private static ResponseEntity<Map<String, Object>> tooManyAttempts(Duration wait) {
        long minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(wait.toSeconds()))
                .body(Map.of("error", "Too many attempts. Try again in " + minutes + " minute" + (minutes == 1 ? "" : "s") + "."));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    /** nginx passes the real client address in X-Real-IP. */
    private static String clientIp(HttpServletRequest http) {
        String realIp = http.getHeader("X-Real-IP");
        return realIp != null && !realIp.isBlank() ? realIp.trim() : http.getRemoteAddr();
    }
}
