package com.bot.botback.security;

import com.bot.botback.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Authenticates requests from the access-token cookie. */
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    public JwtFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        // The principal is the user id; tokens without one (issued before the switch) are ignored,
        // so the request is unauthenticated and the app renews its session with the refresh cookie.
        AuthCookies.read(req, AuthCookies.ACCESS)
                .filter(token -> jwtUtil.isValid(token))
                .flatMap(token -> jwtUtil.extractUserId(token))
                .ifPresent(userId -> SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(String.valueOf(userId), null, List.of())));
        chain.doFilter(req, res);
    }
}
