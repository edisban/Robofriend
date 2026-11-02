package com.bot.botback.service;

import java.util.function.Consumer;

/**
 * Forwards the reply part of the model output as it streams, and holds back everything after
 * {@link #META_MARKER} so the facts/reminder JSON never reaches the user.
 */
final class ReplySplitter {

    /** Separates the streamed reply from the hidden facts/reminder JSON in the model output. */
    static final String META_MARKER = "###META###";

    private final Consumer<String> onToken;
    private final StringBuilder reply = new StringBuilder();
    private final StringBuilder meta = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private boolean inMeta;

    ReplySplitter(Consumer<String> onToken) {
        this.onToken = onToken;
    }

    void feed(String chunk) {
        if (inMeta) {
            meta.append(chunk);
            return;
        }
        pending.append(chunk);
        int idx = pending.indexOf(META_MARKER);
        if (idx >= 0) {
            emit(pending.substring(0, idx));
            meta.append(pending.substring(idx + META_MARKER.length()));
            pending.setLength(0);
            inMeta = true;
            return;
        }
        // Keep a tail that could be the start of a marker split across chunks.
        int safe = pending.length() - (META_MARKER.length() - 1);
        if (safe > 0) {
            emit(pending.substring(0, safe));
            pending.delete(0, safe);
        }
    }

    void finish() {
        if (!inMeta) {
            emit(pending.toString());
            pending.setLength(0);
        }
    }

    boolean emittedAny() {
        return !reply.toString().isBlank();
    }

    String reply() {
        return reply.toString();
    }

    String meta() {
        return meta.toString();
    }

    private void emit(String text) {
        if (text.isEmpty()) {
            return;
        }
        // Don't show the blank lines the model writes right before the marker or at the start.
        if (reply.length() == 0) {
            text = text.stripLeading();
            if (text.isEmpty()) {
                return;
            }
        }
        reply.append(text);
        onToken.accept(text);
    }
}
