
package com.credflow.application.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.credflow.application.entity.SellerMappingEntity;

public interface SellerMappingRepository extends JpaRepository<SellerMappingEntity, Long> {

	@org.springframework.data.jpa.repository.Query(value = "SELECT * FROM CF_SELLER_MAPPINGS WHERE SELLER_ID=:sellerId ORDER BY SELLER_MAPPING_ID FOR UPDATE", nativeQuery = true)
	java.util.List<SellerMappingEntity> lockSeller(
			@org.springframework.data.repository.query.Param("sellerId") String sellerId);

	Optional<SellerMappingEntity> findByMarketplaceIdAndMarketplaceSellerId(String marketplaceId,
			String marketplaceSellerId);
}
