package com.documind.chat.service;

import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Short transactions for generation fencing and canonical snapshots. */
@Service
public class GenerationLifecycle {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public GenerationLifecycle(JdbcClient jdbc, PlatformTransactionManager manager, JsonMapper json) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.json = json;
    }

    public record Usage(Long promptTokens, Long completionTokens, Long embeddingTokens, BigDecimal estimatedCostUsd, boolean estimated) {}
    public record Snapshot(ConversationService.MessageDto message, Usage usage) {}
    public record State(String status, String outcome) {}
    @FunctionalInterface public interface ReplayWriter { void write(Snapshot snapshot) throws java.io.IOException; }

    public boolean heartbeat(UUID id, long version) {
        return jdbc.sql("UPDATE chat_generations SET heartbeat_at=now() WHERE id=:id AND status='GENERATING' AND generation_version=:version")
                .param("id", id).param("version", version).update() == 1;
    }

    public State state(UUID id) {
        return jdbc.sql("SELECT status,outcome FROM chat_generations WHERE id=:id").param("id", id)
                .query((rs, row) -> new State(rs.getString("status"), rs.getString("outcome"))).optional().orElse(null);
    }

    public boolean terminate(UUID generation, long version, String outcome, String partial) {
        if (!java.util.Set.of("CLIENT_DISCONNECTED", "TIMEOUT", "SOURCE_DELETED").contains(outcome))
            throw new IllegalArgumentException("Invalid terminal outcome");
        return Boolean.TRUE.equals(tx.execute(status -> {
            var assistant = jdbc.sql("""
                    UPDATE chat_generations SET status='INCOMPLETE',outcome=:outcome,generation_version=generation_version+1
                    WHERE id=:id AND status='GENERATING' AND generation_version=:version RETURNING assistant_message_id
                    """).param("id", generation).param("version", version).param("outcome", outcome).query(UUID.class).optional();
            if (assistant.isEmpty()) return false;
            jdbc.sql("UPDATE messages SET status='INCOMPLETE',outcome=:outcome,content=:content,sources='[]'::jsonb WHERE id=:id")
                    .param("id", assistant.get()).param("outcome", outcome)
                    .param("content", "SOURCE_DELETED".equals(outcome) ? "" : partial).update();
            return true;
        }));
    }

    public Snapshot snapshot(UUID owner, UUID conversation, UUID message) {
        return tx.execute(status -> {
            var dto = jdbc.sql("""
                    SELECT m.* FROM messages m JOIN conversations c ON c.id=m.conversation_id
                    WHERE c.user_id=:owner AND c.id=:conversation AND c.deleted_requested_at IS NULL AND m.id=:message
                    FOR SHARE OF m,c
                    """).param("owner", owner).param("conversation", conversation).param("message", message)
                    .query((rs, row) -> new ConversationService.MessageDto(rs.getObject("id", UUID.class),
                            rs.getObject("conversation_id", UUID.class), rs.getLong("ordinal"), rs.getString("role"),
                            rs.getString("content"), rs.getString("status"), rs.getString("outcome"),
                            json.readTree(rs.getString("sources")), rs.getTimestamp("created_at").toInstant()))
                    .optional().orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
            var usage = jdbc.sql("""
                    SELECT
                      CASE WHEN count(*) FILTER (WHERE operation LIKE 'CHAT_%')=0 THEN 0
                           WHEN bool_or(prompt_tokens IS NULL) FILTER (WHERE operation LIKE 'CHAT_%') THEN NULL
                           ELSE sum(prompt_tokens) FILTER (WHERE operation LIKE 'CHAT_%') END AS prompt,
                      CASE WHEN count(*) FILTER (WHERE operation LIKE 'CHAT_%')=0 THEN 0
                           WHEN bool_or(completion_tokens IS NULL) FILTER (WHERE operation LIKE 'CHAT_%') THEN NULL
                           ELSE sum(completion_tokens) FILTER (WHERE operation LIKE 'CHAT_%') END AS completion,
                      CASE WHEN count(*) FILTER (WHERE operation='QUERY_EMBEDDING')=0 THEN 0
                           WHEN bool_or(embedding_tokens IS NULL) FILTER (WHERE operation='QUERY_EMBEDDING') THEN NULL
                           ELSE sum(embedding_tokens) FILTER (WHERE operation='QUERY_EMBEDDING') END AS embedding,
                      CASE WHEN count(*)=0 THEN 0 WHEN bool_or(estimated_cost_usd IS NULL) THEN NULL ELSE sum(estimated_cost_usd) END AS cost,
                      coalesce(bool_or(estimated),false) AS estimated
                    FROM usage_logs WHERE message_id=:message
                    """).param("message", message).query((rs, row) -> new Usage(asLong(rs.getBigDecimal("prompt")),
                            asLong(rs.getBigDecimal("completion")), asLong(rs.getBigDecimal("embedding")),
                            rs.getBigDecimal("cost"), rs.getBoolean("estimated"))).single();
            return new Snapshot(dto, usage);
        });
    }

    /** The write finishes before a source DELETE can commit; no provider call occurs under these locks. */
    public void writeReplay(UUID owner, UUID conversation, UUID message, ReplayWriter writer) throws java.io.IOException {
        try {
            tx.executeWithoutResult(status -> {
                String raw = jdbc.sql("""
                        SELECT m.sources::text FROM messages m JOIN conversations c ON c.id=m.conversation_id
                        WHERE c.user_id=:owner AND c.id=:conversation AND c.deleted_requested_at IS NULL AND m.id=:message
                        """).param("owner", owner).param("conversation", conversation).param("message", message)
                        .query(String.class).optional().orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
                var ids = new java.util.HashSet<UUID>();
                for (var source : json.readTree(raw)) {
                    try { ids.add(UUID.fromString(source.path("documentId").asString())); }
                    catch (RuntimeException invalid) { throw new IllegalStateException("Invalid stored source"); }
                }
                if (!ids.isEmpty()) jdbc.sql("SELECT id FROM documents WHERE user_id=:owner AND id IN (:ids) ORDER BY id FOR SHARE")
                        .param("owner", owner).param("ids", ids).query(UUID.class).list();
                try { writer.write(snapshot(owner, conversation, message)); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
        } catch (java.io.UncheckedIOException failure) { throw failure.getCause(); }
    }

    private static Long asLong(BigDecimal value) { return value == null ? null : value.longValueExact(); }
}
