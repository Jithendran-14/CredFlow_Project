package com.credflow.auth.marketplace;

import com.credflow.auth.repository.*;
import com.credflow.auth.entity.*;
import com.credflow.auth.service.JwtService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketplaceWorkerIdentityTest {
	@Test
	void derivesOnlyRegisteredMarketplaceIdentity() throws Exception {
		String market = UUID.randomUUID().toString(), id = "marketplace-submission-" + market;
		var clients = mock(AuthServiceClientRepository.class);
		var users = mock(AuthUserRepository.class);
		var jwt = mock(JwtService.class);
		var ctor = AuthServiceClient.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		var c = ctor.newInstance();
		ReflectionTestUtils.setField(c, "userId", 9L);
		ReflectionTestUtils.setField(c, "clientId", id);
		ReflectionTestUtils.setField(c, "scopes", "application.submit");
		var user = new AuthUser();
		user.setUserRole("MARKETPLACE");
		user.setMarketplaceId(market);
		user.setAccountStatus("ACTIVE");
		when(clients.findByClientId(id)).thenReturn(Optional.of(c));
		when(users.findById(9L)).thenReturn(Optional.of(user));
		when(jwt.generateClientCredentialsToken(user, id, "application.submit")).thenReturn("auth-issued-token");
		var service = new MarketplaceWorkerIdentity(users, clients, jwt);
		assertEquals("auth-issued-token", service.token(market));
		user.setMarketplaceId(UUID.randomUUID().toString());
		assertThrows(Exception.class, () -> service.token(market));
		verify(jwt, times(1)).generateClientCredentialsToken(user, id, "application.submit");
	}
}
