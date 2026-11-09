package com.documind.retrieval.provider;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.http.ContentType;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import tools.jackson.databind.json.JsonMapper;

/** Gemini's synchronous batch endpoint sends one independent content per request entry. */
public final class GeminiEmbeddingModel implements EmbeddingModel {
    public static final String MODEL = "gemini-embedding-2";
    public static final String PIPELINE_VERSION = "phase7-gemini-v1";
    private final CloseableHttpAsyncClient client;
    private final URI endpoint;
    private final String key;
    private final JsonMapper json;

    public GeminiEmbeddingModel(String key, URI endpoint, JsonMapper json) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Gemini API key is required");
        this.key = key; this.endpoint = endpoint; this.json = json;
        this.client = HttpAsyncClients.custom().disableAutomaticRetries().disableRedirectHandling().build();
        this.client.start();
    }

    public static String documentText(String text) { return "title: none | text: " + text; }
    public static String queryText(String text) { return "task: question answering | query: " + text; }

    @Override public EmbeddingResponse call(EmbeddingRequest request) {
        try {
            var inputs = request.getInstructions();
            var entries = inputs.stream().map(text -> Map.of("model", "models/" + MODEL,
                    "content", Map.of("parts", List.of(Map.of("text", text))), "outputDimensionality", 1536)).toList();
            String body = json.writeValueAsString(Map.of("requests", entries));
            var http = SimpleRequestBuilder.post(endpoint).addHeader("x-goog-api-key", key)
                    .setBody(body, ContentType.APPLICATION_JSON).build();
            var pending = client.execute(http, null);
            org.apache.hc.client5.http.async.methods.SimpleHttpResponse response;
            try { response = pending.get(60, TimeUnit.SECONDS); }
            catch (InterruptedException | TimeoutException failure) { pending.cancel(true); throw failure; }
            if (response.getCode() < 200 || response.getCode() >= 300) {
                var header = response.getFirstHeader("Retry-After");
                Duration delay = GeminiRetryDelay.from(response.getCode(), header == null ? null : header.getValue(), response.getBodyText(), json);
                throw new ProviderFailure(response.getCode(), delay);
            }
            var root = json.readTree(response.getBodyText());
            var values = root.path("embeddings");
            if (!values.isArray() || values.size() != inputs.size()) throw new InvalidEmbedding();
            var output = new ArrayList<Embedding>();
            for (int i = 0; i < values.size(); i++) {
                var array = values.get(i).path("values");
                if (!array.isArray() || array.size() != 1536) throw new InvalidEmbedding();
                float[] vector = new float[1536]; boolean nonzero = false;
                for (int j = 0; j < vector.length; j++) {
                    if (!array.get(j).isNumber()) throw new InvalidEmbedding();
                    vector[j] = (float) array.get(j).asDouble();
                    if (!Float.isFinite(vector[j])) throw new InvalidEmbedding();
                    nonzero |= vector[j] != 0;
                }
                if (!nonzero) throw new InvalidEmbedding();
                output.add(new Embedding(vector, i));
            }
            return new EmbeddingResponse(output);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new TransportFailure();
        } catch (ExecutionException | TimeoutException failure) { throw new TransportFailure(); }
    }

    @Override public float[] embed(Document document) { return embedForResponse(List.of(document.getText())).getResults().getFirst().getOutput(); }

    public static final class ProviderFailure extends RuntimeException {
        private final int status; private final Duration retryAfter;
        public ProviderFailure(int status, Duration retryAfter) { super("Gemini request failed"); this.status = status; this.retryAfter = retryAfter; }
        public int status() { return status; }
        public Duration retryAfter() { return retryAfter; }
    }
    public static final class InvalidEmbedding extends RuntimeException { }
    public static final class TransportFailure extends RuntimeException { }
    @PreDestroy public void close() { client.close(org.apache.hc.core5.io.CloseMode.GRACEFUL); }
}
