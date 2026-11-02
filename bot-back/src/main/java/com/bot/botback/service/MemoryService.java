package com.bot.botback.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.bot.botback.model.MemoryVault;
import com.bot.botback.repository.MemoryVaultRepository;

@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final String memoryServiceUrl;
    private final String serviceToken;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final MemoryVaultRepository memoryVaultRepository;

    public MemoryService(@Value("${memory.service.url}") String memoryServiceUrl,
                         @Value("${memory.service.token}") String serviceToken,
                         MemoryVaultRepository memoryVaultRepository) {
        this.memoryServiceUrl = memoryServiceUrl;
        this.serviceToken = serviceToken;
        this.memoryVaultRepository = memoryVaultRepository;
        this.objectMapper = new ObjectMapper();
        this.restClient = RestClient.builder()
                .baseUrl(memoryServiceUrl)
                .requestFactory(requestFactory())
                .build();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory requestFactory() {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    /** A remembered fact plus when it was saved, so relative words like "tomorrow" can be dated. */
    public record Memory(String fact, OffsetDateTime savedAt) {
    }

    /** Facts that hold a current value (personal records, job, city...), one per key. */
    public List<Memory> profileFacts(Long userId) {
        try {
            return memoryVaultRepository.findByUserIdAndFactKeyIsNotNullOrderByFactKeyAsc(userId).stream()
                    .map(MemoryService::toMemory)
                    .toList();
        } catch (Exception e) {
            log.warn("Profile lookup failed for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /** Most recent memories, newest first; used for the daily check-in. */
    public List<Memory> recentMemories(Long userId) {
        try {
            return memoryVaultRepository.findTop150ByUserIdOrderByCreatedAtDesc(userId).stream()
                    .map(MemoryService::toMemory)
                    .toList();
        } catch (Exception e) {
            log.warn("Recent memory lookup failed for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    private static Memory toMemory(MemoryVault row) {
        return new Memory(row.getFact(), row.getCreatedAt());
    }

    public List<String> searchFacts(Long userId, String query, int limit) {
        return searchMemories(userId, query, limit).stream().map(m -> m.fact()).toList();
    }

    public List<Memory> searchMemories(Long userId, String query, int limit) {
        try {
            Map<String, Object> payload = Map.of(
                    "query", query == null ? "" : query,
                    "user_id", userId,
                    "limit", limit);

            String response = restClient.post()
                    .uri("/search")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Service-Token", serviceToken)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            if (response == null || response.isBlank()) {
                return List.of();
            }

            JsonNode root = objectMapper.readTree(response);
            List<Memory> memories = new ArrayList<>();
            for (JsonNode node : root) {
                JsonNode fact = node.get("fact");
                if (fact != null && !fact.asText().isBlank()) {
                    memories.add(new Memory(fact.asText(), parseTimestamp(node.path("created_at").asText(null))));
                }
            }
            return memories;
        } catch (Exception e) {
            log.warn("Memory search failed for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    private static OffsetDateTime parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            try {
                // Python may omit the offset; the database stores UTC.
                return LocalDateTime.parse(value).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    public void ingestFact(Long userId, String fact, String category) {
        ingestFact(userId, fact, category, null);
    }

    public void ingestFact(Long userId, String fact, String category, String key) {
        if (fact == null || fact.isBlank()) {
            return;
        }
        try {
            URI uri = UriComponentsBuilder.fromUriString(memoryServiceUrl + "/ingest")
                    .queryParam("user_id", userId)
                    .queryParam("message", fact)
                    .queryParam("category", category == null ? "General" : category)
                    .queryParamIfPresent("key", java.util.Optional.ofNullable(key))
                    .build()
                    .encode()
                    .toUri();

            restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Service-Token", serviceToken)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Ingested fact for user {}", userId);
        } catch (Exception e) {
            log.warn("Memory ingest failed for user {}: {}", userId, e.getMessage());
        }
    }
}
