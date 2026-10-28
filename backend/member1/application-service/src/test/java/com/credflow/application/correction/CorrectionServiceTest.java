package com.credflow.application.correction;

import com.credflow.application.dto.*;
import com.credflow.application.repository.OutboxEventRepository;
import com.credflow.application.service.ApplicationService;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class CorrectionServiceTest {
	final JdbcTemplate db = mock(JdbcTemplate.class);
	final CorrectionService service = new CorrectionService(db, new ObjectMapper(), mock(OutboxEventRepository.class),
			mock(ApplicationService.class));

	CorrectionInput input(int revision, int version, String amount) {
		return new CorrectionInput(revision, version, amount, LoanPurpose.WORKING_CAPITAL,
				new ApplicationRequest.BusinessProfile("Test Seller", "Test Business", LocalDate.of(2020, 1, 1)));
	}

	Jwt jwt(String role) {
		return Jwt.withTokenValue("unit").header("alg", "RS256").subject("person").claim("roles", List.of(role))
				.claim("sellerId", "owner").build();
	}

	@Test
	void rejectsNonSellerBeforeReadingDatabase() {
		assertThrows(Exception.class, () -> service.submit(UUID.randomUUID(), input(1, 0, "100000.00"),
				UUID.randomUUID().toString(), jwt("ADMIN")));
		verifyNoInteractions(db);
	}

	@Test
	void anotherSellerCannotCorrectApplication() {
		when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
		assertThrows(Exception.class, () -> service.submit(UUID.randomUUID(), input(1, 0, "100000.00"),
				UUID.randomUUID().toString(), jwt("SELLER")));
		verify(db, never()).update(anyString(), any(Object[].class));
	}

	@Test
	void rejectsStaleRevisionWithoutWriting() {
		var id = UUID.randomUUID();
		when(db.queryForList(contains("FOR UPDATE"), eq(id.toString()), eq("owner")))
				.thenReturn(List.of(Map.of("STATUS", "ACTION_REQUIRED", "APPLICATION_REVISION", 2, "VERSION", 5)));
		assertThrows(Exception.class,
				() -> service.submit(id, input(1, 5, "100000.00"), UUID.randomUUID().toString(), jwt("SELLER")));
		verify(db, never()).update(anyString(), any(Object[].class));
	}

	@Test
	void rejectsZeroAmount() {
		assertThrows(Exception.class, () -> CorrectionService.validate(input(1, 0, "0.00")));
	}
}
