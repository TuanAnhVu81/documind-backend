package com.documind.chat;

import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import com.documind.retrieval.ChunkVectorStore;
import com.documind.retrieval.GeminiEmbeddingModel;
import com.documind.retrieval.RetrievalService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Synchronous internal turn processing. HTTP streaming is deliberately left to Phase 9. */
@Service
public class ChatTurnService {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final RetrievalService retrieval;
    private final QueryResolver resolver;
    private final RagAnswerEngine answerEngine;
    private final ObjectProvider<ChatProvider> providers;
    private final JsonMapper json;

    public ChatTurnService(JdbcClient jdbc, PlatformTransactionManager manager, RetrievalService retrieval,
            QueryResolver resolver, RagAnswerEngine answerEngine, ObjectProvider<ChatProvider> providers, JsonMapper json) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.retrieval = retrieval;
        this.resolver = resolver; this.answerEngine = answerEngine; this.providers = providers; this.json = json;
    }

    public record Result(UUID generationId, UUID assistantMessageId, String status, String outcome, String content) {}
    private record Started(UUID generation, UUID assistant, List<QueryResolver.Turn> history) {}

    public Result process(UUID owner, UUID conversation, UUID requestKey, String content, RetrievalService.Scope scope,
            List<UUID> documentIds) {
        if (owner == null || conversation == null || requestKey == null || content == null
                || content.isBlank() || content.codePointCount(0, content.length()) > 4000)
            throw new AppException(ErrorCode.INVALID_REQUEST);
        String question = content.replace("\r\n", "\n").replace('\r', '\n').strip();
        Started started = tx.execute(status -> start(owner, conversation, requestKey, question, scope, documentIds));
        ChatProvider provider = providers.getIfAvailable();
        try {
            ChatProvider rewrite = recording(provider, owner, started.assistant(), "CHAT_REWRITE");
            var resolution = resolver.resolve(question, started.history(), rewrite);
            if (resolution.clarification() != null)
                return finish(owner, started, List.of(), "COMPLETE", "CLARIFICATION_REQUIRED", resolution.clarification(), List.of());
            List<ChunkVectorStore.Hit> hits = retrieval.search(owner, resolution.question(), scope, documentIds,
                    () -> logUsage(owner, started.assistant(), "QUERY_EMBEDDING", GeminiEmbeddingModel.MODEL, null));
            List<UUID> docs = hits.stream().map(ChunkVectorStore.Hit::documentId).distinct().sorted().toList();
            if (!registerContext(owner, started, docs))
                return finish(owner, started, List.of(), "INCOMPLETE", "SOURCE_DELETED", "", List.of());
            var response = answerEngine.answer(question, resolution.question(), hits, SafeResponses.noEvidence(question),
                    SafeResponses.invalidCitation(question),
                    recording(provider, owner, started.assistant(), "CHAT_ANSWER"));
            return finish(owner, started, docs, "COMPLETE", response.outcome(), response.content(), response.sources());
        } catch (org.springframework.dao.DataAccessException failure) {
            try { finish(owner, started, List.of(), "ERROR", "PERSISTENCE_ERROR", "", List.of()); }
            catch (RuntimeException recordingFailure) { failure.addSuppressed(recordingFailure); }
            throw failure;
        } catch (AppException failure) {
            if (failure.errorCode() == ErrorCode.RESOURCE_NOT_FOUND || failure.errorCode() == ErrorCode.DOCUMENT_NOT_READY) {
                return finish(owner, started, List.of(), "INCOMPLETE", "SOURCE_DELETED", "", List.of());
            }
            finish(owner, started, List.of(), "ERROR", "PERSISTENCE_ERROR", "", List.of());
            throw failure;
        } catch (RuntimeException failure) {
            finish(owner, started, List.of(), "ERROR", "PROVIDER_ERROR", "", List.of());
            throw failure;
        }
    }

    private Started start(UUID owner, UUID conversation, UUID key, String question, RetrievalService.Scope scope, List<UUID> ids) {
        if (jdbc.sql("SELECT id FROM conversations WHERE id=:id AND user_id=:owner AND deleted_requested_at IS NULL FOR UPDATE")
                .param("id", conversation).param("owner", owner).query(UUID.class).optional().isEmpty())
            throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM chat_generations WHERE conversation_id=:id AND status='GENERATING')")
                .param("id", conversation).query(Boolean.class).single()) throw new AppException(ErrorCode.GENERATION_IN_PROGRESS);
        if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM chat_generations WHERE conversation_id=:id AND request_key=:key)")
                .param("id", conversation).param("key", key).query(Boolean.class).single()) throw new AppException(ErrorCode.INVALID_REQUEST);
        retrieval.validate(owner, scope, ids);
        var history = history(owner, conversation, scope, ids);
        long ordinal = jdbc.sql("SELECT coalesce(max(ordinal),-1)+1 FROM messages WHERE conversation_id=:id")
                .param("id", conversation).query(Long.class).single();
        UUID user = UUID.randomUUID(), assistant = UUID.randomUUID(), generation = UUID.randomUUID();
        jdbc.sql("INSERT INTO messages(id,conversation_id,ordinal,role,content,status) VALUES (:id,:c,:n,'USER',:content,'COMPLETE')")
                .param("id", user).param("c", conversation).param("n", ordinal).param("content", question).update();
        jdbc.sql("INSERT INTO messages(id,conversation_id,ordinal,role,status) VALUES (:id,:c,:n,'ASSISTANT','GENERATING')")
                .param("id", assistant).param("c", conversation).param("n", ordinal + 1).update();
        jdbc.sql("""
                INSERT INTO chat_generations(id,conversation_id,user_message_id,assistant_message_id,request_key,request_hash,scope_mode,status)
                VALUES (:id,:c,:user,:assistant,:key,:hash,:scope,'GENERATING')
                """).param("id", generation).param("c", conversation).param("user", user).param("assistant", assistant)
                .param("key", key).param("hash", hash(question, scope, ids)).param("scope", scope.name()).update();
        jdbc.sql("UPDATE conversations SET updated_at=now() WHERE id=:id").param("id", conversation).update();
        return new Started(generation, assistant, history);
    }

    private List<QueryResolver.Turn> history(UUID owner, UUID conversation, RetrievalService.Scope scope, List<UUID> ids) {
        return jdbc.sql("""
                SELECT u.content AS question,a.content AS answer,a.status,a.outcome,a.sources::text AS sources
                FROM chat_generations g JOIN messages u ON u.id=g.user_message_id JOIN messages a ON a.id=g.assistant_message_id
                WHERE g.conversation_id=:c AND g.status='COMPLETE' AND a.outcome IN ('ANSWER','CLARIFICATION_REQUIRED')
                ORDER BY u.ordinal DESC LIMIT 6
                """).param("c", conversation).query((rs, row) -> {
                    boolean available = true;
                    var sources = json.readTree(rs.getString("sources"));
                    if ("ANSWER".equals(rs.getString("outcome")) && (!sources.isArray() || sources.isEmpty())) available = false;
                    for (var source : sources) {
                        UUID id;
                        try { id = UUID.fromString(source.path("documentId").asString()); }
                        catch (RuntimeException malformed) { available = false; break; }
                        if (!"AVAILABLE".equals(source.path("availability").asString())
                                || (scope == RetrievalService.Scope.SELECTED && !ids.contains(id))
                                || !jdbc.sql("SELECT EXISTS(SELECT 1 FROM documents WHERE id=:id AND user_id=:owner AND status='READY' AND deleted_requested_at IS NULL)")
                                  .param("id", id).param("owner", owner).query(Boolean.class).single()) { available = false; break; }
                    }
                    return new QueryResolver.Turn(rs.getString("question"), rs.getString("answer"),
                            rs.getString("status"), rs.getString("outcome"), available);
                }).list().reversed();
    }

    private boolean registerContext(UUID owner, Started started, List<UUID> docs) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (!lockReady(owner, docs)) return false;
            int changed = jdbc.sql("UPDATE chat_generations SET context_document_ids=CAST(:docs AS uuid[]) WHERE id=:id AND status='GENERATING' AND generation_version=0")
                    .param("docs", "{" + String.join(",", docs.stream().map(UUID::toString).toList()) + "}")
                    .param("id", started.generation()).update();
            return changed == 1;
        }));
    }

    private boolean lockReady(UUID owner, List<UUID> docs) {
        if (docs.isEmpty()) return true;
        var states = jdbc.sql("SELECT status FROM documents WHERE user_id=:owner AND id IN (:ids) AND deleted_requested_at IS NULL ORDER BY id FOR UPDATE")
                .param("owner", owner).param("ids", docs).query(String.class).list();
        return states.size() == docs.size() && states.stream().allMatch("READY"::equals);
    }

    private Result finish(UUID owner, Started started, List<UUID> docs, String state, String outcome,
            String content, List<RagAnswerEngine.Source> sources) {
        return tx.execute(status -> {
            boolean ready = lockReady(owner, docs);
            String finalState = ready ? state : "INCOMPLETE";
            String finalOutcome = ready ? outcome : "SOURCE_DELETED";
            String finalContent = ready ? content : "";
            String sourceJson = json.writeValueAsString(ready ? sources : List.of());
            int changed = jdbc.sql("""
                    UPDATE chat_generations SET status=:state,outcome=:outcome,generation_version=generation_version+1
                    WHERE id=:id AND status='GENERATING' AND generation_version=0
                    """).param("state", finalState).param("outcome", finalOutcome).param("id", started.generation()).update();
            if (changed == 0) return new Result(started.generation(), started.assistant(), "INCOMPLETE", "SOURCE_DELETED", "");
            jdbc.sql("UPDATE messages SET status=:state,outcome=:outcome,content=:content,sources=CAST(:sources AS jsonb) WHERE id=:id")
                    .param("state", finalState).param("outcome", finalOutcome).param("content", finalContent)
                    .param("sources", sourceJson).param("id", started.assistant()).update();
            return new Result(started.generation(), started.assistant(), finalState, finalOutcome, finalContent);
        });
    }

    private ChatProvider recording(ChatProvider delegate, UUID owner, UUID message, String operation) {
        if (delegate == null) return null;
        var calls = new AtomicInteger();
        return (system, input) -> {
            ChatProvider.Reply reply = null;
            try { reply = delegate.complete(system, input); return reply; }
            finally { logUsage(owner, message, calls.getAndIncrement() == 0 ? operation : "CHAT_CITATION_REPAIR", GeminiChatProvider.MODEL, reply); }
        };
    }

    private void logUsage(UUID owner, UUID message, String operation, String model, ChatProvider.Reply reply) {
        tx.executeWithoutResult(status -> {
            UUID existingMessage = jdbc.sql("SELECT id FROM messages WHERE id=:id FOR KEY SHARE")
                    .param("id", message).query(UUID.class).optional().orElse(null);
            jdbc.sql("""
                    INSERT INTO usage_logs(id,user_id,message_id,operation,provider,model,prompt_tokens,completion_tokens)
                    VALUES (:id,:owner,:message,:operation,'GEMINI',:model,:prompt,:completion)
                    """).param("id", UUID.randomUUID()).param("owner", owner).param("message", existingMessage)
                    .param("operation", operation).param("model", model).param("prompt", reply == null ? null : reply.promptTokens())
                    .param("completion", reply == null ? null : reply.completionTokens()).update();
        });
    }

    private String hash(String question, RetrievalService.Scope scope, List<UUID> ids) {
        try {
            String input = json.writeValueAsString(java.util.Map.of("content", question, "scope_mode", scope.name(),
                    "documentIds", ids == null ? List.of() : ids.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
