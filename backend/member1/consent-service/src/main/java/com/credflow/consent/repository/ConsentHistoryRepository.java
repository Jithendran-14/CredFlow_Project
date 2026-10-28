package com.credflow.consent.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.credflow.consent.entity.ConsentHistoryEntity;
import com.credflow.consent.entity.ConsentHistoryId;

public interface ConsentHistoryRepository extends JpaRepository<ConsentHistoryEntity, ConsentHistoryId> {

	Optional<ConsentHistoryEntity> findByOperationId(String operationId);
}