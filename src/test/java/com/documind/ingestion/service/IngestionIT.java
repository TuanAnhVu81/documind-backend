package com.documind.ingestion.service;

import com.documind.document.service.DocumentService;
import com.documind.DocumindBackendApplication;
import com.documind.retrieval.repository.ChunkVectorStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

@SpringBootTest
@ActiveProfiles("test")
class IngestionIT {
    private static PostgreSQLContainer db;
    @Autowired JdbcClient jdbc;
    @Autowired IngestionQueue queue;
    @Autowired JobClaimStore claimStore;
    @Autowired DocumentService documents;
    @Autowired PlatformTransactionManager manager;
    private UUID owner, document, job;

    @TestConfiguration(proxyBeanMethods = false)
    static class StuckProcessorConfiguration {
        static volatile CountDownLatch started, release;
        @Bean IngestionProcessor stuckProcessor() {
            return claim -> {
                started.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
                throw new InterruptedException("test processor released");
            };
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.7-pg15").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("ingestion_test").withUsername("ingestion_test").withPassword(UUID.randomUUID().toString());
        db.start();
        registry.add("spring.datasource.url", db::getJdbcUrl); registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword); registry.add("spring.flyway.user", db::getUsername);
        registry.add("spring.flyway.password", db::getPassword);
        registry.add("documind.auth.jwt-secret", () -> Base64.getEncoder().encodeToString("test-secret-ingestion-32-bytes-long".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        registry.add("documind.auth.allowed-origins", () -> "https://documind.test");
    }
    @BeforeEach void setup() {
        owner = UUID.randomUUID(); document = UUID.randomUUID(); job = UUID.randomUUID();
        jdbc.sql("INSERT INTO users(id,email,password_hash) VALUES (:id,:email,'fixture')")
                .param("id", owner).param("email", owner + "@example.com").update();
        jdbc.sql("""
                INSERT INTO documents(id,user_id,filename,storage_key,media_type,size_bytes,status)
                VALUES (:id,:owner,'a.txt',:key,'text/plain',1,'PENDING')
                """).param("id", document).param("owner", owner).param("key", UUID.randomUUID().toString()).update();
        jdbc.sql("INSERT INTO processing_jobs(id,document_id,status,next_run_at) VALUES (:id,:doc,'PENDING',now()-interval '1 second')")
                .param("id", job).param("doc", document).update();
    }
    @AfterEach void cleanup() { jdbc.sql("TRUNCATE users CASCADE").update(); }
    @AfterAll static void stopDb() { if (db != null) db.stop(); }
    private JobClaimStore.Claim claim() { return queue.claim("worker", Duration.ofSeconds(120)).orElseThrow(); }
    private String jobStatus() { return jdbc.sql("SELECT status FROM processing_jobs WHERE id=:id").param("id",job).query(String.class).single(); }
    private String documentStatus() { return jdbc.sql("SELECT status FROM documents WHERE id=:id").param("id",document).query(String.class).single(); }
    private void expire() { jdbc.sql("UPDATE processing_jobs SET lease_until=now()-interval '1 second' WHERE id=:id").param("id",job).update(); }
    private void makeDue() { jdbc.sql("UPDATE processing_jobs SET next_run_at=now()-interval '1 second' WHERE id=:id").param("id",job).update(); }
    private IngestionProcessor.Result result() {
        float[] embedding = new float[1536]; embedding[0] = 1;
        return new IngestionProcessor.Result(List.of(new ChunkVectorStore.Chunk(UUID.randomUUID(), 0, null, 1,
                0, 4, "text", embedding, "fixture", "test")), null, 1);
    }

    @Test void twoWorkersCannotClaimOrCommitSameJob() throws Exception {
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { start.await(); return queue.claim("a", Duration.ofSeconds(120)); });
            var b = pool.submit(() -> { start.await(); return queue.claim("b", Duration.ofSeconds(120)); });
            start.countDown();
            var one = a.get(10, TimeUnit.SECONDS); var two = b.get(10, TimeUnit.SECONDS);
            assertThat(List.of(one, two).stream().filter(java.util.Optional::isPresent).count()).isEqualTo(1);
            var winner = one.isPresent() ? one.orElseThrow() : two.orElseThrow();
            assertThat(documentStatus()).isEqualTo("PROCESSING");
            assertThat(queue.complete(winner, result())).isTrue();
            assertThat(queue.complete(winner, result())).isFalse();
            assertThat(jobStatus()).isEqualTo("DONE"); assertThat(documentStatus()).isEqualTo("READY");
            assertThat(jdbc.sql("SELECT count(*) FROM document_chunks WHERE document_id=:id").param("id",document).query(Long.class).single()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void expiredLeaseRecoversWithBackoffAndExhaustsThreeClaims() {
        JobClaimStore.Claim stale = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            var current = claim();
            assertThat(current.attempts()).isEqualTo(attempt);
            if (stale != null) { assertThat(queue.heartbeat(stale, Duration.ofSeconds(120))).isFalse(); assertThat(queue.complete(stale, result())).isFalse(); }
            expire(); assertThat(queue.heartbeat(current, Duration.ofSeconds(120))).isFalse();
            queue.recoverDue();
            assertThat(jobStatus()).isEqualTo(attempt == 3 ? "FAILED" : "PENDING");
            assertThat(documentStatus()).isEqualTo(attempt == 3 ? "FAILED" : "PENDING");
            if (attempt < 3) {
                long seconds = jdbc.sql("SELECT extract(epoch from (next_run_at-clock_timestamp()))::bigint FROM processing_jobs WHERE id=:id")
                        .param("id",job).query(Long.class).single();
                assertThat(seconds).isBetween(attempt == 1 ? 9L : 29L, attempt == 1 ? 12L : 36L);
                makeDue();
            }
            stale = current;
        }
        assertThat(queue.claim("fourth", Duration.ofSeconds(120))).isEmpty();
    }

    @Test void retryAfterAndPermanentFailureAreAppliedWithoutLeakingException() {
        var first = claim(); assertThat(queue.heartbeat(first, Duration.ofSeconds(120))).isTrue();
        assertThat(queue.fail(first, true, Duration.ofSeconds(90))).isTrue();
        long seconds = jdbc.sql("SELECT extract(epoch from (next_run_at-clock_timestamp()))::bigint FROM processing_jobs WHERE id=:id")
                .param("id", job).query(Long.class).single();
        assertThat(seconds).isGreaterThanOrEqualTo(89);
        makeDue(); var second = claim();
        assertThat(queue.fail(second, false, null)).isTrue();
        assertThat(jobStatus()).isEqualTo("FAILED"); assertThat(documentStatus()).isEqualTo("FAILED");
    }

    @Test void heartbeatCannotRenewLeaseThatExpiresWhileWaitingForLock() throws Exception {
        var current = claim();
        jdbc.sql("UPDATE processing_jobs SET lease_until=clock_timestamp()+interval '2 seconds' WHERE id=:id")
                .param("id", job).update();
        var acquired = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                jdbc.sql("SELECT id FROM processing_jobs WHERE id=:id FOR UPDATE").param("id",job).query(UUID.class).single();
                acquired.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return true;
            }));
            assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
            var beat = pool.submit(() -> queue.heartbeat(current, Duration.ofSeconds(120)));
            await(() -> jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%SELECT id FROM processing_jobs WHERE id=%FOR UPDATE%'")
                    .query(Long.class).single() > 0);
            await(() -> jdbc.sql("SELECT lease_until<=clock_timestamp() FROM processing_jobs WHERE id=:id")
                    .param("id",job).query(Boolean.class).single());
            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(beat.get(10, TimeUnit.SECONDS)).isFalse();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void workerRetriesProviderTimeoutAndHonorsRetryAfter() throws Exception {
        IngestionProcessor processor = claim -> { throw new IngestionProcessor.ProcessingFailure(true, Duration.ofSeconds(90)); };
        var worker = new IngestionWorker(queue, processor, 2, 120, 20, 600);
        try {
            worker.poll();
            await(() -> jobStatus().equals("PENDING") && jdbc.sql("SELECT attempts FROM processing_jobs WHERE id=:id")
                    .param("id",job).query(Integer.class).single() == 1);
            long remaining = jdbc.sql("SELECT extract(epoch from (next_run_at-clock_timestamp()))::bigint FROM processing_jobs WHERE id=:id")
                    .param("id",job).query(Long.class).single();
            assertThat(remaining).isGreaterThanOrEqualTo(89);
            assertThat(documentStatus()).isEqualTo("PENDING");
        } finally { worker.stop(); }
    }

