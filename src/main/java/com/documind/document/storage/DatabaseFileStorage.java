package com.documind.document.storage;

import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name = "documind.storage.backend", havingValue = "database")
public class DatabaseFileStorage implements FileStorage {
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final long STORAGE_LOCK = 4919426026388193356L;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final long maxBytes;

    public DatabaseFileStorage(JdbcClient jdbc, PlatformTransactionManager manager,
            @Value("${documind.storage.max-bytes}") long maxBytes) {
        if (maxBytes < MAX_FILE_BYTES) throw new IllegalArgumentException("Storage capacity must allow at least one maximum-size file");
        this.jdbc = jdbc;
        this.maxBytes = maxBytes;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public StoredFile store(InputStream input, String filename) throws IOException {
        String extension = LocalFileStorage.extension(filename);
        byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        if (bytes.length > MAX_FILE_BYTES) throw new AppException(ErrorCode.FILE_TOO_LARGE);
        if (bytes.length == 0) throw new AppException(ErrorCode.INVALID_REQUEST);
        var temporary = Files.createTempFile("documind-upload-", ".tmp");
        String mediaType;
        try {
            Files.write(temporary, bytes);
            mediaType = LocalFileStorage.detect(temporary, extension);
        } finally {
            Files.deleteIfExists(temporary);
        }
        UUID key = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(:id)").param("id", STORAGE_LOCK)
                    .query((rs, row) -> Boolean.TRUE).single();
            long used = jdbc.sql("SELECT coalesce(sum(octet_length(content)), 0) FROM stored_files")
                    .query(Long.class).single();
            if (bytes.length > maxBytes - used) throw new AppException(ErrorCode.STORAGE_CAPACITY_EXCEEDED);
            jdbc.sql("INSERT INTO stored_files(storage_key, content) VALUES (:key, :content)")
                    .param("key", key).param("content", bytes).update();
        });
        return new StoredFile(key.toString(), bytes.length, mediaType);
    }

    @Override public InputStream open(String key) throws IOException {
        byte[] bytes = jdbc.sql("SELECT content FROM stored_files WHERE storage_key=:key")
                .param("key", parseKey(key)).query((rs, row) -> rs.getBytes(1)).optional()
                .orElseThrow(() -> new IOException("Stored file unavailable"));
        return new ByteArrayInputStream(bytes);
    }

    @Override public void delete(String key) {
        UUID id = parseKey(key);
        transactions.executeWithoutResult(status -> jdbc.sql("DELETE FROM stored_files WHERE storage_key=:key")
                .param("key", id).update());
    }

    @Override public List<Entry> entries() {
        return jdbc.sql("SELECT storage_key, created_at FROM stored_files")
                .query((rs, row) -> new Entry(rs.getObject(1, UUID.class).toString(),
                        rs.getTimestamp(2).toInstant())).list();
    }

    private static UUID parseKey(String key) {
        if (key == null || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Invalid storage key");
        return UUID.fromString(key);
    }
}
