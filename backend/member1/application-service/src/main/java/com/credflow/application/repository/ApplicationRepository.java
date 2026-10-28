package com.credflow.application.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.credflow.application.entity.ApplicationEntity;

public interface ApplicationRepository extends JpaRepository<ApplicationEntity, Long> {

	org.springframework.data.domain.Page<ApplicationEntity> findBySellerId(String sellerId,
			org.springframework.data.domain.Pageable pageable);

	org.springframework.data.domain.Page<ApplicationEntity> findByMarketplaceId(String marketplaceId,
			org.springframework.data.domain.Pageable pageable);

	Optional<ApplicationEntity> findByPublicApplicationId(String publicApplicationId);

	Optional<ApplicationEntity> findByMarketplaceIdAndMarketplaceRequestId(String marketplaceId,
			String marketplaceRequestId);

	java.util.Optional<ApplicationEntity> findFirstBySellerIdAndStatusNotInOrderByCreatedAtDesc(String sellerId,
			java.util.Collection<String> statuses);

	java.util.Optional<ApplicationEntity> findFirstBySellerIdAndLoanPurposeAndStatusNotInOrderByCreatedAtDesc(
			String sellerId, String loanPurpose, java.util.Collection<String> statuses);

	boolean existsByApplicationReference(String applicationReference);
}