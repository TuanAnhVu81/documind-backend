package com.documind.document;

import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "document_cleanup_tasks")
public class DocumentCleanupTaskEntity {
    @Id
    @Setter(AccessLevel.NONE)
    private UUID id;
    private UUID documentId;
    private int attempts;
    private Instant nextRunAt;
    private Instant createdAt;
    private String lastErrorCode;

    public DocumentCleanupTaskEntity(UUID id) { this.id = id; }

    @PrePersist
    void initializeDefaults() {
        if (createdAt == null) createdAt = Instant.now();
        if (nextRunAt == null) nextRunAt = createdAt;
    }

}
