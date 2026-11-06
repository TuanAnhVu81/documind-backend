package com.documind.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GeminiChatProviderTest {
    @Test void sendsPromptAndReadsUsageFromLocalServer() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requestBody = new AtomicReference<String>();
        server.createContext("/generate", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"Có nguồn [S1].\"}]}}],\"usageMetadata\":{\"promptTokenCount\":12,\"candidatesTokenCount\":7,\"totalTokenCount\":19}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        var json = JsonMapper.builder().build();
        var provider = new GeminiChatProvider("test-only", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/generate"), json);
        try {
            var response = provider.complete("system", "question");
            assertThat(response.text()).isEqualTo("Có nguồn [S1].");
            assertThat(response.promptTokens()).isEqualTo(12);
            assertThat(response.completionTokens()).isEqualTo(7);
            assertThat(json.readTree(requestBody.get()).path("systemInstruction").path("parts").path(0).path("text").asString()).isEqualTo("system");
            assertThat(json.readTree(requestBody.get()).path("contents").path(0).path("parts").path(0).path("text").asString()).isEqualTo("question");
        } finally { provider.close(); server.stop(0); }
    }

    @Test void disabledProviderRequiresKey() {
        assertThatThrownBy(() -> new GeminiChatProvider("", URI.create("http://127.0.0.1"), JsonMapper.builder().build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsTruncatedCandidateEvenIfItContainsCitations() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/generate", exchange -> {
            byte[] bytes = "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[{\"text\":\"Fact [S1].\"}]}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        var provider = new GeminiChatProvider("test-only", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/generate"), JsonMapper.builder().build());
        try { assertThatThrownBy(() -> provider.complete("system", "question")).isInstanceOf(GeminiChatProvider.ProviderException.class); }
        finally { provider.close(); server.stop(0); }
    }
}
