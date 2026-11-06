package com.documind.retrieval;

import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal service: owner comes from the authenticated principal, never a request field. */
@Service
public class RetrievalService {
    private final JdbcClient jdbc;
    private final ChunkVectorStore vectors;
    private final ObjectProvider<EmbeddingModel> models;
    private final int topK;
    private final double minScore;
    private final TransactionTemplate selectedTx;
    public RetrievalService(JdbcClient jdbc, ChunkVectorStore vectors, ObjectProvider<EmbeddingModel> models,
            PlatformTransactionManager manager, @Value("${documind.retrieval.top-k:5}") int topK,
            @Value("${documind.retrieval.min-score:-1}") double minScore) {
        if (topK < 1 || topK > 100 || !Double.isFinite(minScore) || minScore < -1 || minScore > 1)
            throw new IllegalArgumentException("Invalid retrieval configuration");
        this.jdbc = jdbc; this.vectors = vectors; this.models = models; this.topK = topK; this.minScore = minScore;
        this.selectedTx = new TransactionTemplate(manager);
        this.selectedTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.selectedTx.setTimeout(5);
    }

    public enum Scope { ALL_READY, SELECTED }
    public List<ChunkVectorStore.Hit> search(UUID owner, String question, Scope scope, List<UUID> ids) {
        return search(owner, question, scope, ids, () -> {});
    }
    public List<ChunkVectorStore.Hit> search(UUID owner, String question, Scope scope, List<UUID> ids, Runnable beforeEmbedding) {
        validate(owner, scope, ids);
        if (question == null || question.isBlank()) throw new AppException(ErrorCode.INVALID_REQUEST);
        if (scope == Scope.ALL_READY && !vectors.hasEligible(owner)) return List.of();
        var model = models.getIfAvailable();
        if (model == null) throw new IllegalStateException("Embedding is disabled");
        beforeEmbedding.run();
        var results = model.embedForResponse(List.of(GeminiEmbeddingModel.queryText(question))).getResults();
        if (results == null || results.size() != 1 || results.getFirst() == null || !Integer.valueOf(0).equals(results.getFirst().getIndex()))
            throw new GeminiEmbeddingModel.InvalidEmbedding();
        float[] embedding = results.getFirst().getOutput();
        if (scope == Scope.ALL_READY)
            return vectors.search(owner, null, embedding, topK, GeminiEmbeddingModel.MODEL, minScore);
        // Lock the selected documents only after the remote call; DELETE uses conflicting row locks.
        return selectedTx.execute(status -> {
            jdbc.sql("SELECT id FROM documents WHERE user_id=:owner AND id IN (:ids) ORDER BY id FOR SHARE")
                    .param("owner", owner).param("ids", ids).query(UUID.class).list();
            validate(owner, scope, ids);
            return vectors.search(owner, ids, embedding, topK, GeminiEmbeddingModel.MODEL, minScore);
        });
    }

    public void validate(UUID owner, Scope scope, List<UUID> ids) {
        if (owner == null || scope == null || (scope == Scope.ALL_READY && ids != null)
                || (scope == Scope.SELECTED && (ids == null || ids.isEmpty() || ids.size() > 20
                || ids.stream().anyMatch(java.util.Objects::isNull) || new HashSet<>(ids).size() != ids.size())))
            throw new AppException(ErrorCode.INVALID_DOCUMENT_SCOPE);
        if (scope == Scope.ALL_READY) return;
        var states = jdbc.sql("SELECT CASE WHEN deleted_requested_at IS NOT NULL THEN 'DELETING' ELSE status END FROM documents WHERE user_id=:owner AND id IN (:ids)")
                .param("owner", owner).param("ids", ids).query(String.class).list();
        if (states.size() != ids.size()) throw new AppException(ErrorCode.RESOURCE_NOT_FOUND);
        if (states.stream().anyMatch(status -> !"READY".equals(status))) throw new AppException(ErrorCode.DOCUMENT_NOT_READY);
        long incompatible = jdbc.sql("""
                SELECT count(*) FROM documents d WHERE d.user_id=:owner AND d.id IN (:ids)
                AND (NOT EXISTS (SELECT 1 FROM document_chunks c WHERE c.document_id=d.id AND c.user_id=d.user_id)
                OR EXISTS (SELECT 1 FROM document_chunks c WHERE c.document_id=d.id AND c.user_id=d.user_id
                           AND (c.embedding_model<>:model OR c.pipeline_version<>:pipeline)))
                """).param("owner", owner).param("ids", ids).param("model", GeminiEmbeddingModel.MODEL)
                .param("pipeline", GeminiEmbeddingModel.PIPELINE_VERSION).query(Long.class).single();
        if (incompatible > 0) throw new AppException(ErrorCode.DOCUMENT_NOT_READY);
    }
}
