package com.documind.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.documind.retrieval.ChunkVectorStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GeminiRerankerTest {
    private final GeminiReranker reranker = new GeminiReranker(JsonMapper.builder().build());
    private final List<ChunkVectorStore.Hit> hits = List.of(hit("first"), hit("second"));

    private static ChunkVectorStore.Hit hit(String text) {
        return new ChunkVectorStore.Hit(UUID.randomUUID(), UUID.randomUUID(), "demo.txt", 0,
                null, 1, 0, text.length(), text, 0.2);
    }
    @Test void ordersOnlySuppliedCandidatesAndSkipsUnnecessaryCalls() {
        var calls = new AtomicInteger();
        ChatProvider provider = (system, input) -> {
            calls.incrementAndGet();
            assertThat(input).contains("first", "second");
            return new ChatProvider.Reply("{\"order\":[1,0]}", 20L, 5L);
        };
        assertThat(reranker.rerank("question", hits, provider)).containsExactly(hits.get(1), hits.get(0));
        assertThat(reranker.rerank("question", hits.subList(0, 1), provider)).containsExactly(hits.get(0));
        assertThat(calls).hasValue(1);
    }
    @Test void rejectsInventedDuplicateAndMalformedIndices() {
        for (String value : List.of("{\"order\":[0,0]}", "{\"order\":[0,2]}", "{\"order\":[0]}", "not json")) {
            ChatProvider provider = (system, input) -> new ChatProvider.Reply(value, null, null);
            assertThatThrownBy(() -> reranker.rerank("question", hits, provider))
                    .isInstanceOf(GeminiChatProvider.ProviderException.class);
        }
    }
    @Test void boundsPromptAndKeepsRemainingCandidatesInTheirOriginalOrder() {
        var oversized = hit("x".repeat(65_536));
        var candidates = List.of(hits.get(0), hits.get(1), oversized);
        ChatProvider provider = (system, input) -> {
            assertThat(input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(65_536);
            assertThat(input).doesNotContain(oversized.content());
            return new ChatProvider.Reply("{\"order\":[1,0]}", 10L, 2L);
        };
        assertThat(reranker.rerank("question", candidates, provider))
                .containsExactly(hits.get(1), hits.get(0), oversized);
    }
}
