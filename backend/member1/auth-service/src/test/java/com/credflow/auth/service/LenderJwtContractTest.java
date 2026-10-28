package com.credflow.auth.service;

import com.credflow.auth.entity.AuthUser;
import com.credflow.auth.repository.AuthServiceClientRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LenderJwtContractTest {
	@Test
	void officerTokenHasTrustedLenderAndReviewScope() {
		var encoder = mock(JwtEncoder.class);
		var clients = mock(AuthServiceClientRepository.class);
		when(encoder.encode(any()))
				.thenReturn(Jwt.withTokenValue("signed-test").header("alg", "RS256").subject("officer").build());
		var service = new JwtService(encoder, clients, "credflow-auth", "credflow-api", 15);
		var user = new AuthUser();
		ReflectionTestUtils.setField(user, "publicUserId", "test-officer");
		user.setUsername("officer");
		user.setUserRole("LENDER_OFFICER");
		user.setLenderId("bank-a");
		assertEquals("signed-test", service.generateToken(user));
		var capture = ArgumentCaptor.forClass(JwtEncoderParameters.class);
		verify(encoder).encode(capture.capture());
		assertEquals("bank-a", capture.getValue().getClaims().getClaim("lenderId"));
		assertEquals("lender.review", capture.getValue().getClaims().getClaim("scope"));
	}

	@Test
	void officerWithoutBindingCannotReceiveToken() {
		var service = new JwtService(mock(JwtEncoder.class), mock(AuthServiceClientRepository.class), "credflow-auth",
				"credflow-api", 15);
		var user = new AuthUser();
		ReflectionTestUtils.setField(user, "publicUserId", "test-officer");
		user.setUsername("officer");
		user.setUserRole("LENDER_OFFICER");
		assertThrows(IllegalStateException.class, () -> service.generateToken(user));
	}
}
