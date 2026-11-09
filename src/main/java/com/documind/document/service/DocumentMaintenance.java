package com.documind.document.service;

import com.documind.document.storage.FileStorage;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@EnableScheduling
public class DocumentMaintenance {
    private static final Logger log = LoggerFactory.getLogger(DocumentMaintenance.class);
    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final TransactionTemplate transactions;

    public DocumentMaintenance(JdbcClient jdbc, FileStorage storage, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.storage = storage; this.transactions = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${documind.storage.cleanup-interval-ms:30000}", initialDelayString = "${documind.storage.cleanup-interval-ms:30000}")
    public void cleanupDue() {
        var ids = jdbc.sql("SELECT document_id FROM document_cleanup_tasks WHERE next_run_at<=now() ORDER BY next_run_at,id LIMIT 50")
                .query(UUID.class).list();
        for (UUID id : ids) {
            try { transactions.executeWithoutResult(ignored -> cleanup(id)); }
            catch (RuntimeException failure) { log.error("Document cleanup transaction failed type={}", failure.getClass().getName()); }
        }
    }

    private void cleanup(UUID id) {
        var key = jdbc.sql("SELECT storage_key FROM documents WHERE id=:id AND status='DELETING' FOR UPDATE")
                .param("id", id).query(String.class).optional();
        if (key.isEmpty()) return;
        var task = jdbc.sql("SELECT id FROM document_cleanup_tasks WHERE document_id=:id AND next_run_at<=now() FOR UPDATE")
                .param("id", id).query(UUID.class).optional();
        if (task.isEmpty()) return;
        try {
            storage.delete(key.get());
        } catch (IOException | IllegalArgumentException failure) {
            jdbc.sql("""
                    UPDATE document_cleanup_tasks SET attempts=attempts+1,last_error_code='STORAGE_ERROR',
                        next_run_at=now() + (least(300, 10 * power(2,least(attempts,5))) * interval '1 second')
                    WHERE id=:id
                    """).param("id", task.get()).update();
            log.warn("Document cleanup storage failure documentId={}", id);
            return;
        }
        // Cascades delete the cleanup task, job and chunks only after the file is gone.
        jdbc.sql("DELETE FROM documents WHERE id=:id AND status='DELETING'").param("id", id).update();
    }

    @Scheduled(fixedDelayString = "${documind.storage.orphan-interval-ms:3600000}", initialDelayString = "${documind.storage.orphan-interval-ms:3600000}")
    public void sweepOrphans() {
        try {
            Instant cutoff = Instant.now().minusSeconds(24 * 60 * 60);
            for (FileStorage.Entry entry : storage.entries()) {
                if (!entry.modifiedAt().isBefore(cutoff)) continue;
                long count = jdbc.sql("SELECT count(*) FROM documents WHERE storage_key=:key")
                        .param("key", entry.key()).query(Long.class).single();
                if (count == 0) storage.delete(entry.key());
            }
        } catch (IOException | RuntimeException failure) {
            log.error("Orphan scan failed type={}", failure.getClass().getName());
        }
        long overdue = jdbc.sql("SELECT count(*) FROM document_cleanup_tasks WHERE created_at < now() - interval '24 hours'")
                .query(Long.class).single();
        if (overdue > 0) log.warn("Document cleanup overdue count={}", overdue);
    }
}
