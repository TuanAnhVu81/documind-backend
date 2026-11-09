package com.documind.ingestion.service;

import com.documind.retrieval.repository.ChunkVectorStore;
import java.time.Duration;
import java.util.List;

/** Phase 6 supplies the parser/embedding implementation; processing must honor interruption. */
public interface IngestionProcessor {
    record Result(List<ChunkVectorStore.Chunk> chunks, Integer pageCount, Integer paragraphCount) {}
    Result process(JobClaimStore.Claim claim) throws Exception;

    final class ProcessingFailure extends Exception {
        private final boolean retryable;
        private final Duration retryAfter;
        private final String code;
        public ProcessingFailure(boolean retryable, Duration retryAfter) {
            this(retryable, retryAfter, "INGESTION_FAILED");
        }
        public ProcessingFailure(boolean retryable, Duration retryAfter, String code) {
            super("Ingestion failed"); this.retryable = retryable; this.retryAfter = retryAfter; this.code = code;
        }
        public boolean retryable() { return retryable; }
        public Duration retryAfter() { return retryAfter; }
        public String code() { return code; }
    }
}
