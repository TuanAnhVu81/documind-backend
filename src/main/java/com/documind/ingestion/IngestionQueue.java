package com.documind.ingestion;

import com.documind.retrieval.ChunkVectorStore;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** All document/job transitions use document then job locks; claim locks only the job. */
@Service
public class IngestionQueue {
    private static final Logger log = LoggerFactory.getLogger(IngestionQueue.class);
    private static final int MAX_ATTEMPTS = 3;
    private final JdbcClient jdbc;
    private final JobClaimStore claims;
    private final ChunkVectorStore chunks;
    private final TransactionTemplate tx;
    private final TransactionTemplate shortTx;

    public IngestionQueue(JdbcClient jdbc, JobClaimStore claims, ChunkVectorStore chunks, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.claims = claims; this.chunks = chunks; this.tx = new TransactionTemplate(manager);
        this.shortTx = new TransactionTemplate(manager); this.shortTx.setTimeout(5);
    }

    private Instant now() { return jdbc.sql("SELECT clock_timestamp()").query((rs, row) -> rs.getTimestamp(1).toInstant()).single(); }
    private static java.time.OffsetDateTime sqlTime(Instant time) { return time.atOffset(ZoneOffset.UTC); }

    public Optional<JobClaimStore.Claim> claim(String worker, Duration lease) {
        if (lease == null || lease.isZero() || lease.isNegative()) throw new IllegalArgumentException("Invalid lease");
        Optional<JobClaimStore.Claim> result = tx.execute(status -> {
            Instant time = now();
            return claims.claimDue(worker, UUID.randomUUID(), time, time.plus(lease));
        });
        if (result == null || result.isEmpty()) return Optional.empty();
        JobClaimStore.Claim claim = result.orElseThrow();
        Boolean started = tx.execute(status -> {
            Optional<Locked> locked = lock(claim.documentId(), claim.id());
            if (locked.isEmpty()) return false;
            Locked state = locked.get();
            if ("DELETING".equals(state.documentStatus())) {
                cancel(state.jobId()); return false;
            }
            if (!valid(state, claim, now()) || !"PENDING".equals(state.documentStatus())) return false;
            jdbc.sql("UPDATE documents SET status='PROCESSING',error_code=NULL,error_message=NULL WHERE id=:id")
                    .param("id", claim.documentId()).update();
            return true;
        });
        return Boolean.TRUE.equals(started) ? result : Optional.empty();
    }

    public boolean heartbeat(JobClaimStore.Claim claim, Duration lease) {
        if (lease == null || lease.isZero() || lease.isNegative()) throw new IllegalArgumentException("Invalid lease");
        Boolean updated = shortTx.execute(status -> {
            // Check the actual time only after any competing row lock has been released.
            if (jdbc.sql("SELECT id FROM processing_jobs WHERE id=:id FOR UPDATE")
                    .param("id", claim.id()).query(UUID.class).optional().isEmpty()) return false;
            return jdbc.sql("""
                UPDATE processing_jobs j SET heartbeat_at=clock_timestamp(),
                  lease_until=clock_timestamp() + (:leaseMillis * interval '1 millisecond')
                WHERE id=:id AND status='PROCESSING' AND claim_token=:token AND lease_version=:version
                  AND lease_until>clock_timestamp() AND EXISTS (SELECT 1 FROM documents d WHERE d.id=j.document_id
                    AND d.status='PROCESSING' AND d.deleted_requested_at IS NULL)
                """).param("leaseMillis", lease.toMillis())
                    .param("id", claim.id()).param("token", claim.claimToken()).param("version", claim.leaseVersion()).update() == 1;
        });
        return Boolean.TRUE.equals(updated);
    }

    public boolean complete(JobClaimStore.Claim claim, IngestionProcessor.Result result) {
        return complete(claim, result, () -> true);
    }

