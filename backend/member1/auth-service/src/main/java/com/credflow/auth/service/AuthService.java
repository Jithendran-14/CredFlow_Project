package com.credflow.auth.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.credflow.auth.dto.LoginRequest;
import com.credflow.auth.dto.LoginResponse;
import com.credflow.auth.entity.AuthUser;
import com.credflow.auth.exception.InvalidCredentialsException;
import com.credflow.auth.repository.AuthUserRepository;

@Service
public class AuthService {

	private final AuthUserRepository repository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;

	public AuthService(AuthUserRepository repository, PasswordEncoder passwordEncoder, JwtService jwtService) {
		this.repository = repository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
	}

	@Transactional(readOnly = true)
	public LoginResponse login(LoginRequest request) {
		AuthUser user = repository.findByUsernameIgnoreCase(request.username().trim())
				.orElseThrow(InvalidCredentialsException::new);

		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw new InvalidCredentialsException();
		}

		if (!"ACTIVE".equals(user.getAccountStatus())) {
			throw new InvalidCredentialsException();
		}

		String token = jwtService.generateToken(user);

		return new LoginResponse(token, "Bearer", jwtService.getExpirySeconds(), user.getPublicUserId(),
				user.getUsername(), user.getUserRole(), user.getSellerId(), user.getMarketplaceId());
	}
}
