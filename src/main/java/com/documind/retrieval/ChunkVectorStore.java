package com.documind.retrieval;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owner must come from the authenticated principal, never the request body. */
@Repository
public class ChunkVectorStore {
    private static final int DIMENSIONS = 1536;
    private final JdbcClient jdbc;

    public ChunkVectorStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Chunk(UUID id, int chunkIndex, Integer pageNumber, Integer paragraphIndex,
                        Integer startOffset, Integer endOffset, String content,
                        float[] embedding, String embeddingModel, String pipelineVersion) {}

    public record Hit(UUID id, UUID documentId, int chunkIndex, Integer pageNumber,
                      Integer paragraphIndex, Integer startOffset, Integer endOffset,
                      String content, double distance) {}

    /** Caller must own a short transaction and validate the worker lease before replacing chunks. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean replace(UUID owner, UUID documentId, List<Chunk> chunks) {
        if (owner == null || documentId == null || chunks == null) throw new IllegalArgumentException("Invalid chunks");
        // Check all vectors before deleting any rows. SQL constraints validate locations and indices.
        for (Chunk chunk : chunks) {
            if (chunk == null || chunk.id() == null || chunk.content() == null || chunk.embeddingModel() == null
                    || chunk.pipelineVersion() == null) throw new IllegalArgumentException("Invalid chunk");
            asVector(chunk.embedding());
        }
        boolean locked = jdbc.sql("""
                SELECT id FROM documents WHERE id=:document AND user_id=:owner
                AND status <> 'DELETING' AND deleted_requested_at IS NULL FOR UPDATE
                """).param("document", documentId).param("owner", owner).query(UUID.class).optional().isPresent();
        if (!locked) return false;
        jdbc.sql("DELETE FROM document_chunks WHERE document_id=:document AND user_id=:owner")
                .param("document", documentId).param("owner", owner).update();
        for (Chunk chunk : chunks) {
            jdbc.sql("""
                    INSERT INTO document_chunks(id,document_id,user_id,chunk_index,page_number,paragraph_index,
                        start_offset,end_offset,content,embedding,embedding_model,pipeline_version)
                    VALUES (:id,:document,:owner,:idx,:page,:paragraph,:start,:end,:content,
                        CAST(:embedding AS vector),:model,:pipeline)
                    """).param("id", chunk.id()).param("document", documentId).param("owner", owner)
                    .param("idx", chunk.chunkIndex()).param("page", chunk.pageNumber())
                    .param("paragraph", chunk.paragraphIndex()).param("start", chunk.startOffset())
                    .param("end", chunk.endOffset()).param("content", chunk.content())
                    .param("embedding", asVector(chunk.embedding())).param("model", chunk.embeddingModel())
                    .param("pipeline", chunk.pipelineVersion()).update();
        }
        return true;
    }

    public List<Hit> search(UUID owner, List<UUID> selectedDocuments, float[] embedding, int limit) {
        if (owner == null || limit < 1 || limit > 100 || (selectedDocuments != null && selectedDocuments.isEmpty()))
            throw new IllegalArgumentException("Invalid vector search parameters");
        String vector = asVector(embedding);
        String filter = selectedDocuments == null ? "" : " AND d.id IN (:documents)";
        var query = jdbc.sql("""
            SELECT c.id,c.document_id,c.chunk_index,c.page_number,c.paragraph_index,c.start_offset,c.end_offset,
                   c.content,c.embedding <=> CAST(:vector AS vector) AS distance
            FROM document_chunks c JOIN documents d ON d.id=c.document_id AND d.user_id=c.user_id
            WHERE c.user_id=:owner AND d.user_id=:owner AND d.status='READY' AND d.deleted_requested_at IS NULL
            """ + filter + " ORDER BY c.embedding <=> CAST(:vector AS vector) LIMIT :limit")
                .param("owner", owner).param("vector", vector).param("limit", limit);
        if (selectedDocuments != null) query.param("documents", selectedDocuments);
        return query.query((rs, row) -> new Hit(rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getInt("chunk_index"),
                rs.getObject("page_number", Integer.class), rs.getObject("paragraph_index", Integer.class),
                rs.getObject("start_offset", Integer.class), rs.getObject("end_offset", Integer.class),
                rs.getString("content"), rs.getDouble("distance"))).list();
    }

    private static String asVector(float[] embedding) {
        if (embedding == null || embedding.length != DIMENSIONS) throw new IllegalArgumentException("Invalid vector dimension");
        boolean nonzero = false;
        for (float value : embedding) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite vector");
            nonzero |= value != 0;
        }
        if (!nonzero) throw new IllegalArgumentException("Zero vector cannot be searched with cosine");
        return IntStream.range(0, embedding.length).mapToObj(i -> Float.toString(embedding[i]))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
