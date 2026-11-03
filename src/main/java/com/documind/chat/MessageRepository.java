package com.documind.chat;

import java.util.UUID;
import org.springframework.data.repository.Repository;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;

public interface MessageRepository extends Repository<MessageEntity, UUID> {
    MessageEntity save(MessageEntity entity);
    @Query("select m from MessageEntity m, ConversationEntity c where m.id = :id and m.conversationId = c.id and c.userId = :userId and c.deletedRequestedAt is null")
    Optional<MessageEntity> findOwned(UUID id, UUID userId);
}
