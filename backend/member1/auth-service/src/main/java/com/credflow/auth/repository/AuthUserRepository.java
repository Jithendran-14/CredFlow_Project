package com.credflow.auth.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.credflow.auth.entity.AuthUser;

public interface AuthUserRepository extends JpaRepository<AuthUser, Long> {

	Optional<AuthUser> findByUsernameIgnoreCase(String username);

	boolean existsByUsernameIgnoreCase(String username);

	boolean existsByEmailAddressIgnoreCase(String emailAddress);

	boolean existsByUserRole(String userRole);
}