    boolean complete(JobClaimStore.Claim claim, IngestionProcessor.Result result, java.util.function.BooleanSupplier allowed) {
        if (result == null || result.chunks() == null || result.chunks().isEmpty()) throw new IllegalArgumentException("Empty result");
        Boolean completed = tx.execute(status -> {
            Optional<Locked> found = lock(claim.documentId(), claim.id());
            if (found.isEmpty() || !allowed.getAsBoolean() || !valid(found.get(), claim, now()) || !"PROCESSING".equals(found.get().documentStatus())) return false;
            Locked state = found.get();
            if (!chunks.replace(state.owner(), claim.documentId(), result.chunks())) return false;
            // Long writes must not commit after the lease expires while this transaction holds the row lock.
            if (!allowed.getAsBoolean() || !valid(state, claim, now())) { status.setRollbackOnly(); return false; }
            jdbc.sql("""
                UPDATE documents SET status='READY',page_count=:pages,paragraph_count=:paragraphs,
                chunk_count=:count,error_code=NULL,error_message=NULL WHERE id=:id
                """).param("pages", result.pageCount()).param("paragraphs", result.paragraphCount())
                    .param("count", result.chunks().size()).param("id", claim.documentId()).update();
            jdbc.sql("UPDATE processing_jobs SET status='DONE',claimed_by=NULL,claim_token=NULL,lease_until=NULL,heartbeat_at=NULL WHERE id=:id")
                    .param("id", claim.id()).update();
            return true;
        });
        return Boolean.TRUE.equals(completed);
    }

    public boolean fail(JobClaimStore.Claim claim, boolean retryable, Duration retryAfter) {
        return fail(claim, retryable, retryAfter, "INGESTION_FAILED");
    }

