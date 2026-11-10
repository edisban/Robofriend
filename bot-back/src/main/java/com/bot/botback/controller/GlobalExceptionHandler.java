package com.bot.botback.controller;

import com.bot.botback.security.ChatRateLimitedException;
import com.bot.botback.security.ChatRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException ex) {
        String reason = ex.getReason() != null ? ex.getReason() : HttpStatus.valueOf(ex.getStatusCode().value()).getReasonPhrase();
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("error", reason));
    }

    /** Too many AI replies: 429 with when to try again, and which limit was hit ("minute" or "day"). */
    @ExceptionHandler(ChatRateLimitedException.class)
    public ResponseEntity<Map<String, String>> handleChatRateLimited(ChatRateLimitedException ex) {
        ChatRateLimiter.Blocked blocked = ex.blocked();
        boolean daily = blocked.limit() == ChatRateLimiter.Limit.DAY;
        String message = daily
                ? "You've reached today's message limit. Let's pick this up a bit later."
                : "Slow down a little. Try again in a moment.";
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(Math.max(1, blocked.retryAfter().toSeconds())))
                .body(Map.of("error", message, "limit", daily ? "day" : "minute"));
    }

    /** @Valid failures: report the first problem in words the user can act on. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse("Invalid request.");
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    /** Missing or malformed JSON body. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "Malformed request."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Invalid request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        log.error("Illegal state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "The request could not be completed."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneric(Exception ex) {
        // Spring's own web errors (unknown path 404, wrong method 405, ...) carry their status.
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.valueOf(errorResponse.getStatusCode().value());
            return ResponseEntity.status(status).body(Map.of("error", status.getReasonPhrase()));
        }
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "An unexpected error occurred."));
    }
}
