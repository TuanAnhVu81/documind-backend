package com.documind;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.sql.SQLException;
import java.sql.Savepoint;

import com.documind.auth.UserEntity;
import com.documind.auth.UserRepository;
import com.documind.auth.RefreshTokenEntity;
import com.documind.auth.RefreshTokenRepository;
import com.documind.chat.ConversationEntity;
import com.documind.chat.ConversationRepository;
import com.documind.chat.MessageEntity;
import com.documind.chat.ChatGenerationEntity;
import com.documind.document.DocumentRepository;
import com.documind.document.DocumentEntity;
import com.documind.document.DocumentCleanupTaskEntity;
import com.documind.document.DocumentCleanupTaskRepository;
import com.documind.document.DeletionJournalEntry;
import com.documind.document.DeletionJournalRepository;
import com.documind.chat.MessageRepository;
import com.documind.chat.ChatGenerationRepository;
import com.documind.ingestion.JobClaimStore;
import com.documind.ingestion.ProcessingJobEntity;
import com.documind.ingestion.ProcessingJobRepository;
import com.documind.retrieval.ChunkVectorStore;
import com.documind.usage.QuestionCountRepository;
import com.documind.usage.UsageLogEntity;
import com.documind.usage.UsageLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LocalFoundationIT {
    private static PostgreSQLContainer container;
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired Flyway flyway;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DocumentRepository documents;
    @Autowired JobClaimStore jobs;
    @Autowired ChunkVectorStore vectors;
    @Autowired MessageRepository messages;
    @Autowired ChatGenerationRepository generations;
    @Autowired UserRepository users;
    @Autowired ConversationRepository conversations;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired QuestionCountRepository questionCounts;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired ProcessingJobRepository processingJobs;
    @Autowired DocumentCleanupTaskRepository cleanupTasks;
    @Autowired DeletionJournalRepository deletionJournal;
    @Autowired UsageLogRepository usageLogs;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        byte[] signingKey = new byte[32];
        new java.security.SecureRandom().nextBytes(signingKey);
        String encodedKey = java.util.Base64.getEncoder().encodeToString(signingKey);
        registry.add("documind.auth.jwt-secret", () -> encodedKey);
        registry.add("documind.auth.allowed-origins", () -> "http://localhost:5173");
        Properties local = new Properties();
        Path env = Path.of(".env");
        if (Files.isRegularFile(env)) {
            try (Reader reader = Files.newBufferedReader(env, StandardCharsets.UTF_8)) {
                local.load(reader);
            }
        }
        String url = setting(local, "TEST_DB_URL", null);
        if (Boolean.getBoolean("documind.testcontainers")) url = null;
        String username;
        String password;
        String migrationUsername;
        String migrationPassword;
        if (url == null || url.isBlank()) {
            container = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.7-pg15")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("documind_phase1_test")
                    .withUsername("documind_test")
                    .withPassword(UUID.randomUUID().toString());
            container.start();
            url = container.getJdbcUrl();
            username = container.getUsername();
            password = container.getPassword();
            migrationUsername = username;
            migrationPassword = password;
        } else {
            // Never run integration migrations against the development or another project's DB.
            if (!url.matches("jdbc:postgresql://[^/]+/documind_phase1_test(?:\\?.*)?")) {
                throw new IllegalStateException("TEST_DB_URL must target documind_phase1_test");
            }
            username = required(local, "TEST_DB_USERNAME");
            password = required(local, "TEST_DB_PASSWORD");
            migrationUsername = setting(local, "TEST_DB_MIGRATION_USERNAME", username);
            migrationPassword = setting(local, "TEST_DB_MIGRATION_PASSWORD", password);
        }
        String resolvedUrl = url;
        String resolvedUsername = username;
        String resolvedPassword = password;
        String resolvedMigrationUsername = migrationUsername;
        String resolvedMigrationPassword = migrationPassword;
        registry.add("spring.datasource.url", () -> resolvedUrl);
        registry.add("spring.datasource.username", () -> resolvedUsername);
        registry.add("spring.datasource.password", () -> resolvedPassword);
        registry.add("spring.flyway.user", () -> resolvedMigrationUsername);
        registry.add("spring.flyway.password", () -> resolvedMigrationPassword);
    }

    private static String setting(Properties local, String key, String fallback) {
        return System.getenv().getOrDefault(key, local.getProperty(key, fallback));
    }

    private static String required(Properties local, String key) {
        String value = setting(local, key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing test database setting: " + key);
        }
        return value;
    }

    @AfterAll
    static void closeContainer() {
        if (container != null) container.stop();
    }

    @Test
    void contextMigratesPostgresAndSupportsVectorOperationsWithoutAiCredentials() {
        assertThat(jdbc.sql("SELECT extversion FROM pg_extension WHERE extname='vector'")
                .query(String.class).single()).isEqualTo("0.8.7");
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("3");
        assertThat(jdbc.sql("SELECT '[1,2,3]'::vector <-> '[1,2,3]'::vector")
                .query(Double.class).single()).isZero();
        assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
        assertThat(context.getBeansOfType(EmbeddingModel.class)).isEmpty();
    }

    @Test
    void healthReadinessAndSwaggerAreAccessibleWithoutCredentials() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("DocuMind Backend"));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void unimplementedBusinessRoutesAreClosedWithCorrelatedProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/documents").header("X-Request-ID", "phase1-security"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-ID", "phase1-security"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.requestId").value("phase1-security"));
    }

    @Test
    void flywayCanValidateAndMigrateAgain() {
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    @Transactional
    void constraintsRejectInvalidStateWrongOwnerDuplicateAndWrongVectorDimension() {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID(), document = UUID.randomUUID();
        createUser(owner); createUser(other); createDocument(document, owner, "READY");
        assertSqlFails("insert into documents(id,user_id,filename,storage_key,media_type,size_bytes,status) values ('"
                + UUID.randomUUID() + "','" + owner + "','f','" + UUID.randomUUID() + "','text/plain',1,'BAD')", "23514");
        assertSqlFails("insert into document_chunks(id,document_id,user_id,chunk_index,content,embedding,embedding_model,pipeline_version) values ('"
                + UUID.randomUUID() + "','" + document + "','" + other + "',0,'x',array_fill(1::real,ARRAY[1536])::vector,'model','v1')", "23503");
        assertSqlFails("insert into document_chunks(id,document_id,user_id,chunk_index,content,embedding,embedding_model,pipeline_version) values ('"
                + UUID.randomUUID() + "','" + document + "','" + owner + "',0,'x','[1,2,3]'::vector,'model','v1')", "22000");
        UUID conversation = UUID.randomUUID(), userMessage = UUID.randomUUID(), assistant = UUID.randomUUID();
        jdbc.sql("insert into conversations(id,user_id,title) values (:id,:owner,'test')").param("id",conversation).param("owner",owner).update();
        jdbc.sql("insert into messages(id,conversation_id,ordinal,role,status) values (:id,:c,1,'USER','COMPLETE')")
                .param("id",userMessage).param("c",conversation).update();
        jdbc.sql("insert into messages(id,conversation_id,ordinal,role,status) values (:id,:c,2,'ASSISTANT','GENERATING')")
                .param("id",assistant).param("c",conversation).update();
        String insert = "insert into chat_generations(id,conversation_id,user_message_id,assistant_message_id,request_key,request_hash,scope_mode,status) values ('%s','%s','%s','%s','%s','hash','ALL_READY','GENERATING')";
        jdbcTemplate.execute(insert.formatted(UUID.randomUUID(),conversation,userMessage,assistant,UUID.randomUUID()));
        assertSqlFails(insert.formatted(UUID.randomUUID(),conversation,userMessage,assistant,UUID.randomUUID()), "23505");
    }

    @Test
    @Transactional
    void ownedRepositoriesAndVectorSearchExcludeOtherUsersAndNonReadyDocuments() {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID(), ready = UUID.randomUUID(), pending = UUID.randomUUID(), foreign = UUID.randomUUID();
        createUser(owner); createUser(other);
        createDocument(ready, owner, "READY"); createDocument(pending, owner, "PENDING"); createDocument(foreign, other, "READY");
        insertChunk(ready, owner); insertChunk(pending, owner); insertChunk(foreign, other);
        assertThat(documents.findByIdAndUserId(ready, owner)).isPresent();
        assertThat(documents.findByIdAndUserId(foreign, owner)).isEmpty();
        float[] embedding = new float[1536]; java.util.Arrays.fill(embedding, 1);
        assertThat(vectors.search(owner, null, embedding, 10)).extracting(ChunkVectorStore.Hit::documentId).containsExactly(ready);
        assertThat(vectors.search(owner, List.of(foreign), embedding, 10)).isEmpty();
    }

    @Test
    @Transactional
    void claimUsesLeaseAndDoesNotClaimSameJobTwice() {
        UUID owner = UUID.randomUUID(), document = UUID.randomUUID(), job = UUID.randomUUID(), token = UUID.randomUUID();
        createUser(owner); createDocument(document, owner, "PENDING");
        jdbc.sql("insert into processing_jobs(id,document_id,status,next_run_at) values (:id,:doc,'PENDING',now() - interval '1 second')")
            .param("id",job).param("doc",document).update();
        Instant now = Instant.now();
        assertThat(jobs.claimDue("worker",token,now,now.plusSeconds(120))).get()
            .satisfies(claim -> { assertThat(claim.id()).isEqualTo(job); assertThat(claim.claimToken()).isEqualTo(token);
                assertThat(claim.attempts()).isEqualTo(1); assertThat(claim.leaseVersion()).isEqualTo(1); });
        assertThat(jobs.claimDue("worker",UUID.randomUUID(),now,now.plusSeconds(120))).isEmpty();
    }

    @Test
    @Transactional
    void jpaPersistsDocumentAndReadsOnlyForItsOwner() {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        createUser(owner); createUser(other);
        DocumentEntity document = new DocumentEntity(UUID.randomUUID());
        document.setUserId(owner); document.setFilename("fixture.txt"); document.setStorageKey(UUID.randomUUID().toString());
        document.setMediaType("text/plain"); document.setSizeBytes(1); document.setStatus("PENDING");
        documents.save(document);
        assertThat(documents.findByIdAndUserId(document.getId(), owner)).get()
                .satisfies(saved -> assertThat(saved.getCreatedAt()).isNotNull());
        assertThat(documents.findByIdAndUserId(document.getId(), other)).isEmpty();
    }

    @Test
    @Transactional
    void deletingJobRetainsUsageAndDeletionOutbox() {
        UUID owner = UUID.randomUUID(), document = UUID.randomUUID(), job = UUID.randomUUID(), usage = UUID.randomUUID();
        createUser(owner); createDocument(document, owner, "DELETING");
        jdbc.sql("insert into processing_jobs(id,document_id,status) values (:id,:doc,'CANCELLED')")
                .param("id",job).param("doc",document).update();
        jdbc.sql("insert into usage_logs(id,user_id,job_id,operation,provider,model) values (:id,:owner,:job,'EMBEDDING','test','test')")
                .param("id",usage).param("owner",owner).param("job",job).update();
        jdbc.sql("insert into deletion_journal_outbox(id,document_id,user_id,storage_key) values (:id,:doc,:owner,'fixture')")
                .param("id",UUID.randomUUID()).param("doc",document).param("owner",owner).update();
        jdbc.sql("delete from documents where id=:id").param("id",document).update();
        assertThat(jdbc.sql("select count(*) from usage_logs where id=:id and job_id is null").param("id",usage).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from deletion_journal_outbox where document_id=:id").param("id",document).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @Transactional
    void chatRepositoriesEnforceOwnershipAndConversationDeletion() {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID(), conversation = UUID.randomUUID();
        UUID userMessage = UUID.randomUUID(), assistant = UUID.randomUUID(), generation = UUID.randomUUID();
        createUser(owner); createUser(other);
        jdbc.sql("insert into conversations(id,user_id,title) values (:id,:owner,'test')").param("id",conversation).param("owner",owner).update();
        jdbc.sql("insert into messages(id,conversation_id,ordinal,role,status) values (:id,:c,1,'USER','COMPLETE')").param("id",userMessage).param("c",conversation).update();
        jdbc.sql("insert into messages(id,conversation_id,ordinal,role,status) values (:id,:c,2,'ASSISTANT','GENERATING')").param("id",assistant).param("c",conversation).update();
        jdbc.sql("insert into chat_generations(id,conversation_id,user_message_id,assistant_message_id,request_key,request_hash,scope_mode,status) values (:id,:c,:u,:a,:key,'hash','ALL_READY','GENERATING')")
            .param("id",generation).param("c",conversation).param("u",userMessage).param("a",assistant).param("key",UUID.randomUUID()).update();
        assertThat(messages.findOwned(userMessage, owner)).isPresent();
        assertThat(messages.findOwned(userMessage, other)).isEmpty();
        assertThat(generations.findOwned(generation, owner)).isPresent();
        assertThat(generations.findOwned(generation, other)).isEmpty();
        jdbc.sql("update conversations set deleted_requested_at=now() where id=:id").param("id",conversation).update();
        assertThat(messages.findOwned(userMessage, owner)).isEmpty();
        assertThat(generations.findOwned(generation, owner)).isEmpty();
    }

    @Test
    @Transactional
    void jpaDefaultsAndQuestionCountSurviveConversationDeletion() {
        UUID owner = UUID.randomUUID(), conversation = UUID.randomUUID();
        UserEntity user = new UserEntity(owner);
        user.setEmail(owner + "@example.invalid"); user.setPasswordHash("test-hash");
        users.save(user);
        ConversationEntity chat = new ConversationEntity(conversation);
        chat.setUserId(owner); chat.setTitle("fixture");
        conversations.save(chat);
        entityManager.flush();

        MessageEntity question = new MessageEntity(UUID.randomUUID());
        question.setConversationId(conversation); question.setOrdinal(1); question.setRole("USER"); question.setStatus("COMPLETE");
        messages.save(question);
        MessageEntity answer = new MessageEntity(UUID.randomUUID());
        answer.setConversationId(conversation); answer.setOrdinal(2); answer.setRole("ASSISTANT"); answer.setStatus("GENERATING");
        messages.save(answer);
        entityManager.flush();
        ChatGenerationEntity generation = new ChatGenerationEntity(UUID.randomUUID());
        generation.setConversationId(conversation); generation.setUserMessageId(question.getId());
        generation.setAssistantMessageId(answer.getId()); generation.setRequestKey(UUID.randomUUID());
        generation.setRequestHash("fixture-hash"); generation.setScopeMode("ALL_READY"); generation.setStatus("GENERATING");
        generations.save(generation);
        entityManager.flush(); entityManager.clear();
        assertThat(messages.findOwned(question.getId(), owner)).get().satisfies(saved -> {
            assertThat(saved.getCreatedAt()).isNotNull(); assertThat(saved.getSources()).isEqualTo("[]");
        });
        assertThat(generations.findOwned(generation.getId(), owner)).get().satisfies(saved -> {
            assertThat(saved.getCreatedAt()).isNotNull(); assertThat(saved.getContextDocumentIds()).isEmpty();
        });
        assertThat(jdbc.sql("select count(*) from question_events where user_id=:owner").param("owner",owner).query(Long.class).single()).isEqualTo(1);
        jdbc.sql("update chat_generations set status='COMPLETE' where id=:id").param("id", generation.getId()).update();
        String duplicate = "insert into chat_generations(id,conversation_id,user_message_id,assistant_message_id,request_key,request_hash,scope_mode,status) values ('%s','%s','%s','%s','%s','fixture-hash','ALL_READY','COMPLETE')";
        assertSqlFails(duplicate.formatted(UUID.randomUUID(), conversation, question.getId(), answer.getId(), generation.getRequestKey()), "23505");
        assertThat(jdbc.sql("select count(*) from question_events where user_id=:owner").param("owner",owner).query(Long.class).single()).isEqualTo(1);
        jdbc.sql("delete from conversations where id=:id").param("id",conversation).update();
        assertThat(questionCounts.countByUserId(owner)).isEqualTo(1);
    }

    @Test
    @Transactional
    void documentDetailIncludesDeletingOnlyForOwner() {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID(), document = UUID.randomUUID();
        createUser(owner); createUser(other); createDocument(document, owner, "DELETING");
        assertThat(documents.findByIdAndUserId(document, owner)).get().satisfies(d -> assertThat(d.getStatus()).isEqualTo("DELETING"));
        assertThat(documents.findByIdAndUserId(document, other)).isEmpty();
    }

    @Test
    @Transactional
    void jpaDefaultsApplyToRefreshJobCleanupJournalAndUsage() {
        UUID owner = UUID.randomUUID(), document = UUID.randomUUID();
        createUser(owner); createDocument(document, owner, "PENDING");
        RefreshTokenEntity token = new RefreshTokenEntity(UUID.randomUUID());
        token.setUserId(owner); token.setFamilyId(UUID.randomUUID()); token.setTokenHash(UUID.randomUUID().toString());
        token.setExpiresAt(Instant.now().plusSeconds(300)); refreshTokens.save(token);
        ProcessingJobEntity job = new ProcessingJobEntity(UUID.randomUUID());
        job.setDocumentId(document); job.setStatus("PENDING"); processingJobs.save(job);
        DocumentCleanupTaskEntity task = new DocumentCleanupTaskEntity(UUID.randomUUID());
        task.setDocumentId(document); cleanupTasks.save(task);
        DeletionJournalEntry entry = new DeletionJournalEntry(UUID.randomUUID(), document, owner, "fixture", null);
        deletionJournal.save(entry);
        UsageLogEntity usage = new UsageLogEntity(UUID.randomUUID());
        usage.setUserId(owner); usage.setOperation("EMBEDDING"); usage.setProvider("test"); usage.setModel("test");
        usageLogs.save(usage);
        entityManager.flush(); entityManager.clear();
        assertThat(refreshTokens.findByUserIdAndTokenHash(owner, token.getTokenHash())).get()
                .satisfies(saved -> assertThat(saved.getCreatedAt()).isNotNull());
        assertThat(processingJobs.findByDocumentId(document)).get().satisfies(saved -> {
            assertThat(saved.getCreatedAt()).isNotNull(); assertThat(saved.getNextRunAt()).isNotNull();
        });
        assertThat(cleanupTasks.findByDocumentId(document)).get().satisfies(saved -> {
            assertThat(saved.getCreatedAt()).isNotNull(); assertThat(saved.getNextRunAt()).isNotNull();
        });
        assertThat(usageLogs.findByUserId(owner)).singleElement().satisfies(saved -> assertThat(saved.getCreatedAt()).isNotNull());
        assertThat(jdbc.sql("select count(*) from deletion_journal_outbox where id=:id and requested_at is not null")
                .param("id", entry.getId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @Transactional
    void chunksEnforceLocationAndReturnCitationMetadata() {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID(), document = UUID.randomUUID();
        createUser(owner); createUser(other); createDocument(document, owner, "READY");
        float[] embedding = new float[1536]; Arrays.fill(embedding, 1);
        var first = new ChunkVectorStore.Chunk(UUID.randomUUID(), 0, null, 1, 0, 4, "test", embedding, "model", "v1");
        assertThat(vectors.replace(other, document, List.of(first))).isFalse();
        assertThat(vectors.replace(owner, document, List.of(first))).isTrue();
        assertThat(vectors.search(owner, List.of(document), embedding, 5)).singleElement().satisfies(hit -> {
            assertThat(hit.paragraphIndex()).isEqualTo(1); assertThat(hit.pageNumber()).isNull();
            assertThat(hit.startOffset()).isZero(); assertThat(hit.endOffset()).isEqualTo(4);
        });
        assertThatThrownBy(() -> vectors.replace(owner, document, List.of(new ChunkVectorStore.Chunk(
                UUID.randomUUID(), 0, null, 1, 0, 4, "bad", new float[3], "model", "v1"))))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(vectors.search(owner, null, embedding, 5)).hasSize(1);
        var replacement = new ChunkVectorStore.Chunk(UUID.randomUUID(), 0, 2, null, null, null, "page", embedding, "model", "v1");
        assertThat(vectors.replace(owner, document, List.of(replacement))).isTrue();
        assertThat(vectors.search(owner, null, embedding, 5)).singleElement().satisfies(hit -> {
            assertThat(hit.id()).isEqualTo(replacement.id()); assertThat(hit.pageNumber()).isEqualTo(2);
        });
        String sql = "insert into document_chunks(id,document_id,user_id,chunk_index,paragraph_index,start_offset,end_offset,content,embedding,embedding_model,pipeline_version) values ('%s','%s','%s',1,%s,%s,%s,'x',array_fill(1::real,ARRAY[1536])::vector,'m','v')";
        assertSqlFails(sql.formatted(UUID.randomUUID(),document,owner,"0","0","1"), "23514");
        assertSqlFails(sql.formatted(UUID.randomUUID(),document,owner,"1","NULL","1"), "23514");
        assertSqlFails(sql.formatted(UUID.randomUUID(),document,owner,"1","1","NULL"), "23514");
        assertSqlFails(sql.formatted(UUID.randomUUID(),document,owner,"1","2","1"), "23514");
    }

    @Test
    void concurrentClaimSkipsJobLockedInAnotherTransaction() throws Exception {
        UUID owner = UUID.randomUUID(), document = UUID.randomUUID(), job = UUID.randomUUID();
        CountDownLatch claimed = new CountDownLatch(1), release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        try {
            createUser(owner); createDocument(document, owner, "PENDING");
            jdbc.sql("insert into processing_jobs(id,document_id,status,next_run_at) values (:id,:doc,'PENDING','1970-01-01T00:00:00Z')")
                    .param("id",job).param("doc",document).update();
            Instant now = Instant.now();
            var first = executor.submit(() -> transactions.execute(status -> {
                var result = jobs.claimDue("worker-1", UUID.randomUUID(), now, now.plusSeconds(120));
                claimed.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for test"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return result;
            }));
            assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> transactions.execute(status ->
                    jobs.claimDue("worker-2", UUID.randomUUID(), now, now.plusSeconds(120))));
            assertThat(second.get(10, TimeUnit.SECONDS)).isEmpty();
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).get().satisfies(result -> assertThat(result.id()).isEqualTo(job));
        } finally {
            release.countDown(); executor.shutdown(); executor.awaitTermination(15, TimeUnit.SECONDS);
            jdbc.sql("delete from documents where id=:id").param("id",document).update();
            jdbc.sql("delete from users where id=:id").param("id",owner).update();
        }
    }

    private void createUser(UUID id) {
        jdbc.sql("insert into users(id,email,password_hash) values (:id,:email,'test-hash')")
            .param("id",id).param("email",id + "@example.invalid").update();
    }
    private void createDocument(UUID id, UUID owner, String status) {
        jdbc.sql("insert into documents(id,user_id,filename,storage_key,media_type,size_bytes,status) values (:id,:owner,'fixture',:key,'text/plain',1,:status)")
            .param("id",id).param("owner",owner).param("key",id.toString()).param("status",status).update();
    }
    private void insertChunk(UUID id, UUID owner) {
        jdbc.sql("insert into document_chunks(id,document_id,user_id,chunk_index,content,embedding,embedding_model,pipeline_version) values (:id,:doc,:owner,0,'fixture',array_fill(1::real,ARRAY[1536])::vector,'test','v1')")
            .param("id",UUID.randomUUID()).param("doc",id).param("owner",owner).update();
    }
    private void assertSqlFails(String sql, String state) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            Savepoint savepoint = connection.setSavepoint();
            try (var statement = connection.createStatement()) {
                try { statement.execute(sql); throw new AssertionError("Expected SQL failure"); }
                catch (SQLException ex) { assertThat(ex.getSQLState()).isEqualTo(state); }
                finally { connection.rollback(savepoint); }
            }
            return null;
        });
    }
}
