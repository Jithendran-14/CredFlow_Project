package com.credflow.application;

import com.credflow.application.service.ApplicationService;
import com.credflow.application.repository.*;
import com.credflow.application.util.RequestFingerprint;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationListSecurityTest {
	@Test
	void sellerListAlwaysFiltersByTrustedIdentity() {
		var repo = mock(ApplicationRepository.class);
		when(repo.findBySellerId(eq("seller-one"), any())).thenReturn(Page.empty());
		var service = new ApplicationService(repo, mock(IdempotencyKeyRepository.class),
				mock(SellerMappingRepository.class), mock(OutboxEventRepository.class), mock(RequestFingerprint.class),
				new ObjectMapper());
		var jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject("subject")
				.claim("roles", java.util.List.of("SELLER")).claim("sellerId", "seller-one").build();
		service.list(0, 20, jwt);
		verify(repo).findBySellerId(eq("seller-one"), any());
		verify(repo, never()).findAll(any(Pageable.class));
	}

	@Test
	void sellerWithoutIdentityCannotList() {
		var repo = mock(ApplicationRepository.class);
		var service = new ApplicationService(repo, mock(IdempotencyKeyRepository.class),
				mock(SellerMappingRepository.class), mock(OutboxEventRepository.class), mock(RequestFingerprint.class),
				new ObjectMapper());
		var jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject("subject")
				.claim("roles", java.util.List.of("SELLER")).build();
		assertThrows(AccessDeniedException.class, () -> service.list(0, 20, jwt));
		verifyNoInteractions(repo);
	}
}
