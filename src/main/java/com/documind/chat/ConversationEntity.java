package com.documind.chat;

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
@Table(name = "conversations")
public class ConversationEntity {
    @Id
    @Setter(AccessLevel.NONE)
    private UUID id;
    private UUID userId;
    private String title;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedRequestedAt;

    public ConversationEntity(UUID id) { this.id = id; }

    @PrePersist
    void initializeDefaults() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }

}
