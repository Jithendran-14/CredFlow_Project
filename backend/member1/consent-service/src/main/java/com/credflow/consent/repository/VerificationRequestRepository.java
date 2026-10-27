package com.credflow.consent.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.credflow.consent.entity.VerificationRequestEntity;

public interface VerificationRequestRepository
        extends JpaRepository<VerificationRequestEntity, String> {
}