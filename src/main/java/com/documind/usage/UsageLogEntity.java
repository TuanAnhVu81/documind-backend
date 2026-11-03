package com.documind.usage;

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
import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "usage_logs")
public class UsageLogEntity {
    @Id
    @Setter(AccessLevel.NONE)
    private UUID id;
    private UUID userId;
    private UUID messageId;
    private UUID jobId;
    private String operation;
    private String provider;
    private String model;
    private Long promptTokens;
    private Long completionTokens;
    private Long embeddingTokens;
    private BigDecimal estimatedCostUsd;
    @Getter(AccessLevel.NONE)
    private boolean estimated;
    private Instant createdAt;

    public UsageLogEntity(UUID id) { this.id = id; }

    public boolean getEstimated() { return estimated; }

    @PrePersist
    void initializeDefaults() {
        if (createdAt == null) createdAt = Instant.now();
    }

}
