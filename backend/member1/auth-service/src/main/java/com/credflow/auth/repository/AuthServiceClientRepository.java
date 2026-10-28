package com.credflow.auth.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.credflow.auth.entity.AuthServiceClient;

public interface AuthServiceClientRepository extends JpaRepository<AuthServiceClient, Long> {

	Optional<AuthServiceClient> findByClientId(String clientId);
}