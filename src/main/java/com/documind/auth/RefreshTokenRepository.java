package com.documind.auth;

import java.util.UUID;
import org.springframework.data.repository.Repository;
import java.util.Optional;

public interface RefreshTokenRepository extends Repository<RefreshTokenEntity, UUID> {
    RefreshTokenEntity save(RefreshTokenEntity entity);
    Optional<RefreshTokenEntity> findByUserIdAndTokenHash(UUID userId, String tokenHash);
}
