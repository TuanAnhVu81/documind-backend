package com.documind.chat;

import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import com.documind.retrieval.RetrievalService;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Serializes admission and checks replay before spending a rate-limit slot. */
@Service
public class ChatAdmissionService {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ChatRateLimiter limiter;
    private final ChatTurnService turns;
    private final RetrievalService retrieval;
    private final tools.jackson.databind.json.JsonMapper json;

    public ChatAdmissionService(JdbcClient jdbc, PlatformTransactionManager manager, ChatRateLimiter limiter,
            ChatTurnService turns, RetrievalService retrieval, tools.jackson.databind.json.JsonMapper json) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.limiter = limiter;
        this.turns = turns; this.retrieval = retrieval; this.json = json;
    }

    public record Previous(UUID generation, UUID userMessage, UUID assistant, String status, String hash) {}
    public record Admission(ChatTurnService.Started started, Previous replay) {}

    public Admission admit(UUID owner, UUID conversation, UUID requestKey, ChatRequest request) {
        if (owner == null || conversation == null || requestKey == null || request == null) throw new AppException(ErrorCode.INVALID_REQUEST);
        return limiter.admit(owner, retry -> tx.execute(status -> {
            if (jdbc.sql("SELECT id FROM conversations WHERE id=:id AND user_id=:owner AND deleted_requested_at IS NULL FOR UPDATE")
                    .param("id", conversation).param("owner", owner).query(UUID.class).optional().isEmpty())
                throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
            var previous = jdbc.sql("""
                    SELECT id,user_message_id,assistant_message_id,status,request_hash FROM chat_generations
                    WHERE conversation_id=:c AND request_key=:key
                    """).param("c", conversation).param("key", requestKey).query((rs, row) -> new Previous(
                    rs.getObject("id", UUID.class), rs.getObject("user_message_id", UUID.class),
                    rs.getObject("assistant_message_id", UUID.class), rs.getString("status"), rs.getString("request_hash"))).optional();
            if (previous.isPresent()) {
                var found = previous.get();
                if (!request.matchesStoredHash(found.hash(), json)) throw new ChatConflict(ErrorCode.IDEMPOTENCY_KEY_REUSED, null, null, null);
                if ("GENERATING".equals(found.status()))
                    throw new ChatConflict(ErrorCode.GENERATION_IN_PROGRESS, found.generation(), found.assistant(), found.status());
                if (!"COMPLETE".equals(found.status()))
                    throw new ChatConflict(ErrorCode.GENERATION_NOT_REPLAYABLE, found.generation(), found.assistant(), found.status());
                return new ChatRateLimiter.Decision<>(new Admission(null, found), false);
            }
            var active = jdbc.sql("SELECT id,assistant_message_id FROM chat_generations WHERE conversation_id=:c AND status='GENERATING'")
                    .param("c", conversation).query((rs, row) -> new UUID[]{rs.getObject("id", UUID.class), rs.getObject("assistant_message_id", UUID.class)}).optional();
            if (active.isPresent()) throw new ChatConflict(ErrorCode.GENERATION_IN_PROGRESS, active.get()[0], active.get()[1], "GENERATING");
            retrieval.validate(owner, request.scope(), request.documentIds());
            if (retry > 0) throw new ChatRateLimiter.Limited(retry);
            var started = turns.start(owner, conversation, requestKey, request.content(), request.scope(), request.documentIds(), request.hash());
            return new ChatRateLimiter.Decision<>(new Admission(started, null), true);
        }));
    }
}
