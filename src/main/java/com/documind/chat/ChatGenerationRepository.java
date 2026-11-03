package com.documind.chat;

import java.util.UUID;
import org.springframework.data.repository.Repository;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;

public interface ChatGenerationRepository extends Repository<ChatGenerationEntity, UUID> {
    ChatGenerationEntity save(ChatGenerationEntity entity);
    @Query("select g from ChatGenerationEntity g, ConversationEntity c where g.id = :id and g.conversationId = c.id and c.userId = :userId and c.deletedRequestedAt is null")
    Optional<ChatGenerationEntity> findOwned(UUID id, UUID userId);
}
