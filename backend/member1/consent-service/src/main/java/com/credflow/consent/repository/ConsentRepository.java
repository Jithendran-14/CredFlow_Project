package com.credflow.consent.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.credflow.consent.entity.ConsentEntity;

import jakarta.persistence.LockModeType;

public interface ConsentRepository
        extends JpaRepository<ConsentEntity, String> {

    Optional<ConsentEntity> findByApplicationId(String applicationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select consent
            from ConsentEntity consent
            where consent.applicationId = :applicationId
            """)
    Optional<ConsentEntity> findForUpdateByApplicationId(
            @Param("applicationId") String applicationId
    );
}