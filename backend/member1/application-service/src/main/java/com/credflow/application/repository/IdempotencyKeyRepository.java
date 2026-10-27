package com.credflow.application.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.credflow.application.entity.IdempotencyKeyEntity;

public interface IdempotencyKeyRepository
        extends JpaRepository<IdempotencyKeyEntity, Long> {

    Optional<IdempotencyKeyEntity> findByMarketplaceIdAndIdempotencyKey(
            String marketplaceId,
            String idempotencyKey
    );
}
