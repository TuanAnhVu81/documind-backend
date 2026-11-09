package com.documind.chat.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "messages")
public class MessageEntity {
    @Id
    @Setter(AccessLevel.NONE)
    private UUID id;
    private UUID conversationId;
    private long ordinal;
    private String role;
    private String content;
    @JdbcTypeCode(SqlTypes.JSON)
    private String sources;
    private String status;
    private String outcome;
    private Instant createdAt;

    public MessageEntity(UUID id) { this.id = id; }

    @PrePersist
    void initializeDefaults() {
        if (createdAt == null) createdAt = Instant.now();
        if (content == null) content = "";
        if (sources == null) sources = "[]";
    }

}
