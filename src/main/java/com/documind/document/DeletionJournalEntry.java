package com.documind.document;

import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Transactional outbox only; it is not a substitute for an external restore journal. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "deletion_journal_outbox")
public class DeletionJournalEntry {
    @Id private UUID id;
    private UUID documentId;
    private UUID userId;
    private String storageKey;
    private Instant requestedAt;
    @Setter
    private Instant exportedAt;

    public DeletionJournalEntry(UUID id, UUID documentId, UUID userId, String storageKey, Instant requestedAt) {
        this.id = id; this.documentId = documentId; this.userId = userId;
        this.storageKey = storageKey; this.requestedAt = requestedAt;
    }

    @PrePersist
    void initializeDefaults() {
        if (requestedAt == null) requestedAt = Instant.now();
    }

}
