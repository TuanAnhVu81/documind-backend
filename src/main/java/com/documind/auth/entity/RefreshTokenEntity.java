package com.documind.auth.entity;

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
@Table(name = "refresh_tokens")
public class RefreshTokenEntity {
    @Id
    @Setter(AccessLevel.NONE)
    private UUID id;
    private UUID userId;
    private UUID familyId;
    private String tokenHash;
    private Instant expiresAt;
    private Instant consumedAt;
    private Instant revokedAt;
    private UUID replacedBy;
    private Instant createdAt;

    public RefreshTokenEntity(UUID id) { this.id = id; }

    @PrePersist
    void initializeDefaults() {
        if (createdAt == null) createdAt = Instant.now();
    }

}
