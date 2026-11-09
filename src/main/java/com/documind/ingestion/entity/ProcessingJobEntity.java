package com.documind.ingestion.entity;

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
@Table(name = "processing_jobs")
public class ProcessingJobEntity {
    @Id
    @Setter(AccessLevel.NONE)
    private UUID id;
    private UUID documentId;
    private String status;
    private int attempts;
    private Instant nextRunAt;
    private String claimedBy;
    private UUID claimToken;
    private long leaseVersion;
    private Instant leaseUntil;
    private Instant heartbeatAt;
    private Instant createdAt;

    public ProcessingJobEntity(UUID id) { this.id = id; }

    @PrePersist
    void initializeDefaults() {
        if (createdAt == null) createdAt = Instant.now();
        if (nextRunAt == null) nextRunAt = createdAt;
    }

}
