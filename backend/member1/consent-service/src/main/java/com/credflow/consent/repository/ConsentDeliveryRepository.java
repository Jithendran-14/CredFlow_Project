package com.credflow.consent.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.credflow.consent.entity.ConsentDeliveryEntity;

public interface ConsentDeliveryRepository
        extends JpaRepository<ConsentDeliveryEntity, String> {
}