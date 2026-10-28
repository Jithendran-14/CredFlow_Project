package com.credflow.eligibility;

import com.credflow.eligibility.integration.ApplicationContractMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ApplicationContractMapperTest {
	final ApplicationContractMapper mapper = new ApplicationContractMapper(Member1FinancialFixture.JSON);

	ObjectNode sample() {
		try (var stream = getClass().getResourceAsStream("/contracts/member1/application-details-v1.json")) {
			return (ObjectNode) Member1FinancialFixture.JSON.readTree(stream);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	final UUID id = UUID.fromString("6074143c-1d49-469e-bfac-7de127685e83");

	ApplicationContractMapper.ApplicationDetails map(ObjectNode value) {
		return mapper.application(value.toString(), id, 1, EligibilityTestContext.AS_OF);
	}

	@Test
	void agreedExampleMapsBusinessDateAmountAndTrustedSeller() {
		var result = map(sample());
		assertThat(result.application().businessStartDate()).isEqualTo(LocalDate.of(2020, 1, 15));
		assertThat(result.application().requestedAmount()).isEqualByComparingTo("500000.00");
		assertThat(result.sellerId()).isEqualTo("SELLER-1001");
	}

	@Test
	void absentBusinessExtensionStopsCalculation() {
		var sample = sample();
		sample.remove("businessProfile");
		assertThatThrownBy(() -> map(sample)).hasMessage("Application business start date is not available");
	}

	@Test
	void unknownSellerMappingStaysNull() {
		var sample = sample();
		sample.putNull("sellerId");
		assertThat(map(sample).sellerId()).isNull();
	}

	@Test
	void changedRevisionRejected() {
		var sample = sample();
		sample.put("applicationRevision", 2);
		assertThatThrownBy(() -> map(sample)).hasMessageContaining("trusted context");
	}

	@Test
	void anotherApplicationRejected() {
		var sample = sample();
		sample.put("applicationId", UUID.randomUUID().toString());
		assertThatThrownBy(() -> map(sample)).hasMessageContaining("trusted context");
	}

	@Test
	void fractionalRevisionRejected() {
		var sample = sample();
		sample.put("applicationRevision", 1.5);
		assertThatThrownBy(() -> map(sample))
				.isInstanceOf(ApplicationContractMapper.ApplicationContractException.class);
	}

	@Test
	void futureBusinessDateRejected() {
		var sample = sample();
		((ObjectNode) sample.path("businessProfile")).put("businessStartDate", "2027-01-01");
		assertThatThrownBy(() -> map(sample)).hasMessageContaining("after evaluation");
	}

	@Test
	void numericAmountRejected() {
		var sample = sample();
		sample.put("requestedAmount", 500000);
		assertThatThrownBy(() -> map(sample))
				.isInstanceOf(ApplicationContractMapper.ApplicationContractException.class);
	}

	@Test
	void zeroRequestedAmountRejected() {
		var sample = sample();
		sample.put("requestedAmount", "0.00");
		assertThatThrownBy(() -> map(sample))
				.isInstanceOf(ApplicationContractMapper.ApplicationContractException.class);
	}

	@Test
	void unknownPurposeRejected() {
		var sample = sample();
		sample.put("loanPurpose", "UNKNOWN");
		assertThatThrownBy(() -> map(sample)).hasMessage("Unknown loan purpose");
	}

	ObjectNode ownership(boolean owns) {
		return Member1FinancialFixture.JSON.createObjectNode().put("applicationId", id.toString())
				.put("sellerId", "SELLER-1001").put("ownsApplication", owns);
	}

	@Test
	void positiveOwnershipAccepted() {
		assertThat(mapper.ownsApplication(ownership(true).toString(), id, "SELLER-1001")).isTrue();
	}

	@Test
	void negativeOwnershipRemainsDenied() {
		assertThat(mapper.ownsApplication(ownership(false).toString(), id, "SELLER-1001")).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "applicationId", "sellerId" })
	void wrongOwnershipEchoRejected(String field) {
		var value = ownership(true);
		value.put(field, field.equals("applicationId") ? UUID.randomUUID().toString() : "SELLER-2002");
		assertThatThrownBy(() -> mapper.ownsApplication(value.toString(), id, "SELLER-1001"))
				.hasMessageContaining("trusted context");
	}

	@Test
	void ownershipStringCannotBeTreatedAsBoolean() {
		var value = ownership(true);
		value.put("ownsApplication", "true");
		assertThatThrownBy(() -> mapper.ownsApplication(value.toString(), id, "SELLER-1001"))
				.hasMessage("Ownership decision must be boolean");
	}

	@Test
	void missingOwnershipDecisionRejected() {
		var value = ownership(true);
		value.remove("ownsApplication");
		assertThatThrownBy(() -> mapper.ownsApplication(value.toString(), id, "SELLER-1001"))
				.hasMessage("Ownership decision must be boolean");
	}
}
