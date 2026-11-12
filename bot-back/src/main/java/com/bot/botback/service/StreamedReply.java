package com.bot.botback.service;

import java.util.function.Consumer;

/** Collects a reply as the model streams it and forwards each piece to the user right away. */
final class StreamedReply {

    private final Consumer<String> onToken;
    private final StringBuilder text = new StringBuilder();

    StreamedReply(Consumer<String> onToken) {
        this.onToken = onToken;
    }

    void feed(String chunk) {
        // Don't show the blank lines a model sometimes starts with.
        if (text.isEmpty()) {
            chunk = chunk.stripLeading();
        }
        if (chunk.isEmpty()) {
            return;
        }
        text.append(chunk);
        onToken.accept(chunk);
    }

    /** Whether the user has already seen part of the reply (so it can't be silently started over). */
    boolean emittedAny() {
        return !text.isEmpty();
    }

    String text() {
        return text.toString();
    }
}