    @Test void recoveryHandlesCrashBetweenClaimAndDocumentTransition() {
        var claim = new TransactionTemplate(manager).execute(status -> {
            Instant now = Instant.now();
            return claimStore.claimDue("crashed", UUID.randomUUID(), now, now.plusSeconds(120)).orElseThrow();
        });
        assertThat(documentStatus()).isEqualTo("PENDING"); expire(); queue.recoverDue();
        assertThat(jobStatus()).isEqualTo("PENDING"); assertThat(documentStatus()).isEqualTo("PENDING");
        assertThat(queue.complete(claim, result())).isFalse();
    }

    @Test void recoverySkipsMoreThanOnePageOfLockedDocuments() throws Exception {
        var lockedDocuments = new ArrayList<UUID>(); lockedDocuments.add(document);
        for (int i = 0; i < 100; i++) {
            UUID extraDocument = UUID.randomUUID(), extraJob = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO documents(id,user_id,filename,storage_key,media_type,size_bytes,status)
                    VALUES (:id,:owner,'a.txt',:key,'text/plain',1,'PENDING')
                    """).param("id", extraDocument).param("owner", owner).param("key", UUID.randomUUID().toString()).update();
            jdbc.sql("""
                    INSERT INTO processing_jobs(id,document_id,status,attempts,lease_until,claim_token)
                    VALUES (:id,:doc,'PROCESSING',1,now()+interval '1 hour',:token)
                    """).param("id", extraJob).param("doc", extraDocument).param("token", UUID.randomUUID()).update();
            if (i < 99) lockedDocuments.add(extraDocument);
        }
        jdbc.sql("UPDATE processing_jobs SET status='PROCESSING',attempts=1,lease_until=now()+interval '1 hour',claim_token=:token WHERE id=:id")
                .param("id", job).param("token", UUID.randomUUID()).update();
        var acquired = new CountDownLatch(1); var release = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                jdbc.sql("SELECT id FROM documents WHERE id IN (:ids) FOR UPDATE").param("ids", lockedDocuments).query(UUID.class).list();
                acquired.countDown();
                try { if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return true;
            }));
            assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();
            jdbc.sql("UPDATE processing_jobs SET lease_until=now()-interval '2 minutes' WHERE document_id IN (:ids)")
                    .param("ids", lockedDocuments).update();
            jdbc.sql("UPDATE processing_jobs SET lease_until=now()-interval '1 minute' WHERE document_id NOT IN (:ids)")
                    .param("ids", lockedDocuments).update();
            var recovery = pool.submit(() -> { queue.recoverDue(); return true; });
            assertThat(recovery.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.sql("SELECT count(*) FROM processing_jobs WHERE status='PENDING'").query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT count(*) FROM processing_jobs WHERE status='PROCESSING'").query(Long.class).single()).isEqualTo(100);
            release.countDown(); assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void recoveryContinuesWhenOneJobTransactionFails() {
        UUID otherDocument = UUID.randomUUID(), otherJob = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO documents(id,user_id,filename,storage_key,media_type,size_bytes,status)
                VALUES (:id,:owner,'b.txt',:key,'text/plain',1,'PENDING')
                """).param("id", otherDocument).param("owner", owner).param("key", UUID.randomUUID().toString()).update();
        jdbc.sql("""
                INSERT INTO processing_jobs(id,document_id,status,attempts,lease_until,claim_token)
                VALUES (:id,:doc,'PROCESSING',1,now()-interval '1 minute',:token)
                """).param("id", otherJob).param("doc", otherDocument).param("token", UUID.randomUUID()).update();
        jdbc.sql("UPDATE processing_jobs SET status='PROCESSING',attempts=1,lease_until=now()-interval '2 minutes',claim_token=:token WHERE id=:id")
                .param("id", job).param("token", UUID.randomUUID()).update();
        jdbc.sql("ALTER TABLE processing_jobs ADD CONSTRAINT test_recovery_failure CHECK (id <> '" + job
                + "'::uuid OR status <> 'PENDING') NOT VALID").update();
        try {
            queue.recoverDue();
            assertThat(jobStatus()).isEqualTo("PROCESSING");
            assertThat(jdbc.sql("SELECT status FROM processing_jobs WHERE id=:id").param("id", otherJob).query(String.class).single()).isEqualTo("PENDING");
        } finally { jdbc.sql("ALTER TABLE processing_jobs DROP CONSTRAINT test_recovery_failure").update(); }
    }

