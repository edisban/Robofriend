package com.bot.botback.security;

import com.bot.botback.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the user behind the JWT of the current request, so controllers never
 * trust a client-supplied user id.
 */
@Component
public class CurrentUser {

    private final UserRepository userRepository;

    public CurrentUser(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Long id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return userRepository.findByUsername(auth.getName())
                .map(user -> user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    public void requireSelf(Long userId) {
        if (userId == null || !userId.equals(id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
