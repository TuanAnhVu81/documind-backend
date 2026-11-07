package com.documind;

import com.documind.auth.security.AccessTokens;
import com.documind.document.DocumentMaintenance;
import com.documind.document.DocumentService;
import com.documind.document.FileStorage;
import com.documind.document.LocalFileStorage;
import com.documind.chat.ChatProvider;
import com.documind.chat.ChatTurnService;
import com.documind.chat.ConversationService;
import com.documind.chat.QueryResolver;
import com.documind.chat.RagAnswerEngine;
import com.documind.retrieval.ChunkVectorStore;
import com.documind.retrieval.RetrievalService;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
    @Autowired ConversationService conversations;
    @Autowired ChatTurnService actualChat;
    @Autowired com.documind.chat.GenerationRecovery generationRecovery;
    @Autowired com.documind.chat.ChatAdmissionService chatAdmission;
    @Autowired com.documind.chat.GenerationLifecycle generationLifecycle;
    @Autowired TestChatProvider testChatProvider;
    @org.springframework.boot.test.web.server.LocalServerPort int serverPort;
    @Autowired DocumentService documents;
    @Autowired QueryResolver queryResolver;
    @Autowired RagAnswerEngine answerEngine;
    @Autowired ChunkVectorStore chunkVectors;
    @Autowired PlatformTransactionManager transactionManager;
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
        @Bean TestChatProvider testChatProvider() { return new TestChatProvider(); }
        @Bean org.springframework.ai.embedding.EmbeddingModel testEmbedding() {
            var fake = org.mockito.Mockito.mock(org.springframework.ai.embedding.EmbeddingModel.class);
            float[] vector = new float[1536]; java.util.Arrays.fill(vector, 1);
            org.mockito.Mockito.when(fake.embedForResponse(org.mockito.ArgumentMatchers.anyList()))
                    .thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(List.of(new org.springframework.ai.embedding.Embedding(vector, 0))));
            return fake;
        }
    }

    static class TestChatProvider implements ChatProvider {
        volatile java.util.concurrent.CountDownLatch emitted = new java.util.concurrent.CountDownLatch(1);
        volatile java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(0);
        volatile boolean floodAfterRelease;
        volatile String provisional = "Uncited.";
        final java.util.concurrent.atomic.AtomicInteger completeCalls = new java.util.concurrent.atomic.AtomicInteger();
        @Override public Reply complete(String system, String input) { completeCalls.incrementAndGet(); return new Reply("Repaired [S1].", 2L, 1L); }
        @Override public Reply stream(String system, String input, java.util.function.Consumer<String> tokens) {
            tokens.accept(provisional); emitted.countDown();
            try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test timed out"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            if (floodAfterRelease) for (int i = 0; i < 50; i++) tokens.accept("x".repeat(2048));
            return new Reply(provisional, 2L, 1L);
        }
        void reset() { emitted = new java.util.concurrent.CountDownLatch(1); release = new java.util.concurrent.CountDownLatch(0); floodAfterRelease = false; provisional = "Uncited."; completeCalls.set(0); }
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
        testChatProvider.reset();
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

    @Test void usageIsAccountScopedAndPreservesUnknownValues() throws Exception {
        mvc.perform(get("/api/v1/usage")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/usage").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.questionCount").value(0))
                .andExpect(jsonPath("$.promptTokens").value(0))
                .andExpect(jsonPath("$.embeddingTokens").value(0));
        jdbc.sql("INSERT INTO question_events(generation_id,user_id,created_at) VALUES (:id,:owner,now())")
                .param("id", UUID.randomUUID()).param("owner", owner).update();
        jdbc.sql("""
                INSERT INTO usage_logs(id,user_id,operation,provider,model,prompt_tokens,completion_tokens,estimated_cost_usd,estimated)
                VALUES (:id,:owner,'CHAT_ANSWER','GEMINI','test',10,5,0.01,true)
                """).param("id", UUID.randomUUID()).param("owner", owner).update();
        jdbc.sql("INSERT INTO usage_logs(id,user_id,operation,provider,model) VALUES (:id,:owner,'INGESTION_EMBEDDING','GEMINI','test')")
                .param("id", UUID.randomUUID()).param("owner", owner).update();
        jdbc.sql("INSERT INTO usage_logs(id,user_id,operation,provider,model,prompt_tokens) VALUES (:id,:owner,'CHAT_ANSWER','GEMINI','test',999)")
                .param("id", UUID.randomUUID()).param("owner", other).update();
        var result = mvc.perform(get("/api/v1/usage?userId=" + other).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("questionCount").asLong()).isEqualTo(1);
        assertThat(body.path("promptTokens").asLong()).isEqualTo(10);
        assertThat(body.path("completionTokens").asLong()).isEqualTo(5);
        assertThat(body.path("embeddingTokens").isNull()).isTrue();
        assertThat(body.path("estimatedCostUsd").isNull()).isTrue();
        assertThat(body.path("estimated").asBoolean()).isTrue();
        assertThat(body.path("periodStart").isTextual()).isTrue();
        assertThat(body.path("periodEnd").isTextual()).isTrue();
        mvc.perform(get("/api/v1/usage").header("Authorization", bearer(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.questionCount").value(0))
                .andExpect(jsonPath("$.promptTokens").value(999));
    }

    @Test void uploadLimitIsPerUserAndInvalidUploadsDoNotConsumeSuccessQuota() throws Exception {
        mvc.perform(multipart("/api/v1/documents").file(file("empty.txt", new byte[0]))
                .header("Authorization", bearer(owner))).andExpect(status().isBadRequest());
        for (int i = 0; i < 10; i++) upload();
        mvc.perform(multipart("/api/v1/documents").file(file("eleventh.txt", "text".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", bearer(owner)))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        mvc.perform(multipart("/api/v1/documents").file(file("other.txt", "text".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", bearer(other))).andExpect(status().isAccepted());
    }

    @Test void usageDoesNotReportZeroForUntrackedHistoricalEmbedding() throws Exception {
        jdbc.sql("INSERT INTO usage_untracked_history(user_id) VALUES (:owner)").param("owner", owner).update();
        var result = mvc.perform(get("/api/v1/usage").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("embeddingTokens").isNull()).isTrue();
        assertThat(body.path("estimatedCostUsd").isNull()).isTrue();
    }

    @Test void conversationsEnforceOwnershipAndPageMessages() throws Exception {
        var result = mvc.perform(post("/api/v1/conversations").header("Authorization", bearer(owner))
                .contentType("application/json").content("{}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("Hội thoại mới")).andReturn();
        UUID id = UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asString());
        for (int i = 1; i <= 3; i++) {
            jdbc.sql("INSERT INTO messages(id,conversation_id,ordinal,role,content,status) VALUES (:id,:conversation,:ordinal,'USER','test','COMPLETE')")
                    .param("id", UUID.randomUUID()).param("conversation", id).param("ordinal", i).update();
        }
        mvc.perform(get("/api/v1/conversations/" + id + "/messages?limit=2").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].ordinal").value(2)).andExpect(jsonPath("$.items[1].ordinal").value(3))
                .andExpect(jsonPath("$.hasMore").value(true)).andExpect(jsonPath("$.nextBeforeOrdinal").value(2));
        mvc.perform(get("/api/v1/conversations/" + id + "/messages?limit=2&beforeOrdinal=2").header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].ordinal").value(1)).andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/api/v1/conversations/" + id + "/messages").header("Authorization", bearer(other))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/conversations/" + id).header("Authorization", bearer(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/conversations").header("Authorization", bearer(owner))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(delete("/api/v1/conversations/" + id).header("Authorization", bearer(owner))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/conversations/" + id + "/messages").header("Authorization", bearer(owner))).andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT count(*) FROM messages WHERE conversation_id=:id").param("id", id).query(Long.class).single()).isZero();
    }

    private ChatTurnService chatService(UUID document, ChatProvider provider) {
        var retrieval = org.mockito.Mockito.mock(RetrievalService.class);
        org.mockito.Mockito.when(retrieval.search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(4)).run();
                    return List.of(new ChunkVectorStore.Hit(UUID.randomUUID(), document, "source.txt", 0,
                            null, 1, 0, 4, "fact", 0.1));
                });
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("chat", provider);
        return new ChatTurnService(jdbc, transactionManager, retrieval, queryResolver, answerEngine,
                beans.getBeanProvider(ChatProvider.class), json);
    }

    @Test void realRetrievalWithoutDocumentsReturnsNoEvidenceWithoutProvider() {
        UUID conversation = conversations.create(owner, null).id();
        var result = actualChat.process(owner, conversation, UUID.randomUUID(), "Question?",
                RetrievalService.Scope.ALL_READY, null);
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.outcome()).isEqualTo("NO_EVIDENCE");
        assertThat(jdbc.sql("SELECT count(*) FROM usage_logs").query(Long.class).single()).isZero();
    }

    @Test void admissionReplayDoesNotCreateAnotherGenerationOrUseProvider() throws Exception {
        UUID conversation = conversations.create(owner, null).id(), key = UUID.randomUUID();
        var request = com.documind.chat.ChatRequest.parse(json.readTree("{\"content\":\"Question?\"}"), json);
        var first = chatAdmission.admit(owner, conversation, key, request);
        assertThat(first.started()).isNotNull();
        assertThatThrownBy(() -> chatAdmission.admit(owner, conversation, key, request))
                .isInstanceOf(com.documind.chat.ChatConflict.class)
                .satisfies(error -> assertThat(((com.documind.chat.ChatConflict) error).errorCode())
                        .isEqualTo(com.documind.common.error.ErrorCode.GENERATION_IN_PROGRESS));
        mvc.perform(post("/api/v1/conversations/" + conversation + "/messages").header("Authorization", bearer(owner))
                .header("Idempotency-Key", key).contentType("application/json").accept("text/event-stream")
                .content("{\"content\":\"Question?\"}"))
                .andExpect(status().isConflict()).andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("GENERATION_IN_PROGRESS"))
                .andExpect(jsonPath("$.generationId").value(first.started().generation().toString()));
        var result = actualChat.execute(owner, first.started(), request.content(), request.scope(), request.documentIds());
        assertThat(result.outcome()).isEqualTo("NO_EVIDENCE");
        assertThat(generationLifecycle.snapshot(owner, conversation, first.started().assistant()).message().outcome()).isEqualTo("NO_EVIDENCE");
        var replay = chatAdmission.admit(owner, conversation, key, request);
        assertThat(replay.replay().generation()).isEqualTo(first.started().generation());
        var changed = com.documind.chat.ChatRequest.parse(json.readTree("{\"content\":\"Changed?\"}"), json);
        assertThatThrownBy(() -> chatAdmission.admit(owner, conversation, key, changed))
                .isInstanceOf(com.documind.chat.ChatConflict.class)
                .satisfies(error -> assertThat(((com.documind.chat.ChatConflict) error).errorCode())
                        .isEqualTo(com.documind.common.error.ErrorCode.IDEMPOTENCY_KEY_REUSED));
        assertThatThrownBy(() -> chatAdmission.admit(other, conversation, key, request)).isInstanceOf(com.documind.common.error.AppException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM chat_generations").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM usage_logs").query(Long.class).single()).isZero();
    }

    @Test void directPhaseEightTurnUsesSameCanonicalHashAsSseAdmission() {
        UUID conversation = conversations.create(owner, null).id(), key = UUID.randomUUID();
        actualChat.process(owner, conversation, key, "Question?", RetrievalService.Scope.ALL_READY, null);
        var request = com.documind.chat.ChatRequest.parse(json.readTree("{\"content\":\"Question?\"}"), json);
        assertThat(chatAdmission.admit(owner, conversation, key, request).replay()).isNotNull();
        var legacy = new java.util.LinkedHashMap<String, Object>();
        legacy.put("documentIds", List.of()); legacy.put("scope_mode", "ALL_READY"); legacy.put("content", "Question?");
        try {
            String legacyHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(legacy)));
            jdbc.sql("UPDATE chat_generations SET request_hash=:hash WHERE conversation_id=:conversation")
                    .param("hash", legacyHash).param("conversation", conversation).update();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        assertThat(chatAdmission.admit(owner, conversation, key, request).replay()).isNotNull();
    }

    @Test void sseCommitsThenReplaysWithoutDuplicatingGeneration() throws Exception {
        UUID conversation = conversations.create(owner, null).id(), key = UUID.randomUUID();
        String path = "/api/v1/conversations/" + conversation + "/messages";
        var first = mvc.perform(post(path).header("Authorization", bearer(owner)).header("Idempotency-Key", key)
                .contentType("application/json").accept("text/event-stream").content("{\"content\":\"What?\"}"))
                .andExpect(request().asyncStarted()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var response = mvc.perform(asyncDispatch(first)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("event:start", "event:sources", "event:done", "\"replayed\":false", "NO_EVIDENCE")
                .doesNotContain("event:error");
        var replay = mvc.perform(post(path).header("Authorization", bearer(owner)).header("Idempotency-Key", key)
                .contentType("application/json").accept("text/event-stream").content("{\"content\":\"What?\"}"))
                .andExpect(status().isOk()).andReturn();
        var replayed = replay.getResponse().getContentAsString();
        assertThat(replayed).contains("event:start", "event:token", "event:sources", "event:done", "\"replayed\":true");
        assertThat(jdbc.sql("SELECT count(*) FROM chat_generations").query(Long.class).single()).isEqualTo(1);
        mvc.perform(post(path).header("Authorization", bearer(owner)).header("Idempotency-Key", key)
                .contentType("application/json").accept("text/event-stream").content("{\"content\":\"Different?\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(post(path).header("Authorization", bearer(other)).header("Idempotency-Key", key)
                .contentType("application/json").accept("text/event-stream").content("{\"content\":\"What?\"}"))
                .andExpect(status().isNotFound());
    }

    @Test void sseStreamsTemporaryAnswerAndBuffersCitationRepair() throws Exception {
        UUID document = upload();
        jdbc.sql("UPDATE documents SET status='READY' WHERE id=:id").param("id", document).update();
        jdbc.sql("""
                INSERT INTO document_chunks(id,document_id,user_id,chunk_index,paragraph_index,start_offset,end_offset,
                  content,embedding,embedding_model,pipeline_version)
                VALUES (:id,:doc,:owner,0,1,0,4,'fact',array_fill(1::real,ARRAY[1536])::vector,:model,:pipeline)
                """).param("id", UUID.randomUUID()).param("doc", document).param("owner", owner)
                .param("model", com.documind.retrieval.GeminiEmbeddingModel.MODEL)
                .param("pipeline", com.documind.retrieval.GeminiEmbeddingModel.PIPELINE_VERSION).update();
        UUID conversation = conversations.create(owner, null).id(), key = UUID.randomUUID();
        testChatProvider.release = new java.util.concurrent.CountDownLatch(1);
        testChatProvider.provisional = "Điều kiện.";
        var result = mvc.perform(post("/api/v1/conversations/" + conversation + "/messages").header("Authorization", bearer(owner))
                .header("Idempotency-Key", key).contentType("application/json").accept("text/event-stream")
                .content("{\"content\":\"What?\",\"documentIds\":[\"" + document + "\"]}"))
                .andExpect(request().asyncStarted()).andReturn();
        try {
            assertThat(testChatProvider.emitted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("event:token", "Điều kiện.").doesNotContain("event:done");
        } finally { testChatProvider.release.countDown(); }
        var response = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(response).contains("event:sources", "event:done", "Repaired [S1].");
        assertThat(response.indexOf("Điều kiện.")).isLessThan(response.indexOf("event:sources"));
        assertThat(response.indexOf("Repaired [S1].")).isGreaterThan(response.indexOf("event:done"));
        documents.requestDeletion(owner, document);
        var replay = mvc.perform(post("/api/v1/conversations/" + conversation + "/messages").header("Authorization", bearer(owner))
                .header("Idempotency-Key", key).contentType("application/json").accept("text/event-stream")
                .content("{\"content\":\"What?\",\"documentIds\":[\"" + document + "\"]}"))
                .andExpect(status().isOk()).andReturn();
        String replayed = replay.getResponse().getContentAsString();
        assertThat(replayed).contains("\"replayed\":true", "\"availability\":\"DELETED\"", "\"snippet\":null")
                .doesNotContain("\"snippet\":\"fact\"");
    }

    @Test void incompleteGenerationCannotReplayAndNewKeyCanRetry() {
        UUID conversation = conversations.create(owner, null).id(), key = UUID.randomUUID();
        var request = com.documind.chat.ChatRequest.parse(json.readTree("{\"content\":\"Question?\"}"), json);
        var accepted = chatAdmission.admit(owner, conversation, key, request);
        assertThat(generationLifecycle.terminate(accepted.started().generation(), 0, "CLIENT_DISCONNECTED", "partial")).isTrue();
        assertThat(generationLifecycle.terminate(accepted.started().generation(), 0, "TIMEOUT", "wrong")).isFalse();
        assertThatThrownBy(() -> chatAdmission.admit(owner, conversation, key, request))
                .isInstanceOf(com.documind.chat.ChatConflict.class)
                .satisfies(error -> assertThat(((com.documind.chat.ChatConflict) error).errorCode())
                        .isEqualTo(com.documind.common.error.ErrorCode.GENERATION_NOT_REPLAYABLE));
        assertThat(chatAdmission.admit(owner, conversation, UUID.randomUUID(), request).started()).isNotNull();
        var message = conversations.messages(owner, conversation, null, 50).items().get(1);
        assertThat(message.status()).isEqualTo("INCOMPLETE");
        assertThat(message.content()).isEqualTo("partial");
        assertThat(message.sources().isEmpty()).isTrue();
    }

    @Test void deletingSourceDuringSseDiscardsPartialAndDoesNotSendDone() throws Exception {
        UUID document = upload();
        jdbc.sql("UPDATE documents SET status='READY' WHERE id=:id").param("id", document).update();
        jdbc.sql("""
                INSERT INTO document_chunks(id,document_id,user_id,chunk_index,paragraph_index,start_offset,end_offset,
                  content,embedding,embedding_model,pipeline_version)
                VALUES (:id,:doc,:owner,0,1,0,4,'fact',array_fill(1::real,ARRAY[1536])::vector,:model,:pipeline)
                """).param("id", UUID.randomUUID()).param("doc", document).param("owner", owner)
                .param("model", com.documind.retrieval.GeminiEmbeddingModel.MODEL)
                .param("pipeline", com.documind.retrieval.GeminiEmbeddingModel.PIPELINE_VERSION).update();
        UUID conversation = conversations.create(owner, null).id();
        testChatProvider.release = new java.util.concurrent.CountDownLatch(1);
        var result = mvc.perform(post("/api/v1/conversations/" + conversation + "/messages").header("Authorization", bearer(owner))
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").accept("text/event-stream")
                .content("{\"content\":\"What?\",\"documentIds\":[\"" + document + "\"]}"))
                .andExpect(request().asyncStarted()).andReturn();
        String response;
        try {
            assertThat(testChatProvider.emitted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            documents.requestDeletion(owner, document);
            response = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        } finally { testChatProvider.release.countDown(); }
        assertThat(response).contains("event:error", "SOURCE_DELETED", "\"discardPartial\":true").doesNotContain("event:done");
        var message = conversations.messages(owner, conversation, null, 50).items().getLast();
        assertThat(message.outcome()).isEqualTo("SOURCE_DELETED");
        assertThat(message.content()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM usage_logs WHERE operation='CHAT_CITATION_REPAIR'").query(Long.class).single()).isZero();
    }

    @Test void closingRealHttpStreamFencesAndPersistsIncompleteGeneration() throws Exception {
        UUID document = upload();
        jdbc.sql("UPDATE documents SET status='READY' WHERE id=:id").param("id", document).update();
        jdbc.sql("""
                INSERT INTO document_chunks(id,document_id,user_id,chunk_index,paragraph_index,start_offset,end_offset,
                  content,embedding,embedding_model,pipeline_version)
                VALUES (:id,:doc,:owner,0,1,0,4,'fact',array_fill(1::real,ARRAY[1536])::vector,:model,:pipeline)
                """).param("id", UUID.randomUUID()).param("doc", document).param("owner", owner)
                .param("model", com.documind.retrieval.GeminiEmbeddingModel.MODEL)
                .param("pipeline", com.documind.retrieval.GeminiEmbeddingModel.PIPELINE_VERSION).update();
        UUID conversation = conversations.create(owner, null).id();
        testChatProvider.release = new java.util.concurrent.CountDownLatch(1);
        testChatProvider.floodAfterRelease = true;
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/api/v1/conversations/" + conversation + "/messages"))
                .header("Authorization", bearer(owner)).header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"What?\",\"documentIds\":[\"" + document + "\"]}"))
                .build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                do { line = reader.readLine(); } while (line != null && !line.equals("event:token"));
                assertThat(line).isEqualTo("event:token");
            } finally { testChatProvider.release.countDown(); }
            String state = null;
            for (int i = 0; i < 80; i++) {
                state = jdbc.sql("SELECT status FROM chat_generations WHERE conversation_id=:c").param("c", conversation).query(String.class).single();
                if (!"GENERATING".equals(state)) break;
                Thread.sleep(100);
            }
            assertThat(state).isEqualTo("INCOMPLETE");
            var message = conversations.messages(owner, conversation, null, 50).items().getLast();
            assertThat(message.outcome()).isEqualTo("CLIENT_DISCONNECTED");
            assertThat(message.sources().isEmpty()).isTrue();
        }
    }

    @Test void deadlineStopsGenerationAndPreservesOnlyTemporaryText() throws Exception {
        UUID document = upload();
        jdbc.sql("UPDATE documents SET status='READY' WHERE id=:id").param("id", document).update();
        jdbc.sql("""
                INSERT INTO document_chunks(id,document_id,user_id,chunk_index,paragraph_index,start_offset,end_offset,
                  content,embedding,embedding_model,pipeline_version)
                VALUES (:id,:doc,:owner,0,1,0,4,'fact',array_fill(1::real,ARRAY[1536])::vector,:model,:pipeline)
                """).param("id", UUID.randomUUID()).param("doc", document).param("owner", owner)
                .param("model", com.documind.retrieval.GeminiEmbeddingModel.MODEL)
                .param("pipeline", com.documind.retrieval.GeminiEmbeddingModel.PIPELINE_VERSION).update();
        UUID conversation = conversations.create(owner, null).id();
        var request = com.documind.chat.ChatRequest.parse(json.readTree("{\"content\":\"What?\",\"documentIds\":[\"" + document + "\"]}"), json);
        var accepted = chatAdmission.admit(owner, conversation, UUID.randomUUID(), request);
        testChatProvider.release = new java.util.concurrent.CountDownLatch(1);
        var service = new com.documind.chat.ChatStreamService(actualChat, generationLifecycle, 50, 1_500, json);
        try {
            service.open(owner, conversation, request, accepted, "timeout-test");
            assertThat(testChatProvider.emitted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String state = null;
            for (int i = 0; i < 60; i++) {
                state = jdbc.sql("SELECT status FROM chat_generations WHERE id=:id").param("id", accepted.started().generation()).query(String.class).single();
                if (!"GENERATING".equals(state)) break;
                Thread.sleep(100);
            }
            assertThat(state).isEqualTo("INCOMPLETE");
            assertThat(jdbc.sql("SELECT heartbeat_at > created_at FROM chat_generations WHERE id=:id")
                    .param("id", accepted.started().generation()).query(Boolean.class).single()).isTrue();
            var message = conversations.messages(owner, conversation, null, 50).items().getLast();
            assertThat(message.outcome()).isEqualTo("TIMEOUT");
            assertThat(message.content()).isEqualTo("Uncited.");
            assertThat(message.sources().isEmpty()).isTrue();
        } finally { testChatProvider.release.countDown(); service.shutdown(); }
    }

    @Test void concurrentAdmissionNeverAcceptsMoreThanTwentyPerUser() throws Exception {
        var request = com.documind.chat.ChatRequest.parse(json.readTree("{\"content\":\"Question?\"}"), json);
        var conversations = new java.util.ArrayList<UUID>();
        for (int i = 0; i < 30; i++) conversations.add(this.conversations.create(owner, null).id());
        var accepted = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var tasks = conversations.stream().<java.util.concurrent.Callable<Void>>map(id -> () -> {
                try { chatAdmission.admit(owner, id, UUID.randomUUID(), request); accepted.incrementAndGet(); }
                catch (com.documind.chat.ChatRateLimiter.Limited ignored) { }
                return null;
            }).toList();
            for (var future : pool.invokeAll(tasks)) future.get();
        }
        assertThat(accepted.get()).isEqualTo(20);
        assertThat(jdbc.sql("SELECT count(*) FROM question_events WHERE user_id=:owner").param("owner", owner).query(Long.class).single()).isEqualTo(20);
    }

    @Test void replayBypassesFullRateLimitWithoutCreatingAnotherQuestion() throws Exception {
        UUID conversation = conversations.create(owner, null).id();
        var request = com.documind.chat.ChatRequest.parse(json.readTree("{\"content\":\"Question?\"}"), json);
        UUID firstKey = UUID.randomUUID();
        for (int i = 0; i < 20; i++) {
            var accepted = chatAdmission.admit(owner, conversation, i == 0 ? firstKey : UUID.randomUUID(), request);
            actualChat.execute(owner, accepted.started(), request.content(), request.scope(), request.documentIds());
        }
        assertThat(chatAdmission.admit(owner, conversation, firstKey, request).replay()).isNotNull();
        assertThatThrownBy(() -> chatAdmission.admit(owner, conversation, UUID.randomUUID(), request))
                .isInstanceOf(com.documind.chat.ChatRateLimiter.Limited.class);
        mvc.perform(post("/api/v1/conversations/" + conversation + "/messages").header("Authorization", bearer(owner))
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").accept("text/event-stream")
                .content("{\"content\":\"Question?\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        assertThat(jdbc.sql("SELECT count(*) FROM question_events WHERE user_id=:owner").param("owner", owner).query(Long.class).single()).isEqualTo(20);
    }

    @Test void realVectorRetrievalEnforcesOwnerAndSelectedScopeInChat() throws Exception {
        UUID first = upload(), second = upload(), foreign = UUID.randomUUID();
        jdbc.sql("UPDATE documents SET status='READY' WHERE id IN (:first,:second)").param("first", first).param("second", second).update();
        jdbc.sql("INSERT INTO documents(id,user_id,filename,storage_key,media_type,size_bytes,status) VALUES (:id,:owner,'foreign.txt',:key,'text/plain',1,'READY')")
                .param("id", foreign).param("owner", other).param("key", UUID.randomUUID().toString()).update();
        for (UUID doc : List.of(first, second, foreign)) {
            jdbc.sql("""
                    INSERT INTO document_chunks(id,document_id,user_id,chunk_index,paragraph_index,start_offset,end_offset,
                      content,embedding,embedding_model,pipeline_version)
                    VALUES (:id,:doc,:owner,0,1,0,4,:content,array_fill(1::real,ARRAY[1536])::vector,:model,:pipeline)
                    """).param("id", UUID.randomUUID()).param("doc", doc).param("owner", doc.equals(foreign) ? other : owner)
                    .param("content", "text-" + doc).param("model", com.documind.retrieval.GeminiEmbeddingModel.MODEL)
                    .param("pipeline", com.documind.retrieval.GeminiEmbeddingModel.PIPELINE_VERSION).update();
        }
        var embedding = org.mockito.Mockito.mock(org.springframework.ai.embedding.EmbeddingModel.class);
        float[] vector = new float[1536]; java.util.Arrays.fill(vector, 1);
        org.mockito.Mockito.when(embedding.embedForResponse(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(List.of(new org.springframework.ai.embedding.Embedding(vector, 0))));
        var embeddings = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        embeddings.addBean("fakeEmbedding", embedding);
        var retrieval = new RetrievalService(jdbc, chunkVectors, embeddings.getBeanProvider(org.springframework.ai.embedding.EmbeddingModel.class), transactionManager, 5, -1);
        var providers = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        providers.addBean("fakeChat", (ChatProvider) (system, input) -> {
            assertThat(input).contains(first.toString()).doesNotContain(foreign.toString());
            if (input.contains("All documents?")) assertThat(input).contains(second.toString());
            else assertThat(input).doesNotContain(second.toString());
            return new ChatProvider.Reply("Fact [S1].", 1L, 1L);
        });
        var chat = new ChatTurnService(jdbc, transactionManager, retrieval, queryResolver, answerEngine, providers.getBeanProvider(ChatProvider.class), json);
        UUID conversation = conversations.create(owner, null).id();
        var result = chat.process(owner, conversation, UUID.randomUUID(), "What?", RetrievalService.Scope.SELECTED, List.of(first));
        assertThat(result.outcome()).isEqualTo("ANSWER");
        assertThat(conversations.messages(owner, conversation, null, 50).items().getLast().sources().get(0)
                .path("documentId").asString()).isEqualTo(first.toString());
        assertThatThrownBy(() -> chat.process(owner, conversation, UUID.randomUUID(), "What?", RetrievalService.Scope.SELECTED, List.of(foreign)))
                .isInstanceOf(com.documind.common.error.AppException.class);
        UUID anotherConversation = conversations.create(owner, null).id();
        assertThat(chat.process(owner, anotherConversation, UUID.randomUUID(), "All documents?", RetrievalService.Scope.ALL_READY, null).outcome())
                .isEqualTo("ANSWER");
    }

    @Test void chatTurnPersistsCitedAnswerAndTracksRepairUsage() throws Exception {
        UUID document = upload();
        jdbc.sql("UPDATE documents SET status='READY' WHERE id=:id").param("id", document).update();
        UUID conversation = conversations.create(owner, null).id();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var chat = chatService(document, (system, input) -> new ChatProvider.Reply(
                calls.getAndIncrement() == 0 ? "No citation." : "Fact [S1].", 10L, 4L));
        var result = chat.process(owner, conversation, UUID.randomUUID(), "Question?", RetrievalService.Scope.SELECTED,
                List.of(document));
        assertThat(result.outcome()).isEqualTo("ANSWER");
        var messages = conversations.messages(owner, conversation, null, 50).items();
        assertThat(messages).extracting(ConversationService.MessageDto::role).containsExactly("USER", "ASSISTANT");
        assertThat(messages.getLast().sources().get(0).path("documentId").asString()).isEqualTo(document.toString());
        assertThat(jdbc.sql("SELECT operation FROM usage_logs ORDER BY created_at,id").query(String.class).list())
                .containsExactly("QUERY_EMBEDDING", "CHAT_ANSWER", "CHAT_CITATION_REPAIR");
        assertThat(jdbc.sql("SELECT count(*) FROM question_events").query(Long.class).single()).isEqualTo(1);
        documents.requestDeletion(owner, document);
        assertThat(conversations.messages(owner, conversation, null, 50).items().getLast().sources().get(0)
                .path("availability").asString()).isEqualTo("DELETED");
    }

    @Test void chatDiscardsAnswerIfSourceDeletedDuringProviderCall() throws Exception {
        UUID document = upload();
        jdbc.sql("UPDATE documents SET status='READY' WHERE id=:id").param("id", document).update();
        UUID conversation = conversations.create(owner, null).id();
        var chat = chatService(document, (system, input) -> {
            documents.requestDeletion(owner, document);
            assertThat(jdbc.sql("SELECT status FROM chat_generations WHERE conversation_id=:id").param("id", conversation)
                    .query(String.class).single()).isEqualTo("INCOMPLETE");
            return new ChatProvider.Reply("Fact [S1].", 5L, 2L);
        });
        var result = chat.process(owner, conversation, UUID.randomUUID(), "Question?", RetrievalService.Scope.SELECTED,
                List.of(document));
        assertThat(result.status()).isEqualTo("INCOMPLETE");
        assertThat(result.outcome()).isEqualTo("SOURCE_DELETED");
        var message = conversations.messages(owner, conversation, null, 50).items().getLast();
        assertThat(message.content()).isEmpty();
        assertThat(message.sources().isEmpty()).isTrue();
    }

    @Test void staleGenerationRecoveryFencesLateWorker() {
        UUID conversation = conversations.create(owner, null).id(), userMessage = UUID.randomUUID(), assistant = UUID.randomUUID(), generation = UUID.randomUUID();
        jdbc.sql("INSERT INTO messages(id,conversation_id,ordinal,role,status) VALUES (:id,:c,0,'USER','COMPLETE'),(:assistant,:c,1,'ASSISTANT','GENERATING')")
                .param("id", userMessage).param("assistant", assistant).param("c", conversation).update();
        jdbc.sql("""
                INSERT INTO chat_generations(id,conversation_id,user_message_id,assistant_message_id,request_key,request_hash,scope_mode,status,heartbeat_at)
                VALUES (:id,:c,:u,:a,:key,'hash','ALL_READY','GENERATING',now()-interval '90 seconds')
                """).param("id", generation).param("c", conversation).param("u", userMessage).param("a", assistant).param("key", UUID.randomUUID()).update();
        generationRecovery.recover();
        assertThat(jdbc.sql("SELECT outcome FROM chat_generations WHERE id=:id").param("id", generation).query(String.class).single()).isEqualTo("TIMEOUT");
        assertThat(jdbc.sql("SELECT generation_version FROM chat_generations WHERE id=:id").param("id", generation).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT outcome FROM messages WHERE id=:id").param("id", assistant).query(String.class).single()).isEqualTo("TIMEOUT");
    }

    @Test void sourceBecomingUnavailableBeforeRetrievalIsNotProviderError() throws Exception {
        UUID document = upload(), conversation = conversations.create(owner, null).id();
        var retrieval = org.mockito.Mockito.mock(RetrievalService.class);
        org.mockito.Mockito.when(retrieval.search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new com.documind.common.error.AppException(com.documind.common.error.ErrorCode.DOCUMENT_NOT_READY));
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        var chat = new ChatTurnService(jdbc, transactionManager, retrieval, queryResolver, answerEngine, beans.getBeanProvider(ChatProvider.class), json);
        var result = chat.process(owner, conversation, UUID.randomUUID(), "Question?", RetrievalService.Scope.SELECTED, List.of(document));
        assertThat(result.status()).isEqualTo("INCOMPLETE");
        assertThat(result.outcome()).isEqualTo("SOURCE_DELETED");
        assertThat(conversations.messages(owner, conversation, null, 50).items().getLast().outcome()).isEqualTo("SOURCE_DELETED");
    }

    @Test void chatAllowsOnlyOneGenerationPerConversation() throws Exception {
        UUID document = upload();
        jdbc.sql("UPDATE documents SET status='READY' WHERE id=:id").param("id", document).update();
        UUID conversation = conversations.create(owner, null).id();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var chat = chatService(document, (system, input) -> {
            entered.countDown();
            try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return new ChatProvider.Reply("Fact [S1].", 1L, 1L);
        });
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> chat.process(owner, conversation, UUID.randomUUID(), "First?",
                    RetrievalService.Scope.SELECTED, List.of(document)));
            try {
                assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> chat.process(owner, conversation, UUID.randomUUID(), "Second?",
                        RetrievalService.Scope.SELECTED, List.of(document)))
                        .isInstanceOf(com.documind.common.error.AppException.class);
                assertThatThrownBy(() -> chat.process(other, conversation, UUID.randomUUID(), "Other?",
                        RetrievalService.Scope.SELECTED, List.of(document)))
                        .isInstanceOf(com.documind.common.error.AppException.class);
            } finally { release.countDown(); }
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).outcome()).isEqualTo("ANSWER");
        }
        assertThat(conversations.messages(owner, conversation, null, 50).items()).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM question_events").query(Long.class).single()).isEqualTo(1);
    }

    @Test void followUpAfterSourceDeletionAsksForClarificationWithoutSendingOldAnswer() throws Exception {
        UUID document = upload();
        jdbc.sql("UPDATE documents SET status='READY' WHERE id=:id").param("id", document).update();
        UUID conversation = conversations.create(owner, null).id();
        var chat = chatService(document, (system, input) -> {
            if (system.startsWith("Rewrite")) {
                assertThat(input).contains("missingPriorContext").doesNotContain("Secret answer");
                return new ChatProvider.Reply("{\"question\":null,\"clarification\":\"Which document do you mean?\"}", 5L, 3L);
            }
            return new ChatProvider.Reply("Secret answer [S1].", 5L, 3L);
        });
        assertThat(chat.process(owner, conversation, UUID.randomUUID(), "First?", RetrievalService.Scope.SELECTED,
                List.of(document)).outcome()).isEqualTo("ANSWER");
        documents.requestDeletion(owner, document);
        var followUp = chat.process(owner, conversation, UUID.randomUUID(), "And the second one?",
                RetrievalService.Scope.ALL_READY, null);
        assertThat(followUp.outcome()).isEqualTo("CLARIFICATION_REQUIRED");
        assertThat(conversations.messages(owner, conversation, null, 50).items()).hasSize(4);
    }

    @Test void deletionAfterAdmissionFencesHistoryBeforeResolverCallsProvider() throws Exception {
        UUID document = upload();
        jdbc.sql("UPDATE documents SET status='READY' WHERE id=:id").param("id", document).update();
        UUID conversation = conversations.create(owner, null).id();
        var first = chatService(document, (system, input) -> new ChatProvider.Reply("Secret [S1].", 1L, 1L));
        assertThat(first.process(owner, conversation, UUID.randomUUID(), "First?", RetrievalService.Scope.SELECTED,
                List.of(document)).outcome()).isEqualTo("ANSWER");
        var request = com.documind.chat.ChatRequest.parse(json.readTree("{\"content\":\"And the second?\"}"), json);
        var accepted = chatAdmission.admit(owner, conversation, UUID.randomUUID(), request);
        documents.requestDeletion(owner, document);
        assertThat(generationLifecycle.state(accepted.started().generation()).outcome()).isEqualTo("SOURCE_DELETED");
        assertThatThrownBy(() -> actualChat.execute(owner, accepted.started(), request.content(), request.scope(), request.documentIds()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(testChatProvider.completeCalls.get()).isZero();
    }

    @Test void deletionBeforeProviderSubmissionPreventsSendingHistory() throws Exception {
        UUID document = upload();
        jdbc.sql("UPDATE documents SET status='READY' WHERE id=:id").param("id", document).update();
        UUID conversation = conversations.create(owner, null).id();
        var first = chatService(document, (system, input) -> new ChatProvider.Reply("Secret [S1].", 1L, 1L));
        first.process(owner, conversation, UUID.randomUUID(), "First?", RetrievalService.Scope.SELECTED, List.of(document));
        var request = com.documind.chat.ChatRequest.parse(json.readTree("{\"content\":\"And the second?\"}"), json);
        var accepted = chatAdmission.admit(owner, conversation, UUID.randomUUID(), request);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var sent = new java.util.concurrent.atomic.AtomicBoolean();
        ChatProvider fake = new ChatProvider() {
            @Override public Reply complete(String system, String input) { throw new AssertionError("unexpected direct call"); }
            @Override public Reply complete(String system, String input, com.documind.common.DispatchFence fence) {
                entered.countDown();
                try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return fence.submit(() -> { sent.set(true); return new Reply("{}", 1L, 1L); });
            }
        };
        var chat = chatService(document, fake);
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var task = pool.submit(() -> chat.execute(owner, accepted.started(), request.content(), request.scope(), request.documentIds()));
            try {
                assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                documents.requestDeletion(owner, document);
                release.countDown();
                assertThatThrownBy(() -> task.get(10, java.util.concurrent.TimeUnit.SECONDS))
                        .isInstanceOf(java.util.concurrent.ExecutionException.class);
                assertThat(sent).isFalse();
                assertThat(jdbc.sql("SELECT count(*) FROM usage_logs WHERE message_id=:message")
                        .param("message", accepted.started().assistant()).query(Long.class).single()).isZero();
            } finally { release.countDown(); }
        }
    }

    @Test void replayDeliveryFinishesBeforeConcurrentSourceDeletionCommits() throws Exception {
        UUID document = upload();
        jdbc.sql("UPDATE documents SET status='READY' WHERE id=:id").param("id", document).update();
        UUID conversation = conversations.create(owner, null).id();
        var first = chatService(document, (system, input) -> new ChatProvider.Reply("Fact [S1].", 1L, 1L));
        var answer = first.process(owner, conversation, UUID.randomUUID(), "Question?", RetrievalService.Scope.SELECTED, List.of(document));
        var reading = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var replay = pool.submit(() -> {
                generationLifecycle.writeReplay(owner, conversation, answer.assistantMessageId(), snapshot -> {
                    assertThat(snapshot.message().sources().get(0).path("availability").asString()).isEqualTo("AVAILABLE");
                    reading.countDown();
                    try { if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("timed out"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.IOException(interrupted); }
                });
                return null;
            });
            try {
                assertThat(reading.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var deletion = pool.submit(() -> { documents.requestDeletion(owner, document); return null; });
                assertThatThrownBy(() -> deletion.get(300, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown(); replay.get(10, java.util.concurrent.TimeUnit.SECONDS); deletion.get(10, java.util.concurrent.TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        assertThat(generationLifecycle.snapshot(owner, conversation, answer.assistantMessageId()).message().sources().get(0)
                .path("availability").asString()).isEqualTo("DELETED");
    }

    @Test void followUpResolvesAgainstHistoryAndRetrievesAgain() throws Exception {
        UUID document = upload();
        jdbc.sql("UPDATE documents SET status='READY' WHERE id=:id").param("id", document).update();
        UUID conversation = conversations.create(owner, null).id();
        var answers = new java.util.concurrent.atomic.AtomicInteger();
        var chat = chatService(document, (system, input) -> {
            if (system.startsWith("Rewrite")) {
                assertThat(input).contains("Contract A", "Fact [S1]");
                return new ChatProvider.Reply("{\"question\":\"How long does Contract A condition two apply?\",\"clarification\":null}", 5L, 3L);
            }
            if (answers.getAndIncrement() > 0) assertThat(input).contains("How long does Contract A condition two apply?");
            return new ChatProvider.Reply("Fact [S1].", 5L, 3L);
        });
        assertThat(chat.process(owner, conversation, UUID.randomUUID(), "Contract A?", RetrievalService.Scope.SELECTED,
                List.of(document)).outcome()).isEqualTo("ANSWER");
        assertThat(chat.process(owner, conversation, UUID.randomUUID(), "How long is the second condition?",
                RetrievalService.Scope.SELECTED, List.of(document)).outcome()).isEqualTo("ANSWER");
        assertThat(jdbc.sql("SELECT count(*) FROM usage_logs WHERE operation='QUERY_EMBEDDING'").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM usage_logs WHERE operation='CHAT_REWRITE'").query(Long.class).single()).isEqualTo(1);
    }

    @Test void changingScopeDoesNotSendOutOfScopeHistory() throws Exception {
        UUID first = upload(), second = upload();
        jdbc.sql("UPDATE documents SET status='READY' WHERE id IN (:first,:second)").param("first", first).param("second", second).update();
        UUID conversation = conversations.create(owner, null).id();
        var chat = chatService(first, (system, input) -> {
            if (system.startsWith("Rewrite")) {
                assertThat(input).contains("missingPriorContext").doesNotContain("Secret answer");
                return new ChatProvider.Reply("{\"question\":null,\"clarification\":\"Which document do you mean?\"}", 5L, 3L);
            }
            return new ChatProvider.Reply("Secret answer [S1].", 5L, 3L);
        });
        chat.process(owner, conversation, UUID.randomUUID(), "First?", RetrievalService.Scope.SELECTED,
                List.of(first));
        var result = chat.process(owner, conversation, UUID.randomUUID(), "And the second condition?",
                RetrievalService.Scope.SELECTED, List.of(second));
        assertThat(result.outcome()).isEqualTo("CLARIFICATION_REQUIRED");
    }

    @Test void noEvidenceAndProviderErrorRemainDistinctAndUsageSurvivesDeletion() throws Exception {
        UUID document = upload();
        jdbc.sql("UPDATE documents SET status='READY' WHERE id=:id").param("id", document).update();
        UUID conversation = conversations.create(owner, null).id();
        var noEvidence = chatService(document, (system, input) -> new ChatProvider.Reply("NO_EVIDENCE", null, null));
        assertThat(noEvidence.process(owner, conversation, UUID.randomUUID(), "Question?", RetrievalService.Scope.SELECTED,
                List.of(document)).outcome()).isEqualTo("NO_EVIDENCE");
        var broken = chatService(document, (system, input) -> { throw new IllegalStateException("simulated failure"); });
        assertThatThrownBy(() -> broken.process(owner, conversation, UUID.randomUUID(), "Another question?",
                RetrievalService.Scope.SELECTED, List.of(document)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(conversations.messages(owner, conversation, null, 50).items().getLast().outcome()).isEqualTo("PROVIDER_ERROR");
        long usage = jdbc.sql("SELECT count(*) FROM usage_logs").query(Long.class).single();
        conversations.delete(owner, conversation);
        assertThat(jdbc.sql("SELECT count(*) FROM usage_logs WHERE message_id IS NULL").query(Long.class).single()).isEqualTo(usage);
        assertThat(jdbc.sql("SELECT count(*) FROM question_events").query(Long.class).single()).isEqualTo(2);
    }

    @Test void providerUsageAlreadyObservedIsRetainedWhenStreamFails() throws Exception {
        UUID document = upload();
        jdbc.sql("UPDATE documents SET status='READY' WHERE id=:id").param("id", document).update();
        UUID conversation = conversations.create(owner, null).id();
        ChatProvider fake = new ChatProvider() {
            @Override public Reply complete(String system, String input) { throw new IllegalStateException("unexpected call"); }
            @Override public Reply stream(String system, String input, java.util.function.Consumer<String> tokens,
                    java.util.function.Consumer<Reply> usage) {
                usage.accept(new Reply("", 12L, 7L));
                throw new IllegalStateException("simulated disconnect");
            }
        };
        var chat = chatService(document, fake);
        assertThatThrownBy(() -> chat.process(owner, conversation, UUID.randomUUID(), "Question?",
                RetrievalService.Scope.SELECTED, List.of(document))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT prompt_tokens FROM usage_logs WHERE operation='CHAT_ANSWER'").query(Long.class).single()).isEqualTo(12);
        assertThat(jdbc.sql("SELECT completion_tokens FROM usage_logs WHERE operation='CHAT_ANSWER'").query(Long.class).single()).isEqualTo(7);
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
