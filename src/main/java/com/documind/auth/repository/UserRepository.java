package com.documind.auth.repository;

import com.documind.auth.entity.UserEntity;

import java.util.UUID;
import org.springframework.data.repository.Repository;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends Repository<UserEntity, UUID> {
    UserEntity save(UserEntity entity);
    @Query("select u from UserEntity u where lower(trim(u.email)) = :email")
    Optional<UserEntity> findByEmail(String email);
    Optional<UserEntity> findById(UUID id);
}
