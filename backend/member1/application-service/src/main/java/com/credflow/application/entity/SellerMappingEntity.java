
package com.credflow.application.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "CF_SELLER_MAPPINGS")
public class SellerMappingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(
        name = "SELLER_MAPPING_ID",
        nullable = false,
        updatable = false
    )
    private Long sellerMappingId;

    @Column(
        name = "MARKETPLACE_ID",
        length = 36,
        nullable = false
    )
    private String marketplaceId;

    @Column(
        name = "MARKETPLACE_SELLER_ID",
        length = 100,
        nullable = false
    )
    private String marketplaceSellerId;

    @Column(
        name = "SELLER_ID",
        length = 100,
        nullable = false
    )
    private String sellerId;

    @Column(
        name = "CREATED_AT",
        nullable = false,
        insertable = false,
        updatable = false
    )
    private OffsetDateTime createdAt;

    protected SellerMappingEntity() {
        // Required by JPA.
    }

    public Long getSellerMappingId() {
        return sellerMappingId;
    }

    public String getMarketplaceId() {
        return marketplaceId;
    }

    public String getMarketplaceSellerId() {
        return marketplaceSellerId;
    }

    public String getSellerId() {
        return sellerId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
