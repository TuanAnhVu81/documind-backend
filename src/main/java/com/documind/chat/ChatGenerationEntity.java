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
@Table(name = "chat_generations")
public class ChatGenerationEntity {
    @Id
    @Setter(AccessLevel.NONE)
    private UUID id;
    private UUID conversationId;
    private UUID userMessageId;
    private UUID assistantMessageId;
    private UUID requestKey;
    private String requestHash;
    private String scopeMode;
    private UUID[] contextDocumentIds;
    private String status;
    private String outcome;
    private Instant heartbeatAt;
    private long generationVersion;
    private Instant createdAt;

    public ChatGenerationEntity(UUID id) { this.id = id; }

    @PrePersist
    void initializeDefaults() {
        if (createdAt == null) createdAt = Instant.now();
        if (contextDocumentIds == null) contextDocumentIds = new UUID[0];
    }

}
