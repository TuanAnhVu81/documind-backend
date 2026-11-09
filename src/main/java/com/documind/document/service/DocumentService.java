package com.documind.document.service;

import com.documind.document.storage.FileStorage;

import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final TransactionTemplate transactions;
    private final org.springframework.context.ApplicationEventPublisher events;

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentService(JdbcClient jdbc, FileStorage storage, PlatformTransactionManager manager,
            org.springframework.context.ApplicationEventPublisher events) {
        this.jdbc = jdbc; this.storage = storage; this.transactions = new TransactionTemplate(manager); this.events = events;
    }

    public DocumentService(JdbcClient jdbc, FileStorage storage, PlatformTransactionManager manager) {
        this(jdbc, storage, manager, ignored -> {});
    }

    private record InterruptedGeneration(UUID id, UUID assistant) {}

    public record UploadResult(UUID id, String status) {}
    public record DocumentDto(UUID id, String filename, String mediaType, long sizeBytes, String status,
            Integer pageCount, Integer paragraphCount, Integer chunkCount, Integer attempts, Instant nextRetryAt,
            String errorCode, String errorMessage, Instant createdAt) {}
    public record DocumentPage(List<DocumentDto> items, int page, int size, long totalElements, long totalPages) {}

    public UploadResult upload(UUID owner, MultipartFile file) throws IOException {
        FileStorage.StoredFile stored;
        try (var input = file.getInputStream()) { stored = storage.store(input, file.getOriginalFilename()); }
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(ignored -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            try { storage.delete(stored.key()); } catch (IOException cleanup) {
                                org.slf4j.LoggerFactory.getLogger(DocumentService.class)
                                        .warn("Unable to remove file after rollback; orphan scan will retry");
                            }
                        }
                    }
                });
                jdbc.sql("""
                    INSERT INTO documents(id,user_id,filename,storage_key,media_type,size_bytes,status)
                    VALUES (:id,:owner,:name,:key,:type,:size,'PENDING')
                    """).param("id", id).param("owner", owner).param("name", file.getOriginalFilename())
                        .param("key", stored.key()).param("type", stored.mediaType()).param("size", stored.size()).update();
                jdbc.sql("INSERT INTO processing_jobs(id,document_id,status) VALUES (:id,:doc,'PENDING')")
                        .param("id", UUID.randomUUID()).param("doc", id).update();
            });
        return new UploadResult(id, "PENDING");
    }

    private static final String SELECT = """
            SELECT d.*,j.attempts,j.next_run_at FROM documents d
            LEFT JOIN processing_jobs j ON j.document_id=d.id WHERE d.user_id=:owner
            """;

    public DocumentDto detail(UUID owner, UUID id) {
        return jdbc.sql(SELECT + " AND d.id=:id").param("owner", owner).param("id", id)
                .query(DocumentService::map).optional().orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    public DocumentPage list(UUID owner, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new AppException(ErrorCode.INVALID_REQUEST);
        long count = jdbc.sql("SELECT count(*) FROM documents WHERE user_id=:owner").param("owner", owner)
                .query(Long.class).single();
        List<DocumentDto> items = jdbc.sql(SELECT + " ORDER BY d.created_at DESC,d.id DESC LIMIT :size OFFSET :offset")
                .param("owner", owner).param("size", size).param("offset", (long) page * size)
                .query(DocumentService::map).list();
        return new DocumentPage(items, page, size, count, (count + size - 1) / size);
    }

    private static DocumentDto map(ResultSet rs, int row) throws SQLException {
        var retry = rs.getTimestamp("next_run_at");
        String status = rs.getString("status");
        Integer attempts = rs.getObject("attempts", Integer.class);
        return new DocumentDto(rs.getObject("id", UUID.class), rs.getString("filename"), rs.getString("media_type"),
                rs.getLong("size_bytes"), status, rs.getObject("page_count", Integer.class),
                rs.getObject("paragraph_count", Integer.class), rs.getObject("chunk_count", Integer.class),
                attempts, "PENDING".equals(status) && attempts != null && attempts > 0 && retry != null ? retry.toInstant() : null,
                rs.getString("error_code"), rs.getString("error_message"), rs.getTimestamp("created_at").toInstant());
    }

    public void requestDeletion(UUID owner, UUID id) {
        transactions.executeWithoutResult(ignored -> {
            var key = jdbc.sql("SELECT storage_key FROM documents WHERE id=:id AND user_id=:owner FOR UPDATE")
                    .param("id", id).param("owner", owner).query(String.class).optional()
                    .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
            jdbc.sql("UPDATE documents SET status='DELETING', deleted_requested_at=coalesce(deleted_requested_at,now()) WHERE id=:id")
                    .param("id", id).update();
            var interrupted = jdbc.sql("""
                    UPDATE chat_generations g SET status='INCOMPLETE',outcome='SOURCE_DELETED',generation_version=g.generation_version+1
                    FROM conversations c WHERE c.id=g.conversation_id AND c.user_id=:owner
                    AND g.status='GENERATING' AND :doc=ANY(g.context_document_ids)
                    RETURNING g.id,g.assistant_message_id
                    """).param("owner", owner).param("doc", id).query((rs, row) -> new InterruptedGeneration(
                            rs.getObject("id", UUID.class), rs.getObject("assistant_message_id", UUID.class))).list();
            if (!interrupted.isEmpty()) jdbc.sql("""
                    UPDATE messages SET status='INCOMPLETE',outcome='SOURCE_DELETED',content='',sources='[]'::jsonb
                    WHERE id IN (:ids) AND status='GENERATING'
                    """).param("ids", interrupted.stream().map(InterruptedGeneration::assistant).toList()).update();
            if (!interrupted.isEmpty()) {
                var event = new SourceGenerationsCancelled(interrupted.stream().map(InterruptedGeneration::id).toList());
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        try { events.publishEvent(event); }
                        catch (RuntimeException failure) {
                            org.slf4j.LoggerFactory.getLogger(DocumentService.class)
                                    .warn("Unable to signal cancelled generations; persisted state remains fenced");
                        }
                    }
                });
            }
            jdbc.sql("INSERT INTO document_cleanup_tasks(id,document_id) VALUES (:task,:doc) ON CONFLICT (document_id) DO NOTHING")
                    .param("task", UUID.randomUUID()).param("doc", id).update();
            jdbc.sql("""
                    INSERT INTO deletion_journal_outbox(id,document_id,user_id,storage_key)
                    VALUES (:entry,:doc,:owner,:key) ON CONFLICT (document_id) DO NOTHING
                    """).param("entry", UUID.randomUUID()).param("doc", id).param("owner", owner).param("key", key).update();
            jdbc.sql("""
                    UPDATE messages m SET sources=(SELECT jsonb_agg(
                        CASE WHEN item->>'documentId'=CAST(:doc AS text)
                        THEN item || '{"filename":null,"snippet":null,"availability":"DELETED"}'::jsonb
                        ELSE item END ORDER BY ord)
                        FROM jsonb_array_elements(m.sources) WITH ORDINALITY AS s(item,ord))
                    FROM conversations c WHERE m.conversation_id=c.id AND c.user_id=:owner
                    AND EXISTS (SELECT 1 FROM jsonb_array_elements(m.sources) AS s(item)
                                WHERE item->>'documentId'=CAST(:doc AS text))
                    """).param("doc", id).param("owner", owner).update();
        });
    }
}
