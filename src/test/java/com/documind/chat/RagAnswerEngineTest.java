package com.documind.chat;

import static org.assertj.core.api.Assertions.assertThat;
import com.documind.retrieval.ChunkVectorStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RagAnswerEngineTest {
    private final RagAnswerEngine engine = new RagAnswerEngine(JsonMapper.builder().build(), new CitationValidator());
    private final List<ChunkVectorStore.Hit> hits = List.of(new ChunkVectorStore.Hit(UUID.randomUUID(), UUID.randomUUID(),
            "file.txt", 0, null, 1, 0, 4, "text", 0.1));

    @Test void noEvidenceNeverCallsProvider() {
        var result = engine.answer("question", "question", List.of(), "không có bằng chứng", "không hợp lệ", null);
        assertThat(result.outcome()).isEqualTo("NO_EVIDENCE");
        assertThat(result.sources()).isEmpty(); assertThat(result.calls()).isEmpty();
    }

    @Test void repairsOnceAndFailsClosedWhenStillInvalid() {
        var calls = new AtomicInteger();
        ChatProvider provider = (system, input) -> new ChatProvider.Reply(calls.getAndIncrement() == 0 ? "Không nguồn." : "Có nguồn [S1].", 1L, 1L);
        var result = engine.answer("question", "question", hits, "không có bằng chứng", "không hợp lệ", provider);
        assertThat(result.outcome()).isEqualTo("ANSWER"); assertThat(result.sources()).extracting(RagAnswerEngine.Source::sourceId).containsExactly("S1");
        assertThat(result.calls()).hasSize(2);
        ChatProvider bad = (system, input) -> new ChatProvider.Reply("Sai [S9].", null, null);
        var rejected = engine.answer("question", "question", hits, "không có bằng chứng", "không hợp lệ", bad);
        assertThat(rejected.outcome()).isEqualTo("CITATION_VALIDATION_FAILED");
        assertThat(rejected.content()).isEqualTo("không hợp lệ"); assertThat(rejected.sources()).isEmpty();
        assertThat(rejected.calls()).hasSize(2);
    }

    @Test void irrelevantRetrievedSourcesCanBeRejectedWithoutInventingCitations() {
        ChatProvider provider = (system, input) -> new ChatProvider.Reply("NO_EVIDENCE", 3L, 1L);
        var result = engine.answer("question", "question", hits, "no evidence", "invalid", provider);
        assertThat(result.outcome()).isEqualTo("NO_EVIDENCE");
        assertThat(result.sources()).isEmpty();
        assertThat(result.calls()).hasSize(1);
    }
}
