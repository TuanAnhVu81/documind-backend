package com.documind.ingestion.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Caller owns a short transaction; never keep it open while processing a document. */
@Repository
public class JobClaimStore {
    private final JdbcClient jdbc;
    public JobClaimStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Claim(UUID id, UUID documentId, UUID claimToken, long leaseVersion, int attempts) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Claim> claimDue(String worker, UUID token, Instant now, Instant leaseUntil) {
        if (worker == null || worker.isBlank() || token == null || now == null || leaseUntil == null
                || !leaseUntil.isAfter(now)) throw new IllegalArgumentException("Invalid lease");
        return jdbc.sql("""
            WITH candidate AS (
              SELECT j.id FROM processing_jobs j JOIN documents d ON d.id = j.document_id
              WHERE j.status = 'PENDING' AND j.attempts < 3 AND j.next_run_at <= :now
                AND d.status = 'PENDING'
              ORDER BY j.next_run_at, j.id FOR UPDATE OF j SKIP LOCKED LIMIT 1
            )
            UPDATE processing_jobs j SET status='PROCESSING', attempts=j.attempts+1,
              claimed_by=:worker, claim_token=:token, lease_version=j.lease_version+1,
              lease_until=:leaseUntil, heartbeat_at=:now
            FROM candidate WHERE j.id=candidate.id
            RETURNING j.id,j.document_id,j.claim_token,j.lease_version,j.attempts
            """).param("now", now.atOffset(ZoneOffset.UTC)).param("worker", worker).param("token", token)
            .param("leaseUntil", leaseUntil.atOffset(ZoneOffset.UTC))
            .query((rs, row) -> new Claim(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getObject("claim_token", UUID.class), rs.getLong("lease_version"), rs.getInt("attempts")))
            .optional();
    }
}