    public boolean fail(JobClaimStore.Claim claim, boolean retryable, Duration retryAfter, String code) {
        Boolean changed = shortTx.execute(status -> {
            Optional<Locked> found = lock(claim.documentId(), claim.id());
            if (found.isEmpty() || !valid(found.get(), claim, now())) return false;
            Locked state = found.get();
            if ("DELETING".equals(state.documentStatus())) { cancel(state.jobId()); return true; }
            if (!"PROCESSING".equals(state.documentStatus())) return false;
            transitionFailure(state, retryable, retryAfter, now(), code);
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    public void recoverDue() {
        JobId cursor = null;
        while (true) {
            var query = jdbc.sql("SELECT id,document_id,lease_until FROM processing_jobs WHERE status='PROCESSING' "
                    + "AND lease_until<=clock_timestamp() "
                    + (cursor == null ? "" : "AND (lease_until,id) > (:lastLease,:lastId) ")
                    + "ORDER BY lease_until,id LIMIT 100");
            if (cursor != null) query.param("lastLease", sqlTime(cursor.leaseUntil())).param("lastId", cursor.id());
            var ids = query.query((rs, row) -> new JobId(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                    rs.getTimestamp("lease_until").toInstant())).list();
            if (ids.isEmpty()) return;
            for (JobId id : ids) {
                try { recoverOne(id); }
                catch (RuntimeException failure) {
                    log.warn("Ingestion recovery item failed jobId={} type={}", id.id(), failure.getClass().getName());
                }
            }
            cursor = ids.getLast();
        }
    }

    private void recoverOne(JobId id) {
        shortTx.executeWithoutResult(status -> {
            Optional<Locked> found = lock(id.documentId(), id.id(), true);
            if (found.isEmpty()) return;
            Locked state = found.get();
            Instant time = now();
            if (!"PROCESSING".equals(state.jobStatus()) || state.leaseUntil() == null || state.leaseUntil().isAfter(time)) return;
            if ("DELETING".equals(state.documentStatus())) cancel(state.jobId());
            else if ("PROCESSING".equals(state.documentStatus()) || "PENDING".equals(state.documentStatus()))
                transitionFailure(state, true, null, time, "INGESTION_FAILED");
        });
    }

    private void transitionFailure(Locked state, boolean retryable, Duration retryAfter, Instant time, String code) {
        boolean retry = retryable && state.attempts() < MAX_ATTEMPTS;
        long base = state.attempts() <= 1 ? 10 : 30;
        long delayMillis = base * 1000 + ThreadLocalRandom.current().nextLong(base * 200 + 1);
        if (retryAfter != null && !retryAfter.isNegative()) delayMillis = Math.max(delayMillis, retryAfter.toMillis());
        jdbc.sql("""
            UPDATE processing_jobs SET status=:status,next_run_at=:next,claimed_by=NULL,claim_token=NULL,
                lease_until=NULL,heartbeat_at=NULL WHERE id=:id
            """).param("status", retry ? "PENDING" : "FAILED").param("next", sqlTime(time.plusMillis(delayMillis)))
                .param("id", state.jobId()).update();
        jdbc.sql("UPDATE documents SET status=:status,error_code=:code,error_message=:message WHERE id=:id")
                .param("status", retry ? "PENDING" : "FAILED").param("code", retry ? "INGESTION_RETRY" : safeCode(code))
                .param("message", retry ? "Document processing will be retried." : safeMessage(code))
                .param("id", state.documentId()).update();
    }

    private static String safeMessage(String code) {
        return switch (safeCode(code)) {
            case "NO_EXTRACTABLE_TEXT" -> "No text could be extracted; scanned documents require OCR, which is not supported.";
            case "PDF_PAGE_LIMIT" -> "PDF exceeds the page limit.";
            case "TEXT_LIMIT" -> "Document exceeds the text limit.";
            case "DOCX_RESOURCE_LIMIT" -> "DOCX exceeds the archive resource limit.";
            case "CHUNK_LIMIT" -> "Document exceeds the chunk limit.";
            case "INVALID_TEXT_ENCODING" -> "Text must use UTF-8 encoding.";
            case "INVALID_DOCUMENT" -> "Document is damaged or cannot be read.";
            case "PARSER_TIMEOUT" -> "Document parsing timed out.";
            case "EMBEDDING_INVALID" -> "Embedding response is invalid.";
            case "PROVIDER_RATE_LIMIT" -> "Provider quota or rate limit was reached; check quota before retrying.";
            default -> "Document processing failed.";
        };
    }

    private static String safeCode(String code) {
        return code != null && java.util.Set.of("PDF_PAGE_LIMIT", "TEXT_LIMIT", "DOCX_RESOURCE_LIMIT", "CHUNK_LIMIT", "NO_EXTRACTABLE_TEXT",
                "INVALID_DOCUMENT", "INVALID_TEXT_ENCODING", "UNSUPPORTED_DOCUMENT", "PARSER_TIMEOUT",
                "EMBEDDING_INVALID", "PROVIDER_RATE_LIMIT", "INGESTION_FAILED").contains(code) ? code : "INGESTION_FAILED";
    }

    private void cancel(UUID job) {
        jdbc.sql("UPDATE processing_jobs SET status='CANCELLED',claimed_by=NULL,claim_token=NULL,lease_until=NULL,heartbeat_at=NULL WHERE id=:id")
                .param("id", job).update();
    }

    private record JobId(UUID id, UUID documentId, Instant leaseUntil) {}
    private record DocumentLock(UUID owner, String status) {}
    private record Locked(UUID jobId, UUID documentId, UUID owner, String documentStatus, String jobStatus,
                          UUID token, long version, int attempts, Instant leaseUntil) {}

    private Optional<Locked> lock(UUID document, UUID job) {
        return lock(document, job, false);
    }

    private Optional<Locked> lock(UUID document, UUID job, boolean skipLocked) {
        String suffix = skipLocked ? " FOR UPDATE SKIP LOCKED" : " FOR UPDATE";
        var doc = jdbc.sql("SELECT user_id,status FROM documents WHERE id=:id" + suffix).param("id", document)
                .query((rs, row) -> new DocumentLock(rs.getObject("user_id", UUID.class), rs.getString("status"))).optional();
        if (doc.isEmpty()) return Optional.empty();
        return jdbc.sql("SELECT id,status,claim_token,lease_version,attempts,lease_until FROM processing_jobs WHERE id=:id AND document_id=:doc" + suffix)
                .param("id", job).param("doc", document).query((rs, row) -> {
                    var until = rs.getTimestamp("lease_until");
                    return new Locked(job, document, doc.get().owner(), doc.get().status(), rs.getString("status"),
                            rs.getObject("claim_token", UUID.class), rs.getLong("lease_version"), rs.getInt("attempts"),
                            until == null ? null : until.toInstant());
                }).optional();
    }

    private static boolean valid(Locked state, JobClaimStore.Claim claim, Instant time) {
        return "PROCESSING".equals(state.jobStatus()) && claim.claimToken().equals(state.token())
                && state.version() == claim.leaseVersion() && state.leaseUntil() != null && state.leaseUntil().isAfter(time);
    }
}
