package com.documind.chat;

import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ConversationService {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    public ConversationService(JdbcClient jdbc, JsonMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.json = json; this.tx = new TransactionTemplate(manager);
    }

    public record ConversationDto(UUID id, String title, Instant createdAt, Instant updatedAt) {}
    public record Page(List<ConversationDto> items, int page, int size, long totalElements, long totalPages) {}
    public record MessageDto(UUID id, UUID conversationId, long ordinal, String role, String content,
                             String status, String outcome, JsonNode sources, Instant createdAt) {}
    public record MessagePage(List<MessageDto> items, boolean hasMore, Long nextBeforeOrdinal) {}

    public ConversationDto create(UUID owner, String title) {
        String value = title == null || title.isBlank() ? "Hội thoại mới" : title.strip();
        UUID id = UUID.randomUUID();
        return jdbc.sql("""
                INSERT INTO conversations(id,user_id,title) VALUES (:id,:owner,:title)
                RETURNING id,title,created_at,updated_at
                """).param("id", id).param("owner", owner).param("title", value).query(ConversationService::conversation).single();
    }

    public Page list(UUID owner, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new AppException(ErrorCode.INVALID_REQUEST);
        long total = jdbc.sql("SELECT count(*) FROM conversations WHERE user_id=:owner AND deleted_requested_at IS NULL")
                .param("owner", owner).query(Long.class).single();
        var items = jdbc.sql("""
                SELECT id,title,created_at,updated_at FROM conversations
                WHERE user_id=:owner AND deleted_requested_at IS NULL
                ORDER BY updated_at DESC,id DESC LIMIT :size OFFSET :offset
                """).param("owner", owner).param("size", size).param("offset", (long) page * size)
                .query(ConversationService::conversation).list();
        return new Page(items, page, size, total, (total + size - 1) / size);
    }

    public MessagePage messages(UUID owner, UUID id, Long beforeOrdinal, int limit) {
        if (limit < 1 || limit > 100 || (beforeOrdinal != null && beforeOrdinal < 0))
            throw new AppException(ErrorCode.INVALID_REQUEST);
        return tx.execute(status -> readMessages(owner, id, beforeOrdinal, limit));
    }

    private MessagePage readMessages(UUID owner, UUID id, Long beforeOrdinal, int limit) {
        requireOwned(owner, id);
        String before = beforeOrdinal == null ? "" : " AND m.ordinal < :before";
        var query = jdbc.sql("""
                SELECT m.* FROM messages m JOIN conversations c ON c.id=m.conversation_id
                WHERE c.id=:id AND c.user_id=:owner AND c.deleted_requested_at IS NULL
                """ + before + " ORDER BY m.ordinal DESC LIMIT :limit")
                .param("id", id).param("owner", owner).param("limit", limit + 1);
        if (beforeOrdinal != null) query.param("before", beforeOrdinal);
        var results = new java.util.ArrayList<>(query.query((rs, row) -> message(rs)).list());
        boolean more = results.size() > limit;
        if (more) results.removeLast();
        java.util.Collections.reverse(results);
        return new MessagePage(List.copyOf(results), more, more ? results.getFirst().ordinal() : null);
    }

    public void delete(UUID owner, UUID id) {
        tx.executeWithoutResult(status -> {
            int changed = jdbc.sql("UPDATE conversations SET deleted_requested_at=now() WHERE id=:id AND user_id=:owner AND deleted_requested_at IS NULL")
                    .param("id", id).param("owner", owner).update();
            if (changed == 0) throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
            jdbc.sql("DELETE FROM conversations WHERE id=:id AND user_id=:owner").param("id", id).param("owner", owner).update();
        });
    }

    private void requireOwned(UUID owner, UUID id) {
        if (jdbc.sql("SELECT id FROM conversations WHERE id=:id AND user_id=:owner AND deleted_requested_at IS NULL FOR SHARE")
                .param("id", id).param("owner", owner).query(UUID.class).optional().isEmpty())
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private static ConversationDto conversation(ResultSet rs, int row) throws SQLException {
        return new ConversationDto(rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private MessageDto message(ResultSet rs) throws SQLException {
        return new MessageDto(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class), rs.getLong("ordinal"),
                rs.getString("role"), rs.getString("content"), rs.getString("status"), rs.getString("outcome"),
                json.readTree(rs.getString("sources")), rs.getTimestamp("created_at").toInstant());
    }
}
