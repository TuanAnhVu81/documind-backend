package com.documind.document.repository;

import com.documind.document.entity.DocumentCleanupTaskEntity;

import java.util.UUID;
import org.springframework.data.repository.Repository;
import java.util.Optional;

public interface DocumentCleanupTaskRepository extends Repository<DocumentCleanupTaskEntity, UUID> {
    DocumentCleanupTaskEntity save(DocumentCleanupTaskEntity entity);
    Optional<DocumentCleanupTaskEntity> findByDocumentId(UUID documentId);
}
