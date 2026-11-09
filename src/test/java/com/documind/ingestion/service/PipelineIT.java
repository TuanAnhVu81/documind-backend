package com.documind.ingestion.service;

import com.documind.document.service.DocumentService;
import com.documind.document.storage.FileStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.builder.SpringApplicationBuilder;
import com.documind.DocumindBackendApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PipelineIT.ModelConfig.class)
class PipelineIT {
    private static PostgreSQLContainer db;
    private static Path directory;
    @Autowired JdbcClient jdbc;
    @Autowired FileStorage storage;
    @Autowired DocumentExtractor extractor;
    @Autowired DocumentChunker chunker;
    @Autowired IngestionQueue queue;
    @Autowired DocumentService documents;
    @Autowired tools.jackson.databind.json.JsonMapper json;
    @Autowired com.documind.retrieval.repository.ChunkVectorStore vectorStore;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired org.springframework.beans.factory.ObjectProvider<EmbeddingModel> modelProvider;
    private UUID owner;
    @Autowired FakeEmbedding embeddings;
    @Autowired DocumentPipeline wiredPipeline;
    @Autowired com.documind.retrieval.service.RetrievalService retrieval;
    private final List<DocumentPipeline> pipelines = new ArrayList<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        directory = Files.createTempDirectory("documind-pipeline-");
        db = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.7-pg15").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("pipeline_test").withUsername("pipeline_test").withPassword(UUID.randomUUID().toString());
        db.start();
        registry.add("spring.datasource.url", db::getJdbcUrl); registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword); registry.add("spring.flyway.user", db::getUsername);
        registry.add("spring.flyway.password", db::getPassword);
        registry.add("documind.auth.jwt-secret", () -> Base64.getEncoder().encodeToString("pipeline-secret-test-at-least-32bytes".getBytes(StandardCharsets.UTF_8)));
        registry.add("documind.auth.allowed-origins", () -> "https://documind.test");
        registry.add("documind.storage.path", () -> directory.toString());
        registry.add("documind.storage.initialize-if-empty", () -> "true");
        registry.add("documind.ingestion.pipeline-enabled", () -> "true");
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class ModelConfig { @Bean FakeEmbedding fakeEmbedding() { return new FakeEmbedding(); } }
    @BeforeEach void setup() {
        embeddings.fail.set(false); embeddings.rateLimited.set(false); embeddings.invalidVector.set(false); embeddings.failOnCall = 0;
        embeddings.retryAfter = Duration.ofSeconds(90);
        embeddings.calls.set(0); embeddings.ignoreInterrupt.set(false); embeddings.entered = null; embeddings.release = null;
        embeddings.embeddedInTransaction.set(false);
        owner = UUID.randomUUID();
        jdbc.sql("INSERT INTO users(id,email,password_hash) VALUES (:id,:email,'fixture')")
                .param("id", owner).param("email", owner + "@example.com").update();
    }
    @AfterEach void clean() { pipelines.forEach(DocumentPipeline::close); jdbc.sql("TRUNCATE users CASCADE").update(); }
    @AfterAll static void stop() throws Exception {
        if (db != null) db.stop();
        if (directory != null) {
            try (var files = Files.list(directory)) { for (Path path : files.toList()) Files.delete(path); }
            Files.delete(directory);
        }
    }

    private DocumentPipeline pipeline(long embeddingSeconds) {
        var pipeline = new DocumentPipeline(jdbc, storage, extractor, chunker, embeddings, "gemini-embedding-2", 32, 30, embeddingSeconds,
                "gemini-embedding-2", 0);
        pipelines.add(pipeline); return pipeline;
    }
    private UUID save(String name, byte[] bytes) throws Exception {
        FileStorage.StoredFile file;
        try (var input = new ByteArrayInputStream(bytes)) { file = storage.store(input, name); }
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO documents(id,user_id,filename,storage_key,media_type,size_bytes,status) VALUES (:id,:owner,:name,:key,:type,:size,'PENDING')")
                .param("id", id).param("owner", owner).param("name", name).param("key", file.key())
                .param("type", file.mediaType()).param("size", file.size()).update();
        jdbc.sql("INSERT INTO processing_jobs(id,document_id,status,next_run_at) VALUES (:id,:doc,'PENDING',now()-interval '1 second')")
                .param("id", UUID.randomUUID()).param("doc", id).update();
        return id;
    }
    private String status(UUID id) { return jdbc.sql("SELECT status FROM documents WHERE id=:id").param("id", id).query(String.class).single(); }
    private long chunks(UUID id) { return jdbc.sql("SELECT count(*) FROM document_chunks WHERE document_id=:id").param("id", id).query(Long.class).single(); }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    static class FakeEmbedding implements EmbeddingModel {
        final AtomicBoolean fail = new AtomicBoolean();
        final AtomicBoolean rateLimited = new AtomicBoolean();
        final AtomicBoolean invalidVector = new AtomicBoolean();
        final AtomicBoolean ignoreInterrupt = new AtomicBoolean();
        final AtomicBoolean embeddedInTransaction = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();
        volatile int failOnCall;
        volatile Duration retryAfter = Duration.ofSeconds(90);
        volatile CountDownLatch entered, release;
        @Override public EmbeddingResponse call(EmbeddingRequest request) {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) embeddedInTransaction.set(true);
            int call = calls.incrementAndGet();
            if (call == failOnCall) throw new IllegalStateException("simulated later batch failure");
            if (fail.get()) throw new IllegalStateException("simulated provider failure");
            if (rateLimited.get()) {
                throw new com.documind.retrieval.provider.GeminiEmbeddingModel.ProviderFailure(429, retryAfter);
            }
            if (entered != null) {
                entered.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); }
                    catch (InterruptedException e) {
                        if (!ignoreInterrupt.get()) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    }
                }
            }
            var output = new ArrayList<Embedding>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                float[] vector = new float[invalidVector.get() ? 3 : 1536]; vector[0] = 1; vector[1] = i + 1;
                output.add(new Embedding(vector, i));
            }
            return new EmbeddingResponse(output);
        }
        @Override public float[] embed(Document document) { float[] vector = new float[1536]; vector[0] = 1; return vector; }
    }

    @Test void extractsFixturesAndPreservesUnicodeOffsetsAndLimits() throws Exception {
        try (var input = Files.newInputStream(Path.of("eval/fixtures/handbook-01.pdf"))) {
            var pdf = extractor.extract(input, "application/pdf");
            assertThat(pdf.pages()).isGreaterThan(1); assertThat(pdf.sections()).allMatch(s -> s.page() != null && s.page() >= 1);
            assertThat(pdf.sections()).anySatisfy(section -> {
                assertThat(section.page()).isEqualTo(2);
                assertThat(section.text()).contains("Điều kiện hoàn tiền");
            });
        }
        try (var input = Files.newInputStream(Path.of("eval/fixtures/handbook-01.pdf"))) {
            assertThatThrownBy(() -> new DocumentExtractor(1, 1000000).extract(input, "application/pdf"))
                    .isInstanceOf(DocumentExtractor.ExtractionFailure.class)
                    .extracting(e -> ((DocumentExtractor.ExtractionFailure)e).code()).isEqualTo("PDF_PAGE_LIMIT");
        }
        try (var input = Files.newInputStream(Path.of("eval/fixtures/handbook-09.docx"))) {
            var doc = extractor.extract(input, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            assertThat(doc.pages()).isNull(); assertThat(doc.sections()).allMatch(s -> s.paragraph() != null && s.paragraph() >= 1);
            assertThat(doc.sections().getFirst().paragraph()).isEqualTo(1);
            assertThat(doc.sections().getFirst().text()).contains("Sổ tay 09, mục 1");
        }
        String line = "Xin chào 😀. ".repeat(120);
        var text = extractor.extract(new ByteArrayInputStream(("\n" + line).getBytes(StandardCharsets.UTF_8)), "text/plain");
        var withBom = extractor.extract(new ByteArrayInputStream("\uFEFFNội dung\r\n".getBytes(StandardCharsets.UTF_8)), "text/plain");
        assertThat(withBom.paragraphs()).isEqualTo(1);
        assertThat(withBom.sections().getFirst().text()).isEqualTo("Nội dung");
        for (var part : chunker.split(text)) {
            String normalized = text.sections().getFirst().text();
            assertThat(part.paragraph()).isEqualTo(2);
            assertThat(part.text()).isEqualTo(normalized.substring(normalized.offsetByCodePoints(0, part.start()), normalized.offsetByCodePoints(0, part.end())));
        }
        assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28}), "text/plain"))
                .isInstanceOf(DocumentExtractor.ExtractionFailure.class).extracting(e -> ((DocumentExtractor.ExtractionFailure)e).code()).isEqualTo("INVALID_TEXT_ENCODING");
        var brokenStorage = new java.io.InputStream() { @Override public int read() throws java.io.IOException { throw new java.io.IOException("storage unavailable"); } };
        assertThatThrownBy(() -> extractor.extract(brokenStorage, "application/pdf")).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> new DocumentExtractor(200, 4).extract(new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8)), "text/plain"))
                .isInstanceOf(DocumentExtractor.ExtractionFailure.class);
        assertThatThrownBy(() -> new DocumentChunker(10, 2, 1).split(text)).isInstanceOf(DocumentExtractor.ExtractionFailure.class);
        try (var pdf = new PDDocument(); var out = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage()); pdf.save(out);
            assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(out.toByteArray()), "application/pdf"))
                    .isInstanceOf(DocumentExtractor.ExtractionFailure.class).extracting(e -> ((DocumentExtractor.ExtractionFailure)e).code()).isEqualTo("NO_EXTRACTABLE_TEXT");
        }
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("Đầu tiên");
            doc.createTable(1, 1).getRow(0).getCell(0).setText("Nội dung bảng");
            doc.write(out);
            var extracted = extractor.extract(new ByteArrayInputStream(out.toByteArray()),
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            assertThat(extracted.sections()).extracting(DocumentExtractor.Section::text).contains("Đầu tiên", "Nội dung bảng");
        }
    }

    @Test void workerPersistsVectorsAndNeverCommitsFailedEmbedding() throws Exception {
        UUID id = save("handbook.txt", Files.readAllBytes(Path.of("eval/fixtures/handbook-15.txt")));
        var worker = new IngestionWorker(queue, pipeline(60), 2, 120, 20, 600);
        try {
            worker.poll(); await(() -> status(id).equals("READY"));
            assertThat(chunks(id)).isGreaterThan(0);
            assertThat(jdbc.sql("SELECT count(*) FROM usage_logs l JOIN processing_jobs j ON j.id=l.job_id WHERE j.document_id=:id AND l.operation='INGESTION_EMBEDDING' AND l.embedding_tokens IS NULL")
                    .param("id", id).query(Long.class).single()).isGreaterThan(0);
            assertThat(jdbc.sql("SELECT count(*) FROM document_chunks WHERE document_id=:id AND (paragraph_index IS NULL OR start_offset IS NULL OR end_offset IS NULL OR embedding_model<>'gemini-embedding-2' OR pipeline_version<>'phase7-gemini-v1')")
                    .param("id", id).query(Long.class).single()).isZero();
        } finally { worker.stop(); }
        UUID failed = save("fail.txt", "example".getBytes(StandardCharsets.UTF_8)); embeddings.fail.set(true);
        var retryWorker = new IngestionWorker(queue, pipeline(60), 2, 120, 20, 600);
        try {
            retryWorker.poll(); await(() -> jdbc.sql("SELECT attempts FROM processing_jobs WHERE document_id=:id").param("id", failed).query(Integer.class).single() == 1 && status(failed).equals("PENDING"));
            assertThat(chunks(failed)).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM usage_logs l JOIN processing_jobs j ON j.id=l.job_id WHERE j.document_id=:id AND l.operation='INGESTION_EMBEDDING'")
                    .param("id", failed).query(Long.class).single()).isEqualTo(1);
        } finally { retryWorker.stop(); }
        embeddings.fail.set(false); embeddings.rateLimited.set(true);
        UUID limited = save("limited.txt", "example".getBytes(StandardCharsets.UTF_8));
        var limitedWorker = new IngestionWorker(queue, pipeline(60), 2, 120, 20, 600);
        try {
            limitedWorker.poll(); await(() -> jdbc.sql("SELECT attempts FROM processing_jobs WHERE document_id=:id").param("id", limited).query(Integer.class).single() == 1 && status(limited).equals("PENDING"));
            assertThat(jdbc.sql("SELECT extract(epoch from (next_run_at-clock_timestamp()))::bigint FROM processing_jobs WHERE document_id=:id")
                    .param("id", limited).query(Long.class).single()).isGreaterThanOrEqualTo(89);
            assertThat(chunks(limited)).isZero();
        } finally { limitedWorker.stop(); }
        embeddings.rateLimited.set(false); embeddings.invalidVector.set(true);
        UUID invalid = save("invalid.txt", "example".getBytes(StandardCharsets.UTF_8));
        var invalidWorker = new IngestionWorker(queue, pipeline(60), 2, 120, 20, 600);
        try {
            invalidWorker.poll(); await(() -> status(invalid).equals("FAILED"));
            assertThat(jdbc.sql("SELECT error_code FROM documents WHERE id=:id").param("id", invalid).query(String.class).single()).isEqualTo("EMBEDDING_INVALID");
            assertThat(chunks(invalid)).isZero();
        } finally { invalidWorker.stop(); }
    }

    @Test void uploadedPdfDocxAndTextReachReadyThroughRealWorker() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (String name : List.of("handbook-01.pdf", "handbook-09.docx", "handbook-15.txt")) {
            byte[] bytes = Files.readAllBytes(Path.of("eval/fixtures", name));
            ids.add(documents.upload(owner, new MockMultipartFile("file", name, "application/octet-stream", bytes)).id());
        }
        var worker = new IngestionWorker(queue, pipeline(60), 2, 120, 20, 600);
        try {
            await(() -> { worker.poll(); return ids.stream().allMatch(id -> status(id).equals("READY")); });
            for (UUID id : ids) assertThat(chunks(id)).isGreaterThan(0);
            assertThat(jdbc.sql("SELECT count(*) FROM document_chunks c JOIN documents d ON d.id=c.document_id "
                    + "WHERE d.id IN (:ids) AND d.media_type='application/pdf' AND (c.page_number IS NULL OR c.paragraph_index IS NOT NULL)")
                    .param("ids", ids).query(Long.class).single()).isZero();
        } finally { worker.stop(); }
    }

    @Test void deleteDuringEmbeddingPreventsCommitAndTimeoutIsRetryable() throws Exception {
        UUID id = save("delete.txt", "sensitive text".getBytes(StandardCharsets.UTF_8));
        var claim = queue.claim("test", Duration.ofSeconds(120)).orElseThrow();
        embeddings.entered = new CountDownLatch(1); embeddings.release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var result = pool.submit(() -> pipeline(60).process(claim));
            assertThat(embeddings.entered.await(10, TimeUnit.SECONDS)).isTrue();
            documents.requestDeletion(owner, id); embeddings.release.countDown();
            assertThat(queue.complete(claim, result.get(10, TimeUnit.SECONDS))).isFalse();
            assertThat(status(id)).isEqualTo("DELETING"); assertThat(chunks(id)).isZero();
        } finally { embeddings.release.countDown(); pool.shutdownNow(); }
        UUID timeout = save("timeout.txt", "some text".getBytes(StandardCharsets.UTF_8));
        var next = queue.claim("test", Duration.ofSeconds(120)).orElseThrow();
        embeddings.entered = new CountDownLatch(1); embeddings.release = new CountDownLatch(1);
        try {
            assertThatThrownBy(() -> pipeline(1).process(next)).isInstanceOf(IngestionProcessor.ProcessingFailure.class)
                    .satisfies(e -> assertThat(((IngestionProcessor.ProcessingFailure)e).retryable()).isTrue());
            assertThat(chunks(timeout)).isZero();
        } finally { embeddings.release.countDown(); }
    }

    @Test void deleteDuringParsingPreventsRealWorkerFromCommitting() throws Exception {
        UUID id = save("parse-delete.txt", "real parser text".getBytes(StandardCharsets.UTF_8));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var completed = new CountDownLatch(1);
        var accepted = new AtomicBoolean(true);
        var blockedExtractor = new DocumentExtractor(200, 1000000) {
            @Override public Extracted extract(java.io.InputStream input, String type) throws java.io.IOException, ExtractionFailure, InterruptedException {
                entered.countDown(); release.await(); return super.extract(input, type);
            }
        };
        var pipeline = new DocumentPipeline(jdbc, storage, blockedExtractor, chunker, embeddings, "gemini-embedding-2", 32, 30, 60,
                "gemini-embedding-2", 0);
        pipelines.add(pipeline);
        var observed = spy(queue);
        doAnswer(invocation -> {
            try { boolean value = (Boolean) invocation.callRealMethod(); accepted.set(value); return value; }
            finally { completed.countDown(); }
        }).when(observed).complete(any(), any(), any());
        var worker = new IngestionWorker(observed, pipeline, 2, 120, 20, 600);
        try {
            worker.poll(); assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            documents.requestDeletion(owner, id); release.countDown();
            assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(accepted.get()).isFalse(); assertThat(status(id)).isEqualTo("DELETING");
            assertThat(chunks(id)).isZero();
        } finally { release.countDown(); worker.stop(); }
    }

    @Test void embeddingReturningAfterLeaseRecoveryCannotCommit() throws Exception {
        UUID id = save("stale.txt", "real pipeline text".getBytes(StandardCharsets.UTF_8));
        var claim = queue.claim("test", Duration.ofSeconds(120)).orElseThrow();
        embeddings.entered = new CountDownLatch(1); embeddings.release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var result = pool.submit(() -> pipeline(60).process(claim));
            assertThat(embeddings.entered.await(10, TimeUnit.SECONDS)).isTrue();
            jdbc.sql("UPDATE processing_jobs SET lease_until=now()-interval '1 second' WHERE id=:id").param("id", claim.id()).update();
            queue.recoverDue(); embeddings.release.countDown();
            assertThat(queue.complete(claim, result.get(10, TimeUnit.SECONDS))).isFalse();
            assertThat(status(id)).isEqualTo("PENDING"); assertThat(chunks(id)).isZero();
        } finally { embeddings.release.countDown(); pool.shutdownNow(); }
    }

    @Test void laterBatchFailureNeverPersistsPartialChunksAndRetriesExhaust() throws Exception {
        UUID id = save("long.txt", "chữ test ".repeat(7500).getBytes(StandardCharsets.UTF_8));
        embeddings.failOnCall = 2;
        var pipeline = pipeline(60);
        for (int attempt = 1; attempt <= 3; attempt++) {
            var claim = queue.claim("test", Duration.ofSeconds(120)).orElseThrow();
            if (attempt > 1) embeddings.fail.set(true);
            try { pipeline.process(claim); throw new AssertionError("Expected embedding failure"); }
            catch (IngestionProcessor.ProcessingFailure failure) {
                assertThat(failure.retryable()).isTrue();
                assertThat(queue.fail(claim, true, failure.retryAfter(), failure.code())).isTrue();
            }
            assertThat(chunks(id)).isZero();
            if (attempt < 3) {
                assertThat(status(id)).isEqualTo("PENDING");
                jdbc.sql("UPDATE processing_jobs SET next_run_at=now()-interval '1 second' WHERE document_id=:id").param("id", id).update();
            }
        }
        assertThat(embeddings.calls.get()).isGreaterThanOrEqualTo(2);
        assertThat(status(id)).isEqualTo("FAILED");
    }

    @Test void parserTimeoutAndNoTextHaveSafeFailureCodes() throws Exception {
        UUID slowId = save("slow.txt", "text".getBytes(StandardCharsets.UTF_8));
        var slow = new DocumentExtractor(200, 1000000) {
            @Override public Extracted extract(java.io.InputStream input, String type) throws InterruptedException {
                Thread.sleep(5000); throw new IllegalStateException("unreachable");
            }
        };
        var pipeline = new DocumentPipeline(jdbc, storage, slow, chunker, embeddings, "gemini-embedding-2", 32, 1, 60,
                "gemini-embedding-2", 0);
        pipelines.add(pipeline);
        var claim = queue.claim("test", Duration.ofSeconds(120)).orElseThrow();
        assertThatThrownBy(() -> pipeline.process(claim)).isInstanceOf(IngestionProcessor.ProcessingFailure.class)
                .satisfies(e -> { var failure = (IngestionProcessor.ProcessingFailure)e;
                    assertThat(failure.code()).isEqualTo("PARSER_TIMEOUT"); assertThat(failure.retryable()).isFalse(); });
        assertThat(chunks(slowId)).isZero();
        try (var pdf = new PDDocument(); var out = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage()); pdf.save(out);
            UUID id = save("scan.pdf", out.toByteArray());
            var worker = new IngestionWorker(queue, pipeline(60), 2, 120, 20, 600);
            try {
                worker.poll(); await(() -> status(id).equals("FAILED"));
                assertThat(jdbc.sql("SELECT error_code FROM documents WHERE id=:id").param("id", id).query(String.class).single()).isEqualTo("NO_EXTRACTABLE_TEXT");
                assertThat(jdbc.sql("SELECT error_message FROM documents WHERE id=:id").param("id", id).query(String.class).single()).contains("OCR");
            } finally { worker.stop(); }
        }
    }

    @Test void providerIgnoringTimeoutTriggersFailStopWithoutCommitting() throws Exception {
        UUID id = save("hung.txt", "some text".getBytes(StandardCharsets.UTF_8));
        var claim = queue.claim("test", Duration.ofSeconds(120)).orElseThrow();
        embeddings.entered = new CountDownLatch(1); embeddings.release = new CountDownLatch(1);
        embeddings.ignoreInterrupt.set(true);
        var failStopped = new CountDownLatch(1);
        var pipeline = new DocumentPipeline(jdbc, storage, extractor, chunker, embeddings, "gemini-embedding-2", 32,
                30, 1, "gemini-embedding-2", 0, 1, failStopped::countDown);
        pipelines.add(pipeline);
        try {
            assertThatThrownBy(() -> pipeline.process(claim)).isInstanceOf(IngestionProcessor.ProcessingFailure.class);
            assertThat(failStopped.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(chunks(id)).isZero();
        } finally { embeddings.release.countDown(); }
    }

    @Test void ingestsTwentyEvalFixturesWithFakeEmbeddings() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String extension = i <= 8 ? "pdf" : i <= 14 ? "docx" : "txt";
            String name = String.format("handbook-%02d.%s", i, extension);
            ids.add(save(name, Files.readAllBytes(Path.of("eval/fixtures", name))));
        }
        embeddings.failOnCall = 1; // One transient failure; the queue controls retry and backoff.
        var worker = new IngestionWorker(queue, pipeline(60), 2, 120, 20, 600);
        try {
            await(() -> {
                worker.poll();
                return ids.stream().allMatch(id -> status(id).equals("READY"));
            });
            for (UUID id : ids) assertThat(chunks(id)).isGreaterThan(0);
            long retried = jdbc.sql("SELECT count(*) FROM processing_jobs WHERE document_id IN (:ids) AND attempts=2 AND status='DONE'")
                    .param("ids", ids).query(Long.class).single();
            assertThat(retried).isEqualTo(1);
            System.out.println("Phase 6 eval with fake embeddings: READY=20, FAILED=0, retried=" + retried);
        } finally { worker.stop(); }
    }

    @Test void retrievalChecksScopeOwnerStatusAndEmbeddingModel() throws Exception {
        UUID id = save("ready.txt", "Tài liệu của tài khoản A".getBytes(StandardCharsets.UTF_8));
        var worker = new IngestionWorker(queue, pipeline(60), 2, 120, 20, 600);
        try { worker.poll(); await(() -> status(id).equals("READY")); }
        finally { worker.stop(); }
        UUID pending = save("pending.txt", "Tài liệu đang chờ".getBytes(StandardCharsets.UTF_8));
        UUID other = UUID.randomUUID();
        jdbc.sql("INSERT INTO users(id,email,password_hash) VALUES (:id,:email,'fixture')")
                .param("id", other).param("email", other + "@example.com").update();
        UUID foreignDoc = UUID.randomUUID();
        jdbc.sql("INSERT INTO documents(id,user_id,filename,storage_key,media_type,size_bytes,status) VALUES (:id,:owner,'other.txt',:key,'text/plain',1,'READY')")
                .param("id", foreignDoc).param("owner", other).param("key", UUID.randomUUID().toString()).update();
        jdbc.sql("""
                INSERT INTO document_chunks(id,document_id,user_id,chunk_index,paragraph_index,start_offset,end_offset,content,embedding,embedding_model,pipeline_version)
                SELECT gen_random_uuid(),:foreign,:owner,chunk_index,paragraph_index,start_offset,end_offset,content,embedding,embedding_model,pipeline_version
                FROM document_chunks WHERE document_id=:source
                """).param("foreign", foreignDoc).param("owner", other).param("source", id).update();
        var all = com.documind.retrieval.service.RetrievalService.Scope.ALL_READY;
        var selected = com.documind.retrieval.service.RetrievalService.Scope.SELECTED;
        assertThat(retrieval.search(owner, "nội dung", all, null)).extracting(com.documind.retrieval.repository.ChunkVectorStore.Hit::documentId).containsExactly(id);
        assertThat(retrieval.search(other, "nội dung", all, null)).extracting(com.documind.retrieval.repository.ChunkVectorStore.Hit::documentId).containsExactly(foreignDoc);
        assertThat(retrieval.search(owner, "nội dung", selected, List.of(id))).hasSize(1);
        assertThatThrownBy(() -> retrieval.search(other, "x", selected, List.of(id)))
                .isInstanceOf(com.documind.common.error.AppException.class)
                .satisfies(e -> assertThat(((com.documind.common.error.AppException)e).errorCode()).isEqualTo(com.documind.common.error.ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> retrieval.search(owner, "x", selected, List.of(pending)))
                .satisfies(e -> assertThat(((com.documind.common.error.AppException)e).errorCode()).isEqualTo(com.documind.common.error.ErrorCode.DOCUMENT_NOT_READY));
        assertThatThrownBy(() -> retrieval.search(owner, "x", selected, List.of(foreignDoc)))
                .satisfies(e -> assertThat(((com.documind.common.error.AppException)e).errorCode()).isEqualTo(com.documind.common.error.ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> retrieval.search(owner, "x", selected, List.of()))
                .satisfies(e -> assertThat(((com.documind.common.error.AppException)e).errorCode()).isEqualTo(com.documind.common.error.ErrorCode.INVALID_DOCUMENT_SCOPE));
        assertThatThrownBy(() -> retrieval.search(owner, "x", selected, null))
                .satisfies(e -> assertThat(((com.documind.common.error.AppException)e).errorCode()).isEqualTo(com.documind.common.error.ErrorCode.INVALID_DOCUMENT_SCOPE));
        assertThatThrownBy(() -> retrieval.search(owner, "x", selected, List.of(id, id)))
                .satisfies(e -> assertThat(((com.documind.common.error.AppException)e).errorCode()).isEqualTo(com.documind.common.error.ErrorCode.INVALID_DOCUMENT_SCOPE));
        jdbc.sql("UPDATE documents SET status='DELETING' WHERE id=:id").param("id", pending).update();
        assertThatThrownBy(() -> retrieval.search(owner, "x", selected, List.of(pending)))
                .satisfies(e -> assertThat(((com.documind.common.error.AppException)e).errorCode()).isEqualTo(com.documind.common.error.ErrorCode.DOCUMENT_NOT_READY));
        jdbc.sql("UPDATE document_chunks SET embedding_model='old-model' WHERE document_id=:id").param("id", id).update();
        assertThat(retrieval.search(owner, "x", all, null)).isEmpty();
        assertThatThrownBy(() -> retrieval.search(owner, "x", selected, List.of(id)))
                .satisfies(e -> assertThat(((com.documind.common.error.AppException)e).errorCode()).isEqualTo(com.documind.common.error.ErrorCode.DOCUMENT_NOT_READY));
        jdbc.sql("UPDATE document_chunks SET embedding_model='gemini-embedding-2',pipeline_version='older' WHERE document_id=:id").param("id", id).update();
        assertThat(retrieval.search(owner, "x", all, null)).isEmpty();
        assertThatThrownBy(() -> retrieval.search(owner, "x", selected, List.of(id)))
                .satisfies(e -> assertThat(((com.documind.common.error.AppException)e).errorCode()).isEqualTo(com.documind.common.error.ErrorCode.DOCUMENT_NOT_READY));
    }

    @Test void keywordCandidatesRespectTenantScopeDeletionAndModel() throws Exception {
        UUID first = save("one.txt", "Điều kiện hoàn tiền trong bảy ngày".getBytes(StandardCharsets.UTF_8));
        UUID second = save("two.txt", "Chính sách hoàn tiền trong ba ngày".getBytes(StandardCharsets.UTF_8));
        var worker = new IngestionWorker(queue, pipeline(60), 2, 120, 20, 600);
        try { worker.poll(); await(() -> status(first).equals("READY") && status(second).equals("READY")); }
        finally { worker.stop(); }
        UUID other = UUID.randomUUID();
        jdbc.sql("INSERT INTO users(id,email,password_hash) VALUES (:id,:email,'fixture')")
                .param("id", other).param("email", other + "@example.com").update();
        UUID foreign = UUID.randomUUID();
        jdbc.sql("INSERT INTO documents(id,user_id,filename,storage_key,media_type,size_bytes,status) VALUES (:id,:owner,'other.txt',:key,'text/plain',1,'READY')")
                .param("id", foreign).param("owner", other).param("key", UUID.randomUUID().toString()).update();
        jdbc.sql("""
                INSERT INTO document_chunks(id,document_id,user_id,chunk_index,paragraph_index,start_offset,end_offset,content,embedding,embedding_model,pipeline_version)
                SELECT gen_random_uuid(),:foreign,:owner,chunk_index,paragraph_index,start_offset,end_offset,content,embedding,embedding_model,pipeline_version
                FROM document_chunks WHERE document_id=:source
                """).param("foreign", foreign).param("owner", other).param("source", first).update();
        float[] vector = new float[1536]; vector[0] = 1; vector[1] = 1;
        var hits = vectorStore.searchKeywords(owner, null, vector, "hoàn tiền", 10, "gemini-embedding-2", -1);
        assertThat(hits).extracting(com.documind.retrieval.repository.ChunkVectorStore.Hit::documentId)
                .containsExactlyInAnyOrder(first, second).doesNotContain(foreign);
        org.springframework.test.util.ReflectionTestUtils.setField(retrieval, "hybridEnabled", true);
        try {
            assertThat(retrieval.search(owner, "hoàn tiền", com.documind.retrieval.service.RetrievalService.Scope.ALL_READY, null))
                    .extracting(com.documind.retrieval.repository.ChunkVectorStore.Hit::documentId)
                    .containsExactlyInAnyOrder(first, second).doesNotContain(foreign);
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(retrieval, "hybridEnabled", false); }
        assertThat(vectorStore.searchKeywords(owner, List.of(second), vector, "hoàn tiền", 10, "gemini-embedding-2", -1))
                .extracting(com.documind.retrieval.repository.ChunkVectorStore.Hit::documentId).containsExactly(second);
        jdbc.sql("UPDATE documents SET status='DELETING' WHERE id=:id").param("id", second).update();
        jdbc.sql("UPDATE document_chunks SET pipeline_version='old' WHERE document_id=:id").param("id", first).update();
        assertThat(vectorStore.searchKeywords(owner, null, vector, "hoàn tiền", 10, "gemini-embedding-2", -1)).isEmpty();
        assertThat(vectorStore.searchKeywords(other, null, vector, "hoàn tiền", 10, "gemini-embedding-2", -1))
                .extracting(com.documind.retrieval.repository.ChunkVectorStore.Hit::documentId).containsExactly(foreign);
    }

    @Test void emptyAllReadyDoesNotCallEmbedding() {
        embeddings.fail.set(true);
        assertThat(retrieval.search(owner, "câu hỏi", com.documind.retrieval.service.RetrievalService.Scope.ALL_READY, null)).isEmpty();
        assertThat(embeddings.calls.get()).isZero();
    }

    @Test void providerQuotaDelaySurvivesWorkerAndQueue() throws Exception {
        UUID id = save("quota.txt", "Tài liệu kiểm thử".getBytes(StandardCharsets.UTF_8));
        embeddings.rateLimited.set(true); embeddings.retryAfter = Duration.ofHours(4);
        var worker = new IngestionWorker(queue, pipeline(60), 2, 120, 20, 600);
        try {
            worker.poll();
            await(() -> jdbc.sql("SELECT attempts FROM processing_jobs WHERE document_id=:id").param("id", id).query(Integer.class).single() == 1
                    && status(id).equals("PENDING"));
            long remaining = jdbc.sql("SELECT extract(epoch from (next_run_at-clock_timestamp()))::bigint FROM processing_jobs WHERE document_id=:id")
                    .param("id", id).query(Long.class).single();
            assertThat(remaining).isGreaterThanOrEqualTo(14395);
            assertThat(chunks(id)).isZero();
        } finally { worker.stop(); }
        UUID lastAttempt = save("last-quota.txt", "Tài liệu kiểm thử".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("UPDATE processing_jobs SET attempts=2 WHERE document_id=:id").param("id", lastAttempt).update();
        var lastWorker = new IngestionWorker(queue, pipeline(60), 2, 120, 20, 600);
        try {
            lastWorker.poll(); await(() -> status(lastAttempt).equals("FAILED"));
            assertThat(jdbc.sql("SELECT error_code FROM documents WHERE id=:id").param("id", lastAttempt).query(String.class).single())
                    .isEqualTo("PROVIDER_RATE_LIMIT");
        } finally { lastWorker.stop(); }
    }

    @Test void deleteDuringEmbeddingMakesSelectedScopeNotReady() throws Exception {
        UUID id = save("selected.txt", "Tài liệu".getBytes(StandardCharsets.UTF_8));
        var worker = new IngestionWorker(queue, pipeline(60), 2, 120, 20, 600);
        try { worker.poll(); await(() -> status(id).equals("READY")); }
        finally { worker.stop(); }
        embeddings.entered = new CountDownLatch(1); embeddings.release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> retrieval.search(owner, "câu hỏi", com.documind.retrieval.service.RetrievalService.Scope.SELECTED, List.of(id)));
            assertThat(embeddings.entered.await(5, TimeUnit.SECONDS)).isTrue();
            jdbc.sql("UPDATE documents SET status='DELETING' WHERE id=:id").param("id", id).update();
            embeddings.release.countDown();
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(com.documind.common.error.AppException.class)
                    .satisfies(e -> assertThat(((com.documind.common.error.AppException)e.getCause()).errorCode()).isEqualTo(com.documind.common.error.ErrorCode.DOCUMENT_NOT_READY));
        } finally { embeddings.release.countDown(); }
    }

    @Test void selectedReadHoldsDocumentLockUntilSearchFinishes() throws Exception {
        UUID id = save("lock.txt", "Tài liệu".getBytes(StandardCharsets.UTF_8));
        var worker = new IngestionWorker(queue, pipeline(60), 2, 120, 20, 600);
        try { worker.poll(); await(() -> status(id).equals("READY")); }
        finally { worker.stop(); }
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var blockedStore = spy(new com.documind.retrieval.repository.ChunkVectorStore(jdbc));
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return invocation.callRealMethod();
        }).when(blockedStore).search(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), org.mockito.ArgumentMatchers.anyDouble());
        var service = new com.documind.retrieval.service.RetrievalService(jdbc, blockedStore, modelProvider, transactionManager, 5, -1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var search = executor.submit(() -> service.search(owner, "câu hỏi", com.documind.retrieval.service.RetrievalService.Scope.SELECTED, List.of(id)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var deleted = executor.submit(() -> jdbc.sql("UPDATE documents SET status='DELETING' WHERE id=:id").param("id", id).update());
            assertThatThrownBy(() -> deleted.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            release.countDown();
            assertThat(search.get(5, TimeUnit.SECONDS)).hasSize(1);
            assertThat(embeddings.embeddedInTransaction).isFalse();
            assertThat(deleted.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        } finally { release.countDown(); }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "documind.benchmark", matches = "true")
    void benchmarkExactAndHnswWithTenThousandVectors() throws Exception {
        UUID other = UUID.randomUUID(), ownDoc = UUID.randomUUID(), otherDoc = UUID.randomUUID();
        jdbc.sql("INSERT INTO users(id,email,password_hash) VALUES (:id,:email,'fixture')")
                .param("id", other).param("email", other + "@example.com").update();
        for (var pair : List.of(java.util.Map.entry(ownDoc, owner), java.util.Map.entry(otherDoc, other))) {
            jdbc.sql("INSERT INTO documents(id,user_id,filename,storage_key,media_type,size_bytes,status) VALUES (:id,:owner,'bench.txt',:key,'text/plain',1,'READY')")
                    .param("id", pair.getKey()).param("owner", pair.getValue()).param("key", UUID.randomUUID().toString()).update();
        }
        jdbc.sql("""
            INSERT INTO document_chunks(id,document_id,user_id,chunk_index,paragraph_index,start_offset,end_offset,content,embedding,embedding_model,pipeline_version)
            SELECT gen_random_uuid(), CASE WHEN g % 2 = 0 THEN :ownDoc ELSE :otherDoc END,
                   CASE WHEN g % 2 = 0 THEN :owner ELSE :other END, g, 1, 0, 1, 'x',
                   ('[1,' || (g::double precision / 10000)::text || repeat(',0',1534) || ']')::vector,
                   'gemini-embedding-2','phase7-gemini-v1' FROM generate_series(1,10000) g
            """).param("ownDoc", ownDoc).param("otherDoc", otherDoc).param("owner", owner).param("other", other).update();
        jdbc.sql("ANALYZE document_chunks").update();
        float[] query = new float[1536]; query[0] = 1; query[1] = 0.5f;
        String vector = "[1,0.5" + ",0".repeat(1534) + "]";
        long[] exactTimes = new long[100], approximateTimes = new long[100], hybridTimes = new long[100];
        int exactCount = 0, annCount = 0, overlap = 0;
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL enable_seqscan=off");
            statement.execute("SET LOCAL enable_bitmapscan=off");
            String sql = "SELECT id FROM document_chunks WHERE user_id='" + owner + "' ORDER BY embedding <=> '" + vector + "'::vector LIMIT 5";
            String plan;
            try (var result = statement.executeQuery("EXPLAIN " + sql)) {
                var text = new StringBuilder(); while (result.next()) text.append(result.getString(1)); plan = text.toString();
            }
            assertThat(plan).contains("ix_chunks_embedding_hnsw");
            for (int i = 0; i < 110; i++) {
                long start = System.nanoTime();
                var exact = vectorStore.search(owner, null, query, 5, "gemini-embedding-2", -1);
                long exactElapsed = System.nanoTime() - start;
                start = System.nanoTime();
                var hybrid = vectorStore.hybridSearch(owner, null, query, "x", 5, "gemini-embedding-2", -1);
                long hybridElapsed = System.nanoTime() - start;
                assertThat(hybrid).hasSize(5);
                start = System.nanoTime();
                var ann = new ArrayList<UUID>();
                try (var rows = statement.executeQuery(sql)) { while (rows.next()) ann.add(rows.getObject(1, UUID.class)); }
                long annElapsed = System.nanoTime() - start;
                if (i >= 10) { exactTimes[i-10] = exactElapsed; approximateTimes[i-10] = annElapsed; hybridTimes[i-10] = hybridElapsed; }
                exactCount = exact.size(); annCount = ann.size();
                var ids = exact.stream().map(com.documind.retrieval.repository.ChunkVectorStore.Hit::id).toList();
                overlap = (int) ann.stream().filter(ids::contains).count();
            }
            connection.rollback();
        }
        java.util.Arrays.sort(exactTimes); java.util.Arrays.sort(approximateTimes); java.util.Arrays.sort(hybridTimes);
        System.out.printf("BENCH exact_p95_ms=%.2f hybrid_p95_ms=%.2f hnsw_p95_ms=%.2f exact_count=%d hnsw_count=%d overlap=%d/5%n",
                exactTimes[94] / 1e6, hybridTimes[94] / 1e6, approximateTimes[94] / 1e6, exactCount, annCount, overlap);
        assertThat(exactCount).isEqualTo(5);
    }
}
