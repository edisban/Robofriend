package com.bot.botback.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Talks to the Groq chat-completions API: streaming, retries and error mapping. */
@Component
public class GroqClient {

    private static final Logger log = LoggerFactory.getLogger(GroqClient.class);

    private static final String GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;
    private static final Set<Integer> NON_RETRYABLE_STATUSES = Set.of(400, 401, 402, 403, 404);

    private final String apiKey;
    private final String modelName;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GroqClient(@Value("${groq.api.key:}") String apiKey,
                      @Value("${groq.api.model:openai/gpt-oss-120b}") String modelName) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    void warnIfUnconfigured() {
        if (!isConfigured()) {
            log.warn("GROQ_API_KEY is not configured. RoboFriend will reply using local fallbacks "
                    + "and will not extract facts or reminders from new messages.");
        }
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** One complete answer for a self-contained prompt (nothing streamed to a client). */
    public String complete(String prompt, double temperature) throws Exception {
        if (!isConfigured()) {
            throw new IllegalStateException("Groq API key is not configured");
        }
        ReplySplitter splitter = new ReplySplitter(token -> { });
        stream(List.of(Map.of("role", "user", "content", prompt)), temperature, splitter);
        return splitter.reply().trim();
    }

    /** Streams one chat completion into {@code splitter}, retrying until text has been shown. */
    public void stream(List<Map<String, String>> messages, double temperature, ReplySplitter splitter)
            throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                streamGroq(messages, temperature, splitter);
                return;
            } catch (GroqHttpException | IOException e) {
                lastException = e;
                int status = e instanceof GroqHttpException http ? http.status : -1;
                log.warn("Groq attempt {}/{} failed: {}", attempt, MAX_RETRIES, e.getMessage());
                // Once text has been shown we can't silently start over.
                if (splitter.emittedAny() || NON_RETRYABLE_STATUSES.contains(status)) {
                    throw e;
                }
                if (attempt < MAX_RETRIES) {
                    sleepQuietly(RETRY_DELAY_MS * attempt);
                }
            }
        }
        throw lastException != null ? lastException : new IOException("Groq call failed");
    }

    private void streamGroq(List<Map<String, String>> messages, double temperature, ReplySplitter splitter)
            throws IOException, InterruptedException, GroqHttpException {
        Map<String, Object> body = new HashMap<>();
        body.put("model", modelName);
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("max_completion_tokens", 1024);
        body.put("stream", true);
        if (modelName.startsWith("openai/gpt-oss")) {
            body.put("reasoning_effort", "low");
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(GROQ_CHAT_URL))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();

        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() >= 400) {
                throw new GroqHttpException(response.statusCode(), lines.collect(Collectors.joining("\n")));
            }
            lines.forEach(line -> {
                if (!line.startsWith("data:")) {
                    return;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    return;
                }
                try {
                    JsonNode content = objectMapper.readTree(data)
                            .path("choices").path(0).path("delta").path("content");
                    if (content.isTextual()) {
                        splitter.feed(content.asText());
                    }
                } catch (IOException e) {
                    log.debug("Skipping unparseable stream chunk: {}", data);
                }
            });
        }
        splitter.finish();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public static final class GroqHttpException extends Exception {
        final int status;

        GroqHttpException(int status, String body) {
            super("HTTP " + status + ": " + body);
            this.status = status;
        }
    }
}
