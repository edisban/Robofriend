package com.bot.botback.dto;

import jakarta.validation.constraints.Size;

public class MessageRequest {
    private Long userId;

    /** Empty only for a regenerate request; checked in the controller. */
    @Size(max = 4000, message = "That message is too long (max 4000 characters).")
    private String message;

    @Size(max = 64, message = "Invalid time zone.")
    private String timeZone;
    private boolean regenerate;
    private Long replaceFromId;

    public MessageRequest() {
    }

    public MessageRequest(Long userId, String message) {
        this.userId = userId;
        this.message = message;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public boolean isRegenerate() {
        return regenerate;
    }

    public void setRegenerate(boolean regenerate) {
        this.regenerate = regenerate;
    }

    public Long getReplaceFromId() {
        return replaceFromId;
    }

    public void setReplaceFromId(Long replaceFromId) {
        this.replaceFromId = replaceFromId;
    }
}