    @Test void claimedJobSurvivesApplicationRestartAndIsRecovered() {
        String secret = Base64.getEncoder().encodeToString("test-secret-ingestion-32-bytes-long".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String[] args = {"--server.port=0", "--spring.profiles.active=test", "--spring.datasource.url=" + db.getJdbcUrl(),
                "--spring.datasource.username=" + db.getUsername(), "--spring.datasource.password=" + db.getPassword(),
                "--spring.flyway.user=" + db.getUsername(), "--spring.flyway.password=" + db.getPassword(),
                "--documind.auth.jwt-secret=" + secret, "--documind.auth.allowed-origins=https://documind.test",
                "--documind.ingestion.worker-enabled=true",
                "--logging.level.root=ERROR"};
        JobClaimStore.Claim old;
        try (var first = new SpringApplicationBuilder(DocumindBackendApplication.class).run(args)) {
            first.getBean(IngestionWorker.class).poll();
            assertThat(jobStatus()).isEqualTo("PENDING"); // enabled without a processor never claims jobs
            old = first.getBean(IngestionQueue.class).claim("stopped-worker", Duration.ofSeconds(120)).orElseThrow();
        }
        assertThat(jobStatus()).isEqualTo("PROCESSING");
        expire();
        for (int i = 0; i < args.length; i++)
            if (args[i].equals("--documind.ingestion.worker-enabled=true")) args[i] = "--documind.ingestion.worker-enabled=false";
        args = java.util.Arrays.copyOf(args, args.length + 1);
        args[args.length - 1] = "--documind.ingestion.recovery-ms=100";
        try (var restarted = new SpringApplicationBuilder(DocumindBackendApplication.class).run(args)) {
            assertThat(restarted.getBeansOfType(IngestionWorker.class)).isEmpty();
            try { await(() -> jobStatus().equals("PENDING")); }
            catch (Exception failure) { throw new AssertionError(failure); }
            assertThat(jobStatus()).isEqualTo("PENDING");
            assertThat(restarted.getBean(IngestionQueue.class).complete(old, result())).isFalse();
        }
    }

    @Test void deletingDuringProcessingPreventsStaleWorkerCommit() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var completed = new CountDownLatch(1); var observed = spy(queue);
        doAnswer(invocation -> { try { return invocation.callRealMethod(); } finally { completed.countDown(); } })
                .when(observed).complete(any(), any(), any());
        IngestionProcessor processor = claim -> { started.countDown(); release.await(10, TimeUnit.SECONDS); return result(); };
        var worker = new IngestionWorker(observed, processor, 2, 120, 20, 600);
        try {
            worker.poll(); assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            documents.requestDeletion(owner, document); release.countDown();
            assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();
            expire(); queue.recoverDue();
            assertThat(documentStatus()).isEqualTo("DELETING"); assertThat(jobStatus()).isEqualTo("CANCELLED");
            assertThat(jdbc.sql("SELECT count(*) FROM document_chunks WHERE document_id=:id").param("id",document).query(Long.class).single()).isZero();
        } finally { release.countDown(); worker.stop(); }
    }

