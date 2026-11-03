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
@Table(name = "documents")
public class DocumentEntity {
    @Id
    @Setter(AccessLevel.NONE)
    private UUID id;
    private UUID userId;
    private String filename;
    private String storageKey;
    private String mediaType;
    private long sizeBytes;
    private String status;
    private Integer pageCount;
    private Integer paragraphCount;
    private Integer chunkCount;
    private String errorCode;
    private String errorMessage;
    private Instant createdAt;
    private Instant deletedRequestedAt;

    public DocumentEntity(UUID id) { this.id = id; }

    @PrePersist
    void initializeDefaults() {
        if (createdAt == null) createdAt = Instant.now();
    }

}
