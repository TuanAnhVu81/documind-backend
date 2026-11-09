package com.documind.usage.repository;

import com.documind.usage.entity.UsageLogEntity;

import java.util.UUID;
import org.springframework.data.repository.Repository;
import java.util.List;

public interface UsageLogRepository extends Repository<UsageLogEntity, UUID> {
    UsageLogEntity save(UsageLogEntity entity);
    List<UsageLogEntity> findByUserId(UUID userId);
}
