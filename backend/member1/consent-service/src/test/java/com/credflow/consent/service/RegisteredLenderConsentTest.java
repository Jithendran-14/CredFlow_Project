package com.credflow.consent.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RegisteredLenderConsentTest {
	@Test
	void registeredLendersAreAcceptedAndUnknownOnesRejected() {
		for (Class<?> type : List.of(ConsentDecisionService.class, ConsentLifecycleService.class)) {
			Object service = mock(type, CALLS_REAL_METHODS);
			var db = mock(JdbcTemplate.class);
			String field = type == ConsentDecisionService.class ? "allowedLenderIds" : "allowedLenders";
			ReflectionTestUtils.setField(service, field, Set.of(UUID.randomUUID()));
			ReflectionTestUtils.setField(service, type == ConsentDecisionService.class ? "lenderRegistry" : "jdbc", db);
			UUID lender = UUID.randomUUID();
			when(db.queryForList(anyString(), eq(String.class), eq(lender.toString())))
					.thenReturn(List.of(lender.toString()));
			assertEquals(true, ReflectionTestUtils.invokeMethod(service, "validRecipientLenders", Set.of(lender)));
			assertEquals(false,
					ReflectionTestUtils.invokeMethod(service, "validRecipientLenders", Set.of(UUID.randomUUID())));
		}
	}
}
