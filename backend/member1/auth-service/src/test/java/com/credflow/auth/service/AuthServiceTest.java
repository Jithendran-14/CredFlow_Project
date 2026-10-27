
package com.credflow.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.AfterEach;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.credflow.auth.dto.LoginRequest;
import com.credflow.auth.dto.LoginResponse;
import com.credflow.auth.entity.AuthUser;
import com.credflow.auth.exception.InvalidCredentialsException;
import com.credflow.auth.repository.AuthUserRepository;

class AuthServiceTest {
	@AfterEach
	void tearDown() throws Exception {
	    mocks.close();
	}

    @Mock
    private AuthUserRepository repository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    private AuthService authService;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);

        authService = new AuthService(
                repository,
                passwordEncoder,
                jwtService
        );
    }

    @Test
    void activeUserWithCorrectPasswordShouldLogin() {

        AuthUser user = createUser("ACTIVE");

        when(repository.findByUsernameIgnoreCase("seller1001"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches("correct-password", "stored-hash"))
                .thenReturn(true);

        when(jwtService.generateToken(user))
                .thenReturn("test-jwt");

        when(jwtService.getExpirySeconds())
                .thenReturn(900L);

        LoginResponse response = authService.login(
                new LoginRequest("seller1001", "correct-password")
        );

        assertEquals("test-jwt", response.accessToken());

        verify(jwtService).generateToken(user);
    }

    @Test
    void incorrectPasswordShouldBeRejected() {

        AuthUser user = createUser("ACTIVE");

        when(repository.findByUsernameIgnoreCase("seller1001"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches("wrong-password", "stored-hash"))
                .thenReturn(false);

        assertThrows(
                InvalidCredentialsException.class,
                () -> authService.login(
                        new LoginRequest("seller1001", "wrong-password")
                )
        );

        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void unknownUsernameShouldBeRejected() {

        when(repository.findByUsernameIgnoreCase("unknown"))
                .thenReturn(Optional.empty());

        assertThrows(
                InvalidCredentialsException.class,
                () -> authService.login(
                        new LoginRequest("unknown", "password")
                )
        );

        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void lockedAccountShouldBeRejected() {

        AuthUser user = createUser("LOCKED");

        when(repository.findByUsernameIgnoreCase("seller1001"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches("correct-password", "stored-hash"))
                .thenReturn(true);

        assertThrows(
                InvalidCredentialsException.class,
                () -> authService.login(
                        new LoginRequest("seller1001", "correct-password")
                )
        );

        verify(jwtService, never()).generateToken(any());
    }

    @Test
    void disabledAccountShouldBeRejected() {

        AuthUser user = createUser("DISABLED");

        when(repository.findByUsernameIgnoreCase("seller1001"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches("correct-password", "stored-hash"))
                .thenReturn(true);

        assertThrows(
                InvalidCredentialsException.class,
                () -> authService.login(
                        new LoginRequest("seller1001", "correct-password")
                )
        );

        verify(jwtService, never()).generateToken(any());
    }

    private AuthUser createUser(String status) {

        AuthUser user = new AuthUser();

        user.setUsername("seller1001");
        user.setPasswordHash("stored-hash");
        user.setUserRole("SELLER");
        user.setSellerId("SELLER-1001");
        user.setAccountStatus(status);

        return user;
    }
}
