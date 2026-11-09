package com.documind.usage.service;

import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** All-time account totals, including events whose source messages/jobs were deleted. */
@Service
public class UsageService {
    private final JdbcClient jdbc;
    public UsageService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Summary(Instant periodStart, Instant periodEnd, long questionCount,
            Long promptTokens, Long completionTokens, Long embeddingTokens,
            BigDecimal estimatedCostUsd, boolean estimated) {}

    public Summary summary(UUID owner) {
        return jdbc.sql("""
            SELECT u.created_at, statement_timestamp() AS period_end,
              (SELECT count(*) FROM question_events q WHERE q.user_id=u.id) AS questions,
              CASE WHEN count(l.id) FILTER (WHERE l.operation LIKE 'CHAT_%' OR l.operation LIKE '%RERANK%')=0 THEN 0
                   WHEN bool_or(l.prompt_tokens IS NULL) FILTER (WHERE l.operation LIKE 'CHAT_%' OR l.operation LIKE '%RERANK%') THEN NULL
                   ELSE sum(l.prompt_tokens) FILTER (WHERE l.operation LIKE 'CHAT_%' OR l.operation LIKE '%RERANK%') END AS prompt,
              CASE WHEN count(l.id) FILTER (WHERE l.operation LIKE 'CHAT_%' OR l.operation LIKE '%RERANK%')=0 THEN 0
                   WHEN bool_or(l.completion_tokens IS NULL) FILTER (WHERE l.operation LIKE 'CHAT_%' OR l.operation LIKE '%RERANK%') THEN NULL
                   ELSE sum(l.completion_tokens) FILTER (WHERE l.operation LIKE 'CHAT_%' OR l.operation LIKE '%RERANK%') END AS completion,
              CASE WHEN EXISTS (SELECT 1 FROM usage_untracked_history h WHERE h.user_id=u.id) THEN NULL
                   WHEN count(l.id) FILTER (WHERE l.operation LIKE '%EMBEDDING%')=0 THEN 0
                   WHEN bool_or(l.embedding_tokens IS NULL) FILTER (WHERE l.operation LIKE '%EMBEDDING%') THEN NULL
                   ELSE sum(l.embedding_tokens) FILTER (WHERE l.operation LIKE '%EMBEDDING%') END AS embedding,
              CASE WHEN EXISTS (SELECT 1 FROM usage_untracked_history h WHERE h.user_id=u.id) THEN NULL
                   WHEN count(l.id)=0 THEN 0 WHEN bool_or(l.estimated_cost_usd IS NULL) THEN NULL
                   ELSE sum(l.estimated_cost_usd) END AS cost,
              coalesce(bool_or(l.estimated),false) AS estimated
            FROM users u LEFT JOIN usage_logs l ON l.user_id=u.id
            WHERE u.id=:owner GROUP BY u.id,u.created_at
            """).param("owner", owner).query((rs, row) -> new Summary(
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("period_end").toInstant(),
                rs.getLong("questions"), asLong(rs.getBigDecimal("prompt")), asLong(rs.getBigDecimal("completion")),
                asLong(rs.getBigDecimal("embedding")), rs.getBigDecimal("cost"), rs.getBoolean("estimated")))
                .optional().orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static Long asLong(BigDecimal value) { return value == null ? null : value.longValueExact(); }
}
