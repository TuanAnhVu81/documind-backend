package com.documind.document;

import java.util.UUID;
import org.springframework.data.repository.Repository;
import java.util.Optional;

public interface DocumentRepository extends Repository<DocumentEntity, UUID> {
    DocumentEntity save(DocumentEntity entity);
    Optional<DocumentEntity> findByIdAndUserId(UUID id, UUID userId);
}
