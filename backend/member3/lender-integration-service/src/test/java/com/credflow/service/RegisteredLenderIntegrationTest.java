package com.credflow.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RegisteredLenderIntegrationTest {
	@Test
	void newLenderUsesRegisteredCodeAndTrustedSimulator() {
		var service = new LenderIntegrationService(null, null);
		service.bankA = "bank-a-id";
		service.nbfcB = "nbfc-id";
		service.bankAUrl = "http://localhost:8091";
		service.bankCallback = "mock-bank-a";
		service.registry = mock(JdbcTemplate.class);
		String lender = UUID.randomUUID().toString();
		when(service.registry.queryForList(anyString(), eq(String.class), eq(lender))).thenReturn(List.of("new-bank"));
		assertEquals("http://localhost:8091/mock/lenders/new-bank/applications", service.base(lender));
		assertEquals("mock-bank-a", service.callbackClient(lender));
		assertThrows(IllegalArgumentException.class, () -> service.base(UUID.randomUUID().toString()));
	}
}