    @Test void deadlineInterruptsHungProcessorAndRequeues() throws Exception {
        var started = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        IngestionProcessor processor = claim -> {
            started.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException e) { interrupted.countDown(); throw e; }
            return result();
        };
        var worker = new IngestionWorker(queue, processor, 2, 4, 1, 1);
        try {
            worker.poll(); assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(10, TimeUnit.SECONDS)).isTrue();
            await(() -> jobStatus().equals("PENDING"));
            assertThat(documentStatus()).isEqualTo("PENDING");
        } finally { worker.stop(); }
    }

    @Test void processorIgnoringCancellationStillCannotCommitAfterTimeout() throws Exception {
        var started = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var release = new CountDownLatch(1);
        IngestionProcessor processor = claim -> {
            started.countDown();
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { interrupted.countDown(); }
            }
            return result();
        };
        var worker = new IngestionWorker(queue, processor, 2, 4, 1, 1);
        try {
            worker.poll(); assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(10, TimeUnit.SECONDS)).isTrue();
            await(() -> jobStatus().equals("PENDING"));
            release.countDown(); worker.stop();
            assertThat(documentStatus()).isEqualTo("PENDING");
            assertThat(jdbc.sql("SELECT count(*) FROM document_chunks WHERE document_id=:id").param("id",document).query(Long.class).single()).isZero();
        } finally { release.countDown(); worker.stop(); }
    }

    @Test void heartbeatFailureFencesProcessorEvenWhenInterruptIsSwallowed() throws Exception {
        var started = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var release = new CountDownLatch(1);
        var observed = spy(queue);
        doThrow(new IllegalStateException("synthetic heartbeat failure")).when(observed).heartbeat(any(), any());
        IngestionProcessor processor = claim -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException ignored) { interrupted.countDown(); }
            return result();
        };
        var worker = new IngestionWorker(observed, processor, 2, 30, 1, 20);
        try {
            worker.poll(); assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            worker.stop();
            assertThat(documentStatus()).isNotEqualTo("READY");
            assertThat(jdbc.sql("SELECT count(*) FROM document_chunks WHERE document_id=:id").param("id", document).query(Long.class).single()).isZero();
        } finally { release.countDown(); worker.stop(); }
    }

    @Test void deadlineInterruptsBeforeBlockedDatabaseTransitionFinishes() throws Exception {
        var started = new CountDownLatch(1); var interrupted = new CountDownLatch(1);
        var dbEntered = new CountDownLatch(1); var releaseDb = new CountDownLatch(1);
        var observed = spy(queue);
        doAnswer(invocation -> {
            dbEntered.countDown(); releaseDb.await(10, TimeUnit.SECONDS); return invocation.callRealMethod();
        }).when(observed).fail(any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        IngestionProcessor processor = claim -> {
            started.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException e) { interrupted.countDown(); throw e; }
            return result();
        };
        var worker = new IngestionWorker(observed, processor, 2, 30, 10, 1);
        try {
            worker.poll(); assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(dbEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally { releaseDb.countDown(); worker.stop(); }
    }

    @Test void blockedFailureTransitionHasBoundedDatabaseWait() throws Exception {
        var current = claim();
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                jdbc.sql("SELECT id FROM documents WHERE id=:id FOR UPDATE").param("id", document).query(UUID.class).single();
                locked.countDown();
                try { if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return true;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var transition = pool.submit(() -> queue.fail(current, true, null));
            assertThatThrownBy(() -> transition.get(9, TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);
            release.countDown(); assertThat(holder.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jobStatus()).isEqualTo("PROCESSING");
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void unresponsiveProcessorTriggersFailStopInsteadOfSilentSlotExhaustion() throws Exception {
        var started = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var release = new CountDownLatch(1);
        var failStopped = new CountDownLatch(1); var attempts = new AtomicInteger();
        IngestionProcessor processor = claim -> {
            attempts.incrementAndGet(); started.countDown();
            while (release.getCount() > 0) {
                try { release.await(); } catch (InterruptedException ignored) { interrupted.countDown(); }
            }
            return result();
        };
        var worker = new IngestionWorker(queue, processor, 1, 30, 10, 1, 1, failStopped::countDown);
        try {
            worker.poll(); assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(failStopped.await(5, TimeUnit.SECONDS)).isTrue();
            worker.poll(); assertThat(attempts.get()).isEqualTo(1);
            assertThat(documentStatus()).isNotEqualTo("READY");
        } finally { release.countDown(); worker.stop(); }
    }

    @Test void unresponsiveProcessorClosesActualContextForSupervisor() throws Exception {
        StuckProcessorConfiguration.started = new CountDownLatch(1);
        StuckProcessorConfiguration.release = new CountDownLatch(1);
        var refusing = new CountDownLatch(1);
        String secret = Base64.getEncoder().encodeToString("test-secret-ingestion-32-bytes-long".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String[] args = {"--server.port=0", "--spring.profiles.active=test", "--spring.datasource.url=" + db.getJdbcUrl(),
                "--spring.datasource.username=" + db.getUsername(), "--spring.datasource.password=" + db.getPassword(),
                "--spring.flyway.user=" + db.getUsername(), "--spring.flyway.password=" + db.getPassword(),
                "--documind.auth.jwt-secret=" + secret, "--documind.auth.allowed-origins=https://documind.test",
                "--documind.ingestion.worker-enabled=true", "--documind.ingestion.deadline-seconds=1",
                "--documind.ingestion.heartbeat-seconds=1", "--documind.ingestion.lease-seconds=5",
                "--documind.ingestion.stop-grace-seconds=1", "--logging.level.root=ERROR"};
        ApplicationListener<AvailabilityChangeEvent<?>> listener = event -> {
            if (event.getState() == ReadinessState.REFUSING_TRAFFIC) refusing.countDown();
        };
        var context = new SpringApplicationBuilder(DocumindBackendApplication.class, StuckProcessorConfiguration.class)
                .listeners(listener).run(args);
        try {
            assertThat(StuckProcessorConfiguration.started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(refusing.await(10, TimeUnit.SECONDS)).isTrue();
            await(() -> !context.isActive());
        } finally {
            StuckProcessorConfiguration.release.countDown();
            context.close();
        }
        assertThat(documentStatus()).isNotEqualTo("READY");
    }

    @Test void completionWaitingOnDocumentLockCannotCommitAfterDeadline() throws Exception {
        var started = new CountDownLatch(1); var releaseProcessor = new CountDownLatch(1);
        var locked = new CountDownLatch(1); var releaseLock = new CountDownLatch(1);
        IngestionProcessor processor = claim -> { started.countDown(); releaseProcessor.await(); return result(); };
        var worker = new IngestionWorker(queue, processor, 2, 8, 1, 2);
        var executor = Executors.newSingleThreadExecutor();
        try {
            worker.poll(); assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            var holder = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                jdbc.sql("SELECT id FROM documents WHERE id=:id FOR UPDATE").param("id", document).query(UUID.class).single();
                locked.countDown();
                try { if (!releaseLock.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return true;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            releaseProcessor.countDown();
            await(() -> jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%SELECT user_id,status FROM documents%FOR UPDATE%'")
                    .query(Long.class).single() > 0);
            Thread.sleep(2200);
            releaseLock.countDown(); assertThat(holder.get(10, TimeUnit.SECONDS)).isTrue();
            await(() -> jobStatus().equals("PENDING"));
            assertThat(documentStatus()).isEqualTo("PENDING");
            assertThat(jdbc.sql("SELECT count(*) FROM document_chunks WHERE document_id=:id").param("id", document).query(Long.class).single()).isZero();
        } finally { releaseProcessor.countDown(); releaseLock.countDown(); worker.stop(); executor.shutdownNow(); }
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
