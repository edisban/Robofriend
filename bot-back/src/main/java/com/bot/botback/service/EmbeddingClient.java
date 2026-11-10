package com.bot.botback.service;

import com.bot.botback.config.MemoryServiceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calls the embedding service (ai-memory-service), which turns text into sentence vectors. It has
 * no database access; storing and searching the vectors happens here, in the backend.
 */
@Component
public class EmbeddingClient {

    /** The memory_vault.embedding column is vector(384). */
    public static final int DIMENSION = 384;
    /** The service accepts at most this many texts per request. */
    public static final int MAX_BATCH = 64;

    /** Vectors for the given texts (same order), and the model that made them. */
    public record Embeddings(String model, List<float[]> vectors) {
    }

    private final RestClient restClient;
    private final String serviceToken;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmbeddingClient(MemoryServiceProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.restClient = RestClient.builder().baseUrl(properties.url()).requestFactory(factory).build();
        this.serviceToken = properties.token();
    }

    /** @throws IllegalStateException when the service is unreachable or answers with something unexpected */
    public Embeddings embed(List<String> texts) {
        if (texts.isEmpty() || texts.size() > MAX_BATCH) {
            throw new IllegalArgumentException("Embed 1 to " + MAX_BATCH + " texts at a time");
        }
        JsonNode body;
        try {
            // Read as text and parse here: RestClient's converters are Jackson 3, this JsonNode is Jackson 2.
            String response = restClient.post()
                    .uri("/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Service-Token", serviceToken)
                    .body(objectMapper.writeValueAsString(Map.of("texts", texts)))
                    .retrieve()
                    .body(String.class);
            body = response == null ? null : objectMapper.readTree(response);
        } catch (Exception e) {
            throw new IllegalStateException("Embedding service unavailable: " + e.getMessage(), e);
        }
        if (body == null || !body.path("embeddings").isArray() || body.get("embeddings").size() != texts.size()) {
            throw new IllegalStateException("Embedding service returned an unexpected response");
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (JsonNode node : body.get("embeddings")) {
            if (node.size() != DIMENSION) {
                throw new IllegalStateException("Expected " + DIMENSION + "-dim vectors, got " + node.size());
            }
            float[] vector = new float[DIMENSION];
            for (int i = 0; i < DIMENSION; i++) {
                vector[i] = (float) node.get(i).asDouble();
            }
            vectors.add(vector);
        }
        return new Embeddings(body.path("model").asText(""), vectors);
    }

    public float[] embed(String text) {
        return embed(List.of(text)).vectors().get(0);
    }

    /** The embedding model the service runs, or null when it can't be reached. */
    public String currentModel() {
        try {
            String response = restClient.get().uri("/health").retrieve().body(String.class);
            String model = response == null ? "" : objectMapper.readTree(response).path("model").asText("");
            return model.isBlank() ? null : model;
        } catch (Exception e) {
            return null;
        }
    }

    /** pgvector's text form, e.g. {@code [0.1,-0.2,...]}; cast with {@code CAST(? AS vector)}. */
    public static String toVectorLiteral(float[] vector) {
        StringBuilder out = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(vector[i]);
        }
        return out.append(']').toString();
    }
}
