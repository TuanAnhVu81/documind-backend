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

    public GeminiChatProvider(String key, URI endpoint, JsonMapper json) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Gemini API key is required");
        this.key = key; this.endpoint = endpoint; this.json = json;
        this.http = HttpAsyncClients.custom().disableAutomaticRetries().disableRedirectHandling().build();
        this.http.start();
    }

    @Override public Reply complete(String system, String input) {
        String body = json.writeValueAsString(Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
                "generationConfig", Map.of("maxOutputTokens", 4096),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", input))))));
        var request = SimpleRequestBuilder.post(endpoint).addHeader("x-goog-api-key", key)
                .setBody(body, ContentType.APPLICATION_JSON).build();
        var pending = http.execute(request, null);
        try {
            var response = pending.get(60, TimeUnit.SECONDS);
            if (response.getCode() < 200 || response.getCode() >= 300) throw new ProviderException();
            var root = json.readTree(response.getBodyText());
            var candidate = root.path("candidates").path(0);
            if (!"STOP".equals(candidate.path("finishReason").asString())) throw new ProviderException();
            var parts = candidate.path("content").path("parts");
            if (!parts.isArray()) throw new ProviderException();
            var content = new StringBuilder();
            for (var part : parts) if (!part.path("thought").asBoolean(false)) content.append(part.path("text").asString(""));
            if (content.isEmpty()) throw new ProviderException();
            var usage = root.path("usageMetadata");
            Long prompt = usage.path("promptTokenCount").isNumber() ? usage.path("promptTokenCount").asLong() : null;
            Long total = usage.path("totalTokenCount").isNumber() ? usage.path("totalTokenCount").asLong() : null;
            Long output = prompt != null && total != null && total >= prompt ? total - prompt : null;
            return new Reply(content.toString(), prompt, output);
        } catch (InterruptedException interrupted) {
            pending.cancel(true); Thread.currentThread().interrupt(); throw new ProviderException();
        } catch (ExecutionException | TimeoutException failure) { pending.cancel(true); throw new ProviderException(); }
    }

    @PreDestroy public void close() { http.close(CloseMode.GRACEFUL); }
    public static final class ProviderException extends RuntimeException { }
}
