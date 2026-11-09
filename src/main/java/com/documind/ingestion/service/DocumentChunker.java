package com.documind.ingestion.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DocumentChunker {
    public record Part(String text, Integer page, Integer paragraph, Integer start, Integer end) {}
    private final int size, overlap, maxChunks;

    public DocumentChunker(@Value("${documind.ingestion.chunk-size:1000}") int size,
                           @Value("${documind.ingestion.chunk-overlap:150}") int overlap,
                           @Value("${documind.parser.max-chunks:5000}") int maxChunks) {
        if (size < 1 || overlap < 0 || overlap >= size || maxChunks < 1) throw new IllegalArgumentException("Invalid chunk settings");
        this.size = size; this.overlap = overlap; this.maxChunks = maxChunks;
    }

    public List<Part> split(DocumentExtractor.Extracted extracted) throws DocumentExtractor.ExtractionFailure, InterruptedException {
        List<Part> result = new ArrayList<>();
        for (var section : extracted.sections()) {
            String text = section.text(); int total = text.codePointCount(0, text.length()); int start = 0;
            int[] positions = new int[total + 1];
            for (int i = 0, pos = 0; i < total; i++) {
                if ((i & 4095) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedException("Chunking interrupted");
                positions[i] = pos;
                pos += Character.charCount(text.codePointAt(pos));
                positions[i + 1] = pos;
            }
            while (start < total) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Chunking interrupted");
                int end = Math.min(total, start + size);
                if (end < total) {
                    int min = start + size / 2;
                    for (int i = end; i > min; i--) {
                        if ((i & 4095) == 0 && Thread.currentThread().isInterrupted()) throw new InterruptedException("Chunking interrupted");
                        int cp = text.codePointAt(positions[i - 1]);
                        if (Character.isWhitespace(cp) || cp == '.' || cp == '!' || cp == '?') { end = i; break; }
                    }
                }
                String piece = text.substring(positions[start], positions[end]);
                if (!piece.isBlank()) {
                    if (result.size() >= maxChunks) throw new DocumentExtractor.ExtractionFailure("CHUNK_LIMIT");
                    result.add(new Part(piece, section.page(), section.paragraph(),
                            section.paragraph() == null ? null : start, section.paragraph() == null ? null : end));
                }
                if (end == total) break;
                start = Math.max(start + 1, end - overlap);
            }
        }
        return List.copyOf(result);
    }
}
