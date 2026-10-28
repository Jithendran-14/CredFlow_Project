package com.credflow.auth.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.credflow.auth.dto.CreateUserRequest;
import com.credflow.auth.repository.AuthUserRepository;
import com.credflow.auth.service.AdminUserService;

@Component
public class AdminBootstrapRunner implements CommandLineRunner {

	private final AuthUserRepository repository;
	private final AdminUserService adminUserService;

	@Value("${credflow.bootstrap-admin.enabled:false}")
	private boolean enabled;

	@Value("${CREDFLOW_BOOTSTRAP_ADMIN_USERNAME:}")
	private String username;

	@Value("${CREDFLOW_BOOTSTRAP_ADMIN_EMAIL:}")
	private String email;

	@Value("${CREDFLOW_BOOTSTRAP_ADMIN_PASSWORD:}")
	private String password;

	public AdminBootstrapRunner(AuthUserRepository repository, AdminUserService adminUserService) {
		this.repository = repository;
		this.adminUserService = adminUserService;
	}

	@Override
	public void run(String... args) {
		if (!enabled) {
			return;
		}

		if (repository.existsByUserRole("ADMIN")) {
			return;
		}

		if (username.isBlank() || email.isBlank() || password.length() < 12 || password.length() > 128) {
			throw new IllegalStateException("Admin bootstrap is enabled, but valid bootstrap credentials are missing.");
		}

		adminUserService.createUser(new CreateUserRequest(username, email, password, "ADMIN", null, null));
	}
}
