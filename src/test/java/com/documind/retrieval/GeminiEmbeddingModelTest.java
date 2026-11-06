package com.documind.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GeminiEmbeddingModelTest {
    @Test void sendsIndependentInputsAndChecksResponseWithoutRetry() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger(); var status = new AtomicInteger(200);
        var requestBody = new AtomicReference<String>();
        var json = JsonMapper.builder().build();
        server.createContext("/embeddings", exchange -> {
            requests.incrementAndGet(); requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var vector = new float[1536]; vector[0] = 1;
            byte[] bytes = json.writeValueAsBytes(status.get() == 200
                    ? java.util.Map.of("embeddings", List.of(java.util.Map.of("values", vector), java.util.Map.of("values", vector)))
                    : java.util.Map.of("error", "simulated"));
            exchange.getResponseHeaders().add("Retry-After", "90");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var model = new GeminiEmbeddingModel("test-only", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/embeddings"), json);
            var response = model.embedForResponse(List.of(GeminiEmbeddingModel.documentText("one"), GeminiEmbeddingModel.documentText("two")));
            assertThat(response.getResults()).hasSize(2);
            var inputs = json.readTree(requestBody.get()).get("requests");
            assertThat(inputs.size()).isEqualTo(2);
            assertThat(inputs.get(0).get("outputDimensionality").asInt()).isEqualTo(1536);
            assertThat(inputs.get(0).get("content").get("parts").get(0).get("text").asText()).endsWith("one");
            assertThat(inputs.get(1).get("content").get("parts").get(0).get("text").asText()).endsWith("two");
            assertThatThrownBy(() -> model.embedForResponse(List.of("one")))
                    .isInstanceOf(GeminiEmbeddingModel.InvalidEmbedding.class);
            status.set(429);
            assertThatThrownBy(() -> model.embedForResponse(List.of("query")))
                    .isInstanceOf(GeminiEmbeddingModel.ProviderFailure.class)
                    .satisfies(e -> assertThat(((GeminiEmbeddingModel.ProviderFailure)e).retryAfter()).isEqualTo(java.time.Duration.ofSeconds(90)));
            assertThat(requests.get()).isEqualTo(3);
            model.close();
        } finally { server.stop(0); }
    }

    @Test void doesNotRetryWhenConnectionDrops() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/embeddings", exchange -> { calls.incrementAndGet(); exchange.close(); });
        server.start();
        var model = new GeminiEmbeddingModel("test-only", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/embeddings"), JsonMapper.builder().build());
        try {
            assertThatThrownBy(() -> model.embedForResponse(List.of("text"))).isInstanceOf(GeminiEmbeddingModel.TransportFailure.class);
            assertThat(calls.get()).isEqualTo(1);
        } finally { model.close(); server.stop(0); }
    }

    @Test void propagatesGoogleRetryInfoFromResponseBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/embeddings", exchange -> {
            calls.incrementAndGet();
            byte[] body = """
                    {"error":{"details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"120.25s"},
                    {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"RequestsPerMinute"}]}]}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        var model = new GeminiEmbeddingModel("test-only", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/embeddings"), JsonMapper.builder().build());
        try {
            assertThatThrownBy(() -> model.embedForResponse(List.of("text"))).isInstanceOf(GeminiEmbeddingModel.ProviderFailure.class)
                    .satisfies(e -> assertThat(((GeminiEmbeddingModel.ProviderFailure)e).retryAfter()).isEqualTo(java.time.Duration.ofMillis(120250)));
            assertThat(calls.get()).isEqualTo(1);
        } finally { model.close(); server.stop(0); }
    }
}
