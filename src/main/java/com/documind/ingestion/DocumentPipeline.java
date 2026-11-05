package com.documind.ingestion;

import com.documind.document.FileStorage;
import com.documind.retrieval.ChunkVectorStore;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** No provider is created or called unless pipeline and worker are explicitly enabled. */
@Component
@ConditionalOnProperty(name = "documind.ingestion.pipeline-enabled", havingValue = "true")
public class DocumentPipeline implements IngestionProcessor {
    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final DocumentExtractor extractor;
    private final DocumentChunker chunker;
    private final EmbeddingModel model;
    private final String modelName;
    private final int batchSize;
    private final Duration parseTimeout, embeddingTimeout;
    private final Duration stopGrace;
    private final Runnable failStop;
    private final ScheduledExecutorService guard = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("pipeline-cancellation-guard").factory());
    private final ExecutorService parsers = boundedExecutor("document-parser-");
    private final ExecutorService providers = boundedExecutor("embedding-call-");

    private static ExecutorService boundedExecutor(String prefix) {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2),
                Thread.ofPlatform().daemon().name(prefix, 0).factory());
    }

    @Autowired
    public DocumentPipeline(JdbcClient jdbc, FileStorage storage, DocumentExtractor extractor, DocumentChunker chunker,
            EmbeddingModel model, @Value("${documind.ingestion.embedding-model:text-embedding-3-small}") String modelName,
            @Value("${documind.ingestion.embedding-batch-size:32}") int batchSize,
            @Value("${documind.parser.timeout-seconds:30}") long parseSeconds,
            @Value("${documind.ingestion.embedding-timeout-seconds:60}") long embeddingSeconds,
            @Value("${spring.ai.openai.embedding.model:text-embedding-3-small}") String providerModel,
            @Value("${spring.ai.openai.embedding.max-retries:0}") int sdkAttempts,
            @Value("${spring.ai.openai.embedding.timeout:60s}") String sdkTimeout,
            @Value("${spring.ai.openai.timeout:60s}") String commonTimeout,
            @Value("${documind.ingestion.stop-grace-seconds:10}") long graceSeconds, ConfigurableApplicationContext context) {
        this(jdbc, storage, extractor, chunker, model, modelName, batchSize, parseSeconds, embeddingSeconds, providerModel,
                sdkAttempts, graceSeconds, () -> {
                    AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
                    Thread.ofPlatform().daemon().name("pipeline-fail-stop").start(context::close);
                });
        if (model instanceof org.springframework.ai.openai.OpenAiEmbeddingModel openAi
                && (!modelName.equals(openAi.getOptions().getModel()) || !Integer.valueOf(1536).equals(openAi.getOptions().getDimensions())))
            throw new IllegalArgumentException("Configured OpenAI embedding model does not match the stored vector model");
        validateSdkTimeout(sdkTimeout);
        validateSdkTimeout(commonTimeout);
    }

    private static void validateSdkTimeout(String value) {
        Duration timeout = org.springframework.boot.convert.DurationStyle.detectAndParse(value);
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("OpenAI timeout must be between zero and 60 seconds");
    }

    DocumentPipeline(JdbcClient jdbc, FileStorage storage, DocumentExtractor extractor, DocumentChunker chunker,
            EmbeddingModel model, String modelName, int batchSize, long parseSeconds, long embeddingSeconds,
            String providerModel, int sdkAttempts) {
        this(jdbc, storage, extractor, chunker, model, modelName, batchSize, parseSeconds, embeddingSeconds,
                providerModel, sdkAttempts, 10, () -> {});
    }

    DocumentPipeline(JdbcClient jdbc, FileStorage storage, DocumentExtractor extractor, DocumentChunker chunker,
            EmbeddingModel model, String modelName, int batchSize, long parseSeconds, long embeddingSeconds,
            String providerModel, int sdkAttempts, long graceSeconds, Runnable failStop) {
        if (!"text-embedding-3-small".equals(modelName) || !modelName.equals(providerModel) || sdkAttempts != 0
                || batchSize < 1 || batchSize > 128 || parseSeconds < 1 || embeddingSeconds < 1 || embeddingSeconds > 60 || graceSeconds < 1)
            throw new IllegalArgumentException("Invalid ingestion pipeline settings");
        this.jdbc = jdbc; this.storage = storage; this.extractor = extractor; this.chunker = chunker; this.model = model;
        this.modelName = modelName; this.batchSize = batchSize;
        parseTimeout = Duration.ofSeconds(parseSeconds); embeddingTimeout = Duration.ofSeconds(embeddingSeconds);
        stopGrace = Duration.ofSeconds(graceSeconds); this.failStop = failStop;
    }

    private record FileRef(String key, String mediaType) {}
    private record Parsed(DocumentExtractor.Extracted extracted, List<DocumentChunker.Part> parts) {}

    @Override public Result process(JobClaimStore.Claim claim) throws Exception {
        FileRef file = jdbc.sql("SELECT storage_key,media_type FROM documents WHERE id=:id AND status='PROCESSING' AND deleted_requested_at IS NULL")
                .param("id", claim.documentId()).query((rs, row) -> new FileRef(rs.getString(1), rs.getString(2))).optional()
                .orElseThrow(() -> new ProcessingFailure(false, null, "INGESTION_FAILED"));
        Parsed parsed = timed(parsers, () -> {
            try (var input = storage.open(file.key())) {
                var extracted = extractor.extract(input, file.mediaType());
                return new Parsed(extracted, chunker.split(extracted));
            }
        }, parseTimeout, false, "PARSER_TIMEOUT");
        if (parsed.parts().isEmpty()) throw new ProcessingFailure(false, null, "NO_EXTRACTABLE_TEXT");
        List<ChunkVectorStore.Chunk> chunks = new ArrayList<>();
        for (int offset = 0; offset < parsed.parts().size(); offset += batchSize) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Embedding interrupted");
            var batch = parsed.parts().subList(offset, Math.min(offset + batchSize, parsed.parts().size()));
            var texts = batch.stream().map(DocumentChunker.Part::text).toList();
            var response = timed(providers, () -> model.embedForResponse(texts), embeddingTimeout, true, "INGESTION_FAILED");
            float[][] vectors = new float[batch.size()][];
            if (response == null || response.getResults() == null) throw new ProcessingFailure(false, null, "EMBEDDING_INVALID");
            for (var item : response.getResults()) {
                if (item == null || item.getIndex() == null || item.getIndex() < 0 || item.getIndex() >= vectors.length
                        || vectors[item.getIndex()] != null) throw new ProcessingFailure(false, null, "EMBEDDING_INVALID");
                vectors[item.getIndex()] = item.getOutput();
            }
            for (int i = 0; i < vectors.length; i++) {
                validateVector(vectors[i]);
                var part = batch.get(i);
                chunks.add(new ChunkVectorStore.Chunk(UUID.randomUUID(), offset + i, part.page(), part.paragraph(),
                        part.start(), part.end(), part.text(), vectors[i], modelName, "phase6-v1"));
            }
        }
        return new Result(List.copyOf(chunks), parsed.extracted().pages(), parsed.extracted().paragraphs());
    }

    private static void validateVector(float[] values) throws ProcessingFailure {
        if (values == null || values.length != 1536) throw new ProcessingFailure(false, null, "EMBEDDING_INVALID");
        boolean nonzero = false;
        for (float value : values) {
            if (!Float.isFinite(value)) throw new ProcessingFailure(false, null, "EMBEDDING_INVALID");
            nonzero |= value != 0;
        }
        if (!nonzero) throw new ProcessingFailure(false, null, "EMBEDDING_INVALID");
    }

    private <T> T timed(ExecutorService executor, Callable<T> work, Duration timeout, boolean retryable, String timeoutCode)
            throws ProcessingFailure, InterruptedException {
        java.util.concurrent.Future<T> future;
        AtomicBoolean started = new AtomicBoolean(), finished = new AtomicBoolean();
        try { future = executor.submit(() -> {
            started.set(true);
            try { return work.call(); } finally { finished.set(true); }
        }); }
        catch (RejectedExecutionException busy) { throw new ProcessingFailure(true, null, "INGESTION_FAILED"); }
        try { return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { cancel(future, started, finished); throw new ProcessingFailure(retryable, null, timeoutCode); }
        catch (InterruptedException e) { cancel(future, started, finished); throw e; }
        catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof DocumentExtractor.ExtractionFailure invalid) throw new ProcessingFailure(false, null, invalid.code());
            if (cause instanceof com.openai.errors.OpenAIServiceException provider) {
                int status = provider.statusCode();
                var headers = provider.headers().values("retry-after");
                Duration retryAfter = headers.isEmpty() ? null : retryAfter(headers.getFirst());
                throw new ProcessingFailure(status == 429 || status == 408 || status >= 500, retryAfter, "INGESTION_FAILED");
            }
            if (cause instanceof InterruptedException) throw new InterruptedException("Processing interrupted");
            if (cause instanceof IOException) throw new ProcessingFailure(true, null, "INGESTION_FAILED");
            throw new ProcessingFailure(retryable, null, "INGESTION_FAILED");
        }
    }

    private void cancel(java.util.concurrent.Future<?> future, AtomicBoolean started, AtomicBoolean finished) {
        future.cancel(true);
        if (started.get() && !finished.get()) {
            try { guard.schedule(() -> { if (!finished.get()) failStop.run(); }, stopGrace.toMillis(), TimeUnit.MILLISECONDS); }
            catch (RejectedExecutionException ignored) { /* application is stopping */ }
        }
    }

    static Duration retryAfter(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds < 0 || seconds > Long.MAX_VALUE / 1000) return null;
            java.time.Instant.now().plusSeconds(seconds);
            return Duration.ofSeconds(seconds);
        } catch (java.time.DateTimeException invalid) { return null; }
        catch (NumberFormatException ignored) {
            try {
                Duration delay = Duration.between(java.time.Instant.now(), java.time.ZonedDateTime.parse(value,
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (RuntimeException invalid) { return null; }
        }
    }

    @PreDestroy void close() { parsers.shutdownNow(); providers.shutdownNow(); guard.shutdownNow(); }
}
