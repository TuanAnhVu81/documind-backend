package com.documind.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {
    private static ChunkVectorStore.Hit hit(UUID id) {
        return new ChunkVectorStore.Hit(id, UUID.randomUUID(), "demo.txt", 0,
                null, 1, 0, 1, "demo", 0.25);
    }

    @Test void mergesRanksDeduplicatesAndUsesStableTies() {
        var first = hit(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        var second = hit(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        var shared = hit(UUID.fromString("00000000-0000-0000-0000-000000000003"));
        assertThat(ReciprocalRankFusion.combine(List.of(first, shared), List.of(second, shared), 3))
                .extracting(ChunkVectorStore.Hit::id).containsExactly(shared.id(), first.id(), second.id());
        assertThat(ReciprocalRankFusion.combine(List.of(first), List.of(), 1)).containsExactly(first);
        assertThat(ReciprocalRankFusion.combine(List.of(), List.of(), 1)).isEmpty();
    }
}
