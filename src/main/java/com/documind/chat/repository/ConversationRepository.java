package com.documind.chat.repository;

import com.documind.chat.entity.ConversationEntity;

import java.util.UUID;
import org.springframework.data.repository.Repository;
import java.util.Optional;

public interface ConversationRepository extends Repository<ConversationEntity, UUID> {
    ConversationEntity save(ConversationEntity entity);
    Optional<ConversationEntity> findByIdAndUserIdAndDeletedRequestedAtIsNull(UUID id, UUID userId);
}
