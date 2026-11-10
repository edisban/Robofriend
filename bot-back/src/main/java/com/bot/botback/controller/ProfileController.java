package com.bot.botback.controller;

import com.bot.botback.dto.DeleteAccountRequest;
import com.bot.botback.dto.ProfileResponse;
import com.bot.botback.dto.UpdateProfileRequest;
import com.bot.botback.security.AuthCookies;
import com.bot.botback.security.CurrentUser;
import com.bot.botback.security.LoginRateLimiter;
import com.bot.botback.service.ProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** The signed-in user's profile page: view and edit it, or delete the account. */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;
    private final CurrentUser currentUser;
    private final AuthCookies cookies;
    private final LoginRateLimiter rateLimiter;

    public ProfileController(ProfileService profileService, CurrentUser currentUser, AuthCookies cookies,
                             LoginRateLimiter rateLimiter) {
        this.profileService = profileService;
        this.currentUser = currentUser;
        this.cookies = cookies;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public ProfileResponse get() {
        return ProfileResponse.of(profileService.get(currentUser.id()));
    }

    @PutMapping
    public ProfileResponse update(@Valid @RequestBody UpdateProfileRequest request) {
        return ProfileResponse.of(profileService.update(currentUser.id(), request));
    }

    /**
     * Deletes the account and everything in it, then signs out. Needs the password; wrong guesses
     * count towards the same limit as failed logins.
     */
    @DeleteMapping
    public ResponseEntity<?> delete(@Valid @RequestBody DeleteAccountRequest request, HttpServletRequest http) {
        Long userId = currentUser.id();
        String ip = AuthController.clientIp(http);
        String key = "delete:" + userId;
        Optional<Duration> blocked = rateLimiter.loginBlockedFor(ip, key);
        if (blocked.isPresent()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(blocked.get().toSeconds()))
                    .body(Map.of("error", "Too many attempts. Try again later."));
        }
        if (!profileService.deleteAccount(userId, request.password())) {
            rateLimiter.recordFailedLogin(ip, key);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Wrong password."));
        }
        return cookies.cleared(ResponseEntity.noContent()).build();
    }
}
