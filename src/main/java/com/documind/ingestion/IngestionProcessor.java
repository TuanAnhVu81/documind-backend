package com.documind.ingestion;

import com.documind.retrieval.ChunkVectorStore;
import java.time.Duration;
import java.util.List;

/** Phase 6 supplies the parser/embedding implementation; processing must honor interruption. */
public interface IngestionProcessor {
    record Result(List<ChunkVectorStore.Chunk> chunks, Integer pageCount, Integer paragraphCount) {}
    Result process(JobClaimStore.Claim claim) throws Exception;

    final class ProcessingFailure extends Exception {
        private final boolean retryable;
        private final Duration retryAfter;
        public ProcessingFailure(boolean retryable, Duration retryAfter) {
            super("Ingestion failed"); this.retryable = retryable; this.retryAfter = retryAfter;
        }
        public boolean retryable() { return retryable; }
        public Duration retryAfter() { return retryAfter; }
    }
}
