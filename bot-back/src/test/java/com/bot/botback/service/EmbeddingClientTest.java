package com.bot.botback.service;

import com.bot.botback.config.MemoryServiceProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The embedding client over real HTTP (a small local server playing the embedding service), so
 * request/response handling is tested through the same converters as in production.
 */
class EmbeddingClientTest {

    private HttpServer server;
    private final AtomicReference<String> embedResponse = new AtomicReference<>();
    private final AtomicReference<String> lastToken = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private EmbeddingClient client;

    private static String vectorJson(int dims, double value) {
        return "[" + String.join(",", Collections.nCopies(dims, Double.toString(value))) + "]";
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embed", exchange -> {
            lastToken.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = embedResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"ok\",\"model\":\"test-model\",\"dimension\":384}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new EmbeddingClient(new MemoryServiceProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), "secret-token"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void embedsTextsInOrderAndSendsTheServiceToken() {
        embedResponse.set("{\"model\":\"test-model\",\"dimension\":384,\"embeddings\":["
                + vectorJson(384, 0.25) + "," + vectorJson(384, -0.5) + "]}");

        EmbeddingClient.Embeddings result = client.embed(List.of("Γάτα με όνομα Μίσο", "second"));

        assertEquals("test-model", result.model());
        assertEquals(2, result.vectors().size());
        assertEquals(0.25f, result.vectors().get(0)[383]);
        assertEquals(-0.5f, result.vectors().get(1)[0]);
        assertEquals("secret-token", lastToken.get());
        assertTrue(lastBody.get().contains("Γάτα με όνομα Μίσο"), "Greek text arrives intact: " + lastBody.get());
    }

    @Test
    void rejectsVectorsOfTheWrongSizeOrCount() {
        embedResponse.set("{\"model\":\"m\",\"embeddings\":[" + vectorJson(3, 0.1) + "]}");
        assertThrows(IllegalStateException.class, () -> client.embed("hello"));

        embedResponse.set("{\"model\":\"m\",\"embeddings\":[]}");
        assertThrows(IllegalStateException.class, () -> client.embed("hello"));
    }

    @Test
    void reportsTheModelAndNullWhenTheServiceIsDown() {
        assertEquals("test-model", client.currentModel());

        server.stop(0);
        assertNull(client.currentModel());
        assertThrows(IllegalStateException.class, () -> client.embed("hello"));
    }

    @Test
    void vectorLiteralIsPgvectorsTextForm() {
        assertEquals("[0.5,-1.0,0.0]", EmbeddingClient.toVectorLiteral(new float[] {0.5f, -1f, 0f}));
    }
}
