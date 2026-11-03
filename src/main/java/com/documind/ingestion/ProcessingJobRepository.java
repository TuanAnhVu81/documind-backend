package com.documind.ingestion;

import java.util.UUID;
import org.springframework.data.repository.Repository;
import java.util.Optional;

public interface ProcessingJobRepository extends Repository<ProcessingJobEntity, UUID> {
    ProcessingJobEntity save(ProcessingJobEntity entity);
    Optional<ProcessingJobEntity> findByDocumentId(UUID documentId);
}
