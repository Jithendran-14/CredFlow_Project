package com.credflow.simulator.financial;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = { "spring.datasource.url=jdbc:h2:mem:financialmock;MODE=Oracle;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
		"spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop" })
class FinancialMockPersistenceTest {
	@Autowired
	FinancialMockService service;
	@Autowired
	FinancialMockRecords records;
	@MockitoBean
	JwtDecoder decoder;

	FinancialMockService.Request request(UUID id, String fixture) {
		return new FinancialMockService.Request(id, fixture, FinancialMockService.SCOPE, LocalDate.now(ZoneOffset.UTC),
				YearMonth.now(ZoneOffset.UTC).minusMonths(3).atDay(1), YearMonth.now(ZoneOffset.UTC).atDay(1));
	}

	@Test
	void replayIsStableAndStored() {
		UUID id = UUID.randomUUID();
		var r = request(id, "DEMO-FINANCIAL-GOOD-001");
		var first = service.retrieve(r, id.toString());
		assertThat(service.retrieve(r, id.toString())).isEqualTo(first);
		assertThat(records.findById("request:" + id)).isPresent();
	}

	@Test
	void differentRequestsShareImmutableDataset() {
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		assertThat(service.retrieve(request(a, "DEMO-FINANCIAL-GOOD-001"), a.toString()).get("dataset"))
				.isEqualTo(service.retrieve(request(b, "DEMO-FINANCIAL-GOOD-001"), b.toString()).get("dataset"));
	}

	@Test
	void reusedRequestCannotSwitchSeller() {
		UUID id = UUID.randomUUID();
		service.retrieve(request(id, "DEMO-FINANCIAL-GOOD-001"), id.toString());
		assertThatThrownBy(() -> service.retrieve(request(id, "DEMO-FINANCIAL-LOW-001"), id.toString()))
				.isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
	}

	@Test
	void missingAndZeroAreDifferent() {
		UUID a = UUID.randomUUID(), b = UUID.randomUUID();
		var missing = service.retrieve(request(a, "DEMO-FINANCIAL-MISSING-001"), a.toString()).path("dataset")
				.path("months").get(1);
		var zero = service.retrieve(request(b, "DEMO-FINANCIAL-ZERO-001"), b.toString()).path("dataset").path("months")
				.get(1);
		assertThat(missing.path("status").asText()).isEqualTo("MISSING");
		assertThat(missing.has("grossSalesAmount")).isFalse();
		assertThat(zero.path("grossSalesAmount").asText()).isEqualTo("0.00");
	}

	@Test
	void rejectsWrongIdempotencyKey() {
		UUID id = UUID.randomUUID();
		assertThatThrownBy(() -> service.retrieve(request(id, "DEMO-FINANCIAL-GOOD-001"), UUID.randomUUID().toString()))
				.isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
	}

	@Test
	void missingFixtureIsABadRequestRatherThanAServerError() {
		UUID id = UUID.randomUUID();
		assertThatThrownBy(() -> service.retrieve(request(id, null), id.toString()))
				.isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("400");
	}
}
