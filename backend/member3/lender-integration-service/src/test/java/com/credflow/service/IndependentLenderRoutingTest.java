package com.credflow.service;

import com.credflow.platform.Platform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IndependentLenderRoutingTest {

	@Test
	void bankEndpointsAreSelectedIndependently() {
		var service = IntegrationBoundaryTest.configured(mock(Platform.class));
		service.bankAUrl = "http://bank-a.private:8091";
		service.nbfcBUrl = "http://nbfc-b.private:8092";
		assertEquals("http://bank-a.private:8091/mock/lenders/bank-a/applications", service.base(service.bankA));
		assertEquals("http://nbfc-b.private:8092/mock/lenders/nbfc-b/cases", service.base(service.nbfcB));
		assertThrows(IllegalArgumentException.class, () -> service.base("unregistered-lender"));
	}
}
