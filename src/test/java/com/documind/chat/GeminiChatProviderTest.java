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
            byte[] bytes = "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[{\"text\":\"Fact [S1].\"}]}}],\"usageMetadata\":{\"promptTokenCount\":12,\"totalTokenCount\":19}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        var provider = new GeminiChatProvider("test-only", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/generate"), JsonMapper.builder().build());
        try { assertThatThrownBy(() -> provider.complete("system", "question")).isInstanceOf(GeminiChatProvider.ProviderException.class)
                .satisfies(error -> assertThat(((GeminiChatProvider.ProviderException) error).usage().completionTokens()).isEqualTo(7)); }
        finally { provider.close(); server.stop(0); }
    }

    @Test void receivesSeparateStreamingChunksAndRequiresNaturalStop() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/generate", exchange -> {
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("alt=sse");
            String data = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"One \"}]}}]}\n\n"
                    + "data: {\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"[S1].\"}]}}],"
                    + "\"usageMetadata\":{\"promptTokenCount\":3,\"totalTokenCount\":5}}\n\n";
            byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        var provider = new GeminiChatProvider("test-only", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/generate"), JsonMapper.builder().build());
        try {
            var chunks = new java.util.ArrayList<String>();
            var reply = provider.stream("system", "question", chunks::add);
            assertThat(chunks).containsExactly("One ", "[S1].");
            assertThat(reply.text()).isEqualTo("One [S1].");
            assertThat(reply.promptTokens()).isEqualTo(3);
            assertThat(reply.completionTokens()).isEqualTo(2);
        } finally { provider.close(); server.stop(0); }
    }

    @Test void interruptStopsProviderStreamWaitingForMoreChunks() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var releaseServer = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/generate", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"first\"}]}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                try { releaseServer.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        var provider = new GeminiChatProvider("test-only", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/generate"), JsonMapper.builder().build());
        var first = new java.util.concurrent.CountDownLatch(1);
        var exited = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var task = pool.submit(() -> {
                try { provider.stream("system", "question", ignored -> first.countDown()); }
                finally { exited.countDown(); }
            });
            assertThat(first.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            task.cancel(true);
            assertThat(exited.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally { releaseServer.countDown(); provider.close(); server.stop(0); }
    }

    @Test void timeoutAlsoCoversBodyAfterHeaders() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/generate", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.flush();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        var provider = new GeminiChatProvider("test-only", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/generate"),
                JsonMapper.builder().build(), java.time.Duration.ofMillis(800));
        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> provider.stream("system", "question", ignored -> {})).isInstanceOf(GeminiChatProvider.ProviderException.class);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(3));
        } finally { release.countDown(); provider.close(); server.stop(0); }
    }

    @Test void preservesKnownUsageWhenTokenConsumerDisconnects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/generate", exchange -> {
            String event = "data: {\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"text\"}]}}],"
                    + "\"usageMetadata\":{\"promptTokenCount\":12,\"totalTokenCount\":19}}\n\n";
            byte[] bytes = event.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        var provider = new GeminiChatProvider("test-only", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/generate"), JsonMapper.builder().build());
        var known = new AtomicReference<ChatProvider.Reply>();
        try {
            assertThatThrownBy(() -> provider.stream("system", "question", ignored -> { throw new IllegalStateException("disconnect"); }, known::set))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(known.get().promptTokens()).isEqualTo(12);
            assertThat(known.get().completionTokens()).isEqualTo(7);
        } finally { provider.close(); server.stop(0); }
    }
}
