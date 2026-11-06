package com.documind.document;

import java.util.List;
import java.util.UUID;

/** Published only after the source-deletion transaction commits. */
public record SourceGenerationsCancelled(List<UUID> generationIds) {
    public SourceGenerationsCancelled { generationIds = List.copyOf(generationIds); }
}
