package com.documind.chat;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.io.CloseMode;
import tools.jackson.databind.json.JsonMapper;

public final class GeminiChatProvider implements ChatProvider {
    public static final String MODEL = "gemini-3.5-flash-lite";
    private final String key;
    private final URI endpoint;
    private final JsonMapper json;
    private final CloseableHttpAsyncClient http;
    private final java.net.http.HttpClient streamingHttp;
    private final java.util.concurrent.ScheduledExecutorService timeoutTimer;
    private final java.time.Duration requestTimeout;

    public GeminiChatProvider(String key, URI endpoint, JsonMapper json) {
        this(key, endpoint, json, java.time.Duration.ofSeconds(60));
    }

    GeminiChatProvider(String key, URI endpoint, JsonMapper json, java.time.Duration requestTimeout) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Gemini API key is required");
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) throw new IllegalArgumentException("Invalid provider timeout");
        this.key = key; this.endpoint = endpoint; this.json = json;
        this.requestTimeout = requestTimeout;
        this.timeoutTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "gemini-stream-timeout"); thread.setDaemon(true); return thread;
        });
        this.http = HttpAsyncClients.custom().disableAutomaticRetries().disableRedirectHandling().build();
        this.http.start();
        this.streamingHttp = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
    }

    @Override public Reply stream(String system, String input, java.util.function.Consumer<String> tokens) {
        return stream(system, input, tokens, ignored -> {});
    }

    @Override public Reply stream(String system, String input, java.util.function.Consumer<String> tokens,
            java.util.function.Consumer<Reply> usageObserver) {
        return stream(system, input, tokens, usageObserver, new com.documind.common.DispatchFence() {
            public <T> T submit(java.util.function.Supplier<T> operation) { return operation.get(); }
        });
    }

    @Override public Reply stream(String system, String input, java.util.function.Consumer<String> tokens,
            java.util.function.Consumer<Reply> usageObserver, com.documind.common.DispatchFence fence) {
        String body = json.writeValueAsString(Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
                "generationConfig", Map.of("maxOutputTokens", 4096),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", input))))));
        String url = endpoint.toString().replace(":generateContent", ":streamGenerateContent");
        url += url.contains("?") ? "&alt=sse" : "?alt=sse";
        var request = java.net.http.HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout)
                .header("x-goog-api-key", key).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8)).build();
        long deadline = System.nanoTime() + requestTimeout.toNanos();
        try {
            if (Thread.currentThread().isInterrupted()) throw new ProviderException(null, false);
            var pending = fence.submit(() -> streamingHttp.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream()));
            java.net.http.HttpResponse<java.io.InputStream> response;
            try { response = pending.get(); }
            catch (InterruptedException interrupted) { pending.cancel(true); throw interrupted; }
            catch (ExecutionException failure) { throw new ProviderException(); }
            try (var inputStream = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw new ProviderException();
                var expired = new java.util.concurrent.atomic.AtomicBoolean();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new ProviderException();
                var timeout = timeoutTimer.schedule(() -> {
                    expired.set(true);
                    Thread.startVirtualThread(() -> {
                        try { inputStream.close(); } catch (java.io.IOException ignored) { }
                    });
                }, remaining, TimeUnit.NANOSECONDS);
                try {
                var content = new StringBuilder(); Long prompt = null, total = null; boolean finished = false;
                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8));
                for (String line; (line = reader.readLine()) != null;) {
                    if (!line.startsWith("data:")) continue;
                    var root = json.readTree(line.substring(5).strip());
                    var usage = root.path("usageMetadata");
                    if (usage.path("promptTokenCount").isNumber()) prompt = usage.path("promptTokenCount").asLong();
                    if (usage.path("totalTokenCount").isNumber()) total = usage.path("totalTokenCount").asLong();
                    if (prompt != null || total != null) usageObserver.accept(new Reply("", prompt,
                            prompt != null && total != null && total >= prompt ? total - prompt : null));
                    var candidate = root.path("candidates").path(0);
                    String reason = candidate.path("finishReason").asString("");
                    if (!reason.isEmpty()) {
                        if (!"STOP".equals(reason)) throw new ProviderException();
                        finished = true;
                    }
                    var parts = candidate.path("content").path("parts");
                    if (parts.isArray()) for (var part : parts) {
                        if (part.path("thought").asBoolean(false)) continue;
                        String text = part.path("text").asString("");
                        if (!text.isEmpty()) {
                            content.append(text);
                            if (content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16_384) throw new ProviderException();
                            tokens.accept(text);
                        }
                    }
                }
                if (expired.get() || !finished || content.isEmpty()) throw new ProviderException();
                return new Reply(content.toString(), prompt, prompt != null && total != null && total >= prompt ? total - prompt : null);
                } finally { timeout.cancel(false); }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new ProviderException();
        } catch (java.io.IOException failure) { throw new ProviderException(); }
    }

    @Override public Reply complete(String system, String input) {
        return complete(system, input, new com.documind.common.DispatchFence() {
            public <T> T submit(java.util.function.Supplier<T> operation) { return operation.get(); }
        });
    }

    @Override public Reply complete(String system, String input, com.documind.common.DispatchFence fence) {
        String body = json.writeValueAsString(Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
                "generationConfig", Map.of("maxOutputTokens", 4096),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", input))))));
        var request = SimpleRequestBuilder.post(endpoint).addHeader("x-goog-api-key", key)
                .setBody(body, ContentType.APPLICATION_JSON).build();
        if (Thread.currentThread().isInterrupted()) throw new ProviderException(null, false);
        var pending = fence.submit(() -> http.execute(request, null));
        if (Thread.currentThread().isInterrupted()) {
            pending.cancel(true);
            throw new ProviderException();
        }
        try {
            var response = pending.get(60, TimeUnit.SECONDS);
            if (response.getCode() < 200 || response.getCode() >= 300) throw new ProviderException();
            var root = json.readTree(response.getBodyText());
            var usage = root.path("usageMetadata");
            Long prompt = usage.path("promptTokenCount").isNumber() ? usage.path("promptTokenCount").asLong() : null;
            Long total = usage.path("totalTokenCount").isNumber() ? usage.path("totalTokenCount").asLong() : null;
            Long output = prompt != null && total != null && total >= prompt ? total - prompt : null;
            var candidate = root.path("candidates").path(0);
            if (!"STOP".equals(candidate.path("finishReason").asString())) throw new ProviderException(new Reply("", prompt, output));
            var parts = candidate.path("content").path("parts");
            if (!parts.isArray()) throw new ProviderException(new Reply("", prompt, output));
            var content = new StringBuilder();
            for (var part : parts) if (!part.path("thought").asBoolean(false)) content.append(part.path("text").asString(""));
            if (content.isEmpty()) throw new ProviderException(new Reply("", prompt, output));
            return new Reply(content.toString(), prompt, output);
        } catch (InterruptedException interrupted) {
            pending.cancel(true); Thread.currentThread().interrupt(); throw new ProviderException();
        } catch (ExecutionException | TimeoutException failure) { pending.cancel(true); throw new ProviderException(); }
    }

    @PreDestroy public void close() { timeoutTimer.shutdownNow(); http.close(CloseMode.GRACEFUL); streamingHttp.close(); }
    public static final class ProviderException extends RuntimeException {
        private final Reply usage;
        private final boolean attempted;
        public ProviderException() { this(null, true); }
        public ProviderException(Reply usage) { this(usage, true); }
        ProviderException(Reply usage, boolean attempted) { this.usage = usage; this.attempted = attempted; }
        public Reply usage() { return usage; }
        public boolean attempted() { return attempted; }
    }
}
