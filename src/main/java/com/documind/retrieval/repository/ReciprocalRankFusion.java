package com.documind.retrieval.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** RRF uses ranks, never treats the fusion score as cosine similarity. */
final class ReciprocalRankFusion {
    private static final int RANK_OFFSET = 60;
    private ReciprocalRankFusion() {}

    static List<ChunkVectorStore.Hit> combine(List<ChunkVectorStore.Hit> vector,
                                               List<ChunkVectorStore.Hit> keyword, int limit) {
        Map<UUID, ChunkVectorStore.Hit> hits = new HashMap<>();
        Map<UUID, Double> scores = new HashMap<>();
        add(vector, hits, scores);
        add(keyword, hits, scores);
        return hits.values().stream()
                .sorted((a, b) -> {
                    int rank = Double.compare(scores.get(b.id()), scores.get(a.id()));
                    return rank != 0 ? rank : a.id().compareTo(b.id());
                }).limit(limit).toList();
    }

    private static void add(List<ChunkVectorStore.Hit> ranked, Map<UUID, ChunkVectorStore.Hit> hits,
                            Map<UUID, Double> scores) {
        for (int i = 0; i < ranked.size(); i++) {
            var hit = ranked.get(i);
            hits.putIfAbsent(hit.id(), hit);
            scores.merge(hit.id(), 1.0 / (RANK_OFFSET + i + 1), Double::sum);
        }
    }
}
