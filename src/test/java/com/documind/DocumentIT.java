package com.documind;

import com.documind.auth.security.AccessTokens;
import com.documind.document.DocumentMaintenance;
import com.documind.document.DocumentService;
import com.documind.document.FileStorage;
import com.documind.document.LocalFileStorage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.Duration;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DocumentIT.Config.class)
class DocumentIT {
    private static PostgreSQLContainer container;
    private static Path directory;
    private static final String TEST_SECRET = Base64.getEncoder().encodeToString(
            (UUID.randomUUID().toString() + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AccessTokens tokens;
    @Autowired JsonMapper json;
    @Autowired DocumentMaintenance maintenance;
    @Autowired FailingStorage storage;
    private UUID owner;
    private UUID other;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        directory = Files.createTempDirectory("documind-document-test-");
        container = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.7-pg15").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("document_test").withUsername("document_test").withPassword(UUID.randomUUID().toString());
        container.start();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
        registry.add("spring.flyway.user", container::getUsername);
        registry.add("spring.flyway.password", container::getPassword);
        registry.add("documind.auth.jwt-secret", () -> TEST_SECRET);
        registry.add("documind.auth.allowed-origins", () -> "https://documind.test");
        registry.add("documind.storage.path", () -> directory.toString());
        registry.add("documind.storage.initialize-if-empty", () -> "true");
        registry.add("documind.storage.cleanup-interval-ms", () -> "3600000");
        registry.add("documind.storage.orphan-interval-ms", () -> "3600000");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean @Primary FailingStorage failingStorage(LocalFileStorage delegate) { return new FailingStorage(delegate); }
    }

    static class FailingStorage implements FileStorage {
        private final LocalFileStorage delegate;
        final AtomicBoolean failDelete = new AtomicBoolean();
        final AtomicBoolean failStore = new AtomicBoolean();
        FailingStorage(LocalFileStorage delegate) { this.delegate = delegate; }
        public StoredFile store(InputStream input, String filename) throws IOException {
            if (failStore.get()) throw new IOException("simulated storage failure");
            return delegate.store(input, filename);
        }
        public InputStream open(String key) throws IOException { return delegate.open(key); }
        public void delete(String key) throws IOException {
            if (failDelete.get()) throw new IOException("simulated storage failure");
            delegate.delete(key);
        }
        public List<Entry> entries() throws IOException { return delegate.entries(); }
    }

    @BeforeEach void prepare() throws IOException {
        storage.failDelete.set(false);
        storage.failStore.set(false);
        jdbc.sql("TRUNCATE users CASCADE").update();
        try (var files = Files.list(directory)) {
            for (Path path : files.toList()) if (!path.getFileName().toString().equals(".documind-storage-id")) Files.delete(path);
        }
        owner = createUser(); other = createUser();
    }
    private UUID createUser() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users(id,email,password_hash) VALUES (:id,:email,'fixture')")
                .param("id", id).param("email", id + "@example.com").update();
        return id;
    }
    @AfterAll static void stop() throws IOException {
        if (container != null) container.stop();
        if (directory != null) {
            try (var files = Files.list(directory)) { for (Path path : files.toList()) Files.delete(path); }
            Files.delete(directory);
        }
    }
    private String bearer(UUID user) { return "Bearer " + tokens.issue(user); }
    private MockMultipartFile file(String name, byte[] contents) { return new MockMultipartFile("file", name, "application/octet-stream", contents); }
    private UUID upload() throws Exception {
        var result = mvc.perform(multipart("/api/v1/documents").file(file("test.txt", "hello".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", bearer(owner))).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING")).andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    @Test void uploadIsolationListingAndPersistentFile() throws Exception {
        UUID id = upload();
        String key = jdbc.sql("SELECT storage_key FROM documents WHERE id=:id").param("id", id).query(String.class).single();
        assertThat(Files.readString(directory.resolve(key))).isEqualTo("hello");
        assertThat(new LocalFileStorage(directory.toString(), jdbc, true).entries()).extracting(FileStorage.Entry::key).contains(key);
        try (var reopened = new LocalFileStorage(directory.toString(), jdbc, true).open(key)) {
            assertThat(new String(reopened.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
        assertThat(jdbc.sql("SELECT status FROM processing_jobs WHERE document_id=:id").param("id", id).query(String.class).single()).isEqualTo("PENDING");
        mvc.perform(get("/api/v1/documents").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id.toString())).andExpect(jsonPath("$.items[0].nextRetryAt").doesNotExist());
        mvc.perform(get("/api/v1/documents/" + id).header("Authorization", bearer(other))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/documents/" + id).header("Authorization", bearer(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents").header("Authorization", bearer(other))).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/documents")).andExpect(status().isUnauthorized());
    }

    @Test void listingUsesStablePaginationAndValidatesBounds() throws Exception {
        UUID first = upload(), second = upload();
        jdbc.sql("UPDATE documents SET created_at='2026-01-01 00:00:00+00' WHERE id IN (:first,:second)")
                .param("first", first).param("second", second).update();
        // PostgreSQL UUID ordering is bytewise; Java UUID.compareTo uses signed longs.
        String expectedFirst = first.toString().compareTo(second.toString()) > 0 ? first.toString() : second.toString();
        mvc.perform(get("/api/v1/documents?size=1&page=0").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(expectedFirst))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/v1/documents?size=101").header("Authorization", bearer(owner))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/documents?page=-1").header("Authorization", bearer(owner))).andExpect(status().isBadRequest());
    }

    @Test void rejectsInvalidOrOversizedFilesWithoutEnqueue() throws Exception {
        var fakeZip = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(fakeZip)) {
            for (String name : List.of("[Content_Types].xml", "word/document.xml")) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name));
                zip.write("fixture".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        for (MockMultipartFile invalid : List.of(file("../bad.txt", new byte[]{65}), file("fake.pdf", "hello".getBytes(StandardCharsets.UTF_8)),
                file("fake.pdf", "%PDF-1.7\nfixture".getBytes(StandardCharsets.UTF_8)),
                file("fake.docx", "PKbad".getBytes(StandardCharsets.UTF_8)), file("fake.docx", fakeZip.toByteArray()),
                file("empty.txt", new byte[0]))) {
            mvc.perform(multipart("/api/v1/documents").file(invalid).header("Authorization", bearer(owner)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(400, 415));
        }
        mvc.perform(multipart("/api/v1/documents").file(file("large.txt", new byte[10 * 1024 * 1024 + 1]))
                .header("Authorization", bearer(owner))).andExpect(status().isPayloadTooLarge());
        assertThat(jdbc.sql("SELECT count(*) FROM documents").query(Long.class).single()).isZero();
        assertThat(storage.entries()).isEmpty();
    }

    @Test void acceptsRecognizedPdfAndDocxAndRollsBackFileOnDatabaseFailure() throws Exception {
        for (MockMultipartFile valid : List.of(file("a.pdf", Files.readAllBytes(Path.of("eval/fixtures/handbook-01.pdf"))),
                file("a.docx", Files.readAllBytes(Path.of("eval/fixtures/handbook-09.docx"))))) {
            mvc.perform(multipart("/api/v1/documents").file(valid).header("Authorization", bearer(owner)))
                    .andExpect(status().isAccepted());
        }
        assertThat(storage.entries()).hasSize(2);
        mvc.perform(multipart("/api/v1/documents").file(file("failure.txt", "data".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", bearer(UUID.randomUUID()))).andExpect(status().isInternalServerError());
        assertThat(storage.entries()).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM processing_jobs").query(Long.class).single()).isEqualTo(2);
    }

    @Test void unavailableOrWrongStorageNeverRemovesMetadata() throws Exception {
        UUID id = upload();
        mvc.perform(delete("/api/v1/documents/" + id).header("Authorization", bearer(owner))).andExpect(status().isAccepted());
        Path marker = directory.resolve(".documind-storage-id");
        String correct = Files.readString(marker);
        try {
            Files.delete(marker);
            mvc.perform(multipart("/api/v1/documents").file(file("blocked.txt", new byte[]{65}))
                    .header("Authorization", bearer(owner))).andExpect(status().isInternalServerError());
            assertThat(Files.exists(marker)).isFalse();
            maintenance.cleanupDue();
            assertThat(jdbc.sql("SELECT count(*) FROM documents WHERE id=:id").param("id", id).query(Long.class).single()).isEqualTo(1);
            Files.writeString(marker, UUID.randomUUID().toString());
            jdbc.sql("UPDATE document_cleanup_tasks SET next_run_at=now() WHERE document_id=:id").param("id", id).update();
            maintenance.cleanupDue();
            assertThat(jdbc.sql("SELECT attempts FROM document_cleanup_tasks WHERE document_id=:id").param("id", id).query(Integer.class).single()).isEqualTo(2);
        } finally { Files.writeString(marker, correct); }
        jdbc.sql("UPDATE document_cleanup_tasks SET next_run_at=now() WHERE document_id=:id").param("id", id).update();
        maintenance.cleanupDue();
        assertThat(jdbc.sql("SELECT count(*) FROM documents WHERE id=:id").param("id", id).query(Long.class).single()).isZero();
    }

    @Test void failedJobInsertRollsBackDocumentAndCleansFile() throws Exception {
        jdbc.sql("ALTER TABLE processing_jobs ADD CONSTRAINT test_job_failure CHECK (status <> 'PENDING')").update();
        try {
            mvc.perform(multipart("/api/v1/documents").file(file("failure.txt", new byte[]{65}))
                    .header("Authorization", bearer(owner))).andExpect(status().isInternalServerError());
            assertThat(jdbc.sql("SELECT count(*) FROM documents").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM processing_jobs").query(Long.class).single()).isZero();
            assertThat(storage.entries()).isEmpty();
        } finally {
            jdbc.sql("ALTER TABLE processing_jobs DROP CONSTRAINT test_job_failure").update();
        }
        storage.failStore.set(true);
        mvc.perform(multipart("/api/v1/documents").file(file("failure.txt", new byte[]{65}))
                .header("Authorization", bearer(owner))).andExpect(status().isInternalServerError());
        assertThat(jdbc.sql("SELECT count(*) FROM documents").query(Long.class).single()).isZero();
    }

    @Test void unknownCommitOutcomeKeepsFileWhenDatabaseMayHaveCommitted() throws Exception {
        PlatformTransactionManager uncertain = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                TransactionSynchronizationManager.initSynchronization();
                return new SimpleTransactionStatus();
            }
            public void commit(TransactionStatus status) {
                try {
                    TransactionSynchronizationManager.getSynchronizations().forEach(
                            sync -> sync.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN));
                } finally { TransactionSynchronizationManager.clearSynchronization(); }
                throw new TransactionSystemException("simulated lost commit acknowledgement");
            }
            public void rollback(TransactionStatus status) { TransactionSynchronizationManager.clearSynchronization(); }
        };
        // JDBC uses autocommit here, representing a database commit whose acknowledgement was lost.
        var service = new DocumentService(jdbc, storage, uncertain);
        assertThatThrownBy(() -> service.upload(owner, file("uncertain.txt", "data".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(TransactionSystemException.class);
        String key = jdbc.sql("SELECT storage_key FROM documents WHERE user_id=:owner").param("owner", owner).query(String.class).single();
        assertThat(jdbc.sql("SELECT count(*) FROM processing_jobs").query(Long.class).single()).isEqualTo(1);
        try (var input = storage.open(key)) { assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("data"); }
    }

    @Test void deletionScrubsSnapshotAndRetriesFailureBeforeRemovingMetadata() throws Exception {
        UUID id = upload(), conversation = UUID.randomUUID(), message = UUID.randomUUID();
        jdbc.sql("INSERT INTO conversations(id,user_id,title) VALUES (:id,:owner,'fixture')").param("id", conversation).param("owner", owner).update();
        String sources = "[{\"sourceId\":\"S1\",\"documentId\":\"" + id + "\",\"filename\":\"test.txt\",\"snippet\":\"secret\",\"availability\":\"AVAILABLE\",\"paragraphIndex\":1}]";
        jdbc.sql("INSERT INTO messages(id,conversation_id,ordinal,role,status,sources) VALUES (:id,:conversation,1,'ASSISTANT','COMPLETE',CAST(:sources AS jsonb))")
                .param("id", message).param("conversation", conversation).param("sources", sources).update();
        mvc.perform(delete("/api/v1/documents/" + id).header("Authorization", bearer(owner))).andExpect(status().isAccepted());
        mvc.perform(delete("/api/v1/documents/" + id).header("Authorization", bearer(owner))).andExpect(status().isAccepted());
        mvc.perform(get("/api/v1/documents/" + id).header("Authorization", bearer(owner))).andExpect(jsonPath("$.status").value("DELETING"));
        String scrubbed = jdbc.sql("SELECT sources::text FROM messages WHERE id=:id").param("id", message).query(String.class).single();
        assertThat(scrubbed).contains("DELETED", "paragraphIndex").doesNotContain("secret", "test.txt");
        storage.failDelete.set(true); maintenance.cleanupDue();
        assertThat(jdbc.sql("SELECT attempts FROM document_cleanup_tasks WHERE document_id=:id").param("id", id).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM documents WHERE id=:id").param("id", id).query(Long.class).single()).isEqualTo(1);
        storage.failDelete.set(false);
        jdbc.sql("UPDATE document_cleanup_tasks SET next_run_at=now() WHERE document_id=:id").param("id", id).update();
        maintenance.cleanupDue(); maintenance.cleanupDue();
        mvc.perform(get("/api/v1/documents/" + id).header("Authorization", bearer(owner))).andExpect(status().isNotFound());
        assertThat(storage.entries()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM deletion_journal_outbox WHERE document_id=:id").param("id", id).query(Long.class).single()).isEqualTo(1);
    }

    @Test void removesOnlyOldUnreferencedFiles() throws Exception {
        UUID id = upload();
        String key = jdbc.sql("SELECT storage_key FROM documents WHERE id=:id").param("id", id).query(String.class).single();
        Path oldOrphan = directory.resolve(UUID.randomUUID().toString()); Files.writeString(oldOrphan, "old");
        Path freshOrphan = directory.resolve(UUID.randomUUID().toString()); Files.writeString(freshOrphan, "new");
        Files.setLastModifiedTime(oldOrphan, FileTime.from(Instant.now().minusSeconds(90000)));
        Files.setLastModifiedTime(directory.resolve(key), FileTime.from(Instant.now().minusSeconds(90000)));
        maintenance.sweepOrphans();
        assertThat(Files.exists(oldOrphan)).isFalse();
        assertThat(Files.exists(freshOrphan)).isTrue();
        assertThat(Files.exists(directory.resolve(key))).isTrue();
    }

    @Test void documentAndFileSurviveFullApplicationRestart() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        Process process = null;
        try {
            process = startApplication(port);
            awaitHealth(client, process, port);
            String boundary = "documind-test-boundary";
            String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"restart.txt\"\r\n"
                    + "Content-Type: text/plain\r\n\r\npersistent content\r\n--" + boundary + "--\r\n";
            var upload = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/documents"))
                    .header("Authorization", bearer(owner)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(upload, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(202);
            UUID id = UUID.fromString(json.readTree(response.body()).get("id").asString());
            String key = jdbc.sql("SELECT storage_key FROM documents WHERE id=:id").param("id", id).query(String.class).single();
            stopApplication(process); process = null;
            process = startApplication(port);
            awaitHealth(client, process, port);
            var get = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/documents/" + id))
                    .header("Authorization", bearer(owner)).GET().build();
            var detail = client.send(get, HttpResponse.BodyHandlers.ofString());
            assertThat(detail.statusCode()).isEqualTo(200);
            assertThat(json.readTree(detail.body()).get("status").asString()).isEqualTo("PENDING");
            try (var input = storage.open(key)) {
                assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("persistent content");
            }
        } finally { if (process != null) stopApplication(process); }
    }

    private Process startApplication(int port) throws IOException {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var builder = new ProcessBuilder(executable, "-jar", "target/documind-backend-0.0.1-SNAPSHOT.jar",
                "--spring.profiles.active=test", "--server.port=" + port);
        var env = builder.environment();
        env.put("TEST_DB_URL", container.getJdbcUrl()); env.put("TEST_DB_USERNAME", container.getUsername());
        env.put("TEST_DB_PASSWORD", container.getPassword()); env.put("TEST_DB_MIGRATION_USERNAME", container.getUsername());
        env.put("TEST_DB_MIGRATION_PASSWORD", container.getPassword()); env.put("JWT_SECRET", TEST_SECRET);
        env.put("AUTH_ALLOWED_ORIGINS", "https://documind.test"); env.put("DOCUMENT_STORAGE_PATH", directory.toString());
        env.put("DOCUMIND_STORAGE_INITIALIZE_IF_EMPTY", "true");
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    private static void awaitHealth(HttpClient client, Process process, int port) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        while (System.nanoTime() < deadline && process.isAlive()) {
            try { if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return; }
            catch (IOException ignored) { }
            Thread.sleep(250);
        }
        throw new AssertionError("Application did not become healthy during restart test");
    }

    private static void stopApplication(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly(); process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
