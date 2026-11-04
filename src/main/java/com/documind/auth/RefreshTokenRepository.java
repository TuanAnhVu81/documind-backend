package com.documind.auth;

import java.util.UUID;
import org.springframework.data.repository.Repository;
import java.util.Optional;
import java.time.Instant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends Repository<RefreshTokenEntity, UUID> {
    RefreshTokenEntity save(RefreshTokenEntity entity);
    Optional<RefreshTokenEntity> findByUserIdAndTokenHash(UUID userId, String tokenHash);

    @Query("select r.familyId from RefreshTokenEntity r where r.tokenHash = :hash")
    Optional<UUID> findFamilyIdByTokenHash(String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshTokenEntity r where r.id = :familyId and r.familyId = :familyId")
    Optional<RefreshTokenEntity> lockFamily(UUID familyId);

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshTokenEntity r set r.revokedAt = :now where r.familyId = :familyId and r.revokedAt is null")
    int revokeFamily(UUID familyId, Instant now);
}
