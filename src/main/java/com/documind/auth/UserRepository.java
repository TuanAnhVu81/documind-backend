package com.documind.auth;

import java.util.UUID;
import org.springframework.data.repository.Repository;
import java.util.Optional;

public interface UserRepository extends Repository<UserEntity, UUID> {
    UserEntity save(UserEntity entity);
    Optional<UserEntity> findByEmail(String email);
}
